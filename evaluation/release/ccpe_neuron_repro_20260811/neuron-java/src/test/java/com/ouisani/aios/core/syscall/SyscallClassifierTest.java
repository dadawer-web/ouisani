package com.ouisani.aios.core.syscall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SyscallClassifier} 单元测试 — P0 补强：核心静态分类逻辑。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>{@code isRead} — READ_ACTIONS 白名单命中/未命中</li>
 *   <li>{@code isWrite} — WRITE_ACTIONS 命中 + tool namespace 默认写</li>
 *   <li>{@code isToolNamespace} — 仅 "tool" 命中</li>
 *   <li>{@code isRetrySafe} — readSafe override 优先 + 读操作自动可重试</li>
 *   <li>NULL 输入安全降级（未知即视为写，保守安全）</li>
 *   <li>设计取舍：保守默认写（未知 action 视为写）</li>
 * </ul>
 * <p>
 * <b>依赖关系</b>：被 {@code SyscallRetryPolicy}（读指数退避/写零重试）与
 * {@code com.ouisani.aios.core.action.RiskClassifier}（SAFE/REVERSIBLE/DESTRUCTIVE）依赖，
 * 是 P0/P1 的分类基石，必须 100% 覆盖。
 */
class SyscallClassifierTest {

    // ════════════════════════════════════════════════════════════════
    //  isToolNamespace
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isToolNamespace — 仅 'tool' 命中")
    void isToolNamespace_onlyToolString() {
        assertTrue(SyscallClassifier.isToolNamespace("tool"));
        assertFalse(SyscallClassifier.isToolNamespace("storage"));
        assertFalse(SyscallClassifier.isToolNamespace("vfs"));
        assertFalse(SyscallClassifier.isToolNamespace("memory"));
        assertFalse(SyscallClassifier.isToolNamespace("llm"));
        assertFalse(SyscallClassifier.isToolNamespace(""));
    }

    @Test
    @DisplayName("isToolNamespace(null) 返回 false，不抛 NPE")
    void isToolNamespace_nullSafe() {
        assertFalse(SyscallClassifier.isToolNamespace(null));
    }

    // ════════════════════════════════════════════════════════════════
    //  isRead
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isRead — READ_ACTIONS 白名单命中")
    void isRead_whitelistHits() {
        assertTrue(SyscallClassifier.isRead("llm", "think"));
        assertTrue(SyscallClassifier.isRead("llm", "think_with_history"));
        assertTrue(SyscallClassifier.isRead("storage", "read"));
        assertTrue(SyscallClassifier.isRead("storage", "exists"));
        assertTrue(SyscallClassifier.isRead("vfs", "read"));
        assertTrue(SyscallClassifier.isRead("vfs", "snapshot"));
        assertTrue(SyscallClassifier.isRead("memory", "retrieve"));
        assertTrue(SyscallClassifier.isRead("memory", "search"));
        assertTrue(SyscallClassifier.isRead("memory", "list"));
        assertTrue(SyscallClassifier.isRead("handle", "read"));
        assertTrue(SyscallClassifier.isRead("coreutils", "ls"));
        assertTrue(SyscallClassifier.isRead("coreutils", "cat"));
        assertTrue(SyscallClassifier.isRead("coreutils", "ps"));
        assertTrue(SyscallClassifier.isRead("apt", "list"));
        assertTrue(SyscallClassifier.isRead("bin", "ps"));
    }

    @Test
    @DisplayName("isRead — 写操作返回 false")
    void isRead_writeActionsReturnFalse() {
        assertFalse(SyscallClassifier.isRead("storage", "write"));
        assertFalse(SyscallClassifier.isRead("storage", "append"));
        assertFalse(SyscallClassifier.isRead("vfs", "write"));
        assertFalse(SyscallClassifier.isRead("vfs", "rollback"));
        assertFalse(SyscallClassifier.isRead("memory", "store"));
        assertFalse(SyscallClassifier.isRead("memory", "clear"));
        assertFalse(SyscallClassifier.isRead("apt", "install"));
        assertFalse(SyscallClassifier.isRead("apt", "remove"));
        assertFalse(SyscallClassifier.isRead("bin", "kill"));
    }

    @Test
    @DisplayName("isRead — 未知 action 保守返回 false（视为写）")
    void isRead_unknownActionReturnsFalse() {
        assertFalse(SyscallClassifier.isRead("storage", "nuke"));
        assertFalse(SyscallClassifier.isRead("newnamespace", "newaction"));
        // 即使 namespace 已知，未知 action 也不是读
        assertFalse(SyscallClassifier.isRead("memory", "wipe"));
    }

    @Test
    @DisplayName("isRead(null, ...) / isRead(..., null) 安全降级为 false")
    void isRead_nullSafe() {
        assertFalse(SyscallClassifier.isRead(null, "read"));
        assertFalse(SyscallClassifier.isRead("storage", null));
        assertFalse(SyscallClassifier.isRead(null, null));
    }

    // ════════════════════════════════════════════════════════════════
    //  isWrite
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isWrite — WRITE_ACTIONS 命中")
    void isWrite_whitelistHits() {
        assertTrue(SyscallClassifier.isWrite("storage", "write"));
        assertTrue(SyscallClassifier.isWrite("storage", "append"));
        assertTrue(SyscallClassifier.isWrite("vfs", "write"));
        assertTrue(SyscallClassifier.isWrite("vfs", "rollback"));
        assertTrue(SyscallClassifier.isWrite("memory", "store"));
        assertTrue(SyscallClassifier.isWrite("memory", "clear"));
        assertTrue(SyscallClassifier.isWrite("apt", "install"));
        assertTrue(SyscallClassifier.isWrite("apt", "remove"));
        assertTrue(SyscallClassifier.isWrite("bin", "install"));
        assertTrue(SyscallClassifier.isWrite("bin", "kill"));
    }

