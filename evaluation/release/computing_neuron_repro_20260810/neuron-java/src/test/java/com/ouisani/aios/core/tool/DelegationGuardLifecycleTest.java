package com.ouisani.aios.core.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DelegationGuard} 生命周期控制单测 — 借鉴 3（max_depth 配置化）+ 广度界。
 * <p>
 * 覆盖三维派生预算：深度（可配置）/ 广度（per-agent）/ 全局总数（进程级），
 * 以及 {@link DelegationGuard#checkSpawnAllowed()} 的预检查逻辑。
 * <p>
 * 注意：DelegationGuard 的上限是 static volatile（进程级），每个测试后必须重置为默认值，
 * 防止配置泄漏影响其他测试（如 DelegationGuardTest 依赖默认 depth=3）。
 */
class DelegationGuardLifecycleTest {

    @AfterEach
    void resetConfig() {
        // 重置为默认值，防止 static volatile 配置跨测试泄漏
        DelegationGuard.configureMaxDepth(DelegationGuard.DEFAULT_MAX_DEPTH);
        DelegationGuard.configureMaxSubagentsPerNode(DelegationGuard.DEFAULT_MAX_SUBAGENTS_PER_NODE);
        DelegationGuard.configureMaxTotalSpawns(DelegationGuard.DEFAULT_MAX_TOTAL_SPAWNS);
        DelegationGuard.resetTotalSpawns();
        DelegationGuard.clear();
    }

    // ════════════════════════════════════════════════════════════════
    //  借鉴 3：max_depth 配置化
    // ════════════════════════════════════════════════════════════════

    @Test
    void maxDepth_defaultsTo3_whenEnvNotSet() {
        assertEquals(3, DelegationGuard.maxDepth(),
                "默认 maxDepth 应为 3（env 未设置时）");
    }

    @Test
    void configureMaxDepth_changesLimit() {
        // 配置为 2：A(0)→B(1) 允许，B→C 拒绝
        DelegationGuard.configureMaxDepth(2);
        assertEquals(2, DelegationGuard.maxDepth());

        DelegationGuard.DelegationContext ctx1 = DelegationGuard.enter("A", "B");
        DelegationGuard.activate(ctx1);
        assertEquals(1, DelegationGuard.currentDepth());

        // depth=1, maxDepth=2 → 1 < 2 允许 B→C
        DelegationGuard.DelegationContext ctx2 = DelegationGuard.enter("B", "C");
        DelegationGuard.activate(ctx2);
        assertEquals(2, DelegationGuard.currentDepth());

        // depth=2 >= maxDepth=2 → 拒绝 C→D
        assertThrows(DelegationGuard.DelegationException.class,
                () -> DelegationGuard.enter("C", "D"));
    }

    @Test
    void configureMaxDepth_invalidValue_throws() {
        assertThrows(IllegalArgumentException.class, () -> DelegationGuard.configureMaxDepth(0));
        assertThrows(IllegalArgumentException.class, () -> DelegationGuard.configureMaxDepth(-1));
    }

    // ════════════════════════════════════════════════════════════════
    //  checkSpawnAllowed — 三维预检查
    // ════════════════════════════════════════════════════════════════

    @Test
    void checkSpawnAllowed_allowsWithinLimits() {
        assertNull(DelegationGuard.checkSpawnAllowed(),
                "深度/广度/总数均未超限时应返回 null");
    }

    @Test
    void checkSpawnAllowed_returnsDepthWhenAtLimit() {
        DelegationGuard.activate(new DelegationGuard.DelegationContext(
                DelegationGuard.maxDepth(), java.util.Set.of(), "leaf"));
        assertEquals("depth", DelegationGuard.checkSpawnAllowed(),
                "深度达上限应返回 'depth'");
    }

    @Test
    void checkSpawnAllowed_returnsBreadthWhenExceeded() {
        // 配置单 agent 最多派生 1 个子 agent
        DelegationGuard.configureMaxSubagentsPerNode(1);
        // 派生一次 → breadth=1
        DelegationGuard.enter("mother", "child1");
        assertEquals(1, DelegationGuard.currentBreadth());
        // breadth=1 >= maxSubagentsPerNode=1 → "breadth"
        assertEquals("breadth", DelegationGuard.checkSpawnAllowed());
    }

    @Test
    void checkSpawnAllowed_returnsTotalWhenExceeded() {
        // 配置全局最多派生 1 个
        DelegationGuard.configureMaxTotalSpawns(1);
        DelegationGuard.enter("mother", "child1");
        assertEquals(1, DelegationGuard.totalSpawns());
        // total=1 >= maxTotalSpawns=1 → "total"（depth/breadth 未超，先检查 depth 再 breadth 再 total）
        assertEquals("total", DelegationGuard.checkSpawnAllowed());
    }

    @Test
    void checkSpawnAllowed_depthTakesPrecedenceOverBreadthAndTotal() {
        // 三个维度同时超限，depth 优先返回
        DelegationGuard.configureMaxSubagentsPerNode(1);
        DelegationGuard.configureMaxTotalSpawns(1);
        DelegationGuard.enter("mother", "child1");  // breadth=1, total=1
        // 再 activate 到叶子层使 depth 也超限
        DelegationGuard.activate(new DelegationGuard.DelegationContext(
                DelegationGuard.maxDepth(), java.util.Set.of(), "leaf"));
        assertEquals("depth", DelegationGuard.checkSpawnAllowed(),
                "depth 应优先于 breadth/total");
    }

    // ════════════════════════════════════════════════════════════════
    //  广度计数 — per-agent，activate 时重置
    // ════════════════════════════════════════════════════════════════

    @Test
    void breadthCounter_incrementsOnEnter() {
        assertEquals(0, DelegationGuard.currentBreadth());
        DelegationGuard.enter("A", "B1");
        assertEquals(1, DelegationGuard.currentBreadth());
        DelegationGuard.enter("A", "B2");
        assertEquals(2, DelegationGuard.currentBreadth());
        DelegationGuard.enter("A", "B3");
        assertEquals(3, DelegationGuard.currentBreadth());
    }

    @Test
    void breadthCounter_resetsOnActivate() {
        // 母体派生 2 个子 agent
        DelegationGuard.enter("A", "B1");
        DelegationGuard.enter("A", "B2");
        assertEquals(2, DelegationGuard.currentBreadth());

        // 激活子 agent 上下文 → 广度重置为 0（子 agent 有独立预算）
        DelegationGuard.DelegationContext childCtx = DelegationGuard.enter("A", "B1");
        DelegationGuard.activate(childCtx);
        assertEquals(0, DelegationGuard.currentBreadth(),
                "子 agent activate 后广度应重置为 0");
    }

    // ════════════════════════════════════════════════════════════════
    //  全局总数 — 进程级，跨 activate 累积
    // ════════════════════════════════════════════════════════════════

    @Test
    void totalSpawns_accumulatesAcrossActivate() {
        DelegationGuard.resetTotalSpawns();
        assertEquals(0, DelegationGuard.totalSpawns());

        DelegationGuard.DelegationContext ctx1 = DelegationGuard.enter("A", "B");
        assertEquals(1, DelegationGuard.totalSpawns());
        DelegationGuard.activate(ctx1);

        // 子 agent 派生 → total 继续 +1（不因 activate 重置）
        DelegationGuard.enter("B", "C");
        assertEquals(2, DelegationGuard.totalSpawns(),
                "全局总数应跨 activate 累积");
    }

    @Test
    void resetTotalSpawns_zeroesCounter() {
        DelegationGuard.enter("A", "B");
        DelegationGuard.enter("A", "B2");
        assertTrue(DelegationGuard.totalSpawns() >= 2);

        DelegationGuard.resetTotalSpawns();
        assertEquals(0, DelegationGuard.totalSpawns());
    }
}
