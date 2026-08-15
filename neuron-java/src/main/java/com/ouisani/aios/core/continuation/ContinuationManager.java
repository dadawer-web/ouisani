package com.ouisani.aios.core.continuation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ouisani.aios.core.audit.UnifiedAuditLog;
import com.ouisani.aios.core.config.AiosPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable read-model for an interrupted run.
 *
 * <p>The manager deliberately stores only facts which are safe to carry across a
 * user interruption: completed tool calls, their action digest and the current
 * plan state.  A write/exec result is retained as an audit fact, but is never
 * marked reusable.  A later continuation therefore has to pass through the
 * normal ActionGate again.</p>
 */
public final class ContinuationManager {

    public static final String STATE_INTERRUPTED = "INTERRUPTED";
    public static final String STATE_READY = "READY";
    public static final String STATE_WAITING_APPROVAL = "WAITING_APPROVAL";
    public static final String STATE_RUNNING = "RUNNING";
    public static final String STATE_READY_FOR_REDEPLOY = "READY_FOR_REDEPLOY";

    private static final int MAX_RESULT_CHARS = 8_000;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ContinuationManager INSTANCE = new ContinuationManager();

    private final Map<String, RunState> runs = new LinkedHashMap<>();
    private Path storeFile = Path.of(AiosPaths.aiosHome(), "continuations.json");

    /** A persisted tool invocation/checkpoint. */
    public record ToolCheckpoint(
            String checkpointId,
            String runId,
            String toolName,
            String parametersJson,
            String target,
            String actionType,
            String actionDigest,
            String result,
            String resultHash,
            boolean success,
            boolean readOnly,
            boolean reusable,
            int round,
            long createdAt) {}

    /** A plan item whose status can survive an interruption. */
    public record PlanStep(
            String stepId,
            String label,
            String status,
            String reason,
            List<String> dependsOn,
            String toolName,
            String actionType,
            String actionDigest,
            boolean dangerous) {}

    /** The complete user-facing continuation decision. */
    public record ContinuationPlan(
            String checkpointId,
            String runId,
            String workflowId,
            String missionId,
            String traceId,
            String instruction,
            String state,
            long createdAt,
            long updatedAt,
            List<ToolCheckpoint> retainedTools,
            List<ToolCheckpoint> reusableResults,
            List<PlanStep> retainedSteps,
            List<PlanStep> invalidatedSteps,
            List<PlanStep> requiresApproval) {}

    /** Result returned by a Run continuation request. */
    public record ContinueResult(ContinuationPlan plan, boolean started, String message) {}

    private static final class Store {
        Map<String, RunState> runs = new LinkedHashMap<>();
    }

    private static final class RunState {
        String checkpointId;
        String runId;
        String workflowId;
        String missionId;
        String traceId;
        String instruction;
        String state;
        long createdAt;
        long updatedAt;
        List<ToolState> tools = new ArrayList<>();
        List<StepState> steps = new ArrayList<>();
    }

    private static final class ToolState {
        String checkpointId;
        String runId;
        String toolName;
        String parametersJson;
        String target;
        String actionType;
        String actionDigest;
        String result;
        String resultHash;
        boolean success;
        boolean readOnly;
        boolean reusable;
        int round;
        long createdAt;
    }

    private static final class StepState {
        String stepId;
        String label;
        String status;
        String reason;
        List<String> dependsOn = new ArrayList<>();
        String toolName;
        String actionType;
        String actionDigest;
        boolean dangerous;
    }

    private ContinuationManager() {
        load();
    }

    public static ContinuationManager instance() {
        return INSTANCE;
    }

