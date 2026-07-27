package com.ouisani.aios.core.permission;

import com.ouisani.aios.core.tool.Tool;
import com.ouisani.aios.core.tool.ToolContext;
import com.ouisani.aios.core.tool.ToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;

/**
 * 权限检查器 — 对标 Claude Code 的 hasPermissionsToUseTool 流水线，借鉴 AgentScope 2.0 的"按模式拆方法"结构。
 * <p>
 * <b>入口分发</b>：{@link #checkPermission} 按 {@link PermissionMode} 分发到独立方法
 * （{@link #checkDefault}/{@link #checkPlan}/{@link #checkAuto}/{@link #checkAcceptEdits}/
 * {@link #checkBypass}/{@link #checkDontAsk}），对齐 AgentScope {@code _check_<mode>} 结构，
 * 消除模式间 drift（原 DONT_ASK 缺 read-only fast path 的 bug 即 drift 的结果）。
 * <p>
 * <b>公共 helper</b>：所有模式共享 8 个 helper
 * （deny/ask/read-only-fp/tool-safety/allow/wildcard-deny-fallback/suggestions/ask-to-deny），
 * 各模式按需调用，语义集中可读。
 * <p>
 * <b>read-only fast path 统一</b>：{@link #checkReadOnlyFastPath} 在所有 6 个模式
 * （DEFAULT/PLAN/AUTO/ACCEPT_EDITS/BYPASS/DONT_ASK）的 deny/ask 规则之后统一调用，
 * 保证只读工具（无副作用）在任意模式下自动放行 — 借鉴 AgentScope
 * {@code _check_read_only_fast_path}，消除模式间 drift（原 DEFAULT/BYPASS 缺此步）。
 * <p>
 * <b>DONT_ASK 不变式</b>：{@link #checkDontAsk} 永不返回 ASK；入口处再设防御性断言，
 * 万一未来改动引入 ASK 路径，自动 convertAskToDeny 兜底并打 WARN。
 * <p>
 * <b>suggestedRules</b>：所有 DENY 决策附带"加什么规则能放行"的建议（对齐 AgentScope
 * {@code _generate_suggestions}），供 overnight 晨报聚合呈现给用户。
 * <p>
 * OS 类比：相当于 Linux 的 SELinux DAC + MAC 检查流水线。
 */
public class PermissionChecker {

    private static final Logger log = LoggerFactory.getLogger(PermissionChecker.class);

    private PermissionMode mode = PermissionMode.DEFAULT;
    private final List<PermissionRule> denyRules = new ArrayList<>();
    private final List<PermissionRule> askRules = new ArrayList<>();
    private final List<PermissionRule> allowRules = new ArrayList<>();
    private int consecutiveDenials = 0;
    private int totalDenials = 0;

    /** 最近 DENY 决策日志（FIFO，容量 64）— 供 overnight 晨报聚合。 */
    private final Deque<DenialRecord> recentDenials = new ConcurrentLinkedDeque<>();
    private static final int MAX_RECENT_DENIALS = 64;

    /**
     * 全局 denial sink — 由 overnight runner 在启动时 set，用于跨子 agent 聚合 DENY 决策到统一缓冲区。
     * <p>
     * 设计动机：每个子 agent（OmniMotherAgent/OperatorAgent）有自己的 PermissionChecker 实例，
     * 但 overnight 晨报需要统一视图，故设置一个全局 sink 让所有 PermissionChecker 实例的 DENY
     * 都流向 overnight runner 的缓冲区。permission 包不依赖 overnight 包（避免循环），
     * 仅暴露 {@link #setGlobalDenialSink(Consumer)} / {@link #clearGlobalDenialSink()} 静态方法。
     * <p>
     * 线程安全：sink 由调用方保证线程安全（overnight runner 用 ConcurrentLinkedDeque）。
     */
    private static volatile Consumer<DenialRecord> globalDenialSink;

    private static final int MAX_CONSECUTIVE_DENIALS = 3;
    private static final int MAX_TOTAL_DENIALS = 20;

    /** 安全敏感路径 */
    private static final Set<String> PROTECTED_PATHS = Set.of(
            ".git", ".claude", ".ssh", ".gnupg", ".env", ".aws"
    );

    /** Auto 模式安全白名单工具 */
    private static final Set<String> SAFE_AUTO_TOOLS = Set.of(
            "file_read", "grep", "glob", "web_fetch", "web_search"
    );

    /**
     * 工具行为分级 — 镜像 jcode {@code safety.rs:177-184} 的 {@code classify} 主入口。
     */
    public static ActionTier classify(String toolName) {
        if (toolName == null || toolName.isBlank()) return ActionTier.RequiresPermission;
        return SAFE_AUTO_TOOLS.contains(toolName.toLowerCase())
                ? ActionTier.AutoAllowed : ActionTier.RequiresPermission;
    }

    // ════════════════════════════════════════════════════════════════
    //  入口分发
    // ════════════════════════════════════════════════════════════════

