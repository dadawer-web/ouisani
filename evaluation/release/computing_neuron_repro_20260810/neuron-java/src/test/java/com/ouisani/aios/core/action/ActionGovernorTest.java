package com.ouisani.aios.core.action;

import com.ouisani.aios.core.snapshot.DiffExpectation;
import com.ouisani.aios.core.snapshot.EnvironmentSnapshotManager;
import com.ouisani.aios.core.snapshot.ProcessSnapshot;
import com.ouisani.aios.core.snapshot.SnapshotCapturer;
import com.ouisani.aios.core.snapshot.SnapshotSection;
import com.ouisani.aios.core.snapshot.VfsSection;
import com.ouisani.aios.core.syscall.SyscallRequest;
import com.ouisani.aios.core.syscall.SyscallResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ActionGovernor} 单元测试 — 覆盖三风险级、undo、自动回滚、GC。
 * <p>
 * 用可控 {@link MutableVfsCapturer} 注入真实 EnvironmentSnapshotManager 单例，
 * 在 before/after 间改变 VFS 内容以制造可判定的 diff。@AfterEach 清理全局状态。
 */
class ActionGovernorTest {

    private static final String VFS = "Vfs";

    private ActionGovernor governor;
    private MutableVfsCapturer capturer;

    @BeforeEach
    void setUp() {
        governor = ActionGovernor.getInstance();
        governor.clearAll();
        governor.setMaxStackDepth(32);
        capturer = new MutableVfsCapturer();
        EnvironmentSnapshotManager.instance().registerCapturer(capturer);
    }

    @AfterEach
    void tearDown() {
        EnvironmentSnapshotManager.instance().unregisterCapturer(VFS);
        governor.clearAll();
    }

    // ── 风险分级 ──

    @Test
    void riskClassifier_readsAreSafe() {
        assertEquals(RiskLevel.SAFE, RiskClassifier.classify("llm", "think"));
        assertEquals(RiskLevel.SAFE, RiskClassifier.classify("vfs", "read"));
        assertEquals(RiskLevel.SAFE, RiskClassifier.classify("storage", "read"));
        assertEquals(RiskLevel.SAFE, RiskClassifier.classify("memory", "retrieve"));
    }

    @Test
    void riskClassifier_knownWritesAreReversible() {
        assertEquals(RiskLevel.REVERSIBLE, RiskClassifier.classify("storage", "write"));
        assertEquals(RiskLevel.REVERSIBLE, RiskClassifier.classify("vfs", "write"));
        assertEquals(RiskLevel.REVERSIBLE, RiskClassifier.classify("memory", "store"));
        assertEquals(RiskLevel.REVERSIBLE, RiskClassifier.classify("vfs", "rollback"));
    }

    @Test
    void riskClassifier_toolAndUnknownAreDestructive() {
        assertEquals(RiskLevel.DESTRUCTIVE, RiskClassifier.classify("tool", "call"));
        assertEquals(RiskLevel.DESTRUCTIVE, RiskClassifier.classify("tool", "send_email"));
        assertEquals(RiskLevel.DESTRUCTIVE, RiskClassifier.classify("memory", "clear"));
        assertEquals(RiskLevel.DESTRUCTIVE, RiskClassifier.classify("bin", "kill"));
        assertEquals(RiskLevel.DESTRUCTIVE, RiskClassifier.classify("unknown", "op"));
    }

    // ── beforeAction ──

    @Test
    void beforeAction_safeSkipsSnapshot() {
        SyscallRequest req = new SyscallRequest("llm.think", Map.of("prompt", "hi"));
        ActionContext ctx = governor.beforeAction("agent-1", req);

        assertEquals(RiskLevel.SAFE, ctx.riskLevel());
        assertNull(ctx.snapshotId(), "SAFE 动作不应打快照");
        assertNull(ctx.before());
        assertTrue(governor.history("agent-1").isEmpty(), "SAFE 不入栈");
    }

    @Test
    void beforeAction_reversibleCapturesAndPushes() {
        capturer.set(List.of(handle("/tmp/a", "content-a")));
        SyscallRequest req = new SyscallRequest("storage.write",
                Map.of("path", "/tmp/a", "content", "new"));
        ActionContext ctx = governor.beforeAction("agent-1", req);

        assertEquals(RiskLevel.REVERSIBLE, ctx.riskLevel());
        assertNotNull(ctx.snapshotId(), "REVERSIBLE 应打动作前快照");
        assertNotNull(ctx.before());
        assertEquals(1, governor.history("agent-1").size());
        assertEquals(ctx.requestId(), governor.lastAction("agent-1").requestId());
    }

