package com.ouisani.aios.core.action;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RiskClassifier} 单元测试 — P1 补强：风险分级静态逻辑。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>读操作一律 {@link RiskLevel#SAFE}</li>
 *   <li>可逆写操作 → {@link RiskLevel#REVERSIBLE}（storage.write/vfs.write/memory.store/apt.install 等）</li>
 *   <li>不可逆写操作 → {@link RiskLevel#DESTRUCTIVE}（memory.clear/apt.remove/bin.kill）</li>
 *   <li>tool namespace 默认 DESTRUCTIVE（外部副作用无法靠快照回滚）</li>
 *   <li>未知写操作保守 DESTRUCTIVE</li>
 *   <li>NULL 输入安全降级为 DESTRUCTIVE（最安全）</li>
 *   <li>与 {@link com.ouisani.aios.core.syscall.SyscallClassifier} 协同（读操作分类一致）</li>
 * </ul>
 * <p>
 * <b>依赖关系</b>：被 {@link ActionGovernor#beforeAction} / {@link ActionGovernor#executeGoverned}
 * 用于决定是否在执行前 capture 快照（REVERSIBLE/DESTRUCTIVE 才 capture）。
 */
class RiskClassifierTest {

    // ════════════════════════════════════════════════════════════════
    //  读操作 → SAFE
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("classify — 读操作一律 SAFE")
    void classify_readOpsAreSafe() {
        assertEquals(RiskLevel.SAFE, RiskClassifier.classify("llm", "think"));
        assertEquals(RiskLevel.SAFE, RiskClassifier.classify("llm", "think_with_history"));
        assertEquals(RiskLevel.SAFE, RiskClassifier.classify("storage", "read"));
        assertEquals(RiskLevel.SAFE, RiskClassifier.classify("storage", "exists"));
        assertEquals(RiskLevel.SAFE, RiskClassifier.classify("vfs", "read"));
        assertEquals(RiskLevel.SAFE, RiskClassifier.classify("vfs", "snapshot"));
        assertEquals(RiskLevel.SAFE, RiskClassifier.classify("memory", "retrieve"));
        assertEquals(RiskLevel.SAFE, RiskClassifier.classify("memory", "search"));
        assertEquals(RiskLevel.SAFE, RiskClassifier.classify("memory", "list"));
        assertEquals(RiskLevel.SAFE, RiskClassifier.classify("coreutils", "ls"));
        assertEquals(RiskLevel.SAFE, RiskClassifier.classify("coreutils", "ps"));
        assertEquals(RiskLevel.SAFE, RiskClassifier.classify("apt", "list"));
    }

    // ════════════════════════════════════════════════════════════════
    //  可逆写 → REVERSIBLE
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("classify — storage/vfs 写操作可逆（有影子副本/快照）")
    void classify_storageAndVfsWritesAreReversible() {
        assertEquals(RiskLevel.REVERSIBLE, RiskClassifier.classify("storage", "write"));
        assertEquals(RiskLevel.REVERSIBLE, RiskClassifier.classify("storage", "append"));
        assertEquals(RiskLevel.REVERSIBLE, RiskClassifier.classify("vfs", "write"));
        assertEquals(RiskLevel.REVERSIBLE, RiskClassifier.classify("vfs", "rollback"),
                "回滚本身可再回滚 → REVERSIBLE");
    }

    @Test
    @DisplayName("classify — memory.store 可逆")
    void classify_memoryStoreIsReversible() {
        assertEquals(RiskLevel.REVERSIBLE, RiskClassifier.classify("memory", "store"));
    }

    @Test
    @DisplayName("classify — apt.install / bin.install 可逆（可 apt.remove 还原）")
    void classify_packageInstallIsReversible() {
        assertEquals(RiskLevel.REVERSIBLE, RiskClassifier.classify("apt", "install"));
        assertEquals(RiskLevel.REVERSIBLE, RiskClassifier.classify("bin", "install"));
    }

    // ════════════════════════════════════════════════════════════════
    //  不可逆写 → DESTRUCTIVE
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("classify — memory.clear 不可逆（批量删除）")
    void classify_memoryClearIsDestructive() {
        assertEquals(RiskLevel.DESTRUCTIVE, RiskClassifier.classify("memory", "clear"));
    }

    @Test
    @DisplayName("classify — apt.remove 不可逆（卸载可能丢数据）")
    void classify_packageRemoveIsDestructive() {
        assertEquals(RiskLevel.DESTRUCTIVE, RiskClassifier.classify("apt", "remove"));
    }

    @Test
    @DisplayName("classify — bin.kill 不可逆（杀进程不可恢复）")
    void classify_binKillIsDestructive() {
        assertEquals(RiskLevel.DESTRUCTIVE, RiskClassifier.classify("bin", "kill"));
    }

    // ════════════════════════════════════════════════════════════════
    //  tool namespace → DESTRUCTIVE
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("classify — tool namespace 任何 action 都 DESTRUCTIVE（外部副作用）")
    void classify_toolNamespaceAlwaysDestructive() {
        assertEquals(RiskLevel.DESTRUCTIVE, RiskClassifier.classify("tool", "search"));
        assertEquals(RiskLevel.DESTRUCTIVE, RiskClassifier.classify("tool", "calculate"));
        assertEquals(RiskLevel.DESTRUCTIVE, RiskClassifier.classify("tool", "anything"));
        assertEquals(RiskLevel.DESTRUCTIVE, RiskClassifier.classify("tool", ""));
    }

    // ════════════════════════════════════════════════════════════════
    //  未知 + NULL
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("classify — 未知 namespace+action 保守 DESTRUCTIVE")
    void classify_unknownDefaultsToDestructive() {
        assertEquals(RiskLevel.DESTRUCTIVE, RiskClassifier.classify("newns", "newaction"));
        // 已知 namespace 但未知 action
        assertEquals(RiskLevel.DESTRUCTIVE, RiskClassifier.classify("memory", "weirdop"));
        assertEquals(RiskLevel.DESTRUCTIVE, RiskClassifier.classify("storage", "unknown"));
    }

    @Test
    @DisplayName("classify(null, ...) / classify(..., null) 安全降级 DESTRUCTIVE")
    void classify_nullSafe() {
        assertEquals(RiskLevel.DESTRUCTIVE, RiskClassifier.classify(null, "write"));
        assertEquals(RiskLevel.DESTRUCTIVE, RiskClassifier.classify("storage", null));
        assertEquals(RiskLevel.DESTRUCTIVE, RiskClassifier.classify(null, null));
    }

    // ════════════════════════════════════════════════════════════════
    //  与 SyscallClassifier 协同
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("协同：SyscallClassifier.isRead 命中的操作 → RiskClassifier.classify 必为 SAFE")
    void crossValidation_readOpsAreSafe() {
        // 所有读操作在两个 classifier 中分类一致
        String[][] reads = {
                {"llm", "think"}, {"storage", "read"}, {"vfs", "read"},
                {"memory", "retrieve"}, {"coreutils", "ls"}
        };
        for (String[] r : reads) {
            assertTrue(com.ouisani.aios.core.syscall.SyscallClassifier.isRead(r[0], r[1]),
                    "应被 SyscallClassifier 识别为读: " + r[0] + "." + r[1]);
            assertEquals(RiskLevel.SAFE, RiskClassifier.classify(r[0], r[1]),
                    "读操作应被 RiskClassifier 分级为 SAFE: " + r[0] + "." + r[1]);
        }
    }

    @Test
    @DisplayName("协同：tool namespace 在两个 classifier 中都判定为'写'（isWrite=true, classify=DESTRUCTIVE）")
    void crossValidation_toolNamespaceConsistent() {
        String[] toolActions = {"search", "calc", "anything"};
        for (String a : toolActions) {
            assertTrue(com.ouisani.aios.core.syscall.SyscallClassifier.isWrite("tool", a));
            assertEquals(RiskLevel.DESTRUCTIVE, RiskClassifier.classify("tool", a));
        }
    }

    @Test
    @DisplayName("RiskLevel 枚举完整 — SAFE/REVERSIBLE/DESTRUCTIVE 三档")
    void riskLevel_enumComplete() {
        // 确保枚举值未意外新增或重命名（governance 逻辑依赖三级分类）
        RiskLevel[] levels = RiskLevel.values();
        assertEquals(3, levels.length, "应有且仅有 3 个 RiskLevel");
        // 顺序也重要：SAFE < REVERSIBLE < DESTRUCTIVE（按风险递增）
        // 用 ordinal 验证
        assertTrue(RiskLevel.SAFE.ordinal() < RiskLevel.REVERSIBLE.ordinal());
        assertTrue(RiskLevel.REVERSIBLE.ordinal() < RiskLevel.DESTRUCTIVE.ordinal());
    }
}