    /**
     * 检查工具调用权限 — 按 {@link PermissionMode} 分发到独立 _check<Mode> 方法。
     * <p>
     * DONT_ASK 不变式：本方法对 DONT_ASK 模式永不返回 ASK；万一上游实现 drift，
     * 此处的防御性兜底会把任何 ASK 转 DENY 并打 WARN。
     */
    public <I extends ToolInput> PermissionDecision checkPermission(Tool<I> tool, I input, ToolContext context) {
        PermissionDecision decision = switch (mode) {
            case DEFAULT      -> checkDefault(tool, input, context);
            case PLAN         -> checkPlan(tool, input, context);
            case AUTO         -> checkAuto(tool, input, context);
            case ACCEPT_EDITS -> checkAcceptEdits(tool, input, context);
            case BYPASS       -> checkBypass(tool, input, context);
            case DONT_ASK     -> checkDontAsk(tool, input, context);
        };

        // DONT_ASK 不变式防御性兜底
        if (mode == PermissionMode.DONT_ASK && decision.needsPrompt()) {
            log.warn("[Permission] DONT_ASK invariant violated (got ASK), converting → DENY: {}",
                    decision.message());
            decision = convertAskToDeny(decision, tool, input);
        }

        // 记录 DENY 决策（供 overnight 晨报 + ReviewGate 查询）
        if (decision.isDenied()) {
            recordDenial(tool.name(), input, decision, context);
        }

        return decision;
    }

    // ════════════════════════════════════════════════════════════════
    //  DEFAULT 模式 — 每次工具调用都需用户确认（除只读/allow 规则）
    // ════════════════════════════════════════════════════════════════

    private <I extends ToolInput> PermissionDecision checkDefault(Tool<I> tool, I input, ToolContext context) {
        String toolName = tool.name();
        boolean wildcardDenyActive = false;

        // 1. deny 规则（*:deny 仅置 flag，留待兜底）
        PermissionDecision deny = checkDenyRules(tool, input);
        if (deny != null) {
            if ("*".equals(deny.reason()) || "wildcard_deny_pending".equals(deny.reason())) {
                wildcardDenyActive = true;
            } else {
                return deny.withSuggestions(generateSuggestions(tool, input));
            }
        }

        // 2. ask 规则 → ASK
        PermissionDecision ask = checkAskRules(tool, input);
        if (ask != null) return ask.withSuggestions(generateSuggestions(tool, input));

        // 3. read-only fast path → ALLOW
        // 借鉴 AgentScope _check_read_only_fast_path：read-only invocation 无副作用，
        // 在所有模式（含 DEFAULT）下自动放行。原 DEFAULT 缺此步是 drift，现统一修复。
        // 显式 deny/ask 规则已在上方覆盖，此处仅处理"无显式规则 + 只读"的默认放行。
        PermissionDecision ro = checkReadOnlyFastPath(tool, input);
        if (ro != null) return ro;

        // 4. 工具自身检查
        SafetyCheckResult toolCheck = tool.checkPermissionDetailed(input, context);
        if (toolCheck.isDenied()) {
            return PermissionDecision.deny(toolCheck.message(), "tool_check",
                    generateSuggestions(tool, input));
        }
        if (toolCheck.isSafetyAsk()) {
            return PermissionDecision.safetyAsk(toolCheck.message(), "tool_check",
                    generateSuggestions(tool, input));
        }
        // plain ASK 或 ALLOW → 继续

        // 5. 安全路径检查（非只读）
        if (!tool.readOnly()) {
            String pathError = checkProtectedPaths(input);
            if (pathError != null) {
                return PermissionDecision.deny(pathError, "safety_check",
                        generateSuggestions(tool, input));
            }
        }

        // 6. allow 规则 → ALLOW
        PermissionDecision allow = checkAllowRules(tool, input);
        if (allow != null) return allow;

        // 7. *:deny 兜底
        if (wildcardDenyActive) {
            return PermissionDecision.deny(
                    "Denied by wildcard *:deny (no explicit allow rule matched)",
                    "wildcard_deny", generateSuggestions(tool, input));
        }

        // 8. 默认 → ASK（用户确认）
        return PermissionDecision.ask(
                "Tool '" + toolName + "' requires confirmation", "default",
                generateSuggestions(tool, input));
    }

    // ════════════════════════════════════════════════════════════════
    //  PLAN 模式 — 只读锁定
    // ════════════════════════════════════════════════════════════════

    private <I extends ToolInput> PermissionDecision checkPlan(Tool<I> tool, I input, ToolContext context) {
        String toolName = tool.name();
        boolean wildcardDenyActive = false;

        // 1. deny 规则
        PermissionDecision deny = checkDenyRules(tool, input);
        if (deny != null) {
            if ("wildcard_deny_pending".equals(deny.reason())) {
                wildcardDenyActive = true;
            } else {
                return deny.withSuggestions(generateSuggestions(tool, input));
            }
        }

        // 2. ask 规则 → ASK
        PermissionDecision ask = checkAskRules(tool, input);
        if (ask != null) return ask.withSuggestions(generateSuggestions(tool, input));

        // 3. read-only fast path → ALLOW
        PermissionDecision ro = checkReadOnlyFastPath(tool, input);
        if (ro != null) return ro;

        // 4. 非只读 → DENY（PLAN 模式保证：allow 规则不能覆盖只读约束）
        return PermissionDecision.deny(
                "Write operations not allowed in plan mode", "mode",
                generateSuggestions(tool, input));
    }

