package com.ouisani.aios.core.mission;

import com.google.gson.Gson;
import com.ouisani.aios.core.audit.UnifiedAuditLog;
import com.ouisani.aios.core.config.AiosPaths;
import com.ouisani.aios.core.network.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Lightweight, durable mission registry layered above workflow runs.
 *
 * <p>A mission deliberately contains only the information needed to resume a
 * user's intent: goal, current state/next step, related runs, confirmed
 * knowledge or artifacts, approvals and a completion report.  WorkflowEngine
 * remains the execution authority; this class is the continuity/read-model
 * for the home page.</p>
 */
public final class MissionManager {
    private static final Logger log = LoggerFactory.getLogger(MissionManager.class);
    private static final Gson JSON = new Gson();
    private static final String EVENT_CHANNEL = "sys.mission.updated";

    private static final class Holder {
        private static final MissionManager INSTANCE = new MissionManager();
    }

    public static MissionManager instance() {
        return Holder.INSTANCE;
    }

    public enum MissionStatus {
        ACTIVE,
        WAITING_APPROVAL,
        BACKGROUND,
        PLANNED,
        COMPLETED,
        BLOCKED,
        FAILED
    }

    /** A confirmed fact, discovery, or generated artifact attached to a mission. */
    public record KnowledgeItem(String id, String kind, String title, String summary,
                                String source, long createdAt) {}

    /** An approval action which is waiting for the human decision. */
    public record ApprovalItem(String requestId, String action, String toolName,
                               String target, String workflowId, String traceId,
                               long createdAt) {}

    /** Immutable API/read-model representation returned to the UI. */
    public record Mission(String missionId, String goal, MissionStatus status,
                          String currentState, String nextStep, List<String> runIds,
                          List<KnowledgeItem> confirmedKnowledge,
                          List<ApprovalItem> pendingApprovals,
                          long nextTriggerAt, String nextTriggerEvent,
                          String completionReport, long createdAt, long updatedAt) {}

    /** Gson persistence envelope. */
    private static final class Store {
        List<MissionState> missions = new ArrayList<>();
    }

    /** Mutable in-memory form. Kept private so callers cannot bypass invariants. */
    private static final class MissionState {
        String missionId;
        String goal;
        String status;
        String currentState;
        String nextStep;
        List<String> runIds = new ArrayList<>();
        List<KnowledgeState> confirmedKnowledge = new ArrayList<>();
        List<ApprovalState> pendingApprovals = new ArrayList<>();
        long nextTriggerAt;
        String nextTriggerEvent;
        String completionReport;
        long createdAt;
        long updatedAt;
    }

    private static final class KnowledgeState {
        String id;
        String kind;
        String title;
        String summary;
        String source;
        long createdAt;
    }

    private static final class ApprovalState {
        String requestId;
        String action;
        String toolName;
        String target;
        String workflowId;
        String traceId;
        long createdAt;
    }

    private final Object lock = new Object();
    private final List<MissionState> missions = new ArrayList<>();
    private volatile Path storeFile = Path.of(".aios", "missions.json");
    private boolean loaded;

    private MissionManager() {}

    /** Create a mission directly from a user goal. */
    public Mission create(String goal, String currentState, String nextStep, MissionStatus status) {
        synchronized (lock) {
            ensureLoadedLocked();
            long now = System.currentTimeMillis();
            MissionState state = new MissionState();
            state.missionId = "mission_" + UUID.randomUUID();
            state.goal = clean(goal, "Untitled mission");
            state.status = (status == null ? MissionStatus.ACTIVE : status).name();
            state.currentState = clean(currentState, "Ready to begin");
            state.nextStep = clean(nextStep, "Choose the next action");
            state.createdAt = now;
            state.updatedAt = now;
            missions.add(state);
            persistLocked();
            publishAndAudit(state, "MISSION_CREATED", "goal=" + state.goal, null, null, null);
            return snapshot(state);
        }
    }

    public List<Mission> list() {
        synchronized (lock) {
            ensureLoadedLocked();
            return missions.stream()
                    .sorted(Comparator.comparingLong((MissionState m) -> m.updatedAt).reversed())
                    .map(this::snapshot)
                    .toList();
        }
    }

    public Optional<Mission> get(String missionId) {
        synchronized (lock) {
            ensureLoadedLocked();
            MissionState state = findLocked(missionId);
            return state == null ? Optional.empty() : Optional.of(snapshot(state));
        }
    }

