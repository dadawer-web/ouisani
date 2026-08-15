package com.ouisani.aios.core.action;

import com.ouisani.aios.core.syscall.SyscallClassifier;

import java.util.Set;

/**
 * Syscall 风险分级器 — 按 namespace+action 静态判定 {@link RiskLevel}。
 * <p>
 * 与 {@link SyscallClassifier}（读写分类）协同：读操作一律 SAFE；
 * 写操作按可恢复性细分为 REVERSIBLE / DESTRUCTIVE。保守默认为 DESTRUCTIVE（最安全）。
 * <p>
 * 设计取舍：tool 命名空间默认 DESTRUCTIVE（外部副作用无法靠快照回滚），
 * 调用方可通过 {@link ActionGovernor#beforeAction} 的 override 参数显式降级。
 */
public final class RiskClassifier {

    private RiskClassifier() {}

    /** 明确可逆的写操作（有 VFS 影子副本/环境快照可恢复）。 */
    private static final Set<String> REVERSIBLE_ACTIONS = Set.of(
            "storage.write",
            "storage.append",
            "vfs.write",
            "vfs.rollback",       // 回滚本身可再回滚
            "memory.store",
            "apt.install",        // 可 apt.remove 还原（包级别）
            "bin.install"
    );

    /** 明确不可逆的写操作（外部副作用或数据销毁）。 */
    private static final Set<String> DESTRUCTIVE_ACTIONS = Set.of(
            "memory.clear",       // 批量删除记忆
            "apt.remove",         // 卸载可能丢数据
            "bin.kill"            // 杀进程不可恢复
    );

    /**
     * 判定 syscall 风险等级。
     *
     * @param namespace syscall 命名空间
     * @param action    命名空间内操作
     * @return 风险等级；未知操作保守返回 DESTRUCTIVE
     */
    public static RiskLevel classify(String namespace, String action) {
        if (namespace == null || action == null) {
            return RiskLevel.DESTRUCTIVE;
        }
        String full = namespace + "." + action;

        // 读操作一律 SAFE
        if (SyscallClassifier.isRead(namespace, action)) {
            return RiskLevel.SAFE;
        }
        if (REVERSIBLE_ACTIONS.contains(full)) {
            return RiskLevel.REVERSIBLE;
        }
        if (DESTRUCTIVE_ACTIONS.contains(full)) {
            return RiskLevel.DESTRUCTIVE;
        }
        // tool 命名空间：外部副作用，默认 DESTRUCTIVE
        if (SyscallClassifier.isToolNamespace(namespace)) {
            return RiskLevel.DESTRUCTIVE;
        }
        // 未知写操作：保守 DESTRUCTIVE
        return RiskLevel.DESTRUCTIVE;
    }
}