    // ════════════════════════════════════════════════════════════════
    //  ACCEPT_EDITS 模式 — workdir 内编辑自动 allow
    // ════════════════════════════════════════════════════════════════

    private <I extends ToolInput> PermissionDecision checkAcceptEdits(Tool<I> tool, I input, ToolContext context) {
        String toolName = tool.name();
        boolean wildcardDenyActive = false;

        // 1. deny 规则
        PermissionDecision deny = checkDenyRules(tool, input);
        if (deny != null) {
            if ("wildcard_deny_pending".equals(deny.reason())) {
                wildcardDenyActive = true;
            } else {
                return deny.withSuggestions(generateSuggestions(tool, input));
            }
        }

        // 2. ask 规则 → ASK
        PermissionDecision ask = checkAskRules(tool, input);
        if (ask != null) return ask.withSuggestions(generateSuggestions(tool, input));

        // 3. read-only fast path → ALLOW
        PermissionDecision ro = checkReadOnlyFastPath(tool, input);
        if (ro != null) return ro;

        // 4. 工具自身检查
        SafetyCheckResult toolCheck = tool.checkPermissionDetailed(input, context);
        if (toolCheck.isDenied()) {
            return PermissionDecision.deny(toolCheck.message(), "tool_check",
                    generateSuggestions(tool, input));
        }
        if (toolCheck.isSafetyAsk()) {
            return PermissionDecision.safetyAsk(toolCheck.message(), "tool_check",
                    generateSuggestions(tool, input));
        }

        // 5. 安全路径检查
        if (!tool.readOnly()) {
            String pathError = checkProtectedPaths(input);
            if (pathError != null) {
                return PermissionDecision.deny(pathError, "safety_check",
                        generateSuggestions(tool, input));
            }
        }

        // 6. allow 规则 → ALLOW
        PermissionDecision allow = checkAllowRules(tool, input);
        if (allow != null) return allow;

        // 7. *:deny 兜底
        if (wildcardDenyActive) {
            return PermissionDecision.deny(
                    "Denied by wildcard *:deny (no explicit allow rule matched)",
                    "wildcard_deny", generateSuggestions(tool, input));
        }

        // 8. 非只读工具在 ACCEPT_EDITS 下默认 ALLOW（编辑类自动接受）
        if (!tool.readOnly()) {
            return PermissionDecision.allow("Auto-accepted (acceptEdits mode)", "mode");
        }

        // 9. 默认 → ASK
        return PermissionDecision.ask(
                "Tool '" + toolName + "' requires confirmation", "default",
                generateSuggestions(tool, input));
    }

    // ════════════════════════════════════════════════════════════════
    //  BYPASS 模式 — 跳过 safety ASK，deny/ask 规则仍生效
    // ════════════════════════════════════════════════════════════════

    private <I extends ToolInput> PermissionDecision checkBypass(Tool<I> tool, I input, ToolContext context) {
        // 1. deny 规则（BYPASS 下仅非通配符 deny 生效；*:deny 被忽略 — 上帝模式覆盖通配符拒绝）
        PermissionDecision deny = checkDenyRules(tool, input);
        if (deny != null && !"wildcard_deny_pending".equals(deny.reason()) && !"*".equals(deny.reason())) {
            return deny.withSuggestions(generateSuggestions(tool, input));
        }
        // *:deny 在 BYPASS 下不置 flag、不兜底 — 保留"BYPASS 覆盖 *:deny"语义（零回归）

        // 2. ask 规则 → ASK（BYPASS 下用户的显式 ask 意图仍尊重）
        PermissionDecision ask = checkAskRules(tool, input);
        if (ask != null) return ask.withSuggestions(generateSuggestions(tool, input));

        // 3. read-only fast path → ALLOW
        // 统一 6 模式：BYPASS 也走 fast path，保证 read-only 处理不 drift。
        // BYPASS 下所有工具最终都 ALLOW，此处仅让 read-only 更早返回 + reason 一致。
        PermissionDecision ro = checkReadOnlyFastPath(tool, input);
        if (ro != null) return ro;

        // 4. 工具自身检查 — BYPASS 跳过 safety ASK（仅 ALLOW/DENY 生效）
        SafetyCheckResult toolCheck = tool.checkPermissionDetailed(input, context);
        if (toolCheck.isDenied()) {
            return PermissionDecision.deny(toolCheck.message(), "tool_check",
                    generateSuggestions(tool, input));
        }
        // safetyAsk 在 BYPASS 下故意跳过（per BYPASS 的 "skip safety prompts" 契约）

        // 5. 安全路径检查
        if (!tool.readOnly()) {
            String pathError = checkProtectedPaths(input);
            if (pathError != null) {
                return PermissionDecision.deny(pathError, "safety_check",
                        generateSuggestions(tool, input));
            }
        }

        // 6. allow 规则 → ALLOW
        PermissionDecision allow = checkAllowRules(tool, input);
        if (allow != null) return allow;

        // 7. 默认 → ALLOW（BYPASS — 不走 *:deny 兜底，上帝模式）
        return PermissionDecision.allow("Bypass permissions mode", "mode");
    }

    // ════════════════════════════════════════════════════════════════
    //  AUTO 模式 — 分类器自动决策，减少交互
    // ════════════════════════════════════════════════════════════════

