package com.ouisani.aios.core.syscall;

import java.util.Set;

/**
 * Syscall 读写分类器 — 按 namespace+action 静态判定读/写语义。
 * <p>
 * 用于 {@link SyscallRetryPolicy} 与幂等性强制策略：读操作可安全指数退避重试；
 * 写操作必须走 {@link IdempotencyLedger} 幂等键路径，超时返回 PENDING_UNKNOWN。
 * <p>
 * 设计取舍：保守默认为写（未知 action 视为写，更安全）。显式枚举已知读操作，
 * 其余一律视为写，避免新接入的写操作被误判为可重试读。
 */
public final class SyscallClassifier {

    private SyscallClassifier() {}

    /** 已知读操作白名单（namespace + "." + action）。读 = 无副作用，可自由重试。 */
    private static final Set<String> READ_ACTIONS = Set.of(
            "llm.think",
            "llm.think_with_history",
            "storage.read",
            "storage.exists",
            "vfs.read",
            "vfs.snapshot",        // 快照本身不改变业务数据
            "memory.retrieve",
            "memory.search",
            "memory.list",
            "handle.read",
            "coreutils.ls",
            "coreutils.cat",
            "coreutils.ps",
            "coreutils.whoami",
            "coreutils.uptime",
            "coreutils.free",
            "apt.list",
            "bin.ps",
            "bin.whoami",
            "bin.uptime",
            "bin.free"
    );

    /** 明确的写操作（非穷举，仅用于强制幂等键校验时跳过纯读）。 */
    private static final Set<String> WRITE_ACTIONS = Set.of(
            "storage.write",
            "storage.append",
            "vfs.write",
            "vfs.rollback",
            "memory.store",
            "memory.clear",
            "apt.install",
            "apt.remove",
            "bin.install",
            "bin.kill"
    );

    /** 工具调用命名空间：默认视为写（绝大多数 tool 调用有副作用）。 */
    public static boolean isToolNamespace(String namespace) {
        return "tool".equals(namespace);
    }

    /** 判定是否为读操作（无副作用、可安全重试）。 */
    public static boolean isRead(String namespace, String action) {
        if (namespace == null || action == null) {
            return false;
        }
        return READ_ACTIONS.contains(namespace + "." + action);
    }

    /** 判定是否为写操作（有副作用、需幂等键）。 */
    public static boolean isWrite(String namespace, String action) {
        if (namespace == null || action == null) {
            return false;
        }
        if (WRITE_ACTIONS.contains(namespace + "." + action)) {
            return true;
        }
        // tool 命名空间：除明确的查询类外，一律视为写
        if (isToolNamespace(namespace)) {
            return true;
        }
        return false;
    }

    /**
     * 是否可安全重试：读操作或请求显式标记 readSafe=true。
     * 调用方传入 request.readSafe() 作为显式覆盖。
     */
    public static boolean isRetrySafe(String namespace, String action, boolean readSafeOverride) {
        return readSafeOverride || isRead(namespace, action);
    }
}
