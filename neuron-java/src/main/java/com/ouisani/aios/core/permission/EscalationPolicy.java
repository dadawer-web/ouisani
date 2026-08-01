package com.ouisani.aios.core.permission;

import java.util.Set;

/**
 * 深度感知的权限升级策略 — 闭合 LIM「动态 spawn 子 agent + 经子链请求权限升级」攻击面。
 * <p>
 * <b>动机</b>：传统 cgroup/capability 模型未考虑子 agent 在 spawn 树深层位置发起的升级请求。
 * 一个 depth=3 的子 agent 请求 bash（exec）执行，其可信度远低于顶层 agent 的同类请求——
 * 深层子 agent 更可能是被诱导/劫持的攻击载体。本策略在 {@link ToolPermissionChannel#requestApproval}
 * 之前预判：破坏性工具在 spawn 深层直接 auto-deny，根本不询问人类（避免社会工程骗过审批）。
 * <p>
 * <b>设计借鉴</b>：{@code DelegationGuard} 的 depth/breadth/total 三维限制 + env 驱动配置模式。
 * 本类只读 depth（由 {@code DelegationGuard.currentDepth()} 提供），不修改委托状态。
 * <p>
 * <b>破坏性工具集</b>：{@link #DESTRUCTIVE_TOOLS}。刻意<b>不</b>含 {@code agent}/{@code handoff}——
 * spawn 类工具的深度爆炸已由 {@code DelegationGuard.maxDepth} 硬封顶，此处重复拦截是语义重叠。
 * 与 {@link PermissionChecker#TARGET_SCOPING_EXCLUDED}（target-scoped 预授权排除集）有交集但职责不同：
 * 后者是「不可 target-scoped 预授权」，本集是「深层不可经 ASK 升级」。javadoc 交叉引用以示区分。
 * <p>
 * <b>OS 类比</b>：Linux 的 {@code NoNewPrivs} + seccomp 的深度/调用链感知策略——
 * 子进程在受约束的执行上下文中，某些 syscall 直接 EPERM，不进入审批路径。
 */
public final class EscalationPolicy {

    /** 默认最大升级深度 — depth &ge; 此值的子 agent 对破坏性工具的 ASK 直接 DENY。 */
    public static final int DEFAULT_MAX_ESCALATION_DEPTH = 2;

    /**
     * 破坏性工具集 — 深层子 agent 不可经 ASK 升级。
     * <p>
     * 交集于 {@link PermissionChecker} 的 {@code TARGET_SCOPING_EXCLUDED}（bash/security_scan/shell/agent/handoff），
     * 但移除 {@code agent}/{@code handoff}（spawn 类，已由 {@code DelegationGuard} 硬封顶）。
     */
    static final Set<String> DESTRUCTIVE_TOOLS = Set.of("bash", "security_scan", "shell");

    /** 缓存的 env 解析值；volatile 保证可见性，运行期改 env 后需重启 JVM 才生效（与 DelegationGuard 一致）。 */
    private static volatile int cachedMaxDepth = resolveFromEnv("AIOS_MAX_ESCALATION_DEPTH", DEFAULT_MAX_ESCALATION_DEPTH);

    private EscalationPolicy() {
    }

    /** 升级策略判定结果。 */
    public enum Verdict {
        /** 允许进入 ASK 流程，但 payload 须携带 spawn 树上下文供人类审批者参考。 */
        ASK_WITH_CONTEXT,
        /** 深层子 agent 对破坏性工具的升级直接拒绝，不询问人类。 */
        DENY_DEPTH
    }

    /**
     * 当前生效的最大升级深度（env {@code AIOS_MAX_ESCALATION_DEPTH} 驱动，默认 2）。
     * <p>
     * 供 {@code QueryEngine} 构造 deny 消息时引用，使拒绝原因对用户透明。
     */
    public static int maxEscalationDepth() {
        return cachedMaxDepth;
    }

    /**
     * env 驱动的判定 — 读 {@link #maxEscalationDepth()}。
     *
     * @param depth    请求者 spawn 深度（顶层=0）
     * @param toolName 工具名（大小写不敏感）
     * @return 判定结果
     */
    public static Verdict evaluate(int depth, String toolName) {
        return evaluate(depth, toolName, cachedMaxDepth);
    }

    /**
     * 显式 maxDepth 的判定 — <b>供测试</b>（env 不可 per-test 设置）。
     * <p>
     * 判定规则：{@code depth >= maxDepth && DESTRUCTIVE_TOOLS.contains(toolName)} → {@link Verdict#DENY_DEPTH}；
     * 否则 {@link Verdict#ASK_WITH_CONTEXT}。
     *
     * @param depth    请求者 spawn 深度
     * @param toolName 工具名（null/空 → 视为非破坏性，ASK）
     * @param maxDepth 最大升级深度阈值
     * @return 判定结果
     */
    public static Verdict evaluate(int depth, String toolName, int maxDepth) {
        if (depth >= maxDepth && toolName != null
                && DESTRUCTIVE_TOOLS.contains(toolName.toLowerCase())) {
            return Verdict.DENY_DEPTH;
        }
        return Verdict.ASK_WITH_CONTEXT;
    }

    /** 仅供测试重置缓存（镜像 DelegationGuard.configureMaxDepth 的测试兼容模式）。 */
    static void setMaxEscalationDepthForTest(int maxDepth) {
        cachedMaxDepth = maxDepth > 0 ? maxDepth : DEFAULT_MAX_ESCALATION_DEPTH;
    }

    private static int resolveFromEnv(String key, int defaultVal) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) return defaultVal;
        try {
            int parsed = Integer.parseInt(v.trim());
            return parsed > 0 ? parsed : defaultVal;
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}