    private <I extends ToolInput> PermissionDecision checkAuto(Tool<I> tool, I input, ToolContext context) {
        String toolName = tool.name();
        boolean wildcardDenyActive = false;

        // 1. deny 规则
        PermissionDecision deny = checkDenyRules(tool, input);
        if (deny != null) {
            if ("wildcard_deny_pending".equals(deny.reason())) {
                wildcardDenyActive = true;
            } else {
                return deny.withSuggestions(generateSuggestions(tool, input));
            }
        }

        // 2. ask 规则 → ASK
        PermissionDecision ask = checkAskRules(tool, input);
        if (ask != null) return ask.withSuggestions(generateSuggestions(tool, input));

        // 3. read-only fast path → ALLOW
        PermissionDecision ro = checkReadOnlyFastPath(tool, input);
        if (ro != null) return ro;

        // 4. 工具自身检查
        SafetyCheckResult toolCheck = tool.checkPermissionDetailed(input, context);
        if (toolCheck.isDenied()) {
            return PermissionDecision.deny(toolCheck.message(), "tool_check",
                    generateSuggestions(tool, input));
        }
        if (toolCheck.isSafetyAsk()) {
            return PermissionDecision.safetyAsk(toolCheck.message(), "tool_check",
                    generateSuggestions(tool, input));
        }

        // 5. 安全路径检查
        if (!tool.readOnly()) {
            String pathError = checkProtectedPaths(input);
            if (pathError != null) {
                return PermissionDecision.deny(pathError, "safety_check",
                        generateSuggestions(tool, input));
            }
        }

        // 6. allow 规则 → ALLOW
        PermissionDecision allow = checkAllowRules(tool, input);
        if (allow != null) return allow;

        // 7. *:deny 兜底
        if (wildcardDenyActive) {
            return PermissionDecision.deny(
                    "Denied by wildcard *:deny (no explicit allow rule matched)",
                    "wildcard_deny", generateSuggestions(tool, input));
        }

        // 8. AUTO 安全白名单工具 → ALLOW
        if (SAFE_AUTO_TOOLS.contains(toolName)) {
            return PermissionDecision.allow("Auto-allowed (safe tool)", "mode");
        }

        // 9. 默认 → ALLOW（AUTO 模式简化为允许，未来接分类器）
        return PermissionDecision.allow("Auto-allowed (auto mode)", "mode");
    }

    // ════════════════════════════════════════════════════════════════
    //  DONT_ASK 模式 — 无人值守，永不返回 ASK
    // ════════════════════════════════════════════════════════════════

    /**
     * DONT_ASK 模式权限检查 — overnight runner 专用。
     * <p>
     * <b>不变式：永不返回 ASK</b>（无人可问）。所有 ASK 路径转 DENY，保留 suggestedRules
     * 供晨报呈现给用户"加什么规则能放行"。safety ASK（bypass_immune）也转 DENY，
     * 确保 rm -rf / 等危险操作在 overnight 也拒绝，不静默放行。
     * <p>
     * 评估顺序（对齐 AgentScope {@code _check_dont_ask}）：
     * <ol>
     *   <li>deny 规则 → DENY</li>
     *   <li>ask 规则 → convertAskToDeny</li>
     *   <li>read-only fast path → ALLOW（🚨 修复关键 bug：原代码缺此步，导致 overnight 连 file_read 都用不了）</li>
     *   <li>tool.checkPermissionDetailed:
     *     <ul>
     *       <li>DENY → DENY</li>
     *       <li>safety ASK → convertAskToDeny</li>
     *       <li>ALLOW/plain ASK → 继续</li>
     *     </ul></li>
     *   <li>allow 规则 → ALLOW</li>
     *   <li>*:deny 兜底 → DENY</li>
     *   <li>默认 → DENY（无人值守，不可 ASK）</li>
     * </ol>
     */
    private <I extends ToolInput> PermissionDecision checkDontAsk(Tool<I> tool, I input, ToolContext context) {
        String toolName = tool.name();
        boolean wildcardDenyActive = false;

        // 1. deny 规则
        PermissionDecision deny = checkDenyRules(tool, input);
        if (deny != null) {
            if ("wildcard_deny_pending".equals(deny.reason())) {
                wildcardDenyActive = true;
            } else {
                return deny.withSuggestions(generateSuggestions(tool, input));
            }
        }

        // 2. ask 规则 → DENY（无人值守，不可 ASK）
        PermissionDecision ask = checkAskRules(tool, input);
        if (ask != null) {
            return convertAskToDeny(ask, tool, input);
        }

        // 3. read-only fast path → ALLOW（🚨 修复关键 bug）
        PermissionDecision ro = checkReadOnlyFastPath(tool, input);
        if (ro != null) return ro;

        // 4. 工具自身检查
        SafetyCheckResult toolCheck = tool.checkPermissionDetailed(input, context);
        if (toolCheck.isDenied()) {
            return PermissionDecision.deny(toolCheck.message(), "tool_check",
                    generateSuggestions(tool, input));
        }
        if (toolCheck.isSafetyAsk()) {
            // safety ASK 在 DONT_ASK 下转 DENY（不静默放行危险操作）
            PermissionDecision safetyAskDecision = PermissionDecision.safetyAsk(
                    toolCheck.message(), "tool_check", generateSuggestions(tool, input));
            return convertAskToDeny(safetyAskDecision, tool, input);
        }
        // plain ASK 或 ALLOW → 继续（plain ASK 在 DONT_ASK 下视同 ALLOW 走后续 allow 规则）

        // 5. 安全路径检查
        if (!tool.readOnly()) {
            String pathError = checkProtectedPaths(input);
            if (pathError != null) {
                return PermissionDecision.deny(pathError, "safety_check",
                        generateSuggestions(tool, input));
            }
        }

        // 6. allow 规则 → ALLOW
        PermissionDecision allow = checkAllowRules(tool, input);
        if (allow != null) return allow;

        // 7. *:deny 兜底 → DENY
        if (wildcardDenyActive) {
            return PermissionDecision.deny(
                    "Denied by wildcard *:deny (no explicit allow rule matched)",
                    "wildcard_deny", generateSuggestions(tool, input));
        }

        // 8. 默认 → DENY（DONT_ASK 模式，无人值守）
        return PermissionDecision.deny(
                "Auto-denied (dontAsk mode - no user available)", "mode",
                generateSuggestions(tool, input));
    }