    /** Partial update used by the mission editor and schedule controls. */
    public Optional<Mission> update(String missionId, String goal, String currentState,
                                    String nextStep, MissionStatus status,
                                    Long nextTriggerAt, String nextTriggerEvent,
                                    String completionReport) {
        synchronized (lock) {
            ensureLoadedLocked();
            MissionState state = findLocked(missionId);
            if (state == null) return Optional.empty();
            if (goal != null && !goal.isBlank()) state.goal = goal.trim();
            if (currentState != null) state.currentState = currentState.trim();
            if (nextStep != null) state.nextStep = nextStep.trim();
            if (status != null) state.status = status.name();
            if (nextTriggerAt != null) state.nextTriggerAt = Math.max(0L, nextTriggerAt);
            if (nextTriggerEvent != null) state.nextTriggerEvent = clean(nextTriggerEvent, null);
            if (completionReport != null) state.completionReport = completionReport.trim();
            touch(state);
            persistLocked();
            publishAndAudit(state, "MISSION_UPDATED", "partial_update", null, null, null);
            return Optional.of(snapshot(state));
        }
    }

    /** Link an existing or future workflow run to a mission. */
    public Optional<Mission> attachRun(String missionId, String runId) {
        if (runId == null || runId.isBlank()) return Optional.empty();
        synchronized (lock) {
            ensureLoadedLocked();
            MissionState state = findLocked(missionId);
            if (state == null) return Optional.empty();
            if (!state.runIds.contains(runId.trim())) state.runIds.add(runId.trim());
            touch(state);
            persistLocked();
            publishAndAudit(state, "MISSION_RUN_ATTACHED", "run=" + runId,
                    null, runId, null);
            return Optional.of(snapshot(state));
        }
    }

    public Optional<Mission> addKnowledge(String missionId, String kind, String title,
                                          String summary, String source) {
        synchronized (lock) {
            ensureLoadedLocked();
            MissionState state = findLocked(missionId);
            if (state == null) return Optional.empty();
            String safeTitle = clean(title, "Untitled discovery");
            boolean duplicate = state.confirmedKnowledge.stream()
                    .anyMatch(k -> safeTitle.equals(k.title) && clean(kind, "knowledge").equals(k.kind));
            if (!duplicate) {
                KnowledgeState item = new KnowledgeState();
                item.id = "knowledge_" + UUID.randomUUID();
                item.kind = clean(kind, "knowledge");
                item.title = safeTitle;
                item.summary = clean(summary, "");
                item.source = clean(source, "mission");
                item.createdAt = System.currentTimeMillis();
                state.confirmedKnowledge.add(item);
            }
            touch(state);
            persistLocked();
            publishAndAudit(state, "MISSION_KNOWLEDGE_CONFIRMED", safeTitle, null, null, null);
            return Optional.of(snapshot(state));
        }
    }

    public Optional<Mission> addApproval(String missionId, String requestId, String action,
                                         String toolName, String target, String workflowId,
                                         String traceId) {
        if (requestId == null || requestId.isBlank()) return Optional.empty();
        synchronized (lock) {
            ensureLoadedLocked();
            MissionState state = findLocked(missionId);
            if (state == null) return Optional.empty();
            if (state.pendingApprovals.stream().noneMatch(a -> requestId.equals(a.requestId))) {
                ApprovalState item = new ApprovalState();
                item.requestId = requestId.trim();
                item.action = clean(action, "Approval required");
                item.toolName = clean(toolName, "tool");
                item.target = clean(target, null);
                item.workflowId = clean(workflowId, null);
                item.traceId = clean(traceId, null);
                item.createdAt = System.currentTimeMillis();
                state.pendingApprovals.add(item);
            }
            state.status = MissionStatus.WAITING_APPROVAL.name();
            state.currentState = "Waiting for your approval";
            state.nextStep = "Review the pending action";
            touch(state);
            persistLocked();
            publishAndAudit(state, "MISSION_APPROVAL_REQUESTED", requestId,
                    workflowId, workflowId, traceId);
            return Optional.of(snapshot(state));
        }
    }

    /** Resolve an approval globally, useful when the popup only has requestId. */
    public boolean resolveApproval(String requestId) {
        if (requestId == null || requestId.isBlank()) return false;
        synchronized (lock) {
            ensureLoadedLocked();
            for (MissionState state : missions) {
                boolean removed = state.pendingApprovals.removeIf(a -> requestId.equals(a.requestId));
                if (!removed) continue;
                if (state.pendingApprovals.isEmpty() && MissionStatus.WAITING_APPROVAL.name().equals(state.status)) {
                    state.status = MissionStatus.ACTIVE.name();
                    state.currentState = "Approval resolved";
                    state.nextStep = "Resume the linked run";
                }
                touch(state);
                persistLocked();
                publishAndAudit(state, "MISSION_APPROVAL_RESOLVED", requestId,
                        null, null, null);
                return true;
            }
            return false;
        }
    }

