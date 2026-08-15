package com.ouisani.aios.core.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link DelegationGuard} per-workflow 作用域隔离单测。
 * <p>
 * 验证 {@link DelegationGuard.DelegationScope} 模型：每个工作流拥有独立的配置
 * （maxDepth/maxSubagentsPerNode/maxTotalSpawns）与计数器（totalSpawns），
 * 通过 ThreadLocal 绑定 + {@link DelegationGuard.DelegationContext} 传播实现并发工作流隔离。
 * <p>
 * 旧实现的进程级 static 配置会被并发工作流互相污染（一个 wf 调 maxDepth=2 影响所有 wf；
 * resetTotalSpawns 会清零别人的计数）。本套件验证这些污染已消除。
 * <p>
 * 测试模式：子虚拟线程收集结果到 AtomicReference，主线程断言 — 避免 v-thread 内断言丢失。
 */
class DelegationGuardScopeTest {

    @AfterEach
    void cleanup() {
        // 重置 GLOBAL_DEFAULT_SCOPE 配置为默认值（防止测试间泄漏）
        DelegationGuard.configureMaxDepth(DelegationGuard.DEFAULT_MAX_DEPTH);
        DelegationGuard.configureMaxSubagentsPerNode(DelegationGuard.DEFAULT_MAX_SUBAGENTS_PER_NODE);
        DelegationGuard.configureMaxTotalSpawns(DelegationGuard.DEFAULT_MAX_TOTAL_SPAWNS);
        DelegationGuard.resetTotalSpawns();
        // 清理主线程 ThreadLocal（SCOPE/DEPTH/BREADTH/CHAIN）
        DelegationGuard.clear();
    }

    // ════════════════════════════════════════════════════════════════
    //  并发工作流配置隔离
    // ════════════════════════════════════════════════════════════════

    @Test
    void twoScopes_withDifferentMaxDepth_dontInterfere() {
        // 主线程绑 wf-A (depth=2)
        DelegationGuard.bindScope(DelegationGuard.createScope("wf-A", 2));
        assertEquals(2, DelegationGuard.maxDepth(), "主线程 wf-A maxDepth 应为 2");

        // 子线程绑 wf-B (depth=5)，应互不干扰
        AtomicReference<Integer> bMaxDepth = new AtomicReference<>();
        Thread vt = Thread.startVirtualThread(() -> {
            try {
                DelegationGuard.bindScope(DelegationGuard.createScope("wf-B", 5));
                bMaxDepth.set(DelegationGuard.maxDepth());
            } finally {
                DelegationGuard.clear();
            }
        });
        joinQuietly(vt);

        assertEquals(5, bMaxDepth.get(), "子线程 wf-B maxDepth 应为 5");
        assertEquals(2, DelegationGuard.maxDepth(),
                "子线程绑 wf-B 后，主线程 wf-A maxDepth 仍应为 2（ThreadLocal 隔离）");
    }

    // ════════════════════════════════════════════════════════════════
    //  per-scope 计数器隔离
    // ════════════════════════════════════════════════════════════════

    @Test
    void totalSpawns_isPerScope_notShared() {
        // 主线程 wf-A 派生 1 次 → wf-A totalSpawns=1
        DelegationGuard.bindScope(DelegationGuard.createScope("wf-A", 3, 5, 10));
        DelegationGuard.enter("A", "B");
        assertEquals(1, DelegationGuard.totalSpawns(), "wf-A 派生 1 次后 totalSpawns 应为 1");

        // 子线程 wf-B 应有独立计数器（=0 初始），其派生不影响 wf-A
        AtomicReference<Integer> bTotalBefore = new AtomicReference<>();
        AtomicReference<Integer> bTotalAfter = new AtomicReference<>();
        Thread vt = Thread.startVirtualThread(() -> {
            try {
                DelegationGuard.bindScope(DelegationGuard.createScope("wf-B", 3, 5, 10));
                bTotalBefore.set(DelegationGuard.totalSpawns());
                DelegationGuard.enter("X", "Y");
                bTotalAfter.set(DelegationGuard.totalSpawns());
            } finally {
                DelegationGuard.clear();
            }
        });
        joinQuietly(vt);

        assertEquals(0, bTotalBefore.get(), "wf-B 初始 totalSpawns 应为 0（独立计数器）");
        assertEquals(1, bTotalAfter.get(), "wf-B 派生 1 次后 totalSpawns 应为 1");
        assertEquals(1, DelegationGuard.totalSpawns(),
                "wf-B 派生不应影响 wf-A 的 totalSpawns（仍为 1）");
    }

    // ════════════════════════════════════════════════════════════════
    //  resetTotalSpawns 只影响全局默认 scope
    // ════════════════════════════════════════════════════════════════

    @Test
    void resetTotalSpawns_onlyAffectsGlobalDefaultScope() {
        // 主线程绑 wf-A 并派生 1 次 → wf-A totalSpawns=1
        DelegationGuard.bindScope(DelegationGuard.createScope("wf-A", 3, 5, 10));
        DelegationGuard.enter("A", "B");
        assertEquals(1, DelegationGuard.totalSpawns());

        // resetTotalSpawns 只应影响 GLOBAL_DEFAULT_SCOPE，不应清零 wf-A
        DelegationGuard.resetTotalSpawns();
        assertEquals(1, DelegationGuard.totalSpawns(),
                "resetTotalSpawns 不应影响已绑定的 wf-A scope（仍为 1）");

        // 验证全局默认 scope 确实被清零（在未 bind 的子线程中检查）
        AtomicReference<Integer> globalTotal = new AtomicReference<>();
        Thread vt = Thread.startVirtualThread(() -> {
            try {
                // 不 bind → currentScope 回退 GLOBAL_DEFAULT
                globalTotal.set(DelegationGuard.totalSpawns());
            } finally {
                DelegationGuard.clear();
            }
        });
        joinQuietly(vt);
        assertEquals(0, globalTotal.get(),
                "GLOBAL_DEFAULT_SCOPE 的 totalSpawns 应被 resetTotalSpawns 清零");
    }

