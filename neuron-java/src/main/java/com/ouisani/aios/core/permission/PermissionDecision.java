package com.ouisani.aios.core.permission;

/**
 * 权限决策 — 对标 Claude Code 的 PermissionDecision。
 * <p>
 * 包含行为（allow/deny/ask）和决策原因。
 * <p>
 * OS 类比：相当于 SELinux 的访问向量缓存 (AVC) 决策条目。
 *
 * @param behavior 决策行为
 * @param message  决策消息
 * @param reason   决策原因（rule/mode/tool_check/safety_check/default）
 */
public record PermissionDecision(
        PermissionBehavior behavior,
        String message,
        String reason
) {
    public boolean isAllowed() { return behavior.isAllowed(); }
    public boolean isDenied() { return behavior.isDenied(); }
    public boolean needsPrompt() { return behavior.needsPrompt(); }

    public static PermissionDecision allow(String message, String reason) {
        return new PermissionDecision(PermissionBehavior.ALLOW, message, reason);
    }

    public static PermissionDecision deny(String message, String reason) {
        return new PermissionDecision(PermissionBehavior.DENY, message, reason);
    }

    public static PermissionDecision ask(String message, String reason) {
        return new PermissionDecision(PermissionBehavior.ASK, message, reason);
    }
}
