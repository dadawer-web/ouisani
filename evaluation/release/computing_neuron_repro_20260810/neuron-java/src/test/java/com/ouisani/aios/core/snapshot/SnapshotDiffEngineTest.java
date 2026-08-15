package com.ouisani.aios.core.snapshot;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SnapshotDiffEngine 单元测试 — 验证 sealed switch diff 对各 section 类型的字段级差异计算,
 * 以及 {@link DiffExpectation} 的 meetsExpectation 判定。
 */
class SnapshotDiffEngineTest {

    private EnvironmentSnapshot snap(String id, Map<String, SnapshotSection> sections) {
        return new EnvironmentSnapshot(id, System.currentTimeMillis(), "scope", sections);
    }

    private NodeOutputSection nodeOut(Object... kv) {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], new LinkedHashMap<>((Map<String, Object>) kv[i + 1]));
        }
        return new NodeOutputSection(m);
    }

    @Test
    void identicalSnapshots_produceNoDeltas() {
        EnvironmentSnapshot before = snap("s1", Map.of("NodeOutput",
                nodeOut("a", Map.of("v", 1))));
        EnvironmentSnapshot after = snap("s2", Map.of("NodeOutput",
                nodeOut("a", Map.of("v", 1))));

        StateDiff diff = SnapshotDiffEngine.diff(before, after, DiffExpectation.permissive());

        assertEquals(0, diff.totalDeltas());
        assertTrue(diff.meetsExpectation());
        assertTrue(diff.sectionDiffs().get(0).structurallyEqual());
    }

    @Test
    void nodeOutput_addedNode_emitsAddedDelta() {
        EnvironmentSnapshot before = snap("s1", Map.of("NodeOutput",
                nodeOut("a", Map.of("v", 1))));
        EnvironmentSnapshot after = snap("s2", Map.of("NodeOutput",
                nodeOut("a", Map.of("v", 1), "b", Map.of("v", 2))));

        StateDiff diff = SnapshotDiffEngine.diff(before, after, DiffExpectation.permissive());

        assertEquals(1, diff.totalDeltas());
        FieldDelta d = diff.sectionDiffs().get(0).deltas().get(0);
        assertEquals(DeltaKind.ADDED, d.kind());
        assertEquals("nodeOutputs.b", d.fieldPath());
    }

    @Test
    void nodeOutput_removedNode_emitsRemovedDelta() {
        EnvironmentSnapshot before = snap("s1", Map.of("NodeOutput",
                nodeOut("a", Map.of("v", 1), "b", Map.of("v", 2))));
        EnvironmentSnapshot after = snap("s2", Map.of("NodeOutput",
                nodeOut("a", Map.of("v", 1))));

        StateDiff diff = SnapshotDiffEngine.diff(before, after, DiffExpectation.permissive());

        assertEquals(1, diff.totalDeltas());
        FieldDelta d = diff.sectionDiffs().get(0).deltas().get(0);
        assertEquals(DeltaKind.REMOVED, d.kind());
        assertEquals("nodeOutputs.b", d.fieldPath());
    }

    @Test
    void nodeOutput_changedValue_emitsChangedDelta() {
        EnvironmentSnapshot before = snap("s1", Map.of("NodeOutput",
                nodeOut("a", Map.of("v", 1))));
        EnvironmentSnapshot after = snap("s2", Map.of("NodeOutput",
                nodeOut("a", Map.of("v", 999))));

        StateDiff diff = SnapshotDiffEngine.diff(before, after, DiffExpectation.permissive());

        assertEquals(1, diff.totalDeltas());
        FieldDelta d = diff.sectionDiffs().get(0).deltas().get(0);
        assertEquals(DeltaKind.CHANGED, d.kind());
        assertEquals("nodeOutputs.a", d.fieldPath());
    }

    @Test
    void carryover_fieldChange_emitsChangedDelta() {
        CarryoverSection before = new CarryoverSection(
                Map.of("goal", "v1"), Map.of("/a.txt", "1-10"), Map.of(), List.of());
        CarryoverSection after = new CarryoverSection(
                Map.of("goal", "v2"), Map.of("/a.txt", "1-10"), Map.of(), List.of());

        StateDiff diff = SnapshotDiffEngine.diff(snap("s1", Map.of("Carryover", before)),
                snap("s2", Map.of("Carryover", after)), DiffExpectation.permissive());

        assertEquals(1, diff.totalDeltas());
        FieldDelta d = diff.sectionDiffs().get(0).deltas().get(0);
        assertEquals("taskFocus.goal", d.fieldPath());
        assertEquals("v1", d.before());
        assertEquals("v2", d.after());
    }

    @Test
    void boulder_statusChange_emitsChangedDelta() {
        BoulderSection before = new BoulderSection("wf", "n1", "SUCCESS",
                Map.of(), Map.of(), null, 10L, 0, null);
        BoulderSection after = new BoulderSection("wf", "n1", "SUSPENDED",
                Map.of(), Map.of(), "err", 10L, 0, null);

        StateDiff diff = SnapshotDiffEngine.diff(snap("s1", Map.of("Boulder", before)),
                snap("s2", Map.of("Boulder", after)), DiffExpectation.permissive());

        // status 与 errorMessage 两处变更
        assertEquals(2, diff.totalDeltas());
    }

    @Test
    void vfsSection_opaqueShallowCompare_detectsChange() {
        VfsSection before = new VfsSection(List.of(
                new ProcessSnapshot.OpenHandle("/dev/a", "file", "c1")));
        VfsSection after = new VfsSection(List.of(
                new ProcessSnapshot.OpenHandle("/dev/a", "file", "c2")));

        StateDiff diff = SnapshotDiffEngine.diff(snap("s1", Map.of("Vfs", before)),
                snap("s2", Map.of("Vfs", after)), DiffExpectation.permissive());

        assertEquals(1, diff.totalDeltas());
        FieldDelta d = diff.sectionDiffs().get(0).deltas().get(0);
        assertEquals("<opaque>", d.fieldPath());
        assertFalse(diff.sectionDiffs().get(0).structurallyEqual());
    }

    @Test
    void sectionPresentOnlyInAfter_emitsSectionAdded() {
        EnvironmentSnapshot before = snap("s1", Map.of());
        EnvironmentSnapshot after = snap("s2", Map.of("NodeOutput", nodeOut("a", Map.of("v", 1))));

        StateDiff diff = SnapshotDiffEngine.diff(before, after, DiffExpectation.permissive());

        assertEquals(1, diff.totalDeltas());
        assertEquals(DeltaKind.ADDED, diff.sectionDiffs().get(0).deltas().get(0).kind());
    }

    @Test
    void diffExpectation_forbiddenChange_failsExpectation() {
        EnvironmentSnapshot before = snap("s1", Map.of("NodeOutput", nodeOut("a", Map.of("v", 1))));
        EnvironmentSnapshot after = snap("s2", Map.of("NodeOutput", nodeOut("a", Map.of("v", 2))));

        DiffExpectation exp = new DiffExpectation(Set.of(), Set.of("NodeOutput"));
        StateDiff diff = SnapshotDiffEngine.diff(before, after, exp);

        assertFalse(diff.meetsExpectation());
    }

    @Test
    void diffExpectation_allowedOnly_otherChangeFails() {
        EnvironmentSnapshot before = snap("s1", Map.of(
                "NodeOutput", nodeOut("a", Map.of("v", 1)),
                "Carryover", new CarryoverSection(Map.of("g", "1"), Map.of(), Map.of(), List.of())));
        EnvironmentSnapshot after = snap("s2", Map.of(
                "NodeOutput", nodeOut("a", Map.of("v", 2)),
                "Carryover", new CarryoverSection(Map.of("g", "2"), Map.of(), Map.of(), List.of())));

        // 只允许 NodeOutput 变更,Carryover 变更应使期望不满足
        DiffExpectation exp = new DiffExpectation(Set.of("NodeOutput"), Set.of());
        StateDiff diff = SnapshotDiffEngine.diff(before, after, exp);

        assertFalse(diff.meetsExpectation());
    }

    @Test
    void managerDiff_delegatesToEngine() {
        EnvironmentSnapshot before = snap("s1", Map.of("NodeOutput", nodeOut("a", Map.of("v", 1))));
        EnvironmentSnapshot after = snap("s2", Map.of("NodeOutput", nodeOut("a", Map.of("v", 2))));

        StateDiff diff = EnvironmentSnapshotManager.instance().diff(before, after);

        assertEquals(1, diff.totalDeltas());
        assertTrue(diff.meetsExpectation());
    }
}