    /** Register the plan before its first node/tool starts. */
    public synchronized void registerPlan(String runId, String workflowId, String missionId,
                                           String traceId, List<PlanStep> planSteps) {
        if (runId == null || runId.isBlank()) return;
        RunState state = runs.computeIfAbsent(runId, id -> {
            RunState created = new RunState();
            created.checkpointId = UUID.randomUUID().toString();
            created.runId = id;
            created.createdAt = System.currentTimeMillis();
            return created;
        });
        state.workflowId = clean(workflowId, runId);
        state.missionId = clean(missionId, null);
        state.traceId = clean(traceId, null);
        state.state = STATE_RUNNING;
        if (planSteps != null && !planSteps.isEmpty()) {
            Map<String, StepState> existing = new LinkedHashMap<>();
            for (StepState step : state.steps) existing.put(step.stepId, step);
            for (PlanStep plan : planSteps) {
                if (plan == null || plan.stepId() == null || plan.stepId().isBlank()) continue;
                StepState step = existing.computeIfAbsent(plan.stepId(), ignored -> new StepState());
                step.stepId = plan.stepId();
                step.label = clean(plan.label(), plan.stepId());
                step.dependsOn = plan.dependsOn() == null ? new ArrayList<>() : new ArrayList<>(plan.dependsOn());
                step.toolName = plan.toolName();
                step.actionType = plan.actionType();
                step.actionDigest = plan.actionDigest();
                step.dangerous = plan.dangerous();
                if (step.status == null || "PENDING".equals(step.status)) step.status = "PENDING";
                if (plan.reason() != null && !plan.reason().isBlank()) step.reason = plan.reason();
            }
            state.steps = new ArrayList<>(existing.values());
        }
        touch(state);
        persist();
    }

    /** Record every completed/failed tool call before the next model turn. */
    public synchronized void recordToolResult(String runId, String toolName, String parametersJson,
                                               String target, String actionType, String actionDigest,
                                               String result, boolean success, boolean readOnly, int round) {
        if (runId == null || runId.isBlank()) return;
        RunState state = runs.computeIfAbsent(runId, id -> {
            RunState created = new RunState();
            created.checkpointId = UUID.randomUUID().toString();
            created.runId = id;
            created.workflowId = id;
            created.createdAt = System.currentTimeMillis();
            created.state = STATE_RUNNING;
            return created;
        });
        ToolState tool = new ToolState();
        tool.checkpointId = UUID.randomUUID().toString();
        tool.runId = runId;
        tool.toolName = clean(toolName, "unknown");
        tool.parametersJson = clean(parametersJson, "{}");
        tool.target = clean(target, "");
        tool.actionType = clean(actionType, readOnly ? "read" : "execute");
        tool.actionDigest = clean(actionDigest, "");
        tool.result = truncate(result);
        tool.resultHash = sha256(tool.result);
        tool.success = success;
        tool.readOnly = readOnly;
        // Only successful read-only calls are safe to inject into a continuation prompt.
        tool.reusable = success && readOnly;
        tool.round = Math.max(0, round);
        tool.createdAt = System.currentTimeMillis();
        state.tools.add(tool);
        touch(state);
        persist();
        audit("CONTINUATION_TOOL_CHECKPOINT", runId,
                tool.toolName + (tool.reusable ? " reusable" : " retained_only"));
    }

    public synchronized void markStepCompleted(String runId, String stepId, boolean reused) {
        RunState state = runs.get(runId);
        if (state == null || stepId == null) return;
        for (StepState step : state.steps) {
            if (stepId.equals(step.stepId)) {
                step.status = reused ? "REUSED" : "COMPLETED";
                step.reason = reused ? "restored from checkpoint" : "completed before interruption";
                break;
            }
        }
        touch(state);
        persist();
    }

    /** Capture the current state at the exact interruption boundary. */
    public synchronized ContinuationPlan captureInterruption(String runId, String reason) {
        RunState state = runs.get(runId);
        if (state == null) return emptyPlan(runId, STATE_INTERRUPTED);
        state.state = STATE_INTERRUPTED;
        state.instruction = clean(reason, "user interruption");
        touch(state);
        persist();
        audit("CONTINUATION_CAPTURED", runId, state.instruction);
        return toPlan(state);
    }