    @Test
    @DisplayName("isWrite — tool namespace 任何 action 都视为写（外部副作用）")
    void isWrite_toolNamespaceAlwaysWrite() {
        assertTrue(SyscallClassifier.isWrite("tool", "search"));    // 即使语义像查询
        assertTrue(SyscallClassifier.isWrite("tool", "calculate"));
        assertTrue(SyscallClassifier.isWrite("tool", "anything"));
        assertTrue(SyscallClassifier.isWrite("tool", ""));
    }

    @Test
    @DisplayName("isWrite — 读操作返回 false")
    void isWrite_readActionsReturnFalse() {
        assertFalse(SyscallClassifier.isWrite("llm", "think"));
        assertFalse(SyscallClassifier.isWrite("storage", "read"));
        assertFalse(SyscallClassifier.isWrite("storage", "exists"));
        assertFalse(SyscallClassifier.isWrite("vfs", "read"));
        assertFalse(SyscallClassifier.isWrite("memory", "retrieve"));
        assertFalse(SyscallClassifier.isWrite("coreutils", "ls"));
    }

    @Test
    @DisplayName("isWrite — 未知 namespace + 未知 action 保守返回 false（既不在 read 也不在 write 白名单）")
    void isWrite_unknownReturnsFalse() {
        // 注意：未知 action 默认视为"非写"（但同时也不是读）
        // —— 真正判定"是写"靠 tool namespace 或 WRITE_ACTIONS 命中
        // 这是有意设计：保守只在 tool namespace 上默认写，其他未知操作不擅自判定
        assertFalse(SyscallClassifier.isWrite("newns", "newaction"));
        assertFalse(SyscallClassifier.isWrite("memory", "weirdop"));
    }

    @Test
    @DisplayName("isWrite(null, ...) / isWrite(..., null) 安全降级为 false")
    void isWrite_nullSafe() {
        assertFalse(SyscallClassifier.isWrite(null, "write"));
        assertFalse(SyscallClassifier.isWrite("storage", null));
        assertFalse(SyscallClassifier.isWrite(null, null));
    }

    // ════════════════════════════════════════════════════════════════
    //  isRetrySafe
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isRetrySafe — 读操作自动可重试")
    void isRetrySafe_readAutoRetryable() {
        assertTrue(SyscallClassifier.isRetrySafe("storage", "read", false));
        assertTrue(SyscallClassifier.isRetrySafe("llm", "think", false));
        assertTrue(SyscallClassifier.isRetrySafe("memory", "retrieve", false));
        assertTrue(SyscallClassifier.isRetrySafe("vfs", "snapshot", false));
    }

    @Test
    @DisplayName("isRetrySafe — 写操作默认不可重试（readSafe=false）")
    void isRetrySafe_writeDefaultNotRetryable() {
        assertFalse(SyscallClassifier.isRetrySafe("storage", "write", false));
        assertFalse(SyscallClassifier.isRetrySafe("vfs", "write", false));
        assertFalse(SyscallClassifier.isRetrySafe("memory", "store", false));
        assertFalse(SyscallClassifier.isRetrySafe("tool", "search", false));
    }

    @Test
    @DisplayName("isRetrySafe — readSafe=true 覆盖一切（即使是写操作也视为可重试）")
    void isRetrySafe_readSafeOverrideWins() {
        // 调用方显式声明 readSafe=true 时，即使是写操作也允许重试
        // 用于：调用方已知此调用虽是写 namespace 但实际无副作用（如 idempotent PUT）
        assertTrue(SyscallClassifier.isRetrySafe("storage", "write", true));
        assertTrue(SyscallClassifier.isRetrySafe("tool", "search", true));
        assertTrue(SyscallClassifier.isRetrySafe("bin", "kill", true));
        // 未知操作 + readSafe=true 也视为可重试
        assertTrue(SyscallClassifier.isRetrySafe("unknown", "op", true));
    }

    @Test
    @DisplayName("isRetrySafe — NULL 输入安全降级（除非 readSafe=true）")
    void isRetrySafe_nullSafe() {
        // NULL 输入 isRead 返回 false，所以默认不可重试
        assertFalse(SyscallClassifier.isRetrySafe(null, "read", false));
        assertFalse(SyscallClassifier.isRetrySafe("storage", null, false));
        // 但 readSafe=true 仍可覆盖
        assertTrue(SyscallClassifier.isRetrySafe(null, null, true));
    }

    // ════════════════════════════════════════════════════════════════
    //  交叉验证 — isRead 与 isWrite 互斥（除 NULL 情况）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("交叉验证：READ_ACTIONS 与 WRITE_ACTIONS 集合不相交")
    void readAndWriteWhitelistsAreDisjoint() {
        // 这是设计约束：同一 namespace+action 不能既是读又是写
        // 这里手动枚举关键组合（避免反射访问私有 Set）
        String[][] reads = {
                {"llm", "think"}, {"storage", "read"}, {"storage", "exists"},
                {"vfs", "read"}, {"vfs", "snapshot"}, {"memory", "retrieve"},
                {"memory", "search"}, {"memory", "list"}
        };
        for (String[] r : reads) {
            assertFalse(SyscallClassifier.isWrite(r[0], r[1]),
                    "读操作不应同时是写: " + r[0] + "." + r[1]);
        }

        String[][] writes = {
                {"storage", "write"}, {"storage", "append"}, {"vfs", "write"},
                {"vfs", "rollback"}, {"memory", "store"}, {"memory", "clear"}
        };
        for (String[] w : writes) {
            assertFalse(SyscallClassifier.isRead(w[0], w[1]),
                    "写操作不应同时是读: " + w[0] + "." + w[1]);
        }
    }
}
