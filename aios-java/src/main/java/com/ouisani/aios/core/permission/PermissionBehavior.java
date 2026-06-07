package com.ouisani.aios.core.permission;

/**
 * 权限行为 — 对标 Claude Code 的 PermissionBehavior。
 * <p>
 * 三态决策：允许 / 拒绝 / 询问用户。
 * <p>
 * OS 类比：相当于 Linux 的 SELinux 策略决策 — allow/deny/audit。
 */
public enum PermissionBehavior {
    /** 允许操作 */
    ALLOW,
    /** 拒绝操作 */
    DENY,
    /** 需要询问用户 */
    ASK;

    public boolean isAllowed() { return this == ALLOW; }
    public boolean isDenied() { return this == DENY; }
    public boolean needsPrompt() { return this == ASK; }
}
