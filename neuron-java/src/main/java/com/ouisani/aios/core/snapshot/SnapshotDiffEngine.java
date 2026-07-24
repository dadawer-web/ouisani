package com.ouisani.aios.core.snapshot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 快照差异引擎 — 借鉴 mobilegym 的结构化 state-diff,对两份 {@link EnvironmentSnapshot}
 * 计算 {@link StateDiff}。
 * <p>
 * 用 Java 21 sealed switch 对 6 个 {@link SnapshotSection} record 做穷尽分支:
 * <ul>
 *   <li>纯数据 section(NodeOutput/Carryover/Boulder)做字段级 diff,产出细粒度 {@link FieldDelta}</li>
 *   <li>不透明 section(Process/Hibernation/Vfs,内嵌大对象)退化为 {@code equals} 浅比,
 *       不等时记单个 CHANGED delta(fieldPath="<opaque>")</li>
 * </ul>
 * 保持 record 纯数据,所有 diff 逻辑集中在本引擎(契约层内,不依赖 user 态)。
 */
public final class SnapshotDiffEngine {

    private SnapshotDiffEngine() {}

    /**
     * 计算两份快照的差异。
     *
     * @param before 之前快照(可为 null,视为空)
     * @param after  之后快照(可为 null,视为空)
     * @param exp    期望约束(用于判定 meetsExpectation)
     * @return StateDiff
     */
    public static StateDiff diff(EnvironmentSnapshot before, EnvironmentSnapshot after, DiffExpectation exp) {
        Map<String, SnapshotSection> beforeSecs = before != null ? before.sections() : Map.of();
        Map<String, SnapshotSection> afterSecs = after != null ? after.sections() : Map.of();

        Set<String> allTypes = new TreeSet<>(beforeSecs.keySet());
        allTypes.addAll(afterSecs.keySet());

        List<SectionDiff> sectionDiffs = new ArrayList<>();
        int totalDeltas = 0;
        for (String type : allTypes) {
            SnapshotSection b = beforeSecs.get(type);
            SnapshotSection a = afterSecs.get(type);
            SectionDiff sd;
            if (b == null && a == null) {
                continue;
            } else if (b == null) {
                sd = new SectionDiff(type,
                        List.of(new FieldDelta(type, "<section>", DeltaKind.ADDED, null, a)),
                        false);
            } else if (a == null) {
                sd = new SectionDiff(type,
                        List.of(new FieldDelta(type, "<section>", DeltaKind.REMOVED, b, null)),
                        false);
            } else {
                sd = diffSection(b, a);
            }
            sectionDiffs.add(sd);
            totalDeltas += sd.deltas().size();
        }

        StateDiff stateDiff = new StateDiff(sectionDiffs, totalDeltas, false);
        boolean meets = exp.validate(stateDiff);
        return new StateDiff(sectionDiffs, totalDeltas, meets);
    }

    /**
     * 对同类型两 section 做字段级 diff。类型不一致时退化为整体 CHANGED。
     */
    static SectionDiff diffSection(SnapshotSection before, SnapshotSection after) {
        if (before.getClass() != after.getClass()) {
            return new SectionDiff(before.sectionType(),
                    List.of(new FieldDelta(before.sectionType(), "<section>", DeltaKind.CHANGED, before, after)),
                    false);
        }
        List<FieldDelta> deltas = new ArrayList<>();
        switch (before) {
            case NodeOutputSection b -> diffNodeOutput(b, (NodeOutputSection) after, deltas);
            case CarryoverSection b -> diffCarryover(b, (CarryoverSection) after, deltas);
            case BoulderSection b -> diffBoulder(b, (BoulderSection) after, deltas);
            case OverlayDiffSection b -> diffOverlayDiff(b, (OverlayDiffSection) after, deltas);
            case ProcessSection b -> diffOpaque(b, after, deltas);
            case HibernationSection b -> diffOpaque(b, after, deltas);
            case VfsSection b -> diffOpaque(b, after, deltas);
        }
        return new SectionDiff(before.sectionType(), deltas, deltas.isEmpty());
    }