    public Optional<Mission> complete(String missionId, String report) {
        return update(missionId, null, "Mission completed", "Review the completion report",
                MissionStatus.COMPLETED, 0L, null, report == null ? "Completed" : report);
    }

    /** Ensure a mission exists for a run emitted by the workflow engine. */
    public Mission ensureForRun(String runId, String workflowId, String traceId, String goal) {
        synchronized (lock) {
            ensureLoadedLocked();
            MissionState existing = missions.stream()
                    .filter(m -> runId != null && m.runIds.contains(runId))
                    .findFirst().orElse(null);
            if (existing != null) return snapshot(existing);
            long now = System.currentTimeMillis();
            MissionState state = new MissionState();
            state.missionId = "mission_" + UUID.randomUUID();
            state.goal = clean(goal, clean(workflowId, "Workflow mission"));
            state.status = MissionStatus.ACTIVE.name();
            state.currentState = "Starting the linked workflow";
            state.nextStep = "Observe the first node result";
            if (runId != null && !runId.isBlank()) state.runIds.add(runId);
            state.createdAt = now;
            state.updatedAt = now;
            missions.add(state);
            persistLocked();
            publishAndAudit(state, "MISSION_CREATED_FOR_RUN", "run=" + runId,
                    workflowId, runId, traceId);
            return snapshot(state);
        }
    }

    /** Update the continuity read-model from a workflow lifecycle event. */
    public void observeRun(String runId, String workflowId, String traceId, String runStatus,
                           String currentState, String nextStep, String report) {
        if (runId == null || runId.isBlank()) return;
        synchronized (lock) {
            ensureLoadedLocked();
            MissionState state = missions.stream().filter(m -> m.runIds.contains(runId)).findFirst().orElse(null);
            if (state == null) {
                ensureForRun(runId, workflowId, traceId, workflowId);
                state = missions.stream().filter(m -> m.runIds.contains(runId)).findFirst().orElse(null);
            }
            if (state == null) return;
            String normalized = runStatus == null ? "ACTIVE" : runStatus.trim().toUpperCase(Locale.ROOT);
            if ("SUCCEEDED".equals(normalized)) state.status = MissionStatus.COMPLETED.name();
            else if ("FAILED".equals(normalized)) state.status = MissionStatus.FAILED.name();
            else if ("CANCELLED".equals(normalized) || "CANCEL_REQUESTED".equals(normalized)) state.status = MissionStatus.BLOCKED.name();
            else if ("PAUSED".equals(normalized)) state.status = MissionStatus.BACKGROUND.name();
            else if (!state.pendingApprovals.isEmpty()) state.status = MissionStatus.WAITING_APPROVAL.name();
            else state.status = MissionStatus.ACTIVE.name();
            if (currentState != null && !currentState.isBlank()) state.currentState = currentState.trim();
            if (nextStep != null && !nextStep.isBlank()) state.nextStep = nextStep.trim();
            if (report != null && !report.isBlank()) state.completionReport = report.trim();
            if ("SUCCEEDED".equals(normalized)) recordRunArtifacts(state, workflowId, runId, report);
            touch(state);
            persistLocked();
            publishAndAudit(state, "MISSION_RUN_OBSERVED", normalized,
                    workflowId, runId, traceId);
        }
    }

    /** Test/embedding hook for relocating the small JSON store. */
    public void setStoreFileForTest(Path file) {
        synchronized (lock) {
            storeFile = file == null ? Path.of(".aios", "missions.json") : file;
            missions.clear();
            loaded = false;
        }
    }

    public void clearForTesting() {
        synchronized (lock) {
            missions.clear();
            loaded = true;
            persistLocked();
        }
    }

    private MissionState findLocked(String missionId) {
        if (missionId == null) return null;
        return missions.stream().filter(m -> missionId.equals(m.missionId)).findFirst().orElse(null);
    }

    private void ensureLoadedLocked() {
        if (loaded) return;
        loaded = true;
        if (storeFile == null || !Files.exists(storeFile)) return;
        try {
            String raw = Files.readString(storeFile, StandardCharsets.UTF_8);
            Store store = JSON.fromJson(raw, Store.class);
            if (store != null && store.missions != null) {
                for (MissionState state : store.missions) {
                    if (state == null || state.missionId == null) continue;
                    if (state.runIds == null) state.runIds = new ArrayList<>();
                    if (state.confirmedKnowledge == null) state.confirmedKnowledge = new ArrayList<>();
                    if (state.pendingApprovals == null) state.pendingApprovals = new ArrayList<>();
                    missions.add(state);
                }
            }
        } catch (Exception e) {
            log.warn("[Mission] failed to load {}: {}", storeFile, e.getMessage());
        }
    }

