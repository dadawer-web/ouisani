package com.ouisani.aios.core.plan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PlanGraphQuery 纯函数测试 — 镜像 jcode test 670-886。
 */
class PlanGraphQueryTest {

    @Test
    void summarize_reports_ready_and_blocked() {
        PlanItem a = PlanItem.queued("a", "task A", "high", null, null, null);
        PlanItem b = PlanItem.queued("b", "task B", "medium", null, null, List.of("a"));

        PlanGraphSummary s = PlanGraphQuery.summarize(List.of(a, b));

        assertTrue(s.readyIds().contains("a"), "a has no deps → ready");
        assertTrue(s.blockedIds().contains("b"), "b blocked by a → blocked");
        assertFalse(s.readyIds().contains("b"));
    }

    @Test
    void summarize_reports_missing_dependencies() {
        PlanItem a = PlanItem.queued("a", "task A", "high", null, null, List.of("nonexistent"));

        PlanGraphSummary s = PlanGraphQuery.summarize(List.of(a));

        assertTrue(s.unresolvedDependencyIds().contains("a"),
                "a depends on unknown id → unresolved");
        assertFalse(s.readyIds().contains("a"));
    }

    @Test
    void summarize_reports_cycle_three_nodes() {
        // 三节点环：a → c → b → a
        PlanItem a = PlanItem.queued("a", "A", "medium", null, null, List.of("c"));
        PlanItem b = PlanItem.queued("b", "B", "medium", null, null, List.of("a"));
        PlanItem c = PlanItem.queued("c", "C", "medium", null, null, List.of("b"));

        List<String> cycles = PlanGraphQuery.cycleItemIds(List.of(a, b, c));

        assertEquals(List.of("a", "b", "c"), cycles, "all three form a cycle");
    }

    @Test
    void newly_ready_diffs_before_after() {
        PlanItem a = PlanItem.queued("a", "A", "high", null, null, null);
        PlanItem b = PlanItem.queued("b", "B", "medium", null, null, List.of("a"));

        List<PlanItem> before = List.of(a, b);
        // After: a completed
        PlanItem aDone = a.withStatus("completed");
        List<PlanItem> after = List.of(aDone, b);

        List<String> newlyReady = PlanGraphQuery.newlyReadyItemIds(before, after);

        assertTrue(newlyReady.contains("b"), "b becomes ready after a completes");
        assertFalse(newlyReady.contains("a"));
    }

    @Test
    void priority_rank_orders_runnable() {
        PlanItem low = PlanItem.queued("low", "L", "low", null, null, null);
        PlanItem high = PlanItem.queued("high", "H", "high", null, null, null);
        PlanItem medium = PlanItem.queued("med", "M", "medium", null, null, null);

        List<String> next = PlanGraphQuery.nextRunnableItemIds(List.of(low, high, medium), 3);

        assertEquals("high", next.get(0), "high priority first");
        assertEquals("med", next.get(1), "medium priority second");
        assertEquals("low", next.get(2), "low priority last");
    }

    @Test
    void affinities_count_dependency_and_metadata_carryover() {
        // target depends on dep1, dep1 assigned to "worker1"
        PlanItem dep1 = PlanItem.queued("dep1", "D1", "medium", "vfs", List.of("Main.java"), null)
                .withAssignedTo("worker1")
                .withStatus("completed");
        // worker2 has done a task with matching subsystem + overlapping file
        PlanItem other = PlanItem.queued("other", "O", "medium", "memory", List.of("Util.java"), null)
                .withAssignedTo("worker2")
                .withStatus("completed");
        // target: subsystem=memory, files=[Main.java, Util.java], blockedBy=[dep1]
        PlanItem target = PlanItem.queued("target", "T", "high", "memory",
                List.of("Main.java", "Util.java"), List.of("dep1"));

        PlanGraphQuery.AssignmentAffinities aff = PlanGraphQuery.affinitiesForTask(
                List.of(dep1, other, target),
                Set.of("worker1", "worker2"),
                "target"
        );

        // worker1: dependency carryover +1 (completed dep1)
        assertEquals(1, aff.dependencyCarryover().get("worker1"),
                "worker1 completed dependency → +1");
        // worker2: no dependency carryover
        assertEquals(0, aff.dependencyCarryover().get("worker2"));

        // worker2: subsystem match +2 (memory), fileScope overlap +1 (Util.java)
        assertTrue(aff.metadataCarryover().get("worker2") >= 2,
                "worker2 has subsystem match +2 and file overlap +1");
        // worker1: subsystem mismatch (vfs vs memory), but fileScope overlap +1 (Main.java)
        assertEquals(1, aff.metadataCarryover().get("worker1"),
                "worker1 has file overlap +1 only (subsystem mismatch)");
    }

    @Test
    void cycle_detection_empty_and_single() {
        assertTrue(PlanGraphQuery.cycleItemIds(List.of()).isEmpty(), "empty → no cycle");
        PlanItem single = PlanItem.queued("a", "A", "medium", null, null, null);
        assertTrue(PlanGraphQuery.cycleItemIds(List.of(single)).isEmpty(), "single no-dep → no cycle");
    }

    @Test
    void self_referencing_cycle_detected() {
        PlanItem self = PlanItem.queued("a", "A", "medium", null, null, List.of("a"));
        assertEquals(List.of("a"), PlanGraphQuery.cycleItemIds(List.of(self)),
                "self-reference is a cycle");
    }
}