    // ════════════════════════════════════════════════════════════════
    //  公共 helper（所有模式共享）
    // ════════════════════════════════════════════════════════════════

    /**
     * deny 规则检查 — 处理 *:deny 通配符特殊化。
     * <p>
     * 通配符 {@code *} 仅置 flag（返回 reason="wildcard_deny_pending"），不立即拒绝；
     * 非通配符 deny 保持绝对性，立即拒绝。
     * 调用方根据 reason 判断是否设置 wildcardDenyActive flag，留待兜底步骤。
     *
     * @return DENY 决策（普通 deny）；wildcard_deny_pending 决策（*:deny flag）；null（无匹配）
     */
    private <I extends ToolInput> PermissionDecision checkDenyRules(Tool<I> tool, I input) {
        String toolName = tool.name();
        for (PermissionRule rule : denyRules) {
            if (matchesRule(rule, toolName, input)) {
                if ("*".equals(rule.toolName())) {
                    // 通配符：仅 flag，不立即拒绝（allow 白名单可覆盖）
                    return PermissionDecision.deny(
                            "wildcard_deny_pending", "wildcard_deny_pending");
                }
                log.debug("[Permission] Denied by rule: {} → {}", rule.toRuleString(), toolName);
                return PermissionDecision.deny(
                        "Denied by rule: " + rule.toRuleString(), "rule");
            }
        }
        return null;
    }

    /** ask 规则检查 → 返回 ASK 决策或 null。 */
    private <I extends ToolInput> PermissionDecision checkAskRules(Tool<I> tool, I input) {
        String toolName = tool.name();
        for (PermissionRule rule : askRules) {
            if (matchesRule(rule, toolName, input)) {
                return PermissionDecision.ask(
                        "Tool '" + toolName + "' requires confirmation (ask rule)", "rule");
            }
        }
        return null;
    }

    /**
     * Read-only fast path — 只读工具自动 ALLOW。
     * <p>
     * 借鉴 AgentScope {@code _check_read_only_fast_path}：read-only invocation 没有副作用，
     * 在所有模式（含 DONT_ASK）下自动放行。集中此 helper 防止模式间 drift
     * （原 DONT_ASK 缺此步导致 overnight 连 file_read 都用不了）。
     */
    private <I extends ToolInput> PermissionDecision checkReadOnlyFastPath(Tool<I> tool, I input) {
        if (tool.readOnly()) {
            return PermissionDecision.allow(
                    "Read-only tool auto-allowed", "read_only_fast_path");
        }
        return null;
    }

    /** allow 规则检查 → 返回 ALLOW 决策或 null。 */
    private <I extends ToolInput> PermissionDecision checkAllowRules(Tool<I> tool, I input) {
        String toolName = tool.name();
        for (PermissionRule rule : allowRules) {
            if (matchesRule(rule, toolName, input)) {
                return PermissionDecision.allow(
                        "Allowed by rule: " + rule.toRuleString(), "rule");
            }
        }
        return null;
    }

    /**
     * ASK → DENY 转换 — DONT_ASK 模式专用。
     * <p>
     * 保留原 ASK 的 suggestedRules（如有）或重新生成；保留原 reason 用于追溯。
     * <b>保留 bypassImmune 标记</b>：safety ASK 转的 DENY 仍标记 bypass_immune=true，
     * 让 overnight 晨报能区分"普通拒绝"（可加 allow 规则放行）和"危险操作拒绝"（无法通过规则放行）。
     */
    private <I extends ToolInput> PermissionDecision convertAskToDeny(
            PermissionDecision askDecision, Tool<I> tool, I input) {
        List<PermissionRule> suggestions = askDecision.suggestedRules().isEmpty()
                ? generateSuggestions(tool, input)
                : askDecision.suggestedRules();
        String reason = askDecision.bypassImmune()
                ? "dont_ask_converted_safety_ask"
                : "dont_ask_converted_ask";
        String message = askDecision.bypassImmune()
                ? "Auto-denied (dontAsk mode - safety ASK converted, dangerous operation): "
                  + askDecision.message()
                : "Auto-denied (dontAsk mode - ASK converted, user not available): "
                  + askDecision.message();
        return PermissionDecision.deny(message, reason, suggestions)
                .withBypassImmune(askDecision.bypassImmune());
    }

