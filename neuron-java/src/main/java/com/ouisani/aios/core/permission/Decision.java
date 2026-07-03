package com.ouisani.aios.core.permission;

/**
 * 权限裁决决策记录 — 镜像 jcode {@code safety.rs:76-84} 的 {@code Decision} 结构。
 * <p>
 * history.json 的存储单元。每次裁决完成（auto/user_sync/file_async/timeout）追加一条，
 * 形成完整审计链。{@code decidedVia} 裸字符串（对齐 jcode），实际值约定：
 * <ul>
 *   <li>{@code "auto"} — ActionTier=AutoAllowed 白名单直通</li>
 *   <li>{@code "user_sync"} — 用户同步裁决（CompletableFuture.complete 触发）</li>
 *   <li>{@code "file_async"} — 外部进程通过文件回填</li>
 *   <li>{@code "timeout"} — sweepStale 过期清理</li>
 * </ul>
 *
 * @param requestId   对应 {@link PermissionRequest#requestId()}
 * @param action      工具名或 syscall action
 * @param approved    true=批准，false=拒绝
 * @param decidedAtMs 裁决时间戳
 * @param decidedVia  裁决来源（auto/user_sync/file_async/timeout）
 * @param reason      裁决原因（可选）
 * @param urgency     紧急度
 * @param tier        行为分级
 */
public record Decision(
        String requestId,
        String action,
        boolean approved,
        long decidedAtMs,
        String decidedVia,
        String reason,
        Urgency urgency,
        ActionTier tier
) {
    public Decision {
        if (urgency == null) urgency = Urgency.DEFAULT;
        if (tier == null) tier = ActionTier.RequiresPermission;
        if (decidedVia == null) decidedVia = "unknown";
        if (reason == null) reason = "";
    }
}
