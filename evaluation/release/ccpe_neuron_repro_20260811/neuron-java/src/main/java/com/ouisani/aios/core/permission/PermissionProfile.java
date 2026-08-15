package com.ouisani.aios.core.permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 权限画像 — 角色蓝图的运行时权限维度，对标 Claude Code 的 permission 配置块。
 * <p>
 * 借鉴 OpenScience {@code agent.ts}：把 reviewer 子 agent 的 {@code *:deny + 只读工具白名单}
 * 从 prompt 文字约束提升为结构化权限层强制（blindness 由权限层保证，比 prompt "你是只读的" 可靠）。
 * <p>
 * 四维（对齐 AgentScope 2.0 的 allow/ask/deny 三类规则 + mode）：
 * <ul>
 *   <li>{@code mode} — 基础权限模式（DEFAULT/PLAN/BYPASS/DONT_ASK/...），null 表示不覆盖</li>
 *   <li>{@code denyRules} — 拒绝规则列表（支持通配符 {@code *}）</li>
 *   <li>{@code askRules} — 询问规则列表（DONT_ASK 模式下自动转 DENY）</li>
 *   <li>{@code allowRules} — 允许规则列表（白名单）</li>
 * </ul>
 * <p>
 * 通过 {@link PermissionChecker#applyProfile} 注入。{@code *:deny} 走"默认拒绝 flag"语义：
 * 不立即拒绝，留待 allow 规则之后的兜底步骤 —— 这样 allow 白名单可覆盖默认拒绝。
 *
 * @param mode       基础权限模式；null 表示沿用既有模式
 * @param denyRules  拒绝规则（不可变）
 * @param askRules   询问规则（不可变）
 * @param allowRules 允许规则（不可变）
 */
public record PermissionProfile(
        PermissionMode mode,
        List<PermissionRule> denyRules,
        List<PermissionRule> askRules,
        List<PermissionRule> allowRules
) {

    public PermissionProfile {
        if (denyRules == null) denyRules = List.of();
        if (askRules == null) askRules = List.of();
        if (allowRules == null) allowRules = List.of();
        denyRules = List.copyOf(denyRules);
        askRules = List.copyOf(askRules);
        allowRules = List.copyOf(allowRules);
    }

    /** 空画像 —— 不改变任何权限行为（no-op）。 */
    public static PermissionProfile empty() {
        return new PermissionProfile(null, List.of(), List.of(), List.of());
    }

    /**
     * 向后兼容构造器 — 旧 3 参数（无 askRules），等价于 askRules=空。
     * <p>
     * 供旧调用点（如 RoleBlueprint）继续工作；新代码应使用 4 参数构造器或 {@link #fromMap}。
     */
    public PermissionProfile(PermissionMode mode,
                             List<PermissionRule> denyRules,
                             List<PermissionRule> allowRules) {
        this(mode, denyRules, List.of(), allowRules);
    }

    /**
     * 从 YAML {@code permission:} 子树构造画像。
     * <p>
     * 期望结构：
     * <pre>
     * mode: default
     * deny: ["*", "Bash(rm:*)"]
     * ask: ["Bash(sudo:*)"]
     * allow: [file_read, grep, glob, web_fetch, web_search]
     * </pre>
     *
     * @param map YAML 加载后的 permission 子树；null/空 → empty()
     */
    public static PermissionProfile fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return empty();

        PermissionMode mode = null;
        Object modeVal = map.get("mode");
        if (modeVal instanceof String s && !s.isBlank()) {
            mode = PermissionMode.fromString(s);
        }

        List<PermissionRule> deny = parseRuleList(map.get("deny"), PermissionBehavior.DENY);
        List<PermissionRule> ask = parseRuleList(map.get("ask"), PermissionBehavior.ASK);
        List<PermissionRule> allow = parseRuleList(map.get("allow"), PermissionBehavior.ALLOW);
        return new PermissionProfile(mode, deny, ask, allow);
    }

    private static List<PermissionRule> parseRuleList(Object val, PermissionBehavior behavior) {
        if (!(val instanceof List<?> list) || list.isEmpty()) return List.of();
        List<PermissionRule> rules = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o == null) continue;
            try {
                rules.add(PermissionRule.parse(
                        PermissionRule.RuleSource.POLICY_SETTINGS.name(), behavior, o.toString()));
            } catch (Throwable t) {
                // 单条规则解析失败不阻断整体加载（best-effort）
            }
        }
        return List.copyOf(rules);
    }
}