    /**
     * 规则匹配 — 检查规则是否匹配当前工具调用。
     * <p>
     * 通配符 {@code *} 匹配任意工具名（ruleContent 仍按子串匹配）—— 用于 reviewer 子 agent 的
     * {@code *:deny + 只读工具白名单} blindness 画像。
     */
    private boolean matchesRule(PermissionRule rule, String toolName, ToolInput input) {
        boolean toolMatches = "*".equals(rule.toolName()) || rule.toolName().equalsIgnoreCase(toolName);
        if (!toolMatches) return false;
        if (rule.ruleContent() == null || rule.ruleContent().isEmpty()) return true;

        // Shell 规则匹配（Bash 工具）
        if ("bash".equalsIgnoreCase(toolName) && input instanceof BashToolLike bashInput) {
            return matchShellRule(rule.ruleContent(), bashInput.getCommand());
        }

        // 通用：规则内容作为子串匹配
        String inputStr = input.toJson().toLowerCase();
        return inputStr.contains(rule.ruleContent().toLowerCase());
    }

    /**
     * Shell 命令规则匹配 — 支持精确、前缀、通配符三种模式。
     * <p>
     * <b>检查顺序</b>：必须先检查 {@code :*} 后缀再检查 {@code *} 后缀，
     * 否则 {@code "ls:*"} 会被通配符分支捕获（prefix="ls:"），导致 {@code "ls -la"} 不匹配。
     */
    private boolean matchShellRule(String ruleContent, String command) {
        if (command == null) return false;
        String cmd = command.trim();

        // 前缀模式：npm: 或 npm:* → 匹配 npm install, npm run, npm（裸命令）等
        // 必须在通配符 * 之前检查，否则 "ls:*" 被通配符分支捕获为 prefix="ls:"
        if (ruleContent.endsWith(":*") || ruleContent.endsWith(":")) {
            String prefix = ruleContent.replace(":*", "").replace(":", "");
            return cmd.startsWith(prefix + " ") || cmd.equals(prefix);
        }

        // 通配符模式：git* → 匹配 git, git commit, git push 等
        if (ruleContent.endsWith("*")) {
            String prefix = ruleContent.substring(0, ruleContent.length() - 1);
            return cmd.startsWith(prefix);
        }

        // 精确匹配
        return cmd.equals(ruleContent) || cmd.startsWith(ruleContent + " ");
    }

    /**
     * 检查受保护路径。
     */
    private String checkProtectedPaths(ToolInput input) {
        String json = input.toJson().toLowerCase();
        for (String prot : PROTECTED_PATHS) {
            if (json.contains("/" + prot + "/") || json.contains("/" + prot + "\"")) {
                return "Access to " + prot + " directory is restricted for safety";
            }
        }
        return null;
    }

    /**
     * 生成建议规则 — 对齐 AgentScope {@code _generate_suggestions}。
     * <p>
     * 策略：
     * <ul>
     *   <li>Bash: 提取命令前缀（"npm run build:prod" → "npm run:*"）</li>
     *   <li>文件操作: 提取目录前缀（"src/file.py" → "src/**"）</li>
     *   <li>兜底: 工具名精确匹配</li>
     * </ul>
     */
    private <I extends ToolInput> List<PermissionRule> generateSuggestions(Tool<I> tool, I input) {
        String toolName = tool.name();
        List<PermissionRule> suggestions = new ArrayList<>(2);

        // Bash 工具：提取命令前缀
        if ("bash".equalsIgnoreCase(toolName) && input instanceof BashToolLike bashInput) {
            String cmd = bashInput.getCommand();
            if (cmd != null) {
                String prefix = extractCommandPrefix(cmd.trim());
                if (prefix != null && !prefix.isBlank()) {
                    suggestions.add(new PermissionRule(
                            PermissionRule.RuleSource.SESSION, PermissionBehavior.ALLOW,
                            "Bash", prefix + ":*"));
                }
            }
        }

        // 文件操作工具：提取目录前缀
        String path = extractPath(input);
        if (path != null && !path.isBlank()) {
            String dir = extractDirectory(path);
            if (dir != null && !dir.isBlank()) {
                suggestions.add(new PermissionRule(
                        PermissionRule.RuleSource.SESSION, PermissionBehavior.ALLOW,
                        toolName, dir + "/**"));
            }
        }

        // 兜底：工具名精确匹配
        if (suggestions.isEmpty()) {
            suggestions.add(new PermissionRule(
                    PermissionRule.RuleSource.SESSION, PermissionBehavior.ALLOW,
                    toolName, null));
        }
        return suggestions;
    }

    /** 提取 Bash 命令的前缀（前 1-2 个 token）。如 "npm run build:prod" → "npm run"。 */
    private static String extractCommandPrefix(String cmd) {
        if (cmd == null || cmd.isBlank()) return null;
        String[] tokens = cmd.split("\\s+");
        if (tokens.length == 0) return null;
        // 单 token 命令：返回该 token
        if (tokens.length == 1) return tokens[0];
        // 多 token：取前两个 token（覆盖 "npm run", "git push", "mvn test" 等常见模式）
        return tokens[0] + " " + tokens[1];
    }