    /** Apply a new user instruction and calculate retained/invalidated/re-approval sets. */
    public synchronized ContinuationPlan prepare(String runId, String instruction) {
        RunState state = runs.get(runId);
        if (state == null) return emptyPlan(runId, STATE_READY_FOR_REDEPLOY);
        String next = clean(instruction, "继续之前的任务");
        state.instruction = next;
        boolean changed = changesPlan(next);
        for (StepState step : state.steps) {
            if ("COMPLETED".equals(step.status) || "REUSED".equals(step.status)) continue;
            if (changed && "PENDING".equals(step.status)) {
                if (step.dangerous && !removesDangerousAction(next)) {
                    step.status = "REQUIRES_APPROVAL";
                    step.reason = "changed dangerous action requires fresh approval";
                } else {
                    step.status = "INVALIDATED";
                    step.reason = "superseded by the new user instruction";
                }
            } else if (step.dangerous && ("PENDING".equals(step.status) || "REQUIRES_APPROVAL".equals(step.status))) {
                step.status = "REQUIRES_APPROVAL";
                step.reason = "dangerous action must be approved again after interruption";
            }
        }
        state.state = state.steps.stream().anyMatch(step -> "REQUIRES_APPROVAL".equals(step.status))
                ? STATE_WAITING_APPROVAL : STATE_READY;
        touch(state);
        persist();
        audit("CONTINUATION_PREPARED", runId, changed ? "plan_changed" : "plan_kept");
        for (StepState step : state.steps) {
            if ("INVALIDATED".equals(step.status)) audit("CONTINUATION_INVALIDATED", runId, step.stepId);
        }
        for (ToolState tool : state.tools) {
            if (tool.success && !tool.readOnly) {
                audit("CONTINUATION_SIDE_EFFECT_RETAINED", runId, tool.toolName);
            } else if (tool.reusable) {
                audit("CONTINUATION_REUSE_AVAILABLE", runId, tool.toolName);
            }
        }
        return toPlan(state);
    }

    public synchronized Optional<ContinuationPlan> get(String runId) {
        return Optional.ofNullable(runs.get(runId)).map(this::toPlan);
    }

    /** Compact context injected into a resumed agent so safe reads are not repeated. */
    public synchronized String reusableResultsSummary(String runId) {
        RunState state = runs.get(runId);
        if (state == null) return "";
        StringBuilder summary = new StringBuilder();
        for (ToolState tool : state.tools) {
            if (!tool.reusable) continue;
            summary.append(tool.toolName).append(" ")
                    .append(tool.target == null ? "" : tool.target)
                    .append(": ").append(tool.result).append("\n");
        }
        return summary.toString().trim();
    }

    /** Prompt fragment for agents that choose to continue in-process. */
    public synchronized String continuationPrompt(String runId, String instruction) {
        ContinuationPlan plan = prepare(runId, instruction);
        StringBuilder prompt = new StringBuilder();
        prompt.append("[CONTINUATION CHECKPOINT]\n");
        prompt.append("New user instruction: ").append(plan.instruction()).append("\n");
        if (!plan.reusableResults().isEmpty()) {
            prompt.append("Reusable read-only results (do not repeat these calls):\n");
            for (ToolCheckpoint tool : plan.reusableResults()) {
                prompt.append("- ").append(tool.toolName()).append(": ").append(tool.result()).append("\n");
            }
        }
        if (!plan.invalidatedSteps().isEmpty()) {
            prompt.append("Invalidated old steps: ").append(plan.invalidatedSteps().stream().map(PlanStep::stepId).toList()).append("\n");
        }
        if (!plan.requiresApproval().isEmpty()) {
            prompt.append("Dangerous actions require fresh approval before execution.\n");
        }
        return prompt.toString();
    }

    /** Tests can isolate persistence without touching the user's ~/.aios directory. */
    public synchronized void setStoreFileForTest(Path file) {
        storeFile = file;
        runs.clear();
        load();
    }

    public synchronized void clearForTest() {
        runs.clear();
        persist();
    }

    private ContinuationPlan toPlan(RunState state) {
        List<ToolCheckpoint> allTools = state.tools.stream().map(this::toTool).toList();
        List<ToolCheckpoint> retainedTools = allTools.stream().filter(ToolCheckpoint::success).toList();
        List<ToolCheckpoint> reusable = allTools.stream().filter(ToolCheckpoint::reusable).toList();
        List<PlanStep> retainedSteps = state.steps.stream()
                .filter(step -> "COMPLETED".equals(step.status) || "REUSED".equals(step.status))
                .map(this::toStep).toList();
        List<PlanStep> invalidated = state.steps.stream().filter(step -> "INVALIDATED".equals(step.status))
                .map(this::toStep).toList();
        List<PlanStep> approvals = new ArrayList<>();
        state.steps.stream().filter(step -> "REQUIRES_APPROVAL".equals(step.status)).map(this::toStep).forEach(approvals::add);
        String effectiveState = approvals.isEmpty() ? state.state : STATE_WAITING_APPROVAL;
        return new ContinuationPlan(state.checkpointId, state.runId, state.workflowId, state.missionId,
                state.traceId, state.instruction, effectiveState, state.createdAt, state.updatedAt,
                retainedTools, reusable, retainedSteps, invalidated, List.copyOf(approvals));
    }

