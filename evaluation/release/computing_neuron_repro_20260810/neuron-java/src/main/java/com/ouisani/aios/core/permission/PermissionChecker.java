package com.ouisani.aios.core.permission;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.audit.UnifiedAuditLog;
import com.ouisani.aios.core.ipc.TraceContext;
import com.ouisani.aios.core.telemetry.SemanticEtw;
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

    /**
     * 最近一次经 {@link #applyProfile} 注入的权限画像原对象 — 供 spawn 子 agent 继承。
     * <p>
     * {@code applyProfile} 把画像<b>展平</b>进 denyRules/askRules/allowRules + mode，但展平后
     * 无法还原原画像对象。为支持 LIM 动态 spawn 的「权限非递增」约束（子 agent 必须继承父的有效
     * profile），这里额外保留原 {@link PermissionProfile} 引用，经
     * {@link SpawnPrivilegeContext} 传播到子线程。
     * <p>
     * null 表示从未 applyProfile（DEFAULT 路径）——等价于「无限制可继承」。
     */
    private PermissionProfile appliedProfile;

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

    /**
     * 角色替换校验器（角色级权限闸门）— 供 {@link #checkRoleMutation} 统一管道复用。
     * <p>
     * Phase 4 defense #2/#3：把"恢复动作"（拓扑突变的角色替换）和"正常动作"（工具调用）走同一套
     * 权限管道 —— 都经 PermissionChecker。角色替换不调 {@link #checkPermission}（那是工具级），
     * 而调 {@link #checkRoleMutation}（角色级），底层共享 {@link PermissionProfileComparator}。
     */
    private static final com.ouisani.aios.core.recovery.RoleReplacementValidator ROLE_REPLACEMENT_VALIDATOR =
            new com.ouisani.aios.core.recovery.RoleReplacementValidator();

    /** Auto 模式安全白名单工具 */
    private static final Set<String> SAFE_AUTO_TOOLS = Set.of(
            "file_read", "grep", "glob", "web_fetch", "web_search"
    );

    /**
     * 不允许 target-scoped 预授权的工具（exec/destructive）。
     * <p>
     * 借鉴 OpenWorker permissions.py:62-80 的 standing_rule_candidate：
     * 构造性排除 exec/destructive 工具，只有 EXTERNAL 风险 + 工具声明 target 参数
     * + 调用实际命名 target 才有资格。
     * <p>
     * AIOS 对应：bash（exec）、security_scan（destructive）、agent/handoff（spawn）
     * 不可被 target-scoped 预授权 — 这些工具的副作用不受 target 约束。
     */
    private static final Set<String> TARGET_SCOPING_EXCLUDED = Set.of(
            "bash", "security_scan", "shell", "agent", "handoff"
    );

    /**
     * Session 级 target-scoped 预授权 — {tool: {allowed targets}}。
     * <p>
     * 借鉴 OpenWorker 的 standing scoped approvals：
     * 用户批准 "always allow this tool for this target" 后，
     * 后续相同 tool + target 的调用自动放行，无需再次询问。
     * <p>
     * 粒度比 {@link #allowRules} 更细：allowRules 是工具级
     * （"允许 send_message"），target-scoped 是目标级
     * （"允许 send_message 到 #general"）。
     * <p>
     * 线程安全：ConcurrentHashMap + ConcurrentHashMap.newKeySet。
     */
    private final Map<String, Set<String>> sessionAllowTargetRules = new ConcurrentHashMap<>();

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
    /**
     * 校验角色替换（拓扑突变）是否允许 — 角色级权限闸门，把"恢复动作"统一进 PermissionChecker 管道。
     * <p>
     * <b>Phase 4 defense #2/#3（洞2 修复）</b>：{@link com.ouisani.aios.core.recovery.TopologyMutationStrategy}
     * 把 LLM 诊断吐出的 {@code suggested_role} 直接喂 {@code resumeNode()} 全程零校验（洞2）。本方法把
     * "恢复动作"和"正常动作"（工具调用走 {@link #checkPermission}）用同一套权限管道处理 —— 角色替换
     * 走本方法（角色级），底层共享 {@link PermissionProfileComparator} 的权限分数模型。
     * <p>
     * <b>两道校验</b>：
     * <ol>
     *   <li><b>存在性白名单</b>（defense #3）：{@code suggestedRole} 必须是 {@code aios_roles} 注册角色，
     *       不是 LLM 随口编的任意字符串（如 {@code admin}）。</li>
     *   <li><b>非越权</b>（defense #2）：目标角色权限分数 ≤ 当前角色（{@link PermissionProfileComparator}）。</li>
     * </ol>
     * <b>BYPASS 不豁免</b>：即便本 PermissionChecker 处于 {@link PermissionMode#BYPASS}，角色替换仍须过白名单 +
     * 非越权 —— 恢复通道的提权攻击不能用 BYPASS 绕过（与 checkPermission 的"硬前置跨租户校验"同精神）。
     *
     * @param currentRole  当前角色名；null/未知 → 仅做存在性白名单
     * @param suggestedRole LLM 建议的目标角色名
     * @return 校验结果（valid=true 可替换；valid=false 附拒绝类别）
     */
    public com.ouisani.aios.core.recovery.RoleReplacementValidator.Result checkRoleMutation(
            String currentRole, String suggestedRole) {
        // BYPASS 不豁免角色替换校验 —— 即便 mode=BYPASS，越权角色替换仍被拦
        return ROLE_REPLACEMENT_VALIDATOR.validate(currentRole, suggestedRole);
    }

    public <I extends ToolInput> PermissionDecision checkPermission(Tool<I> tool, I input, ToolContext context) {
        // 硬前置：跨租户所有权校验（mode-independent，BYPASS/DONT_ASK 不可逃逸）
        // 返回 null 表示不适用或放行；返回 DENY 则跳过模式分发直接定罪（仍走 recordDenial）
        PermissionDecision decision = checkTenantOwnership(tool, input, context);
        if (decision == null) {
            decision = switch (mode) {
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
        }

        // 记录 DENY 决策（供 overnight 晨报 + ReviewGate 查询）
        if (decision.isDenied()) {
            recordDenial(tool.name(), input, decision, context);
        }

        return decision;
    }

    /**
     * 跨租户所有权硬前置 — 校验 {@code caller.tenantId == target.ownerTenantId}。
     * <p>
     * 借鉴 OpenWorker 的显式资源归属模型：每个 VFS 节点携带 {@code ownerTenantId}，
     * 权限校验时直接比对调用者租户与节点归属租户，取代脆弱的路径子串匹配
     * （子串匹配会被 {@code /tenants/tenantA_evil/} 之类前缀碰撞绕过）。
     * <p>
     * <b>硬前置语义</b>：本检查在所有 {@link PermissionMode} 分发之前执行，
     * BYPASS / DONT_ASK 亦不可逃逸 — 跨租户隔离是安全不变式，非可配置策略。
     * 返回的 DENY 标记 {@code bypassImmune=true}，overnight 晨报据此识别
     * "无法通过规则放行的硬拒绝"。
     * <p>
     * <b>向后兼容</b>：任一侧为 null（legacy 节点 / legacy 调用者）一律 skip，
     * 不影响现有无租户语义的调用。仅当两侧均为非 null 且不等时才 DENY。
     *
     * @return DENY 决策（跨租户越权，bypassImmune=true）；null（不适用 / legacy / 放行）
     */
    private <I extends ToolInput> PermissionDecision checkTenantOwnership(Tool<I> tool, I input, ToolContext context) {
        String callerTenantId = context == null ? null : context.tenantId();
        // legacy 调用者无租户声明 → skip（向后兼容）
        if (callerTenantId == null || callerTenantId.isBlank()) return null;

        String path = extractPath(input);
        if (path == null || path.isBlank()) return null;

        // 查 VFS 节点；未初始化或不存在（新建文件）→ skip。
        // 新建文件的所有权由 VfsManager.writeText(path, content, tenantId) 创建时盖戳。
        Optional<VfsNode> nodeOpt = VfsManager.instance().resolve(path);
        if (nodeOpt.isEmpty()) return null;

        String ownerTenantId = nodeOpt.get().ownerTenantId();
        // legacy 节点无租户声明 → skip（向后兼容）
        if (ownerTenantId == null || ownerTenantId.isBlank()) return null;

        if (!callerTenantId.equals(ownerTenantId)) {
            return PermissionDecision.deny(
                    "Cross-tenant access denied: caller tenant '" + callerTenantId
                            + "' != resource owner tenant '" + ownerTenantId
                            + "' for path '" + path + "'",
                    "tenant_ownership",
                    generateSuggestions(tool, input))
                    .withBypassImmune(true);
        }
        return null;
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

        // 6.5. target-scoped 预授权 → ALLOW（借鉴 OpenWorker standing scoped approvals）
        PermissionDecision targetAllow = checkTargetScopedAllow(tool, input);
        if (targetAllow != null) return targetAllow;

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

        // 6.5. target-scoped 预授权 → ALLOW（借鉴 OpenWorker standing scoped approvals）
        PermissionDecision targetAllow = checkTargetScopedAllow(tool, input);
        if (targetAllow != null) return targetAllow;

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

        // 6.5. target-scoped 预授权 → ALLOW（借鉴 OpenWorker standing scoped approvals）
        PermissionDecision targetAllow = checkTargetScopedAllow(tool, input);
        if (targetAllow != null) return targetAllow;

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

        // 6.5. target-scoped 预授权 → ALLOW（借鉴 OpenWorker standing scoped approvals）
        // DONT_ASK 下用户预授权的 target 应放行 — 这正是 standing scoped approvals 的核心价值：
        // 用户在 attended 时预批准某 target，overnight 无人值守时该 target 自动放行，无需 ASK。
        // 构造性排除 exec/destructive 工具由 checkTargetScopedAllow 内部处理。
        PermissionDecision targetAllow = checkTargetScopedAllow(tool, input);
        if (targetAllow != null) return targetAllow;

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
     * Target-scoped 预授权检查 — 借鉴 OpenWorker standing scoped approvals。
     * <p>
     * 如果当前调用的 target 在 session 预授权集合中，ALLOW。
     * 在 allow 规则之后、*:deny 兜底之前检查，让 target-scoped 预授权能覆盖 *:deny。
     * <p>
     * 构造性排除 exec/destructive 工具（bash/security_scan/agent/handoff），
     * 这些工具的副作用不受 target 约束，不可被 target-scoped 预授权。
     *
     * @return ALLOW 决策（target 匹配）；null（无匹配或不适用）
     */
    private <I extends ToolInput> PermissionDecision checkTargetScopedAllow(Tool<I> tool, I input) {
        String toolName = tool.name();
        // 排除 exec/destructive 工具
        if (TARGET_SCOPING_EXCLUDED.contains(toolName.toLowerCase())) return null;

        Set<String> allowedTargets = sessionAllowTargetRules.get(toolName);
        if (allowedTargets == null || allowedTargets.isEmpty()) return null;

        String target = extractTarget(input);
        if (target == null || target.isBlank()) return null;

        if (allowedTargets.contains(target)) {
            return PermissionDecision.allow(
                    "Allowed by target-scoped rule: " + toolName + " → " + target,
                    "target_scoped_allow");
        }
        return null;
    }

    /**
     * 从工具输入提取 target（target/path/url/channel/to 字段）。
     * <p>
     * 优先级：target > path > url > channel > to。
     * 与 {@link #extractPath} 类似但更通用，支持非文件类工具（如 send_message 的 target 参数）。
     * <p>
     * public 以便 {@link com.ouisani.aios.core.tool.QueryEngine} 在 ASK 分支提取 target
     * 用于审批 UI 展示与 {@link #grantTargetApproval} 记账。
     */
    public static String extractTarget(ToolInput input) {
        if (input == null) return null;
        String json = input.toJson();
        if (json == null) return null;
        for (String key : new String[]{"\"target\"", "\"path\"", "\"url\"", "\"channel\"", "\"to\""}) {
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
     * 授予 target-scoped 预授权 — 用户批准 "always allow this tool for this target"。
     * <p>
     * 借鉴 OpenWorker permissions.py:62-80 的 standing_rule_candidate：
     * <ul>
     *   <li>只有非 exec/destructive 工具 + 非空 target 才有资格</li>
     *   <li>批准后，后续相同 tool + target 的调用自动放行</li>
     *   <li>不同 target 仍需单独批准（send_message 到 #general 不影响 #random）</li>
     * </ul>
     * <p>
     * 由审批 UI/CLI 在用户选择 "Always allow for this target" 时调用。
     *
     * @param toolName 工具名
     * @param target   目标标识（如 "#general"、"slack:C12345"、"https://api.example.com"）
     */
    public void grantTargetApproval(String toolName, String target) {
        if (toolName == null || target == null || target.isBlank()) return;
        if (TARGET_SCOPING_EXCLUDED.contains(toolName.toLowerCase())) {
            log.debug("[Permission] Target scoping not eligible for exec/destructive tool: {}", toolName);
            return;
        }
        sessionAllowTargetRules
                .computeIfAbsent(toolName, k -> ConcurrentHashMap.newKeySet())
                .add(target);
        log.info("[Permission] Target-scoped approval granted: {} → {}", toolName, target);
    }

    /** 清除所有 target-scoped 预授权 — 供 session 重置或测试使用。 */
    public void clearTargetApprovals() {
        sessionAllowTargetRules.clear();
    }

    /** 查询某工具是否有 target-scoped 预授权（供测试断言）。 */
    public boolean hasTargetApproval(String toolName, String target) {
        Set<String> targets = sessionAllowTargetRules.get(toolName);
        return targets != null && targets.contains(target);
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
     * <p>
     * 同时接入两个跨层审计 sink（P0 联合治理）：
     * <ul>
     *   <li>{@link SemanticEtw#logAuditEvent} — 把 permission 拒绝写入语义审计环形缓冲
     *       （此前 permission 层只写 DenialLedger 不写 ETW，三层审计缺一条腿）</li>
     *   <li>{@link UnifiedAuditLog#append} — 按 traceId 把本条拒绝与 cgroup/sandbox 决策
     *       聚合到统一审计链，供 {@code listByTraceId} 端到端回溯</li>
     * </ul>
     * 两者均 best-effort，永不抛出，不影响权限决策本身。
     */
    public void recordDenial(String toolName, ToolInput input, PermissionDecision decision,
                             ToolContext context) {
        String inputDigest = summarizeInput(input);
        String agentId = context != null ? context.agentId() : "";
        String traceId = TraceContext.getCurrentTraceId();
        DenialRecord record = new DenialRecord(
                System.currentTimeMillis(), agentId, toolName, inputDigest, decision, traceId);
        recentDenials.addLast(record);
        while (recentDenials.size() > MAX_RECENT_DENIALS) {
            recentDenials.pollFirst();
        }
        // 持久化到磁盘（供 ReviewGate 跨 session 查询）— best-effort
        PermissionDenialLedger.append(record);

        // ── P0: 接入 SemanticEtw 语义审计（补齐三层审计的 permission 这条腿）──
        try {
            SemanticEtw.getInstance().logAuditEvent(
                    agentId,
                    "permission_check",
                    null,                          // thinkingContext — permission 层无 LLM 思考上下文
                    decision.bypassImmune() ? "high" : "medium",
                    "permission_rule",
                    toolName + ":" + (inputDigest == null ? "" : inputDigest),
                    decision.reason());
        } catch (Throwable t) {
            log.debug("[Permission] SemanticEtw logAuditEvent failed: {}", t.getMessage());
        }

        // ── P0: 接入 UnifiedAuditLog 跨层联合审计链 ──
        try {
            UnifiedAuditLog.append(
                    UnifiedAuditLog.LAYER_PERMISSION,
                    decision.behavior().name(),     // DENY / ASK 等
                    agentId,
                    toolName,
                    decision.reason());
        } catch (Throwable t) {
            log.debug("[Permission] UnifiedAuditLog append failed: {}", t.getMessage());
        }

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
     * @param traceId     端到端追踪标识（可能为 null）。由 {@link com.ouisani.aios.core.ipc.TraceContext}
     *                    在 turn 入口注入，使本条 permission 拒绝可与同 traceId 下的 cgroup/sandbox
     *                    决策在 {@link com.ouisani.aios.core.audit.UnifiedAuditLog} 中关联——
     *                    这是"联合治理 vs 各自为战"的跨层审计证据。
     */
    public record DenialRecord(
            long timestamp,
            String agentId,
            String toolName,
            String inputDigest,
            PermissionDecision decision,
            String traceId
    ) {

        /**
         * 向后兼容构造 — 5 参数（无 traceId），等价于 traceId=null。
         * 供旧调用方 / 测试使用（零回归）。
         */
        public DenialRecord(long timestamp, String agentId, String toolName,
                            String inputDigest, PermissionDecision decision) {
            this(timestamp, agentId, toolName, inputDigest, decision, null);
        }

        /**
         * 向后兼容构造 — 旧 4 参数（无 agentId、无 traceId），等价于 agentId="" + traceId=null。
         * 供旧调用方 / 测试使用（零回归）。
         */
        public DenialRecord(long timestamp, String toolName, String inputDigest, PermissionDecision decision) {
            this(timestamp, "", toolName, inputDigest, decision, null);
        }

        /**
         * 返回带指定 traceId 的副本 — 供 {@link PermissionDenialLedger} 在调用方未带 traceId 时
         * 从 {@link TraceContext} 补全（保证审计链每条 denial 都有 traceId 锚点）。
         */
        public DenialRecord withTraceId(String traceId) {
            return new DenialRecord(timestamp, agentId, toolName, inputDigest, decision, traceId);
        }

        /**
         * 序列化为 JSONL 一行 — 供 {@link PermissionDenialLedger} 持久化。
         * 手写转义，不引 Jackson（同 ReviewRecord 范式）。
         */
        public String toJsonLine() {
            StringBuilder sb = new StringBuilder(256);
            sb.append('{');
            sb.append("\"ts\":").append(timestamp).append(',');
            sb.append("\"traceId\":").append(traceId == null ? "null" : escape(traceId)).append(',');
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
                String traceId = o.has("traceId") && !o.get("traceId").isJsonNull()
                        && o.get("traceId").isJsonPrimitive() ? o.get("traceId").getAsString() : null;

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
                return new DenialRecord(ts, agentId, toolName, inputDigest, decision, traceId);
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
        this.appliedProfile = profile;
        if (profile.mode() != null) setMode(profile.mode());
        profile.denyRules().forEach(this::addRule);
        profile.askRules().forEach(this::addRule);
        profile.allowRules().forEach(this::addRule);
    }

    /**
     * 返回最近一次经 {@link #applyProfile} 注入的有效权限画像 — 供 spawn 子 agent 继承。
     * <p>
     * <b>归一化</b>：{@code null}（从未 applyProfile）或 {@link PermissionProfile#empty()}
     * （mode=null 且三规则列表皆空）一律返回 null，表示「无限制可继承」——下游
     * {@code AgentTool} 据此传 {@link PermissionProfile#empty()} 给子 QueryEngine（no-op，
     * 子保持 DEFAULT，与父 DEFAULT 一致，零回归）。
     * <p>
     * <b>LIM 攻击面闭合</b>：非 null 返回值携带父的 {@code *:deny} + allowlist 等限制，
     * 子 agent 经 {@code QueryEngine(sdk, agentId, workingDir, List.of(), currentProfile())}
     * 重应用同一 profile，强制「子权限 ⊆ 父权限」，堵住「spawn 即升级」漏洞。
     */
    public PermissionProfile currentProfile() {
        if (appliedProfile == null) return null;
        if (appliedProfile.mode() == null
                && appliedProfile.denyRules().isEmpty()
                && appliedProfile.askRules().isEmpty()
                && appliedProfile.allowRules().isEmpty()) {
            return null;
        }
        return appliedProfile;
    }

    public void clearRules() {
        denyRules.clear();
        askRules.clear();
        allowRules.clear();
        sessionAllowTargetRules.clear();
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