    /** 从工具输入 JSON 提取路径字段（"path"、"file"、"filePath"）。 */
    private static String extractPath(ToolInput input) {
        if (input == null) return null;
        String json = input.toJson();
        if (json == null) return null;
        // 简单字符串扫描，避免引 Gson 依赖（项目记忆约束：core 包不用 Gson 反射）
        for (String key : new String[]{"\"path\"", "\"file\"", "\"filePath\"", "\"filename\""}) {
            int idx = json.indexOf(key);
            if (idx < 0) continue;
            int colon = json.indexOf(':', idx);
            if (colon < 0) continue;
            int quoteStart = json.indexOf('"', colon + 1);
            if (quoteStart < 0) continue;
            int quoteEnd = json.indexOf('"', quoteStart + 1);
            if (quoteEnd < 0) continue;
            return json.substring(quoteStart + 1, quoteEnd);
        }
        return null;
    }

    /**
     * 提取路径的目录前缀。如 "src/foo/bar.py" → "src"，"/tmp/x/y.txt" → "/tmp"。
     * <p>
     * 绝对路径（以 {@code /} 开头）跳过前导斜杠，从第二个路径段提取首段目录，
     * 避免 {@code indexOf('/')}=0 触发 {@code slash <= 0} 的 null 返回。
     */
    private static String extractDirectory(String path) {
        if (path == null || path.isBlank()) return null;
        String normalized = path.replace('\\', '/');
        int start = normalized.startsWith("/") ? 1 : 0;
        int slash = normalized.indexOf('/', start);
        if (slash <= start) return null;
        return normalized.substring(0, slash);
    }

    // ════════════════════════════════════════════════════════════════
    //  Denial 日志（供 overnight 晨报聚合）
    // ════════════════════════════════════════════════════════════════

    /**
     * 记录一次 DENY 决策到 recent 队列，转发到全局 sink（如已设置），并持久化到
     * {@link PermissionDenialLedger}（供 ReviewGate 按 agent 查询）。
     */
    private void recordDenial(String toolName, ToolInput input, PermissionDecision decision,
                              ToolContext context) {
        String inputDigest = summarizeInput(input);
        String agentId = context != null ? context.agentId() : "";
        DenialRecord record = new DenialRecord(
                System.currentTimeMillis(), agentId, toolName, inputDigest, decision);
        recentDenials.addLast(record);
        while (recentDenials.size() > MAX_RECENT_DENIALS) {
            recentDenials.pollFirst();
        }
        // 持久化到磁盘（供 ReviewGate 跨 session 查询）— best-effort
        PermissionDenialLedger.append(record);
        // 转发到全局 sink（如 overnight runner 已注册）
        Consumer<DenialRecord> sink = globalDenialSink;
        if (sink != null) {
            try {
                sink.accept(record);
            } catch (Throwable t) {
                // sink 异常不影响权限决策本身
                log.debug("[Permission] globalDenialSink accept failed: {}", t.getMessage());
            }
        }
    }

    /**
     * 注册全局 denial sink — 由 overnight runner 在 startOvernightRun 时调用。
     * <p>
     * 注册后，所有 PermissionChecker 实例的 DENY 决策都会转发到 sink。
     * overnight 停止时调 {@link #clearGlobalDenialSink()} 注销。
     */
    public static void setGlobalDenialSink(Consumer<DenialRecord> sink) {
        globalDenialSink = sink;
    }

    /** 注销全局 denial sink — 由 overnight runner 在停止时调用。 */
    public static void clearGlobalDenialSink() {
        globalDenialSink = null;
    }

    /** 输入摘要（前 200 字符，避免日志爆炸）。 */
    private static String summarizeInput(ToolInput input) {
        if (input == null) return "";
        String json = input.toJson();
        if (json == null) return "";
        return json.length() > 200 ? json.substring(0, 200) + "..." : json;
    }

    /** 取出并清空 recent denials — 供 overnight 晨报聚合调用。 */
    public List<DenialRecord> drainRecentDenials() {
        List<DenialRecord> snapshot = new ArrayList<>(recentDenials);
        recentDenials.clear();
        return snapshot;
    }

    /** peek（不清空）recent denials — 供测试断言。 */
    public List<DenialRecord> peekRecentDenials() {
        return new ArrayList<>(recentDenials);
    }

