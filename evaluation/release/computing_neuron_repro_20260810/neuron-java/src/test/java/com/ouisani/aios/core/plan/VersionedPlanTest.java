package com.ouisani.aios.core.plan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VersionedPlan 版本递增规则测试 — 镜像 jcode swarm.rs:175-226。
 */
class VersionedPlanTest {

    @Test
    void heartbeat_does_not_bump_version() {
        VersionedPlan plan = newPlan("t1");
        long v0 = plan.version();

        plan.startTask("t1");
        long v1 = plan.version();
        assertEquals(v0 + 1, v1, "startTask bumps version");

        plan.recordHeartbeat("t1", "session1", "alive", System.currentTimeMillis());
        assertEquals(v1, plan.version(), "heartbeat does NOT bump version");
    }

    @Test
    void checkpoint_does_not_bump_version() {
        VersionedPlan plan = newPlan("t1");
        plan.startTask("t1");
        long v = plan.version();

        plan.recordCheckpoint("t1", "step done", System.currentTimeMillis());
        assertEquals(v, plan.version(), "checkpoint does NOT bump version");
    }

    @Test
    void startTask_completeTask_bump_version() {
        VersionedPlan plan = newPlan("t1");
        long v0 = plan.version();

        plan.startTask("t1");
        assertEquals(v0 + 1, plan.version(), "startTask bumps +1");

        plan.completeTask("t1");
        assertEquals(v0 + 2, plan.version(), "completeTask bumps +1");
    }

    @Test
    void assignTask_bumps_version() {
        VersionedPlan plan = newPlan("t1");
        long v0 = plan.version();

        plan.assignTask("t1", "worker1", "session1");
        assertEquals(v0 + 1, plan.version(), "assignTask bumps +1");
        assertEquals("worker1", plan.findItem("t1").assignedTo());
    }

    @Test
    void flipToStale_then_revive_bumps_version_twice() {
        VersionedPlan plan = newPlan("t1");
        plan.startTask("t1");
        long v = plan.version();

        long now = System.currentTimeMillis();
        assertTrue(plan.flipToStale("t1", now));
        assertEquals(v + 1, plan.version(), "flipToStale bumps +1");
        assertEquals("running_stale", plan.findItem("t1").status());

        assertTrue(plan.revive("t1", now + 1000));
        assertEquals(v + 2, plan.version(), "revive bumps +1");
        assertEquals("running", plan.findItem("t1").status());
    }

    @Test
    void stale_since_get_or_insert_does_not_overwrite() {
        VersionedPlan plan = newPlan("t1");
        plan.startTask("t1");
        long now = System.currentTimeMillis();

        plan.flipToStale("t1", now);
        SwarmTaskProgress p1 = plan.progress("t1");
        assertNotNull(p1.staleSinceUnixMs());
        assertEquals(now, p1.staleSinceUnixMs(), "first stale_since = now");

        // flip again (should not change status since already running_stale)
        assertFalse(plan.flipToStale("t1", now + 5000), "already running_stale → no-op");
        SwarmTaskProgress p2 = plan.progress("t1");
        assertEquals(now, p2.staleSinceUnixMs(), "get_or_insert does not overwrite");
    }

    @Test
    void revive_clears_stale_since() {
        VersionedPlan plan = newPlan("t1");
        plan.startTask("t1");
        long now = System.currentTimeMillis();

        plan.flipToStale("t1", now);
        assertNotNull(plan.progress("t1").staleSinceUnixMs());

        plan.revive("t1", now + 1000);
        assertNull(plan.progress("t1").staleSinceUnixMs(), "revive clears stale_since");
    }

    @Test
    void replaceItems_bumps_version() {
        VersionedPlan plan = new VersionedPlan();
        long v0 = plan.version();

        PlanItem a = PlanItem.queued("a", "A", "high", null, null, null);
        plan.replaceItems(List.of(a), Set.of("worker1"));

        assertEquals(v0 + 1, plan.version(), "replaceItems bumps +1");
        assertEquals(1, plan.snapshotItems().size());
        assertTrue(plan.participants().contains("worker1"));
    }

    @Test
    void heartbeat_revives_running_stale() {
        VersionedPlan plan = newPlan("t1");
        plan.startTask("t1");
        long now = System.currentTimeMillis();
        plan.flipToStale("t1", now);
        long v = plan.version();

        // heartbeat on running_stale → revive (version+1)
        plan.recordHeartbeat("t1", "session1", "back alive", now + 2000);

        assertEquals(v + 1, plan.version(), "heartbeat on running_stale triggers revive → version+1");
        assertEquals("running", plan.findItem("t1").status());
        assertNull(plan.progress("t1").staleSinceUnixMs(), "stale_since cleared on revive");
    }

    @Test
    void summarizeGraph_returns_valid_summary() {
        VersionedPlan plan = new VersionedPlan();
        PlanItem a = PlanItem.queued("a", "A", "high", null, null, null);
        PlanItem b = PlanItem.queued("b", "B", "medium", null, null, List.of("a"));
        plan.replaceItems(List.of(a, b), Set.of());

        PlanGraphSummary s = plan.summarizeGraph();
        assertTrue(s.readyIds().contains("a"));
        assertTrue(s.blockedIds().contains("b"));
    }

    private VersionedPlan newPlan(String taskId) {
        VersionedPlan plan = new VersionedPlan();
        PlanItem item = PlanItem.queued(taskId, "task " + taskId, "medium", null, null, null);
        plan.replaceItems(List.of(item), Set.of());
        return plan;
    }
}