    private ToolCheckpoint toTool(ToolState tool) {
        return new ToolCheckpoint(tool.checkpointId, tool.runId, tool.toolName, tool.parametersJson,
                tool.target, tool.actionType, tool.actionDigest, tool.result, tool.resultHash,
                tool.success, tool.readOnly, tool.reusable, tool.round, tool.createdAt);
    }

    private PlanStep toStep(StepState step) {
        return new PlanStep(step.stepId, step.label, step.status, step.reason,
                step.dependsOn == null ? List.of() : List.copyOf(step.dependsOn),
                step.toolName, step.actionType, step.actionDigest, step.dangerous);
    }

    private ContinuationPlan emptyPlan(String runId, String state) {
        long now = System.currentTimeMillis();
        return new ContinuationPlan("", runId, runId, null, null, "", state, now, now,
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static boolean changesPlan(String instruction) {
        String text = instruction == null ? "" : instruction.toLowerCase(Locale.ROOT);
        return text.contains("改") || text.contains("换") || text.contains("重新") || text.contains("不要")
                || text.contains("取消") || text.contains("放弃") || text.contains("跳过")
                || text.contains("仅") || text.contains("只") || text.contains("replace")
                || text.contains("change") || text.contains("restart") || text.contains("instead");
    }

    private static boolean removesDangerousAction(String instruction) {
        String text = instruction == null ? "" : instruction.toLowerCase(Locale.ROOT);
        return text.contains("不要") || text.contains("取消") || text.contains("放弃")
                || text.contains("只读") || text.contains("只检查") || text.contains("仅检查")
                || text.contains("do not") || text.contains("read only") || text.contains("skip write");
    }

    /** Mark a node that the caller explicitly removed from the revised plan. */
    public synchronized void invalidateStep(String runId, String stepId, String reason) {
        RunState state = runs.get(runId);
        if (state == null || stepId == null) return;
        for (StepState step : state.steps) {
            if (stepId.equals(step.stepId) && !"COMPLETED".equals(step.status) && !"REUSED".equals(step.status)) {
                step.status = "INVALIDATED";
                step.reason = clean(reason, "superseded by user");
                break;
            }
        }
        touch(state);
        persist();
    }

    private void touch(RunState state) {
        state.updatedAt = System.currentTimeMillis();
        if (state.createdAt <= 0) state.createdAt = state.updatedAt;
        if (state.checkpointId == null || state.checkpointId.isBlank()) state.checkpointId = UUID.randomUUID().toString();
    }

    private void load() {
        try {
            if (!Files.exists(storeFile)) return;
            Store store = JSON.fromJson(Files.readString(storeFile), Store.class);
            if (store != null && store.runs != null) runs.putAll(store.runs);
        } catch (Exception ignored) {
            // A corrupt continuation file must not prevent the runtime from starting.
        }
    }

    private void persist() {
        try {
            Path parent = storeFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = storeFile.resolveSibling(storeFile.getFileName() + ".tmp");
            Store store = new Store();
            store.runs = new LinkedHashMap<>(runs);
            Files.writeString(tmp, JSON.toJson(store));
            Files.move(tmp, storeFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            // Checkpointing is best-effort; the in-memory read model remains available.
        }
    }

    private static String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String truncate(String value) {
        if (value == null) return "";
        return value.length() <= MAX_RESULT_CHARS ? value : value.substring(0, MAX_RESULT_CHARS) + "…";
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((value == null ? "" : value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return "";
        }
    }

    private static void audit(String decision, String runId, String reason) {
        try {
            UnifiedAuditLog.append(new UnifiedAuditLog.TimelineEvent(
                    UnifiedAuditLog.LAYER_CONTINUATION, "CONTINUATION", decision,
                    null, runId, reason,
                    new UnifiedAuditLog.AuditContext(null, runId, runId, null,
                            null, null, null, null, -1)));
        } catch (Throwable ignored) { }
    }
}
