package com.ouisani.aios.core.permission;

import java.util.List;

/**
 * 权限决策 — 对标 Claude Code 的 PermissionDecision，借鉴 AgentScope 2.0 的 suggested_rules / bypass_immune。
 * <p>
 * 包含行为（allow/deny/ask）、决策原因，以及两个 AgentScope 风格的扩展字段：
 * <ul>
 *   <li>{@code suggestedRules} — 被拒时附带"加什么规则能放行"的建议清单，供 overnight 晨报聚合呈现给用户</li>
 *   <li>{@code bypassImmune} — safety ASK 标记；true 表示此 ASK 不可被 allow 规则覆盖
 *       （rm -rf /、写 ~/.bashrc 等危险操作）。BYPASS 模式跳过，DONT_ASK 模式转 DENY</li>
 * </ul>
 * <p>
 * OS 类比：相当于 SELinux 的访问向量缓存 (AVC) 决策条目，suggestedRules 相当于 audit 日志里的"建议放宽规则"提示，
 * bypassImmune 相当于 SELinux 的 dontaudit* 不可被 user override 的硬约束。
 *
 * @param behavior        决策行为
 * @param message         决策消息
 * @param reason          决策原因（rule/mode/tool_check/safety_check/default/wildcard_deny）
 * @param suggestedRules  被拒时的建议规则清单；允许/询问决策可为空
 * @param bypassImmune    safety ASK 标记（仅 ASK 决策有意义），true 表示不可被 allow 覆盖
 */
public record PermissionDecision(
        PermissionBehavior behavior,
        String message,
        String reason,
        List<PermissionRule> suggestedRules,
        boolean bypassImmune
) {

    /** 紧凑构造器：null suggestedRules 归一化为空列表，避免下游 NPE。 */
    public PermissionDecision {
        if (suggestedRules == null) suggestedRules = List.of();
    }

    public boolean isAllowed() { return behavior.isAllowed(); }
    public boolean isDenied() { return behavior.isDenied(); }
    public boolean needsPrompt() { return behavior.needsPrompt(); }

    /** 是否为 safety ASK（bypass_immune=true 的 ASK）。 */
    public boolean isSafetyAsk() {
        return behavior == PermissionBehavior.ASK && bypassImmune;
    }

    // ── 向后兼容工厂方法（旧 3 参数，等价于 suggestedRules=空 + bypassImmune=false） ──

    public static PermissionDecision allow(String message, String reason) {
        return new PermissionDecision(PermissionBehavior.ALLOW, message, reason, List.of(), false);
    }

    public static PermissionDecision deny(String message, String reason) {
        return new PermissionDecision(PermissionBehavior.DENY, message, reason, List.of(), false);
    }

    public static PermissionDecision ask(String message, String reason) {
        return new PermissionDecision(PermissionBehavior.ASK, message, reason, List.of(), false);
    }

    // ── 新增工厂方法：带建议 / safety ASK ──

    public static PermissionDecision deny(String message, String reason, List<PermissionRule> suggestions) {
        return new PermissionDecision(PermissionBehavior.DENY, message, reason, suggestions, false);
    }

    public static PermissionDecision ask(String message, String reason, List<PermissionRule> suggestions) {
        return new PermissionDecision(PermissionBehavior.ASK, message, reason, suggestions, false);
    }

    /** Safety ASK — 不可被 allow 规则覆盖的危险操作询问。 */
    public static PermissionDecision safetyAsk(String message, String reason, List<PermissionRule> suggestions) {
        return new PermissionDecision(PermissionBehavior.ASK, message, reason, suggestions, true);
    }

    // ── 链式 with 方法（用于在公共 helper 返回后附加建议） ──

    public PermissionDecision withSuggestions(List<PermissionRule> suggestions) {
        return new PermissionDecision(behavior, message, reason,
                suggestions == null ? List.of() : suggestions, bypassImmune);
    }

    public PermissionDecision withBypassImmune(boolean flag) {
        return new PermissionDecision(behavior, message, reason, suggestedRules, flag);
    }
}
