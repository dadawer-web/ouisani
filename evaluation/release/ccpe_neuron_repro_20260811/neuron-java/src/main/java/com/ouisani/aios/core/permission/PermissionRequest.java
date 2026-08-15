package com.ouisani.aios.core.permission;

/**
 * 权限裁决请求 — 镜像 jcode {@code safety.rs} 的 {@code PermissionRequest} 结构。
 * <p>
 * queue.json 的存储单元。当 {@link PrivilegeSyscallFilter#askPermission} 同步超时
 * 未收到裁决时，将请求持久化到 queue.json，等待外部进程（IMAP poller / Dashboard）
 * 通过 {@code recordPermissionViaFile} 回填结果。
 *
 * @param requestId   请求唯一标识（前缀 "req_" + UUID）
 * @param action      工具名或 syscall action
 * @param description 人类可读描述
 * @param urgency     紧急度
 * @param tier        行为分级
 * @param createdAtMs 创建时间戳
 * @param agentId     发起 Agent ID
 */
public record PermissionRequest(
        String requestId,
        String action,
        String description,
        Urgency urgency,
        ActionTier tier,
        long createdAtMs,
        String agentId
) {
    public PermissionRequest {
        if (urgency == null) urgency = Urgency.DEFAULT;
        if (tier == null) tier = ActionTier.RequiresPermission;
    }
}
