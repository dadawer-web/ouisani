package com.ouisani.aios.core.plan;

import com.ouisani.aios.core.network.EventBus;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VersionedPlanStore 守护进程测试 — sweep 翻转、复活、广播、持久化。
 */
class VersionedPlanStoreTest {

    private VersionedPlanStore store;
    private CopyOnWriteArrayList<String> planVersionPayloads;
    private CopyOnWriteArrayList<String> recompilePayloads;
    private Consumer<String> planVersionHandler;
    private Consumer<String> recompileHandler;

    @BeforeEach
    void setUp() {
        store = VersionedPlanStore.instance();
        // 覆盖常量为短阈值
        VersionedPlanStore.staleAfterMs = 50;
        VersionedPlanStore.recompileAfterMs = 100;

        planVersionPayloads = new CopyOnWriteArrayList<>();
        recompilePayloads = new CopyOnWriteArrayList<>();
        planVersionHandler = planVersionPayloads::add;
        recompileHandler = recompilePayloads::add;
        EventBus.instance().subscribe("plan_version", planVersionHandler);
        EventBus.instance().subscribe("topology_recompile_needed", recompileHandler);
    }

    @AfterEach
    void tearDown() {
        EventBus.instance().unsubscribe("plan_version", planVersionHandler);
        EventBus.instance().unsubscribe("topology_recompile_needed", recompileHandler);
    }

    @Test
    void sweep_flips_running_to_stale_after_threshold() {
        String swarmId = "sweep-flip-test";
        VersionedPlan plan = store.getOrCreatePlan(swarmId);
        PlanItem item = PlanItem.queued("t1", "task", "medium", null, null, null);
        plan.replaceItems(List.of(item), Set.of());
        plan.startTask("t1");

        // 注入旧心跳（50ms 前已陈旧）
        plan.putProgress("t1", new SwarmTaskProgress(
                "sess", null, 1000L, 1000L, 1000L, "old", null, null, null, null, 1L, 0L
        ));

        long now = 2000L; // 1000ms 后，远超 staleAfterMs=50
        store.sweepOnce(now);

        assertEquals("running_stale", plan.findItem("t1").status(),
                "running task with stale heartbeat → running_stale");
        assertNotNull(plan.progress("t1").staleSinceUnixMs(),
                "stale_since set on flip");
    }

    @Test
    void heartbeat_revives_stale_task() {
        String swarmId = "revive-test";
        VersionedPlan plan = store.getOrCreatePlan(swarmId);
        PlanItem item = PlanItem.queued("t1", "task", "medium", null, null, null);
        plan.replaceItems(List.of(item), Set.of());
        plan.startTask("t1");
        plan.flipToStale("t1", 1000L);

        assertEquals("running_stale", plan.findItem("t1").status());

        // 发心跳 → revive
        store.recordHeartbeat(swarmId, "t1", "sess", "alive");

        assertEquals("running", plan.findItem("t1").status(), "heartbeat revives stale task");
        assertNull(plan.progress("t1").staleSinceUnixMs(), "stale_since cleared");
    }

    @Test
    void recompile_needed_broadcast_when_stale_for_recompile_after() {
        String swarmId = "recompile-test";
        VersionedPlan plan = store.getOrCreatePlan(swarmId);
        PlanItem item = PlanItem.queued("t1", "task", "medium", null, null, null);
        plan.replaceItems(List.of(item), Set.of());
        plan.startTask("t1");

        // 直接置 running_stale，stale_since = 1000
        plan.flipToStale("t1", 1000L);

        // now = 2000, stale_for = 1000 > recompileAfterMs=100
        store.sweepOnce(2000L);

        assertFalse(recompilePayloads.isEmpty(),
                "stale_for exceeds recompileAfter → broadcast topology_recompile_needed");
        assertTrue(recompilePayloads.get(0).contains("t1"),
                "recompile payload contains stale task id");
    }

    @Test
    void plan_version_broadcast_on_start_task() {
        String swarmId = "broadcast-test";
        VersionedPlan plan = store.getOrCreatePlan(swarmId);
        PlanItem item = PlanItem.queued("t1", "task", "medium", null, null, null);
        plan.replaceItems(List.of(item), Set.of());

        planVersionPayloads.clear();
        store.startTask(swarmId, "t1");

        // EventBus 异步派发到虚拟线程 — 等待 handler 投递 payload 后再断言
        Awaitility.await().atMost(2, TimeUnit.SECONDS)
                .until(() -> !planVersionPayloads.isEmpty());
        assertTrue(planVersionPayloads.get(0).contains("\"reason\":\"start_task\""));
    }

    @Test
    void plan_version_broadcast_contains_newly_ready_ids() {
        String swarmId = "newly-ready-test";
        VersionedPlan plan = store.getOrCreatePlan(swarmId);
        PlanItem a = PlanItem.queued("a", "A", "high", null, null, null);
        PlanItem b = PlanItem.queued("b", "B", "medium", null, null, List.of("a"));
        plan.replaceItems(List.of(a, b), Set.of());
        plan.startTask("a");

        planVersionPayloads.clear();
        store.completeTask(swarmId, "a");

        // EventBus 异步派发 — 等待 payload 到达后再检查 newlyReadyIds
        Awaitility.await().atMost(2, TimeUnit.SECONDS)
                .until(() -> !planVersionPayloads.isEmpty());
        // a completed → b becomes ready → newlyReadyIds should contain b
        boolean foundNewlyReady = false;
        for (String payload : planVersionPayloads) {
            if (payload.contains("\"b\"")) {
                foundNewlyReady = true;
                break;
            }
        }
        assertTrue(foundNewlyReady, "broadcast contains newly ready id 'b' after 'a' completes");
    }

    @Test
    void persistence_round_trip_forces_running_to_running_stale() {
        String swarmId = "persistence-test";
        VersionedPlan plan = store.getOrCreatePlan(swarmId);
        PlanItem item = PlanItem.queued("t1", "task", "medium", null, null, null);
        plan.replaceItems(List.of(item), Set.of());
        plan.startTask("t1");
        assertEquals("running", plan.findItem("t1").status());

        // 保存
        VersionedPlanPersistence.save(swarmId, plan);

        // 加载
        VersionedPlan loaded = VersionedPlanPersistence.load(swarmId);
        assertNotNull(loaded, "loaded plan not null");
        assertEquals("running_stale", loaded.findItem("t1").status(),
                "running → running_stale on reload");
        assertNotNull(loaded.progress("t1").staleSinceUnixMs(),
                "stale_since set on reload");
    }
}
