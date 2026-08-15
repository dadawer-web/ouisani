package com.ouisani.aios.core.review;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.ouisani.aios.core.action.ActionGovernor;
import com.ouisani.aios.core.action.ActionRecord;
import com.ouisani.aios.core.action.RiskLevel;
import com.ouisani.aios.core.audit.UnifiedAuditLog;
import com.ouisani.aios.core.config.AiosPaths;
import com.ouisani.aios.core.syscall.schema.StoragePayload;
import com.ouisani.aios.core.syscall.ResultState;
import com.ouisani.aios.core.VfsManager;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Persistent review/undo read model backed by the existing ActionGovernor snapshots. */
public final class DiffTimelineManager {
    private static final Gson JSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<DiffEntry>>() {}.getType();
    private static final class Holder { static final DiffTimelineManager INSTANCE = new DiffTimelineManager(); }
    public static DiffTimelineManager instance() { return Holder.INSTANCE; }

    private final Path file;
    private final List<DiffEntry> entries = new ArrayList<>();

    private DiffTimelineManager() {
        file = Path.of(AiosPaths.aiosHome(), "var", "diff-timeline.json");
        load();
    }

    public record DiffEntry(
            String diffId,
            String requestId,
            String agentId,
            String action,
            String target,
            String risk,
            String snapshotId,
            int deltaCount,
            boolean meetsExpectation,
            long createdAt,
            String review,
            boolean reverted,
            long revertedAt,
            String backupPath,
            boolean existedBefore) {
        public DiffEntry {
            backupPath = backupPath == null ? "" : backupPath;
        }
    }

    /** Called by ActionGovernor after an action has a before/after record. */
    public synchronized void record(ActionRecord record) {
        if (record == null || record.riskLevel() == RiskLevel.SAFE || record.requestId() == null
                || record.resultState() == null || record.resultState() == ResultState.FAILED) return;
        if (entries.stream().anyMatch(e -> record.requestId().equals(e.requestId()))) return;
        String target = targetOf(record);
        int deltas = record.diff() == null ? 0 : record.diff().totalDeltas();
        boolean meets = record.diff() == null || record.diff().meetsExpectation();
        entries.add(new DiffEntry("diff-" + UUID.randomUUID(), record.requestId(), record.agentId(),
                record.request() == null ? "" : record.request().fullAction(), target,
                record.riskLevel().name(), record.snapshotId(), deltas, meets,
                record.startedAtMs(), "PENDING", false, 0L, "", false));
        save();
        audit("DIFF_RECORDED", record.requestId(), "action=" + (record.request() == null ? "" : record.request().fullAction()));
    }

    public synchronized List<DiffEntry> list(String requestId, String agentId) {
        return entries.stream().filter(e -> requestId == null || requestId.isBlank() || requestId.equals(e.requestId()))
                .filter(e -> agentId == null || agentId.isBlank() || agentId.equals(e.agentId()))
                .sorted(Comparator.comparingLong(DiffEntry::createdAt).reversed()).toList();
    }

    /** Attach a durable before-image to an IDE VFS save without exposing content in the read model. */
    public synchronized void attachVfsBackup(String requestId, String target, String before,
                                              boolean existedBefore) {
        int i = indexByRequest(requestId);
        if (i < 0 || target == null || target.isBlank()) return;
        DiffEntry current = entries.get(i);
        try {
            Path backup = Path.of(AiosPaths.aiosHome(), "var", "diff-backups",
                    current.diffId() + ".before");
            Files.createDirectories(backup.getParent());
            Files.writeString(backup, before == null ? "" : before, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            entries.set(i, new DiffEntry(current.diffId(), current.requestId(), current.agentId(),
                    current.action(), target, current.risk(), current.snapshotId(), current.deltaCount(),
                    current.meetsExpectation(), current.createdAt(), current.review(), current.reverted(),
                    current.revertedAt(), backup.toString(), existedBefore));
            save();
            audit("DIFF_BACKUP_ATTACHED", current.diffId(), "target=" + target);
        } catch (IOException e) {
            audit("DIFF_BACKUP_FAILED", current.diffId(), e.getClass().getSimpleName());
        }
    }

    public synchronized Optional<DiffEntry> review(String diffId, String decision) {
        int i = indexOf(diffId);
        if (i < 0) return Optional.empty();
        DiffEntry current = entries.get(i);
        String normalized = decision == null ? "" : decision.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("APPROVED", "REJECTED", "PENDING").contains(normalized)) normalized = "PENDING";
        DiffEntry updated = new DiffEntry(current.diffId(), current.requestId(), current.agentId(), current.action(),
                current.target(), current.risk(), current.snapshotId(), current.deltaCount(), current.meetsExpectation(),
                current.createdAt(), normalized, current.reverted(), current.revertedAt(), current.backupPath(), current.existedBefore());
        entries.set(i, updated); save();
        audit("DIFF_REVIEWED", diffId, "decision=" + normalized);
        return Optional.of(updated);
    }