    @Test
    void beforeAction_destructiveCapturesForAudit() {
        capturer.set(List.of(handle("/tmp/a", "content-a")));
        SyscallRequest req = new SyscallRequest("tool.call", Map.of("tool", "send_email"));
        ActionContext ctx = governor.beforeAction("agent-1", req);

        assertEquals(RiskLevel.DESTRUCTIVE, ctx.riskLevel());
        assertNotNull(ctx.snapshotId(), "DESTRUCTIVE 也打快照用于审计");
        assertEquals(1, governor.history("agent-1").size());
    }

    @Test
    void beforeAction_overrideTakesPrecedence() {
        SyscallRequest req = new SyscallRequest("tool.call", Map.of("tool", "query_only"));
        // 显式降级为 REVERSIBLE（如某 tool 实为可逆查询）
        ActionContext ctx = governor.beforeAction("agent-1", req, RiskLevel.REVERSIBLE);
        assertEquals(RiskLevel.REVERSIBLE, ctx.riskLevel());
        assertNotNull(ctx.snapshotId());
    }

    // ── undo ──

    @Test
    void undo_refusesDestructive() {
        capturer.set(List.of(handle("/tmp/a", "a")));
        SyscallRequest req = new SyscallRequest("tool.call", Map.of("tool", "send_email"));
        ActionContext ctx = governor.beforeAction("agent-1", req);
        governor.afterAction(ctx, SyscallResponse.ok("sent"));

        assertFalse(governor.undo(ctx.requestId()), "DESTRUCTIVE 不可 undo");
    }

    @Test
    void undo_restoresReversible() {
        capturer.set(List.of(handle("/tmp/a", "original")));
        SyscallRequest req = new SyscallRequest("storage.write",
                Map.of("path", "/tmp/a", "content", "modified"));
        ActionContext ctx = governor.beforeAction("agent-1", req);

        // 模拟动作改变了 VFS
        capturer.set(List.of(handle("/tmp/a", "modified")));
        governor.afterAction(ctx, SyscallResponse.ok("written"));

        // undo 应回滚到动作前
        assertTrue(governor.undo(ctx.requestId()));
        assertEquals("original", capturer.get().get(0).frozenContent(),
                "undo 后 VFS 应恢复为动作前内容");

        // 重复 undo 同一动作应失败
        assertFalse(governor.undo(ctx.requestId()));
    }

    @Test
    void undo_unknownRequestIdReturnsFalse() {
        assertFalse(governor.undo("nonexistent-id"));
    }

    @Test
    void undoLast_picksMostRecentReversible() {
        capturer.set(List.of(handle("/a", "1")));
        // 1) DESTRUCTIVE 动作
        ActionContext dctx = governor.beforeAction("agent-1",
                new SyscallRequest("tool.call", Map.of("tool", "x")));
        governor.afterAction(dctx, SyscallResponse.ok());
        // 2) REVERSIBLE 动作（栈顶）
        capturer.set(List.of(handle("/a", "before-write")));
        ActionContext rctx = governor.beforeAction("agent-1",
                new SyscallRequest("storage.write", Map.of("path", "/a", "content", "z")));
        capturer.set(List.of(handle("/a", "after-write")));
        governor.afterAction(rctx, SyscallResponse.ok());

        assertTrue(governor.undoLast("agent-1"), "应回滚最近可逆动作");
        assertEquals("before-write", capturer.get().get(0).frozenContent());
    }

    // ── afterAction 自动回滚 ──

    @Test
    void afterAction_permissiveNeverAutoRollsBack() {
        capturer.set(List.of(handle("/a", "before")));
        ActionContext ctx = governor.beforeAction("agent-1",
                new SyscallRequest("storage.write", Map.of("path", "/a", "content", "x")));
        capturer.set(List.of(handle("/a", "changed"))); // 制造 diff

        AfterActionResult r = governor.afterAction(ctx, SyscallResponse.ok("written"));
        assertFalse(r.autoRolledBack(), "宽松期望不应自动回滚");
        assertEquals("changed", capturer.get().get(0).frozenContent(), "未回滚，内容保持改变后");
    }

    @Test
    void afterAction_violatedExpectationAutoRollsBackReversible() {
        capturer.set(List.of(handle("/a", "before")));
        ActionContext ctx = governor.beforeAction("agent-1",
                new SyscallRequest("storage.write", Map.of("path", "/a", "content", "x")));
        // 动作后 VFS 改变 —— 期望禁止 VFS 变更（如某写操作不应动 VFS）
        capturer.set(List.of(handle("/a", "changed")));
        DiffExpectation forbidVfs = new DiffExpectation(Set.of(), Set.of(VFS));

        AfterActionResult r = governor.afterAction(ctx, SyscallResponse.ok("written"), forbidVfs);
        assertTrue(r.autoRolledBack(), "REVERSIBLE 违反期望应自动回滚");
        assertFalse(r.success(), "自动回滚后视为失败");
        assertEquals("before", capturer.get().get(0).frozenContent(),
                "回滚后 VFS 应恢复为动作前");
        assertNotNull(r.diff());
        assertFalse(r.diff().meetsExpectation());
    }