    // ── 字段级 diff:NodeOutput ──
    private static void diffNodeOutput(NodeOutputSection before, NodeOutputSection after, List<FieldDelta> out) {
        diffMap(before.nodeOutputs(), after.nodeOutputs(), "NodeOutput", "nodeOutputs", out);
    }

    // ── 字段级 diff:Carryover ──
    private static void diffCarryover(CarryoverSection before, CarryoverSection after, List<FieldDelta> out) {
        diffMap(before.taskFocus(), after.taskFocus(), "Carryover", "taskFocus", out);
        diffMap(before.readFiles(), after.readFiles(), "Carryover", "readFiles", out);
        diffMap(before.invokedTools(), after.invokedTools(), "Carryover", "invokedTools", out);
        if (!Objects.equals(before.workLog(), after.workLog())) {
            out.add(new FieldDelta("Carryover", "workLog", DeltaKind.CHANGED, before.workLog(), after.workLog()));
        }
    }

    // ── 字段级 diff:Boulder ──
    private static void diffBoulder(BoulderSection before, BoulderSection after, List<FieldDelta> out) {
        diffScalar(before.status(), after.status(), "Boulder", "status", out);
        diffMap(before.outputSnapshot(), after.outputSnapshot(), "Boulder", "outputSnapshot", out);
        diffMap(before.carryoverSnapshot(), after.carryoverSnapshot(), "Boulder", "carryoverSnapshot", out);
        diffScalar(before.errorMessage(), after.errorMessage(), "Boulder", "errorMessage", out);
        if (before.durationMs() != after.durationMs()) {
            out.add(new FieldDelta("Boulder", "durationMs", DeltaKind.CHANGED, before.durationMs(), after.durationMs()));
        }
        if (before.retryCount() != after.retryCount()) {
            out.add(new FieldDelta("Boulder", "retryCount", DeltaKind.CHANGED, before.retryCount(), after.retryCount()));
        }
        diffScalar(before.environmentSnapshotId(), after.environmentSnapshotId(), "Boulder", "environmentSnapshotId", out);
    }

    // ── 浅比:Process/Hibernation/Vfs(record equals 已结构化)──
    private static void diffOpaque(SnapshotSection before, SnapshotSection after, List<FieldDelta> out) {
        if (!Objects.equals(before, after)) {
            out.add(new FieldDelta(before.sectionType(), "<opaque>", DeltaKind.CHANGED, before, after));
        }
    }

    // ── 字段级 diff:OverlayDiff(relPath → content,检测意外文件丢失)──
    private static void diffOverlayDiff(OverlayDiffSection before, OverlayDiffSection after, List<FieldDelta> out) {
        diffMap(before.files(), after.files(), "OverlayDiff", "files", out);
    }

    // ── 辅助:Map 逐键 diff ──
    private static <K, V> void diffMap(Map<K, V> before, Map<K, V> after,
                                       String sectionType, String prefix, List<FieldDelta> out) {
        Map<K, V> b = before != null ? before : Map.of();
        Map<K, V> a = after != null ? after : Map.of();
        Set<K> keys = new LinkedHashSet<>();
        keys.addAll(b.keySet());
        keys.addAll(a.keySet());
        for (K key : keys) {
            String path = prefix + "." + key;
            if (!b.containsKey(key)) {
                out.add(new FieldDelta(sectionType, path, DeltaKind.ADDED, null, a.get(key)));
            } else if (!a.containsKey(key)) {
                out.add(new FieldDelta(sectionType, path, DeltaKind.REMOVED, b.get(key), null));
            } else if (!Objects.equals(b.get(key), a.get(key))) {
                out.add(new FieldDelta(sectionType, path, DeltaKind.CHANGED, b.get(key), a.get(key)));
            }
        }
    }

    // ── 辅助:标量 diff ──
    private static void diffScalar(Object before, Object after,
                                   String sectionType, String field, List<FieldDelta> out) {
        if (!Objects.equals(before, after)) {
            out.add(new FieldDelta(sectionType, field, DeltaKind.CHANGED, before, after));
        }
    }
}
