package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.tool.DelegationGuard.DelegationContext;
import com.ouisani.aios.core.tool.DelegationGuard.DelegationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DelegationGuard 单元测试 — 验证深度限制、防环、自委托禁止、上下文继承。
 */
class DelegationGuardTest {

    @AfterEach
    void cleanup() {
        DelegationGuard.clear();
    }

    // ════════════════════════════════════════════════════════════════
    //  正常用例
    // ════════════════════════════════════════════════════════════════

    @Test
    void enter_rootAgent_depth1() {
        DelegationContext ctx = DelegationGuard.enter("root", "child_a");
        assertEquals(1, ctx.depth());
        assertEquals("child_a", ctx.agentId());
    }

    @Test
    void enter_secondLevel_depth2() {
        DelegationContext ctx1 = DelegationGuard.enter("root", "child_a");
        DelegationGuard.activate(ctx1);

        DelegationContext ctx2 = DelegationGuard.enter("child_a", "child_b");
        assertEquals(2, ctx2.depth());
        assertEquals("child_b", ctx2.agentId());
    }

    @Test
    void enter_thirdLevel_depth3_allowed() {
        // A(0)→B(1)→C(2)→D(3) — 第3层允许
        DelegationContext ctx1 = DelegationGuard.enter("A", "B");
        DelegationGuard.activate(ctx1);
        DelegationContext ctx2 = DelegationGuard.enter("B", "C");
        DelegationGuard.activate(ctx2);
        DelegationContext ctx3 = DelegationGuard.enter("C", "D");
        assertEquals(3, ctx3.depth());
    }

    @Test
    void activate_inheritsChain() {
        DelegationContext ctx1 = DelegationGuard.enter("A", "B");
        DelegationGuard.activate(ctx1);

        // B 现在在委托链中
        assertTrue(DelegationGuard.currentChain().contains("A"));
        assertTrue(DelegationGuard.currentChain().contains("B"));
    }

    // ════════════════════════════════════════════════════════════════
    //  深度超限
    // ════════════════════════════════════════════════════════════════

    @Test
    void enter_fourthLevel_depthExceeded() {
        // A(0)→B(1)→C(2)→D(3)→E(4) — 第4层拒绝
        DelegationContext ctx1 = DelegationGuard.enter("A", "B");
        DelegationGuard.activate(ctx1);
        DelegationContext ctx2 = DelegationGuard.enter("B", "C");
        DelegationGuard.activate(ctx2);
        DelegationContext ctx3 = DelegationGuard.enter("C", "D");
        DelegationGuard.activate(ctx3);

        DelegationException ex = assertThrows(DelegationException.class,
                () -> DelegationGuard.enter("D", "E"));
        assertTrue(ex.getMessage().contains("深度超限"));
        assertTrue(ex.getMessage().contains("3"));
    }

    // ════════════════════════════════════════════════════════════════
    //  自委托禁止
    // ════════════════════════════════════════════════════════════════

    @Test
    void enter_selfDelegation_rejected() {
        DelegationException ex = assertThrows(DelegationException.class,
                () -> DelegationGuard.enter("agentA", "agentA"));
        assertTrue(ex.getMessage().contains("自委托"));
    }

    // ════════════════════════════════════════════════════════════════
    //  环检测
    // ════════════════════════════════════════════════════════════════

    @Test
    void enter_cycleDetected_rejected() {
        // A→B, 然后 B 尝试委托回 A
        DelegationContext ctx1 = DelegationGuard.enter("A", "B");
        DelegationGuard.activate(ctx1);

        DelegationException ex = assertThrows(DelegationException.class,
                () -> DelegationGuard.enter("B", "A"));
        assertTrue(ex.getMessage().contains("环"));
    }

    @Test
    void enter_longerCycleDetected_rejected() {
        // A→B→C, 然后 C 尝试委托回 A
        DelegationContext ctx1 = DelegationGuard.enter("A", "B");
        DelegationGuard.activate(ctx1);
        DelegationContext ctx2 = DelegationGuard.enter("B", "C");
        DelegationGuard.activate(ctx2);

        DelegationException ex = assertThrows(DelegationException.class,
                () -> DelegationGuard.enter("C", "A"));
        assertTrue(ex.getMessage().contains("环"));
    }

    // ════════════════════════════════════════════════════════════════
    //  clear() 恢复初始状态
    // ════════════════════════════════════════════════════════════════

    @Test
    void clear_resetsDepth() {
        DelegationContext ctx = DelegationGuard.enter("A", "B");
        DelegationGuard.activate(ctx);
        assertEquals(1, DelegationGuard.currentDepth());

        DelegationGuard.clear();
        assertEquals(0, DelegationGuard.currentDepth());
    }

    @Test
    void clear_resetsChain() {
        DelegationContext ctx = DelegationGuard.enter("A", "B");
        DelegationGuard.activate(ctx);
        assertFalse(DelegationGuard.currentChain().isEmpty());

        DelegationGuard.clear();
        assertTrue(DelegationGuard.currentChain().isEmpty());
    }

    @Test
    void clear_allowsFreshDelegation() {
        // 清理后可以重新开始委托
        DelegationContext ctx1 = DelegationGuard.enter("A", "B");
        DelegationGuard.activate(ctx1);
        DelegationGuard.clear();

        // 重新委托 A→B 应该成功 (不是环,因为链已清空)
        DelegationContext ctx2 = DelegationGuard.enter("A", "B");
        assertEquals(1, ctx2.depth());
    }

    // ════════════════════════════════════════════════════════════════
    //  跨线程上下文继承
    // ════════════════════════════════════════════════════════════════

    @Test
    void activate_inChildThread_inheritsDepth() throws Exception {
        DelegationContext ctx1 = DelegationGuard.enter("A", "B");

        Thread[] holder = new Thread[1];
        holder[0] = Thread.startVirtualThread(() -> {
            DelegationGuard.activate(ctx1);
            assertEquals(1, DelegationGuard.currentDepth());
            assertTrue(DelegationGuard.currentChain().contains("A"));
            assertTrue(DelegationGuard.currentChain().contains("B"));

            // 子线程中可以继续委托 B→C
            DelegationContext ctx2 = DelegationGuard.enter("B", "C");
            assertEquals(2, ctx2.depth());
            DelegationGuard.clear();
        });
        holder[0].join();
    }
}