    @Test
    void afterAction_destructiveDoesNotAutoRollBack() {
        capturer.set(List.of(handle("/a", "before")));
        ActionContext ctx = governor.beforeAction("agent-1",
                new SyscallRequest("tool.call", Map.of("tool", "send")));
        capturer.set(List.of(handle("/a", "changed")));
        DiffExpectation forbidVfs = new DiffExpectation(Set.of(), Set.of(VFS));

        AfterActionResult r = governor.afterAction(ctx, SyscallResponse.ok("sent"), forbidVfs);
        assertFalse(r.autoRolledBack(), "DESTRUCTIVE 即使违反期望也不自动回滚");
        assertEquals("changed", capturer.get().get(0).frozenContent(),
                "DESTRUCTIVE 不回滚，内容保持改变后");
    }

    @Test
    void afterAction_meetingExpectationNoRollback() {
        capturer.set(List.of(handle("/a", "before")));
        ActionContext ctx = governor.beforeAction("agent-1",
                new SyscallRequest("storage.write", Map.of("path", "/a", "content", "x")));
        capturer.set(List.of(handle("/a", "changed")));
        // 期望允许 VFS 变更 —— 满足，不回滚
        DiffExpectation allowVfs = new DiffExpectation(Set.of(VFS), Set.of());

        AfterActionResult r = governor.afterAction(ctx, SyscallResponse.ok("written"), allowVfs);
        assertFalse(r.autoRolledBack());
        assertTrue(r.diff().meetsExpectation());
        assertEquals("changed", capturer.get().get(0).frozenContent());
    }

    @Test
    void afterAction_safeReturnsNoSnapshot() {
        SyscallRequest req = new SyscallRequest("llm.think", Map.of("prompt", "hi"));
        ActionContext ctx = governor.beforeAction("agent-1", req);
        AfterActionResult r = governor.afterAction(ctx, SyscallResponse.ok("answer"));
        assertNull(r.diff());
        assertFalse(r.autoRolledBack());
        assertTrue(r.success());
    }

    // ── GC / 栈深 ──

    @Test
    void gcOlderThan_evictsOldCompletedActions() throws InterruptedException {
        capturer.set(List.of(handle("/a", "1")));
        ActionContext ctx = governor.beforeAction("agent-1",
                new SyscallRequest("storage.write", Map.of("path", "/a", "content", "x")));
        governor.afterAction(ctx, SyscallResponse.ok());
        assertEquals(1, governor.history("agent-1").size());

        Thread.sleep(60);
        int evicted = governor.gcOlderThan(50);
        assertEquals(1, evicted);
        assertTrue(governor.history("agent-1").isEmpty());
    }

    @Test
    void maxStackDepth_evictsOldestOnOverflow() {
        governor.setMaxStackDepth(2);
        capturer.set(List.of(handle("/a", "1")));
        for (int i = 0; i < 3; i++) {
            ActionContext ctx = governor.beforeAction("agent-1",
                    new SyscallRequest("storage.write", Map.of("k", String.valueOf(i))));
            governor.afterAction(ctx, SyscallResponse.ok());
        }
        assertEquals(2, governor.history("agent-1").size(), "栈应裁剪到 maxStackDepth");
    }

    // ── helpers ──

    private static ProcessSnapshot.OpenHandle handle(String path, String content) {
        return new ProcessSnapshot.OpenHandle(path, "File", content);
    }

    /** 可控 VFS capturer —— 测试在 before/after 间改变其内容以制造 diff。 */
    private static final class MutableVfsCapturer implements SnapshotCapturer {
        private List<ProcessSnapshot.OpenHandle> current = List.of();

        @Override
        public String sectionType() {
            return VFS;
        }

        @Override
        public SnapshotSection capture() {
            return new VfsSection(List.copyOf(current));
        }

        @Override
        public void restore(SnapshotSection section) {
            current = new ArrayList<>(((VfsSection) section).handles());
        }

        void set(List<ProcessSnapshot.OpenHandle> h) {
            this.current = new ArrayList<>(h);
        }

        List<ProcessSnapshot.OpenHandle> get() {
            return current;
        }
    }
}