    // ════════════════════════════════════════════════════════════════
    //  scope 跨虚拟线程传播（activate）
    // ════════════════════════════════════════════════════════════════

    @Test
    void scopePropagatesAcrossVirtualThreadViaActivate() {
        // 主线程绑 wf-A (depth=4, total=20)，enter 拿到 ctx（携带 wf-A scope 快照）
        DelegationGuard.bindScope(DelegationGuard.createScope("wf-A", 4, 5, 20));
        DelegationGuard.DelegationContext ctx = DelegationGuard.enter("A", "B");
        assertEquals("wf-A", ctx.scope().workflowId(),
                "enter 返回的 ctx 应携带当前 wf-A scope");

        // 子线程 activate(ctx) 应继承 wf-A scope
        AtomicReference<String> childScopeId = new AtomicReference<>();
        AtomicReference<Integer> childMaxDepth = new AtomicReference<>();
        AtomicReference<Integer> childMaxTotal = new AtomicReference<>();
        Thread vt = Thread.startVirtualThread(() -> {
            try {
                DelegationGuard.activate(ctx);
                childScopeId.set(DelegationGuard.currentScope().workflowId());
                childMaxDepth.set(DelegationGuard.maxDepth());
                childMaxTotal.set(DelegationGuard.maxTotalSpawns());
            } finally {
                DelegationGuard.clear();
            }
        });
        joinQuietly(vt);

        assertEquals("wf-A", childScopeId.get(), "子线程 activate 后应继承 wf-A scope");
        assertEquals(4, childMaxDepth.get(), "子线程 maxDepth 应为 wf-A 的 4");
        assertEquals(20, childMaxTotal.get(), "子线程 maxTotalSpawns 应为 wf-A 的 20");
    }

    // ════════════════════════════════════════════════════════════════
    //  3 参 DelegationContext 构造器动态捕获 currentScope
    // ════════════════════════════════════════════════════════════════

    @Test
    void threeArgDelegationContext_capturesCurrentScope() {
        // 绑 wf-X，3 参构造应捕获 wf-X
        DelegationGuard.bindScope(DelegationGuard.createScope("wf-X", 7));
        DelegationGuard.DelegationContext ctx1 =
                new DelegationGuard.DelegationContext(1, Set.of(), "x");
        assertEquals("wf-X", ctx1.scope().workflowId(),
                "3 参构造器应捕获当前 currentScope (wf-X)");

        // 切换到 wf-Y，再构造应捕获 wf-Y（动态读取，非静态缓存）
        DelegationGuard.bindScope(DelegationGuard.createScope("wf-Y", 9));
        DelegationGuard.DelegationContext ctx2 =
                new DelegationGuard.DelegationContext(1, Set.of(), "y");
        assertEquals("wf-Y", ctx2.scope().workflowId(),
                "切换 scope 后，3 参构造器应捕获新的 currentScope (wf-Y)");
        assertNotSame(ctx1.scope(), ctx2.scope(),
                "两次构造的 scope 应是不同实例（wf-X ≠ wf-Y）");
    }

    // ════════════════════════════════════════════════════════════════
    //  bindScope 契约
    // ════════════════════════════════════════════════════════════════

    @Test
    void bindNullScope_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> DelegationGuard.bindScope(null),
                "bindScope(null) 应抛 IllegalArgumentException");
    }

    // ════════════════════════════════════════════════════════════════
    //  clear 回退到全局默认
    // ════════════════════════════════════════════════════════════════

    @Test
    void clear_resetsScopeBinding_toGlobalDefault() {
        DelegationGuard.bindScope(DelegationGuard.createScope("wf-A", 2));
        assertNotSame(DelegationGuard.globalDefaultScope(), DelegationGuard.currentScope(),
                "bind 后 currentScope 应是 wf-A（非全局默认）");

        DelegationGuard.clear();

        assertSame(DelegationGuard.globalDefaultScope(), DelegationGuard.currentScope(),
                "clear 后 currentScope 应回退到 GLOBAL_DEFAULT_SCOPE（同一实例）");
    }

    @Test
    void noBind_fallsBackToGlobalDefault() {
        // @AfterEach 已 clear，确保未 bind
        DelegationGuard.clear();
        assertSame(DelegationGuard.globalDefaultScope(), DelegationGuard.currentScope(),
                "未 bind 时 currentScope 应返回 GLOBAL_DEFAULT_SCOPE（同一实例）");
        assertEquals("global-default", DelegationGuard.currentScope().workflowId(),
                "全局默认 scope 的 workflowId 应为 'global-default'");
    }

    // ════════════════════════════════════════════════════════════════
    //  辅助
    // ════════════════════════════════════════════════════════════════

    /** 静默 join 虚拟线程（中断不期望发生） */
    private static void joinQuietly(Thread t) {
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("join 被中断", e);
        }
    }
}