    private void persistLocked() {
        if (storeFile == null) return;
        try {
            Path parent = storeFile.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Store store = new Store();
            store.missions = new ArrayList<>(missions);
            Path temp = storeFile.resolveSibling(storeFile.getFileName() + ".tmp-" + UUID.randomUUID());
            Files.writeString(temp, JSON.toJson(store), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temp, storeFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, storeFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.debug("[Mission] persistence skipped: {}", e.getMessage());
        }
    }

    private void touch(MissionState state) {
        state.updatedAt = System.currentTimeMillis();
    }

    /** Best-effort discovery of files produced by the completed workflow. */
    private void recordRunArtifacts(MissionState state, String workflowId, String runId, String report) {
        String workspaceId = clean(workflowId, runId);
        Path factory = Path.of(AiosPaths.aiosHome(), "workspaces", workspaceId, "factory");
        int discovered = 0;
        try (var files = Files.list(factory)) {
            for (Path file : files.filter(Files::isRegularFile).limit(32).toList()) {
                String title = file.getFileName().toString();
                if (state.confirmedKnowledge.stream().anyMatch(k -> "artifact".equals(k.kind) && title.equals(k.title))) continue;
                KnowledgeState artifact = new KnowledgeState();
                artifact.id = "artifact_" + UUID.randomUUID();
                artifact.kind = "artifact";
                artifact.title = title;
                artifact.summary = "Generated by run " + runId + " (" + Files.size(file) + " bytes)";
                artifact.source = "workflow:" + workspaceId;
                artifact.createdAt = System.currentTimeMillis();
                state.confirmedKnowledge.add(artifact);
                discovered++;
            }
        } catch (Exception ignored) { }
        if (discovered == 0 && report != null && !report.isBlank()
                && state.confirmedKnowledge.stream().noneMatch(k -> "artifact".equals(k.kind) && report.equals(k.summary))) {
            KnowledgeState artifact = new KnowledgeState();
            artifact.id = "artifact_" + UUID.randomUUID();
            artifact.kind = "artifact";
            artifact.title = "Run " + runId + " completion";
            artifact.summary = report.trim();
            artifact.source = "workflow:" + workspaceId;
            artifact.createdAt = System.currentTimeMillis();
            state.confirmedKnowledge.add(artifact);
        }
    }

    private Mission snapshot(MissionState state) {
        List<String> runs = state.runIds == null ? List.of() : List.copyOf(state.runIds);
        List<KnowledgeItem> knowledge = state.confirmedKnowledge == null ? List.of() : state.confirmedKnowledge.stream()
                .map(k -> new KnowledgeItem(k.id, k.kind, k.title, k.summary, k.source, k.createdAt)).toList();
        List<ApprovalItem> approvals = state.pendingApprovals == null ? List.of() : state.pendingApprovals.stream()
                .map(a -> new ApprovalItem(a.requestId, a.action, a.toolName, a.target,
                        a.workflowId, a.traceId, a.createdAt)).toList();
        MissionStatus status;
        try {
            status = MissionStatus.valueOf(state.status == null ? MissionStatus.ACTIVE.name() : state.status);
        } catch (IllegalArgumentException e) {
            status = MissionStatus.ACTIVE;
        }
        return new Mission(state.missionId, state.goal, status, state.currentState, state.nextStep,
                runs, knowledge, approvals, state.nextTriggerAt, state.nextTriggerEvent,
                state.completionReport, state.createdAt, state.updatedAt);
    }

    private void publishAndAudit(MissionState state, String decision, String reason,
                                 String workflowId, String runId, String traceId) {
        Mission view = snapshot(state);
        try {
            EventBus.instance().broadcast(EVENT_CHANNEL, JSON.toJson(view));
        } catch (Throwable t) {
            log.debug("[Mission] event publish skipped: {}", t.getMessage());
        }
        UnifiedAuditLog.append(new UnifiedAuditLog.TimelineEvent(
                UnifiedAuditLog.LAYER_MISSION, "MISSION", decision, null,
                state.missionId, reason,
                new UnifiedAuditLog.AuditContext(null, workflowId, runId, traceId,
                        null, null, null, null, -1)));
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.trim();
    }
}
