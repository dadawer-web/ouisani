package com.ouisani.aios.core.security;

import com.ouisani.aios.core.syscall.SyscallRequest;
import com.ouisani.aios.core.syscall.schema.LlmPayload;
import com.ouisani.aios.core.syscall.schema.RawPayload;
import com.ouisani.aios.core.syscall.schema.StoragePayload;
import com.ouisani.aios.core.syscall.schema.SyscallPayload;
import com.ouisani.aios.core.syscall.schema.ToolPayload;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 语义级 eBPF 探针 — AIOS 的意图拦截防火墙。
 * <p>
 * 传统的 eBPF 只能拦截 Syscall 查阅 UID 和内存地址。我们的 BpfManager
 * 在 {@code SyscallDispatcher.execute()} 执行前进行"意图拦截 (Intent Interception)"：
 * <ol>
 *   <li>提取 SyscallRequest 的 Payload 内容</li>
 *   <li>结合内置的语义规则库（正则/敏感词/路径黑名单），判断意图</li>
 *   <li>校验 ImpersonationContext 中的 SecurityToken 权限</li>
 *   <li>恶意行为 → 抛出 {@link SecurityException}，写入 SemanticEtw 审计</li>
 * </ol>
 *
 * <h3>OS 类比: eBPF + Seccomp + SELinux</h3>
 * Linux 的 eBPF 在内核态拦截 Syscall，Seccomp 限制可用的 Syscall 号，
 * SELinux 基于安全上下文做 MAC 检查。BpfManager 将三者融合：
 * <ul>
 *   <li>eBPF → 运行时探针注册 ({@link #attachProbe})</li>
 *   <li>Seccomp → 内置规则库 ({@link SemanticRule})</li>
 *   <li>SELinux → SecurityToken 权能校验 ({@link #checkCapability})</li>
 * </ul>
 *
 * <h3>内置语义规则</h3>
 * <ul>
 *   <li>{@code VFS_DESTRUCTIVE_WRITE} — 阻止对核心 VFS 节点的破坏性写入</li>
 *   <li>{@code DANGEROUS_MODULE_LOAD} — 阻止加载高危内核模块</li>
 *   <li>{@code PROMPT_INJECTION} — 检测 LLM Prompt 注入攻击</li>
 *   <li>{@code PRIVILEGE_ESCALATION} — 阻止低权限 Agent 执行特权操作</li>
 *   <li>{@code RESOURCE_ABUSE} — 检测资源滥用模式</li>
 * </ul>
 *
 * @see SyscallFilter
 * @see ImpersonationContext
 * @see SecurityToken
 * @see SemanticEtw
 */
public final class BpfManager implements SyscallFilter {

    private static final Logger log = LoggerFactory.getLogger(BpfManager.class);

    private static final class Holder {
        static final BpfManager INSTANCE = new BpfManager();
    }

    public static BpfManager instance() {
        return Holder.INSTANCE;
    }

    // ════════════════════════════════════════════════════════════════
    //  语义规则引擎
    // ════════════════════════════════════════════════════════════════

    /** 语义规则 — 一条可执行的意图检测策略 */
    public record SemanticRule(
            String id,
            String description,
            ThreatLevel threatLevel,
            Set<String> targetNamespaces,
            RuleEvaluator evaluator
    ) {}

    /** 威胁等级 */
    public enum ThreatLevel {
        LOW(1),       // 记录但不拦截
        MEDIUM(2),    // 记录 + 警告
        HIGH(3),      // 拦截 + 审计
        CRITICAL(4);  // 拦截 + 审计 + Kill Agent

        public final int severity;
        ThreatLevel(int severity) { this.severity = severity; }
    }

    /** 规则评估结果 */
    public record RuleVerdict(boolean blocked, String reason, ThreatLevel level) {}

    /** 规则评估器接口 */
    @FunctionalInterface
    public interface RuleEvaluator {
        RuleVerdict evaluate(String agentId, SyscallRequest request, SecurityToken token);
    }

    // ── 状态 ──

    /** 内置语义规则（按优先级排序） */
    private final List<SemanticRule> builtinRules = new ArrayList<>();

    /** 用户自定义 JS 探针（兼容旧接口） */
    private final ConcurrentHashMap<String, String> jsProbes = new ConcurrentHashMap<>();

    /** 拦截统计 */
    private final ConcurrentHashMap<String, AtomicLong> interceptStats = new ConcurrentHashMap<>();

    /** 路径黑名单 — 这些 VFS 路径绝不允许低权限 Agent 写入 */
    private static final Set<String> PROTECTED_PATHS = Set.of(
            "/dev/gui/dom",
            "/dev/gui/action",
            "/dev/ws",
            "/dev/remote",
            "/var/db/memory",
            "/proc",
            "/sys"
    );

    /** 危险模块黑名单 — 这些模块名不允许低权限 Agent 加载 */
    private static final Set<String> DANGEROUS_MODULES = Set.of(
            "kernel_insmod",
            "kernel_rmmod",
            "rpa.screenshot",
            "rpa.mouse_move",
            "rpa.click",
            "rpa.type",
            "rpa.key_combo"
    );

    /** Prompt 注入检测正则 — 已移除 exec/eval/runtime.exec 规则，由沙箱保障执行安全 */
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("(?i)ignore\\s+(all\\s+)?previous\\s+(instructions|prompts)"),
            Pattern.compile("(?i)you\\s+are\\s+now\\s+a"),
            Pattern.compile("(?i)system\\s*:\\s*"),
            Pattern.compile("(?i)forget\\s+(your\\s+)?(instructions|rules|constraints)"),
            Pattern.compile("(?i)override\\s+(safety|security|filter)"),
            Pattern.compile("(?i)jailbreak"),
            Pattern.compile("(?i)DAN\\s+mode"),
            Pattern.compile("(?i)sudo\\s+rm\\s+-rf"),
            Pattern.compile("(?i)drop\\s+table")
    );

    /** 内核级 Agent 白名单 — 这些 Agent 豁免 Prompt Injection 检查 */
    private static final Set<String> KERNEL_AGENT_WHITELIST = Set.of(
            "topology_compiler",
            "operator_agent",
            "omni_mother"
    );

    private BpfManager() {
        registerBuiltinRules();
        log.info("[BpfManager] 语义 eBPF 引擎已初始化: {} 条内置规则", builtinRules.size());
    }

    // ════════════════════════════════════════════════════════════════
    //  SyscallFilter 接口实现 — 接入 SyscallDispatcher 过滤器链
    // ════════════════════════════════════════════════════════════════

    @Override
    public void preFilter(String agentId, SyscallRequest request) throws SecurityException {
        SecurityToken token = SecurityToken.getEffective();

        for (SemanticRule rule : builtinRules) {
            // 跳过不匹配的命名空间
            if (!rule.targetNamespaces().isEmpty()
                    && !rule.targetNamespaces().contains(request.namespace())) {
                continue;
            }

            RuleVerdict verdict = rule.evaluator().evaluate(agentId, request, token);

            if (verdict.blocked()) {
                // 记录拦截统计
                interceptStats.computeIfAbsent(rule.id(), k -> new AtomicLong(0)).incrementAndGet();

                // 写入 SemanticEtw 审计追踪
                String auditPayload = String.format(
                        "rule=%s agent=%s action=%s threat=%s reason=%s token=%s",
                        rule.id(), agentId, request.fullAction(), verdict.level(),
                        verdict.reason(),
                        token != null ? token.ownerId() + "(level=" + token.privilegeLevel() + ")" : "null");

                SemanticEtw.getInstance().logEvent("SECURITY", "BPF_INTERCEPT", auditPayload);

                log.warn("[BpfManager] 已拦截: 规则={}, Agent={}, 操作={}, 威胁={}, 原因={}",
                        rule.id(), agentId, request.fullAction(), verdict.level(), verdict.reason());

                // HIGH 及以上威胁等级直接拦截
                if (verdict.level().severity >= ThreatLevel.HIGH.severity) {
                    throw new SecurityException(String.format(
                            "[语义 eBPF] Agent '%s' 被规则 '%s' 拦截: %s",
                            agentId, rule.id(), verdict.reason()));
                }
            }
        }

        // 执行 JS 探针（兼容旧接口）
        evaluateJsProbes(agentId, request);
    }

    // ════════════════════════════════════════════════════════════════
    //  内置语义规则注册
    // ════════════════════════════════════════════════════════════════

    private void registerBuiltinRules() {
        // ── 规则 1: VFS 破坏性写入保护 ──
        builtinRules.add(new SemanticRule(
                "VFS_DESTRUCTIVE_WRITE",
                "阻止对核心 VFS 节点的破坏性写入（删除/覆盖系统关键文件）",
                ThreatLevel.HIGH,
                Set.of("storage", "vfs"),
                this::evaluateVfsDestructiveWrite
        ));

        // ── 规则 2: 危险模块加载拦截 ──
        builtinRules.add(new SemanticRule(
                "DANGEROUS_MODULE_LOAD",
                "阻止低权限 Agent 加载高危内核模块（RPA/内核操作）",
                ThreatLevel.HIGH,
                Set.of("tool"),
                this::evaluateDangerousModuleLoad
        ));

        // ── 规则 3: Prompt 注入检测 ──
        builtinRules.add(new SemanticRule(
                "PROMPT_INJECTION",
                "检测 LLM Prompt 注入攻击模式",
                ThreatLevel.HIGH,
                Set.of("llm"),
                this::evaluatePromptInjection
        ));

        // ── 规则 4: 权限提升拦截 ──
        builtinRules.add(new SemanticRule(
                "PRIVILEGE_ESCALATION",
                "阻止低权限 Agent 执行超出其 SecurityToken 权能的操作",
                ThreatLevel.CRITICAL,
                Set.of(),
                this::evaluatePrivilegeEscalation
        ));

        // ── 规则 5: 资源滥用检测 ──
        builtinRules.add(new SemanticRule(
                "RESOURCE_ABUSE",
                "检测 Agent 在短时间内的资源滥用模式（无限循环反思）",
                ThreatLevel.MEDIUM,
                Set.of("llm", "memory"),
                this::evaluateResourceAbuse
        ));
    }

    // ════════════════════════════════════════════════════════════════
    //  规则评估器实现
    // ════════════════════════════════════════════════════════════════

    /**
     * 规则 1: VFS 破坏性写入保护。
     * <p>
     * 如果 Agent 尝试写入受保护的 VFS 路径（/proc, /sys, /dev/gui 等），
     * 且其 SecurityToken 的 privilegeLevel > 1（非内核/非系统级），则拦截。
     */
    private RuleVerdict evaluateVfsDestructiveWrite(String agentId, SyscallRequest request, SecurityToken token) {
        String path = extractPath(request);
        if (path == null) return new RuleVerdict(false, "", ThreatLevel.LOW);

        // 检查是否为受保护路径
        boolean isProtected = PROTECTED_PATHS.stream().anyMatch(path::startsWith);
        if (!isProtected) return new RuleVerdict(false, "", ThreatLevel.LOW);

        // 检查是否为写操作
        boolean isWrite = isWriteOperation(request);
        if (!isWrite) return new RuleVerdict(false, "", ThreatLevel.LOW);

        // 内核级令牌放行
        if (token != null && token.privilegeLevel() <= 1) {
            return new RuleVerdict(false, "", ThreatLevel.LOW);
        }

        return new RuleVerdict(true,
                "Agent attempted destructive write to protected path: " + path,
                ThreatLevel.HIGH);
    }

    /**
     * 规则 2: 危险模块加载拦截。
     * <p>
     * 阻止低权限 Agent 加载 RPA 物理驱动、内核模块操作等高危工具。
     */
    private RuleVerdict evaluateDangerousModuleLoad(String agentId, SyscallRequest request, SecurityToken token) {
        SyscallPayload payload = request.payload();
        String toolName = null;

        if (payload instanceof ToolPayload tool) {
            toolName = tool.toolName();
        } else if (payload instanceof RawPayload raw) {
            toolName = raw.paramString("toolName");
            if (toolName == null) toolName = raw.paramString("tool_name");
        }

        if (toolName == null) return new RuleVerdict(false, "", ThreatLevel.LOW);

        // 检查是否为危险模块
        boolean isDangerous = DANGEROUS_MODULES.stream().anyMatch(toolName::startsWith)
                || toolName.startsWith("rpa.");

        if (!isDangerous) return new RuleVerdict(false, "", ThreatLevel.LOW);

        // 内核级令牌放行
        if (token != null && token.hasCapability(SecurityToken.SE_REALTIME)) {
            return new RuleVerdict(false, "", ThreatLevel.LOW);
        }

        return new RuleVerdict(true,
                "Agent attempted to load dangerous module: " + toolName,
                ThreatLevel.HIGH);
    }

    /**
     * 规则 3: Prompt 注入检测。
     * <p>
     * 在 LLM Prompt 中检测常见的注入攻击模式，如"忽略之前的指令"、
     * "你现在是一个..."、jailbreak 等。
     */
    private RuleVerdict evaluatePromptInjection(String agentId, SyscallRequest request, SecurityToken token) {
        // 内核级 Agent 豁免 Prompt Injection 检查
        if (KERNEL_AGENT_WHITELIST.contains(agentId)) {
            return new RuleVerdict(false, "", ThreatLevel.LOW);
        }

        String prompt = extractPrompt(request);
        if (prompt == null || prompt.isEmpty()) return new RuleVerdict(false, "", ThreatLevel.LOW);

        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(prompt).find()) {
                // 内核级令牌放行（但仍然记录审计）
                if (token != null && token.privilegeLevel() == 0) {
                    SemanticEtw.getInstance().logEvent("SECURITY", "INJECTION_ALLOWED",
                            "agent=" + agentId + " pattern=" + pattern.pattern() + " reason=kernel_token");
                    return new RuleVerdict(false, "", ThreatLevel.LOW);
                }
                return new RuleVerdict(true,
                        "Prompt injection pattern detected: " + pattern.pattern(),
                        ThreatLevel.HIGH);
            }
        }

        return new RuleVerdict(false, "", ThreatLevel.LOW);
    }

    /**
     * 规则 4: 权限提升拦截。
     * <p>
     * 检查 Agent 的 SecurityToken 是否具有执行当前操作所需的权能。
     * 这是 AIOS 的 MAC (Mandatory Access Control) 实现。
     */
    private RuleVerdict evaluatePrivilegeEscalation(String agentId, SyscallRequest request, SecurityToken token) {
        if (token == null) {
            // 无令牌 — 只允许只读操作
            if (isWriteOperation(request)) {
                return new RuleVerdict(true,
                        "No SecurityToken — write operation denied",
                        ThreatLevel.CRITICAL);
            }
            return new RuleVerdict(false, "", ThreatLevel.LOW);
        }

        String action = request.fullAction();

        // 特权操作需要 SE_REGISTRY_WRITE 权能
        if (action.startsWith("apt.") || action.contains("mount") || action.contains("insmod")) {
            if (!token.hasCapability(SecurityToken.SE_REGISTRY_WRITE)) {
                return new RuleVerdict(true,
                        "Agent (level=" + token.privilegeLevel() + ") lacks SE_REGISTRY_WRITE for: " + action,
                        ThreatLevel.CRITICAL);
            }
        }

        // RPA 操作需要 SE_REALTIME 权能
        if (action.startsWith("rpa.") || action.contains("rpa")) {
            if (!token.hasCapability(SecurityToken.SE_REALTIME)) {
                return new RuleVerdict(true,
                        "Agent (level=" + token.privilegeLevel() + ") lacks SE_REALTIME for RPA: " + action,
                        ThreatLevel.CRITICAL);
            }
        }

        // 访问密钥/凭证路径需要 SE_SECRET_ACCESS 权能
        String path = extractPath(request);
        if (path != null && (path.contains("secret") || path.contains("key") || path.contains("credential"))) {
            if (!token.hasCapability(SecurityToken.SE_SECRET_ACCESS)) {
                return new RuleVerdict(true,
                        "Agent (level=" + token.privilegeLevel() + ") lacks SE_SECRET_ACCESS for path: " + path,
                        ThreatLevel.CRITICAL);
            }
        }

        return new RuleVerdict(false, "", ThreatLevel.LOW);
    }

    /**
     * 规则 5: 资源滥用检测。
     * <p>
     * 检测 Agent 是否在短时间内发起大量 LLM 调用（无限自我反思死循环）。
     * 通过滑动窗口计数器实现。
     */
    private final ConcurrentHashMap<String, LinkedList<Long>> callTimestamps = new ConcurrentHashMap<>();
    private static final int ABUSE_WINDOW_MS = 10_000;  // 10 秒窗口
    private static final int ABUSE_THRESHOLD = 20;       // 窗口内最大调用次数

    private RuleVerdict evaluateResourceAbuse(String agentId, SyscallRequest request, SecurityToken token) {
        // 只检测 LLM 和 Memory 调用
        String ns = request.namespace();
        if (!"llm".equals(ns) && !"memory".equals(ns)) {
            return new RuleVerdict(false, "", ThreatLevel.LOW);
        }

        // 内核级令牌放行
        if (token != null && token.hasCapability(SecurityToken.SE_REALTIME)) {
            return new RuleVerdict(false, "", ThreatLevel.LOW);
        }

        long now = System.currentTimeMillis();
        LinkedList<Long> timestamps = callTimestamps.computeIfAbsent(agentId, k -> new LinkedList<>());

        synchronized (timestamps) {
            // 清理过期时间戳
            while (!timestamps.isEmpty() && now - timestamps.getFirst() > ABUSE_WINDOW_MS) {
                timestamps.removeFirst();
            }

            timestamps.addLast(now);

            if (timestamps.size() > ABUSE_THRESHOLD) {
                return new RuleVerdict(true,
                        "Resource abuse detected: " + timestamps.size() + " calls in " + ABUSE_WINDOW_MS + "ms",
                        ThreatLevel.MEDIUM);
            }
        }

        return new RuleVerdict(false, "", ThreatLevel.LOW);
    }

    // ════════════════════════════════════════════════════════════════
    //  JS 探针兼容层（保留旧接口）
    // ════════════════════════════════════════════════════════════════

    private void evaluateJsProbes(String agentId, SyscallRequest request) {
        if (jsProbes.isEmpty()) return;

        // 提取 prompt 供 JS 探针使用
        String prompt = extractPrompt(request);
        if (prompt == null) prompt = "";

        // 委托给旧的 evaluatePrompt 逻辑
        if (!evaluatePrompt(agentId, prompt)) {
            throw new SecurityException(
                    "[语义 eBPF] Agent '" + agentId + "' 被 JS 探针拦截");
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Payload 提取工具
    // ════════════════════════════════════════════════════════════════

    private static String extractPath(SyscallRequest request) {
        SyscallPayload payload = request.payload();
        if (payload instanceof StoragePayload s) return s.path();
        if (payload instanceof RawPayload raw) {
            String p = raw.paramString("path");
            return p != null ? p : raw.paramString("Path");
        }
        return null;
    }

    private static String extractPrompt(SyscallRequest request) {
        SyscallPayload payload = request.payload();
        if (payload instanceof LlmPayload llm) return llm.prompt();
        if (payload instanceof RawPayload raw) {
            String p = raw.paramString("prompt");
            return p != null ? p : raw.paramString("Prompt");
        }
        return null;
    }

    private static boolean isWriteOperation(SyscallRequest request) {
        String action = request.action().toLowerCase();
        if (action.contains("write") || action.contains("mount") || action.contains("remove")
                || action.contains("delete") || action.contains("install") || action.contains("append")) {
            return true;
        }
        SyscallPayload payload = request.payload();
        if (payload instanceof StoragePayload s) {
            return "write".equals(s.mode()) || "append".equals(s.mode());
        }
        if (payload instanceof RawPayload raw) {
            String mode = raw.paramString("mode");
            return "write".equals(mode) || "append".equals(mode);
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════
    //  公共 API
    // ════════════════════════════════════════════════════════════════

    /**
     * 检查 Agent 的 SecurityToken 是否拥有指定权能。
     * 供外部模块调用的便捷方法。
     */
    public boolean checkCapability(String agentId, String capability) {
        SecurityToken token = SecurityToken.getEffective();
        if (token == null) return false;
        return token.hasCapability(capability);
    }

    /**
     * 附加 JS 探针（兼容旧接口）。
     */
    public void attachProbe(String name, String jsCode) {
        jsProbes.put(name, jsCode);
        log.info("[BpfManager] JS 探针 '{}' 已附加 ({} 字符)", name, jsCode.length());
    }

    /**
     * 移除 JS 探针。
     */
    public void detachProbe(String name) {
        jsProbes.remove(name);
        log.info("[BpfManager] JS 探针 '{}' 已分离", name);
    }

    /**
     * 评估 Prompt（兼容旧接口 — 仅 JS 探针）。
     */
    public boolean evaluatePrompt(String agentId, String prompt) {
        if (jsProbes.isEmpty()) return true;

        org.graalvm.polyglot.Context ctx = getOrCreateJsContext();

        for (Map.Entry<String, String> entry : jsProbes.entrySet()) {
            String probeName = entry.getKey();
            String jsCode = entry.getValue();

            try {
                var bindings = ctx.getBindings("js");
                bindings.putMember("agentId", agentId != null ? agentId : "unknown");
                bindings.putMember("prompt", prompt != null ? prompt : "");

                var result = ctx.eval("js", jsCode);

                if (result.isBoolean() && !result.asBoolean()) {
                    SemanticEtw.getInstance().logEvent("SECURITY", "JS_PROBE_BLOCK",
                            "agent=" + agentId + " probe=" + probeName);
                    log.warn("[BpfManager] JS 探针 '{}' 拦截了 Agent '{}'", probeName, agentId);
                    return false;
                }
            } catch (Exception e) {
                log.warn("[BpfManager] JS 探针 '{}' 错误: {}", probeName, e.getMessage());
            }
        }

        return true;
    }

    private volatile org.graalvm.polyglot.Context sharedJsContext;

    private org.graalvm.polyglot.Context getOrCreateJsContext() {
        if (sharedJsContext == null) {
            synchronized (this) {
                if (sharedJsContext == null) {
                    sharedJsContext = org.graalvm.polyglot.Context.newBuilder("js")
                            .allowAllAccess(true).build();
                }
            }
        }
        return sharedJsContext;
    }

    /** 获取拦截统计 */
    public Map<String, Long> getInterceptStats() {
        Map<String, Long> stats = new LinkedHashMap<>();
        interceptStats.forEach((k, v) -> stats.put(k, v.get()));
        return stats;
    }

    /** 获取内置规则列表 */
    public List<SemanticRule> getBuiltinRules() {
        return List.copyOf(builtinRules);
    }

    /** 获取 JS 探针 */
    public Map<String, String> getProbes() {
        return Map.copyOf(jsProbes);
    }

    public int probeCount() {
        return jsProbes.size();
    }

    public void clearProbes() {
        jsProbes.clear();
    }

    /** 清理指定 Agent 的资源滥用追踪状态 */
    public void cleanupAgent(String agentId) {
        callTimestamps.remove(agentId);
    }
}
