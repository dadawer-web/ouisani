package com.ouisani.aios.core.permission;

/**
 * 工具安全检查结果 — 借鉴 AgentScope 2.0 的 {@code bypass_immune} 字段。
 * <p>
 * {@link com.ouisani.aios.core.tool.Tool#checkPermissionDetailed} 的返回类型，替代旧的 {@code String} 返回值，
 * 表达三态决策 + bypass_immune 标记：
 * <ul>
 *   <li>{@link #allowed()} — 工具自身判定允许</li>
 *   <li>{@link #deny(String)} — 工具自身判定拒绝（非 bypass_immune，allow 规则可覆盖）</li>
 *   <li>{@link #safetyAsk(String)} — 危险操作的 safety ASK（bypass_immune=true，allow 规则无法覆盖）
 *       <br>用于 rm -rf /、写 ~/.bashrc、命令注入模式等不可由用户偏好放行的操作</li>
 * </ul>
 * <p>
 * 在权限引擎中的处理（对齐 AgentScope {@code _is_safety_ask}）：
 * <ul>
 *   <li>DEFAULT / ACCEPT_EDITS：safetyAsk 返回 ASK 决策（bypass_immune=true）</li>
 *   <li>BYPASS：safetyAsk 跳过（不返回 ASK，继续后续 allow 流程）</li>
 *   <li>DONT_ASK：safetyAsk 转 DENY（无人值守场景拒绝危险操作）</li>
 *   <li>PLAN：不调用工具自身检查（非只读直接 DENY）</li>
 * </ul>
 *
 * @param behavior      ALLOW / DENY / ASK
 * @param message       拒绝/询问原因；ALLOW 时为 null
 * @param bypassImmune  true = safety ASK，allow 规则无法覆盖
 */
public record SafetyCheckResult(
        PermissionBehavior behavior,
        String message,
        boolean bypassImmune
) {

    public static SafetyCheckResult allowed() {
        return new SafetyCheckResult(PermissionBehavior.ALLOW, null, false);
    }

    /** 普通拒绝（非 bypass_immune）— allow 规则可覆盖。 */
    public static SafetyCheckResult deny(String message) {
        return new SafetyCheckResult(PermissionBehavior.DENY, message, false);
    }

    /** Safety ASK — 不可被 allow 覆盖的危险操作。 */
    public static SafetyCheckResult safetyAsk(String message) {
        return new SafetyCheckResult(PermissionBehavior.ASK, message, true);
    }

    public boolean isAllowed() { return behavior == PermissionBehavior.ALLOW; }
    public boolean isDenied() { return behavior == PermissionBehavior.DENY; }
    public boolean isSafetyAsk() { return behavior == PermissionBehavior.ASK && bypassImmune; }
    public boolean isPlainAsk() { return behavior == PermissionBehavior.ASK && !bypassImmune; }
}