    /** Explicit user-triggered undo. Reuses ActionGovernor's snapshot and risk checks. */
    public synchronized Optional<DiffEntry> revert(String diffId) {
        int i = indexOf(diffId);
        if (i < 0) return Optional.empty();
        DiffEntry current = entries.get(i);
        if (current.reverted() || !"APPROVED".equals(current.review())) {
            audit("DIFF_REVERT_DENIED", diffId, "review must be APPROVED and entry must be active");
            return Optional.empty();
        }
        audit("DIFF_REVERT_REQUESTED", diffId, "requestId=" + current.requestId());
        // Prefer the target-scoped IDE before-image when present. If that durable
        // restore exists but fails, do not claim success via an empty/no-op
        // snapshot; the governor fallback is only for non-IDE entries.
        boolean hasBackup = current.backupPath() != null && !current.backupPath().isBlank();
        boolean backupRestored = hasBackup && restoreBackup(current);
        boolean governorRestored;
        if (hasBackup) {
            governorRestored = backupRestored;
            if (backupRestored) ActionGovernor.getInstance().markUndone(current.requestId());
        } else {
            governorRestored = ActionGovernor.getInstance().undo(current.requestId());
        }
        if (!governorRestored && !backupRestored) {
            audit("DIFF_REVERT_DENIED", diffId, "snapshot unavailable or action is not undoable");
            return Optional.empty();
        }
        DiffEntry updated = new DiffEntry(current.diffId(), current.requestId(), current.agentId(), current.action(),
                current.target(), current.risk(), current.snapshotId(), current.deltaCount(), current.meetsExpectation(),
                current.createdAt(), current.review(), true, System.currentTimeMillis(), current.backupPath(), current.existedBefore());
        entries.set(i, updated); save();
        audit("DIFF_REVERTED", diffId, "requestId=" + current.requestId());
        return Optional.of(updated);
    }

    private int indexOf(String id) {
        if (id == null) return -1;
        for (int i = 0; i < entries.size(); i++) if (id.equals(entries.get(i).diffId())) return i;
        return -1;
    }

    private int indexByRequest(String requestId) {
        if (requestId == null) return -1;
        for (int i = 0; i < entries.size(); i++) {
            if (requestId.equals(entries.get(i).requestId())) return i;
        }
        return -1;
    }

    private boolean restoreBackup(DiffEntry entry) {
        if (entry.backupPath() == null || entry.backupPath().isBlank()) return false;
        try {
            Path backup = Path.of(entry.backupPath()).toAbsolutePath().normalize();
            Path allowed = Path.of(AiosPaths.aiosHome(), "var", "diff-backups").toAbsolutePath().normalize();
            if (!backup.startsWith(allowed) || !Files.isRegularFile(backup)) return false;
            if (!entry.existedBefore()) return VfsManager.instance().unmount(entry.target());
            return VfsManager.instance().writeText(entry.target(),
                    Files.readString(backup, StandardCharsets.UTF_8));
        } catch (Exception e) {
            audit("DIFF_BACKUP_RESTORE_FAILED", entry.diffId(), e.getClass().getSimpleName());
            return false;
        }
    }

    private String targetOf(ActionRecord record) {
        if (record == null || record.request() == null) return "";
        if (record.request().payload() instanceof StoragePayload storage) return storage.path();
        String raw = record.request().paramString("path");
        return raw == null ? "" : raw;
    }

    private void load() {
        try {
            if (Files.exists(file)) {
                List<DiffEntry> loaded = JSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), LIST_TYPE);
                if (loaded != null) entries.addAll(loaded);
            }
        } catch (Exception ignored) { }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.toJson(entries), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ignored) { }
    }

    private void audit(String decision, String target, String reason) {
        UnifiedAuditLog.append(new UnifiedAuditLog.TimelineEvent(
                UnifiedAuditLog.LAYER_DIFF, decision, decision, null, target, reason,
                UnifiedAuditLog.AuditContext.current()));
    }
}