    /**
     * Denial 记录 — overnight 晨报 + ReviewGate 数据载体。
     *
     * @param timestamp   拒绝时间戳
     * @param agentId     被拒 agent 标识（Phase 7：供 ReviewGate 按 agent 查询）
     * @param toolName    工具名
     * @param inputDigest 输入摘要
     * @param decision    决策（含 suggestedRules / bypassImmune）
     */
    public record DenialRecord(
            long timestamp,
            String agentId,
            String toolName,
            String inputDigest,
            PermissionDecision decision
    ) {

        /**
         * 向后兼容构造 — 旧 4 参数（无 agentId），等价于 agentId=""。
         * 供旧调用方 / 测试使用（零回归）。
         */
        public DenialRecord(long timestamp, String toolName, String inputDigest, PermissionDecision decision) {
            this(timestamp, "", toolName, inputDigest, decision);
        }

        /**
         * 序列化为 JSONL 一行 — 供 {@link PermissionDenialLedger} 持久化。
         * 手写转义，不引 Jackson（同 ReviewRecord 范式）。
         */
        public String toJsonLine() {
            StringBuilder sb = new StringBuilder(256);
            sb.append('{');
            sb.append("\"ts\":").append(timestamp).append(',');
            sb.append("\"agentId\":").append(escape(agentId)).append(',');
            sb.append("\"toolName\":").append(escape(toolName)).append(',');
            sb.append("\"inputDigest\":").append(escape(inputDigest)).append(',');
            sb.append("\"decision\":{");
            sb.append("\"behavior\":").append(escape(decision.behavior().name())).append(',');
            sb.append("\"message\":").append(escape(decision.message())).append(',');
            sb.append("\"reason\":").append(escape(decision.reason())).append(',');
            sb.append("\"bypassImmune\":").append(decision.bypassImmune()).append(',');
            sb.append("\"suggestedRules\":");
            List<PermissionRule> rules = decision.suggestedRules();
            if (rules.isEmpty()) {
                sb.append("[]");
            } else {
                sb.append('[');
                for (int i = 0; i < rules.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append(escape(rules.get(i).toRuleString()));
                }
                sb.append(']');
            }
            sb.append("}}");
            return sb.toString();
        }

        /**
         * 从 JSONL 一行反序列化 — 供跨 session 磁盘回读。
         * best-effort：解析失败返回 null。
         */
        public static DenialRecord fromJsonLine(String line) {
            if (line == null || line.isBlank()) return null;
            try {
                com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
                long ts = o.has("ts") && o.get("ts").isJsonPrimitive() ? o.get("ts").getAsLong() : 0L;
                String agentId = optStr(o, "agentId", "");
                String toolName = optStr(o, "toolName", "");
                String inputDigest = optStr(o, "inputDigest", "");

                com.google.gson.JsonObject d = o.has("decision") && o.get("decision").isJsonObject()
                        ? o.getAsJsonObject("decision") : new com.google.gson.JsonObject();
                PermissionBehavior behavior = PermissionBehavior.valueOf(
                        optStr(d, "behavior", "DENY"));
                String message = optStr(d, "message", "");
                String reason = optStr(d, "reason", "");
                boolean bypassImmune = d.has("bypassImmune") && d.get("bypassImmune").isJsonPrimitive()
                        && d.get("bypassImmune").getAsBoolean();

                List<PermissionRule> suggestions = new ArrayList<>();
                if (d.has("suggestedRules") && d.get("suggestedRules").isJsonArray()) {
                    for (com.google.gson.JsonElement el : d.getAsJsonArray("suggestedRules")) {
                        if (el.isJsonPrimitive()) {
                            try {
                                suggestions.add(PermissionRule.parse(
                                        PermissionRule.RuleSource.SESSION.name(),
                                        PermissionBehavior.ALLOW, el.getAsString()));
                            } catch (Throwable ignored) { /* best-effort */ }
                        }
                    }
                }

                PermissionDecision decision = new PermissionDecision(behavior, message, reason,
                        suggestions, bypassImmune);
                return new DenialRecord(ts, agentId, toolName, inputDigest, decision);
            } catch (Exception e) {
                return null;
            }
        }

        private static String optStr(com.google.gson.JsonObject o, String key, String def) {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
        }

        private static String escape(String s) {
            if (s == null) return "null";
            StringBuilder out = new StringBuilder(s.length() + 8);
            out.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            out.append(String.format("\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            out.append('"');
            return out.toString();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  状态管理
    // ════════════════════════════════════════════════════════════════

    public void setMode(PermissionMode mode) { this.mode = mode; }
    public PermissionMode getMode() { return mode; }

    public void addRule(PermissionRule rule) {
        switch (rule.behavior()) {
            case DENY -> denyRules.add(rule);
            case ASK -> askRules.add(rule);
            case ALLOW -> allowRules.add(rule);
        }
    }

    /**
     * 应用权限画像 —— 把 RoleBlueprint 的 {@code permission:} 块注入检查器。
     * <p>
     * 注入顺序：先设模式（mode），再加 deny/ask/allow 规则（经 {@link #addRule} 按 behavior 分流）。
     * {@code *:deny} 经 1a 的通配符特殊化 + 兜底实现"默认拒绝，allow 白名单可覆盖"语义。
     * null 画像为 no-op（零回归）。
     */
    public void applyProfile(PermissionProfile profile) {
        if (profile == null) return;
        if (profile.mode() != null) setMode(profile.mode());
        profile.denyRules().forEach(this::addRule);
        profile.askRules().forEach(this::addRule);
        profile.allowRules().forEach(this::addRule);
    }

    public void clearRules() {
        denyRules.clear();
        askRules.clear();
        allowRules.clear();
    }

    public void recordDenial() {
        consecutiveDenials++;
        totalDenials++;
    }

    public void resetDenialStreak() { consecutiveDenials = 0; }

    public boolean isDenialLimitReached() {
        return consecutiveDenials >= MAX_CONSECUTIVE_DENIALS || totalDenials >= MAX_TOTAL_DENIALS;
    }

    /**
     * Bash 类工具输入的接口 — 用于 Shell 规则匹配。
     */
    public interface BashToolLike {
        String getCommand();
    }
}
