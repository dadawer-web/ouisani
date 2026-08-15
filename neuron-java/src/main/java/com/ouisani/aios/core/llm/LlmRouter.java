package com.ouisani.aios.core.llm;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.NumaAffinity;
import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 异构算力调度路由器 — AIOS 的 ARM big.LITTLE 调度器。
 * <p>
 * 借鉴 ARM big.LITTLE 架构和 VCPToolBox 的 SemanticModelRouter 思想，
 * 不能让所有 AgentTask 都消耗最昂贵的旗舰模型算力。LlmRouter 在
 * TaskScheduler 执行任务时拦截 LLM 请求，根据任务的算力亲和性和
 * 上下文复杂度，动态路由到 P_CORE 或 E_CORE。
 *
 * <h3>调度策略</h3>
 * <table>
 *   <tr><th>亲和性</th><th>行为</th><th>类比</th></tr>
 *   <tr><td>REQUIRE_P_CORE</td><td>必须路由到 P_CORE</td><td>taskset -c 4-7（绑大核）</td></tr>
 *   <tr><td>PREFER_P_CORE</td><td>优先 P_CORE，低负载时可降级</td><td>默认调度大核</td></tr>
 *   <tr><td>AUTO</td><td>Router 根据上下文自动决定</td><td>HMP 调度器</td></tr>
 *   <tr><td>PREFER_E_CORE</td><td>优先 E_CORE，Turbo Boost 时可拉升</td><td>默认调度小核</td></tr>
 *   <tr><td>REQUIRE_E_CORE</td><td>必须路由到 E_CORE</td><td>taskset -c 0-3（绑小核）</td></tr>
 * </table>
 *
 * <h3>动态升降级</h3>
 * <ul>
 *   <li><b>降级 (Downgrade)</b>：简单任务自动降级到 E_CORE 省成本</li>
 *   <li><b>拉升 (Turbo Boost)</b>：E_CORE 处理失败或遇到复杂任务时，自动拉升到 P_CORE</li>
 * </ul>
 *
 * @see ComputeCore
 * @see ComputeAffinity
 */
public class LlmRouter implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    // ── 复杂度检测阈值 ──

    /** Prompt 长度超过此值视为复杂任务 */
    private static final int COMPLEX_PROMPT_LENGTH = 500;

    /** 复杂任务关键词 */
    private static final List<String> COMPLEX_KEYWORDS = List.of(
            "代码", "分析", "Bug", "bug", "debug", "Debug", "代码审查", "重构", "refactor",
            "架构", "设计", "推理", "逻辑", "证明", "数学", "算法"
    );

    /** 简单任务关键词 — 自动降级到 E_CORE */
    private static final List<String> SIMPLE_KEYWORDS = List.of(
            "总结", "格式化", "翻译", "摘要", "格式", "列表", "命名", "注释",
            "summarize", "format", "translate", "list"
    );

    /** Budget threshold for cross-node (remote) model access. */
    private static final int REMOTE_BUDGET_THRESHOLD = 100;

    // ── Noop 降级 Provider（借鉴 Langflow Noop 服务设计） ──
    private final NoopLlmProvider noopProvider = new NoopLlmProvider();

    // ── 后端注册 ──

    /** 按名称注册的 Provider */
    private final ConcurrentHashMap<String, LlmProvider> backendProviders = new ConcurrentHashMap<>();

    /** 按 ComputeCore 分类的 Provider 列表 */
    private final ConcurrentHashMap<ComputeCore, List<LlmProvider>> coreProviders = new ConcurrentHashMap<>();

    // ── 调度统计 ──

    private final AtomicLong pCoreDispatches = new AtomicLong(0);
    private final AtomicLong eCoreDispatches = new AtomicLong(0);
    private final AtomicLong turboBoosts = new AtomicLong(0);
    private final AtomicLong downgrades = new AtomicLong(0);

    public LlmRouter() {
        coreProviders.put(ComputeCore.P_CORE, new ArrayList<>());
        coreProviders.put(ComputeCore.E_CORE, new ArrayList<>());
    }

    // ════════════════════════════════════════════════════════════════
    //  Provider 注册
    // ════════════════════════════════════════════════════════════════

    /**
     * 注册一个 LLM 后端 Provider。
     * <p>
     * Provider 会根据其 {@link LlmProvider#computeCore()} 返回值
     * 自动分类到 P_CORE 或 E_CORE 池中。
     *
     * @param name     后端名称（如 "gpt4o", "gpt4o_mini"）
     * @param provider LLM Provider 实例
     */
    public void registerProvider(String name, LlmProvider provider) {
        backendProviders.put(name, provider);

        ComputeCore core = provider.computeCore();
        coreProviders.computeIfAbsent(core, k -> new ArrayList<>()).add(provider);

        log.info("[LLM Router] 已注册后端: '{}' → {} (core={}, NUMA={})",
                name, provider.name(), core,
                isLocalNode(name) ? "LOCAL" : "REMOTE");
    }

    public void unregisterProvider(String name) {
        LlmProvider removed = backendProviders.remove(name);
        if (removed != null) {
            coreProviders.getOrDefault(removed.computeCore(), List.of()).remove(removed);
        }
        log.info("[LLM Router] 已注销 Provider: name={}", name);
    }

    public Map<String, LlmProvider> getBackends() {
        return Collections.unmodifiableMap(backendProviders);
    }

    // ════════════════════════════════════════════════════════════════
    //  LlmProvider 接口实现
    // ════════════════════════════════════════════════════════════════

    @Override
    public String name() {
        return "llm-router";
    }

    @Override
    public ComputeCore computeCore() {
        return ComputeCore.P_CORE; // Router 本身是 P_CORE 级别
    }

    @Override
    public String think(String prompt, String systemPrompt) {
        RoutingDecision decision = route(prompt);
        LlmProvider provider = resolveProvider(decision.backendName);

        log.info("[LLM Router] 分发: promptLen={}, core={}, backend={}, reason={}",
                prompt.length(), decision.targetCore, decision.backendName, decision.reason);

        SemanticEtw.getInstance().logEvent("LLM", "ROUTE",
                "core=" + decision.targetCore + " backend=" + decision.backendName
                + " reason=" + decision.reason + " promptLen=" + prompt.length());

        try {
            String result = provider.think(prompt, systemPrompt);

            // ── 安全断言：绝不允许空响应穿透 ──
            if (result == null || result.isBlank()) {
                log.error("[LLM Router] 致命错误: Provider '{}' 返回空响应! core={}, backend={}",
                        provider.name(), decision.targetCore, decision.backendName);
                if (decision.targetCore == ComputeCore.E_CORE) {
                    log.warn("[LLM Router] E_CORE 返回空，强制 Turbo Boost 至 P_CORE");
                    return turboBoost(prompt, systemPrompt, decision, null);
                }
                // P_CORE 也返回空 — 致命错误，绝不允许系统带着空智能体代码往下走
                throw new RuntimeException("Turbo Boost returned empty payload: P_CORE provider '"
                        + provider.name() + "' returned null/blank response");
            }

            // 检测是否需要 Turbo Boost（E_CORE 返回质量不足时拉升）
            if (decision.targetCore == ComputeCore.E_CORE && needsTurboBoost(result)) {
                return turboBoost(prompt, systemPrompt, decision, result);
            }

            return result;

        } catch (RuntimeException e) {
            // 空响应断言抛出的 RuntimeException 必须穿透，不能被 Turbo Boost 吞掉
            if (e.getMessage() != null && e.getMessage().startsWith("Turbo Boost returned empty payload")) {
                throw e;
            }
            // E_CORE 失败时自动 Turbo Boost 到 P_CORE
            if (decision.targetCore == ComputeCore.E_CORE) {
                log.warn("[LLM Router] E_CORE 失败，Turbo Boost 至 P_CORE: {}", e.getMessage());
                return turboBoost(prompt, systemPrompt, decision, null);
            }
            throw e;
        }
    }

    @Override
    public String think(String prompt) {
        return think(prompt, "");
    }

    /**
     * 流式推理路由 — 将流式请求路由到底层 Provider。
     * <p>
     * 借鉴 CopilotKit 的 SSE 流式渲染：LLM 响应逐 token 推送到前端。
     * LlmRouter 在这里只做路由转发，不改变流式语义。
     *
     * @param prompt       用户提示词
     * @param systemPrompt 系统提示词
     * @param onDelta      每个 token 片段的回调
     * @return 完整的文本回复
     */
    public String thinkStream(String prompt, String systemPrompt, java.util.function.Consumer<String> onDelta) {
        RoutingDecision decision = route(prompt);
        LlmProvider provider = resolveProvider(decision.backendName());

        if (provider == null) {
            log.warn("[LlmRouter] 无可用 Provider，降级到 NoopLlmProvider");
            provider = firstAvailable(ComputeCore.P_CORE);
            if (provider == null) {
                String fallback = "Error: No LLM provider available";
                onDelta.accept(fallback);
                return fallback;
            }
        }

        log.debug("[LlmRouter] thinkStream 路由到: {} (core={})", provider.name(), provider.computeCore());

        try {
            return provider.thinkStream(prompt, systemPrompt, onDelta);
        } catch (Exception e) {
            log.error("[LlmRouter] thinkStream 异常: {}", e.getMessage());
            // 降级到同步模式
            String result = provider.think(prompt, systemPrompt);
            onDelta.accept(result);
            return result;
        }
    }

    /**
     * 流式推理路由（含 ephemeral 系统上下文块）— 把 ephemeralContext 透传给底层 Provider。
     * <p>
     * Provider（如 OpenAiAdapter）会把 ephemeralContext 追加为 &lt;system-context&gt; 块到最后一条
     * user message（send-time only，永不持久化）。降级分支同样透传 ephemeralContext 到
     * {@code provider.think(prompt, systemPrompt, ephemeralContext)}，保证错误恢复路径不丢上下文。
     */
    public String thinkStream(String prompt, String systemPrompt, String ephemeralContext,
                              java.util.function.Consumer<String> onDelta) {
        RoutingDecision decision = route(prompt);
        LlmProvider provider = resolveProvider(decision.backendName());

        if (provider == null) {
            log.warn("[LlmRouter] 无可用 Provider，降级到 NoopLlmProvider");
            provider = firstAvailable(ComputeCore.P_CORE);
            if (provider == null) {
                String fallback = "Error: No LLM provider available";
                onDelta.accept(fallback);
                return fallback;
            }
        }

        log.debug("[LlmRouter] thinkStream(ephemeral) 路由到: {} (core={})", provider.name(), provider.computeCore());

        try {
            return provider.thinkStream(prompt, systemPrompt, ephemeralContext, onDelta);
        } catch (Exception e) {
            log.error("[LlmRouter] thinkStream(ephemeral) 异常: {}", e.getMessage());
            // 降级到同步模式 — 同样透传 ephemeralContext
            String result = provider.think(prompt, systemPrompt, ephemeralContext);
            onDelta.accept(result);
            return result;
        }
    }

    /** 流式推理（无系统提示词） */
    public String thinkStream(String prompt, java.util.function.Consumer<String> onDelta) {
        return thinkStream(prompt, "", onDelta);
    }

    /**
     * 带扩展点的 LLM 调用 — 借鉴 Agent Zero 的 @extensible 机制。
     * <p>
     * 包装 {@link #think(String, String)}，支持 before/after 钩子。
     * <ul>
     *   <li>before 钩子可短路：返回非 null 时直接作为最终结果</li>
     *   <li>after 钩子可修改返回值</li>
     * </ul>
     * 不替换原始 think 方法，仅提供带扩展点的入口。
     *
     * @param prompt       用户 prompt
     * @param systemPrompt 系统提示词
     * @return LLM 响应（经过 after 钩子处理）
     */
    @com.ouisani.aios.core.plugin.Extensible("llm_think")
    public String thinkWithExtensions(String prompt, String systemPrompt) {
        // before 钩子
        Map<String, Object> hookArgs = new HashMap<>();
        hookArgs.put("prompt", prompt);
        hookArgs.put("systemPrompt", systemPrompt);
        Object shortCircuit = com.ouisani.aios.core.plugin.ExtensibleHookRegistry.before("llm_think", this, hookArgs);
        if (shortCircuit != null) {
            String result = (String) shortCircuit;
            return (String) com.ouisani.aios.core.plugin.ExtensibleHookRegistry.after("llm_think", this, result, hookArgs);
        }

        // 执行原始逻辑
        String result = think(prompt, systemPrompt);

        // after 钩子
        return (String) com.ouisani.aios.core.plugin.ExtensibleHookRegistry.after("llm_think", this, result, hookArgs);
    }

    @Override
    public String thinkWithHistory(List<ChatMessage> messages, String systemPrompt) {
        String lastUserMsg = messages.stream()
                .filter(m -> "user".equals(m.role()))
                .map(ChatMessage::contentAsString)
                .reduce((first, second) -> second)
                .orElse("");

        RoutingDecision decision = route(lastUserMsg);
        LlmProvider provider = resolveProvider(decision.backendName);

        log.info("[LLM Router] Route history: messages={}, core={}, backend={}",
                messages.size(), decision.targetCore, decision.backendName);

        String result = provider.thinkWithHistory(messages, systemPrompt);

        // ── 安全断言：绝不允许空响应穿透 ──
        if (result == null || result.isBlank()) {
            log.error("[LLM Router] 致命错误: thinkWithHistory 返回空! provider={}, core={}",
                    provider.name(), decision.targetCore);
            if (decision.targetCore == ComputeCore.E_CORE) {
                LlmProvider pCoreProvider = firstAvailable(ComputeCore.P_CORE);
                if (pCoreProvider != null) {
                    log.warn("[LLM Router] E_CORE thinkWithHistory 为空，Turbo Boost 至 P_CORE");
                    String pCoreResult = pCoreProvider.thinkWithHistory(messages, systemPrompt);
                    if (pCoreResult != null && !pCoreResult.isBlank()) return pCoreResult;
                }
            }
            throw new RuntimeException("Turbo Boost returned empty payload: thinkWithHistory provider '"
                    + provider.name() + "' returned null/blank response");
        }

        return result;
    }

    /**
     * 多轮对话流式推理 — 与 {@link #thinkWithHistory} 对应的流式版本。
     * <p>
     * 路由策略与 thinkWithHistory 一致（按最后一条 user 消息选路），
     * 流式语义与 thinkStream 一致（仅转发，不改变 delta）。异常时降级为
     * 同步 thinkWithHistory 并一次性回调，保证调用方总能拿到完整回复。
     */
    @Override
    public String thinkWithHistoryStream(List<ChatMessage> messages, String systemPrompt,
                                         java.util.function.Consumer<String> onDelta) {
        return thinkWithHistoryStream(messages, systemPrompt, "", onDelta);
    }

    /** Streaming history call with send-time-only ephemeral context. */
    @Override
    public String thinkWithHistoryStream(List<ChatMessage> messages, String systemPrompt,
                                         String ephemeralContext,
                                         java.util.function.Consumer<String> onDelta) {
        String lastUserMsg = messages.stream()
                .filter(m -> "user".equals(m.role()))
                .map(ChatMessage::contentAsString)
                .reduce((first, second) -> second)
                .orElse("");

        RoutingDecision decision = route(lastUserMsg);
        LlmProvider provider = resolveProvider(decision.backendName());

        if (provider == null) {
            log.warn("[LlmRouter] thinkWithHistoryStream 无可用 Provider，降级到 NoopLlmProvider");
            provider = firstAvailable(ComputeCore.P_CORE);
            if (provider == null) {
                String fallback = "Error: No LLM provider available";
                onDelta.accept(fallback);
                return fallback;
            }
        }

        log.debug("[LlmRouter] thinkWithHistoryStream 路由到: {} (core={})", provider.name(), provider.computeCore());

        try {
            return provider.thinkWithHistoryStream(messages, systemPrompt, ephemeralContext, onDelta);
        } catch (Exception e) {
            log.error("[LlmRouter] thinkWithHistoryStream 异常: {}", e.getMessage());
            // 降级到同步模式
            String result = provider.thinkWithHistory(messages, systemPrompt, ephemeralContext);
            onDelta.accept(result);
            return result;
        }
    }

    @Override
    public float[] embed(String text) {
        // Embedding 优先使用 E_CORE（低成本）
        LlmProvider eCoreProvider = firstAvailable(ComputeCore.E_CORE);
        if (eCoreProvider != null) return eCoreProvider.embed(text);

        var first = backendProviders.entrySet().stream().findFirst();
        if (first.isPresent()) return first.get().getValue().embed(text);
        return mockEmbed(text);
    }

    @Override
    public boolean isAvailable() {
        return !backendProviders.isEmpty();
    }

    /**
     * 检查是否有可用的 LLM 后端。
     */
    public boolean hasAvailableProvider() {
        return backendProviders.values().stream().anyMatch(LlmProvider::isAvailable);
    }

    /**
     * 获取指定名称的 LlmProvider。
     *
     * @param name 提供者名称
     * @return LlmProvider，不存在则返回 null
     */
    public LlmProvider getProvider(String name) {
        return backendProviders.get(name);
    }

    // ════════════════════════════════════════════════════════════════
    //  核心路由逻辑 — big.LITTLE 调度器
    // ════════════════════════════════════════════════════════════════

    /**
     * 路由决策 — AIOS 的 HMP (Heterogeneous Multi-Processing) 调度器。
     * <p>
     * 综合考虑三个维度：
     * <ol>
     *   <li><b>ComputeAffinity</b> — 任务的算力亲和性（硬性约束）</li>
     *   <li><b>NumaAffinity</b> — NUMA 亲和性（跨节点约束）</li>
     *   <li><b>Prompt Complexity</b> — 上下文复杂度（动态因素）</li>
     * </ol>
     */
    private RoutingDecision route(String prompt) {
        AgentTask currentTask = TaskScheduler.CURRENT_TASK.get();
        ComputeAffinity affinity = (currentTask != null)
                ? currentTask.computeAffinity() : ComputeAffinity.AUTO;
        NumaAffinity numaAffinity = (currentTask != null)
                ? currentTask.affinity() : NumaAffinity.PREFER_LOCAL;
        int budget = (currentTask != null) ? currentTask.budget() : Integer.MAX_VALUE;

        // 1. 根据 ComputeAffinity 确定目标核心
        ComputeCore targetCore = resolveTargetCore(affinity, prompt);

        // 2. 根据 NumaAffinity 确定是否允许跨节点
        boolean wantsRemote = shouldRouteRemote(prompt);
        String backendName = resolveBackendName(targetCore, numaAffinity, wantsRemote, budget);

        // 3. 记录调度统计
        if (targetCore == ComputeCore.P_CORE) {
            pCoreDispatches.incrementAndGet();
        } else {
            eCoreDispatches.incrementAndGet();
        }

        String reason = buildRouteReason(affinity, targetCore, prompt);
        return new RoutingDecision(targetCore, backendName, reason);
    }

    /**
     * 根据算力亲和性和 Prompt 复杂度，确定目标核心。
     */
    private ComputeCore resolveTargetCore(ComputeAffinity affinity, String prompt) {
        return switch (affinity) {
            case REQUIRE_P_CORE -> {
                log.debug("[big.LITTLE] 任务固定至 P_CORE (REQUIRE_P_CORE)");
                yield ComputeCore.P_CORE;
            }

            case PREFER_P_CORE -> {
                // 优先 P_CORE，但简单任务可降级
                if (isSimplePrompt(prompt)) {
                    downgrades.incrementAndGet();
                    log.info("[big.LITTLE] 降级: PREFER_P_CORE → E_CORE (简单任务, len={})",
                            prompt.length());
                    yield ComputeCore.E_CORE;
                }
                yield ComputeCore.P_CORE;
            }

            case AUTO -> {
                // 根据复杂度自动决定
                if (isSimplePrompt(prompt)) {
                    downgrades.incrementAndGet();
                    log.info("[big.LITTLE] AUTO → E_CORE (simple prompt, len={})", prompt.length());
                    yield ComputeCore.E_CORE;
                }
                if (isComplexPrompt(prompt)) {
                    log.info("[big.LITTLE] AUTO → P_CORE (complex prompt, len={})", prompt.length());
                    yield ComputeCore.P_CORE;
                }
                // 中等复杂度：默认 E_CORE（省成本）
                yield ComputeCore.E_CORE;
            }

            case PREFER_E_CORE -> {
                // 优先 E_CORE，但复杂任务可拉升
                if (isComplexPrompt(prompt)) {
                    turboBoosts.incrementAndGet();
                    log.info("[big.LITTLE] Turbo Boost: PREFER_E_CORE → P_CORE (复杂任务, len={})",
                            prompt.length());
                    yield ComputeCore.P_CORE;
                }
                yield ComputeCore.E_CORE;
            }

            case REQUIRE_E_CORE -> {
                log.debug("[big.LITTLE] 任务固定至 E_CORE (REQUIRE_E_CORE)");
                yield ComputeCore.E_CORE;
            }
        };
    }

    /**
     * 根据目标核心和 NUMA 亲和性，确定后端名称。
     */
    private String resolveBackendName(ComputeCore targetCore, NumaAffinity numaAffinity,
                                       boolean wantsRemote, int budget) {
        // 检查目标核心是否有可用的 Provider
        List<LlmProvider> providers = coreProviders.getOrDefault(targetCore, List.of());

        if (!providers.isEmpty()) {
            // 优先选择第一个匹配核心的 Provider
            LlmProvider selected = providers.get(0);
            String name = findProviderName(selected);
            return name != null ? name : "fast_model";
        }

        // 目标核心无可用 Provider，回退到 NUMA 路由
        return numaFallbackRoute(numaAffinity, wantsRemote, budget, targetCore);
    }

    /**
     * NUMA 回退路由 — 当目标核心无 Provider 时的降级策略。
     */
    private String numaFallbackRoute(NumaAffinity numaAffinity, boolean wantsRemote,
                                      int budget, ComputeCore targetCore) {
        return switch (numaAffinity) {
            case LOCAL_ONLY -> "fast_model";
            case PREFER_LOCAL -> wantsRemote ? "smart_model" : "fast_model";
            case ANY -> {
                if (wantsRemote && budget > REMOTE_BUDGET_THRESHOLD) {
                    yield "smart_model";
                }
                if (wantsRemote) {
                    throw new NumaOomException(budget, REMOTE_BUDGET_THRESHOLD);
                }
                yield "fast_model";
            }
        };
    }

    // ════════════════════════════════════════════════════════════════
    //  Turbo Boost — E_CORE 失败或质量不足时自动拉升到 P_CORE
    // ════════════════════════════════════════════════════════════════

    /**
     * Turbo Boost — 将请求从 E_CORE 拉升到 P_CORE。
     * <p>
     * 类比 CPU 的 Turbo Boost：当小核无法处理时，自动切换到大核。
     * 触发条件：
     * <ol>
     *   <li>E_CORE Provider 抛出异常</li>
     *   <li>E_CORE 返回结果质量不足（如包含"无法回答"、"I cannot"等）</li>
     * </ol>
     */
    private String turboBoost(String prompt, String systemPrompt,
                               RoutingDecision originalDecision, String eCoreResult) {
        turboBoosts.incrementAndGet();

        log.warn("[big.LITTLE] ╔══════════════════════════════════════════════════╗");
        log.warn("[big.LITTLE] ║  TURBO BOOST: E_CORE → P_CORE                   ║");
        log.warn("[big.LITTLE] ║  Reason: {}   ║",
                eCoreResult != null ? "质量不足" : "E_CORE 失败");
        log.warn("[big.LITTLE] ╚══════════════════════════════════════════════════╝");

        SemanticEtw.getInstance().logEvent("LLM", "TURBO_BOOST",
                "from=" + originalDecision.backendName + " reason="
                + (eCoreResult != null ? "quality_insufficient" : "e_core_failure"));

        // 查找 P_CORE Provider
        LlmProvider pCoreProvider = firstAvailable(ComputeCore.P_CORE);
        if (pCoreProvider != null) {
            String pCoreResult = pCoreProvider.think(prompt, systemPrompt);
            // ── 安全断言：P_CORE 也不允许返回空 ──
            if (pCoreResult == null || pCoreResult.isBlank()) {
                throw new RuntimeException("Turbo Boost returned empty payload: P_CORE provider '"
                        + pCoreProvider.name() + "' returned null/blank response after Turbo Boost");
            }
            return pCoreResult;
        }

        // 无 P_CORE 可用，检查 E_CORE 结果
        if (eCoreResult != null && !eCoreResult.isBlank()) return eCoreResult;

        throw new RuntimeException("Turbo Boost failed: no P_CORE provider available and E_CORE result is empty");
    }

    /**
     * 检测 E_CORE 返回结果是否需要 Turbo Boost。
     * <p>
     * 启发式规则：如果 E_CORE 的回复包含"无法"、"I cannot"、"I'm unable"等
     * 拒绝性关键词，说明任务超出了小核的能力范围，需要拉升到大核。
     */
    private boolean needsTurboBoost(String result) {
        if (result == null || result.isEmpty()) return true;

        String lower = result.toLowerCase();
        List<String> boostTriggers = List.of(
                "i cannot", "i can't", "i'm unable", "i am unable",
                "无法完成", "无法回答", "超出了", "需要更高级",
                "this requires", "this is beyond"
        );

        for (String trigger : boostTriggers) {
            if (lower.contains(trigger)) return true;
        }

        return false;
    }

    // ════════════════════════════════════════════════════════════════
    //  Prompt 复杂度分析
    // ════════════════════════════════════════════════════════════════

    private boolean isComplexPrompt(String prompt) {
        if (prompt == null || prompt.isEmpty()) return false;
        if (prompt.length() > COMPLEX_PROMPT_LENGTH) return true;
        for (String keyword : COMPLEX_KEYWORDS) {
            if (prompt.contains(keyword)) return true;
        }
        return false;
    }

    private boolean isSimplePrompt(String prompt) {
        if (prompt == null || prompt.isEmpty()) return true;
        if (prompt.length() < 50) return true;
        for (String keyword : SIMPLE_KEYWORDS) {
            if (prompt.contains(keyword)) return true;
        }
        return false;
    }

    private boolean shouldRouteRemote(String prompt) {
        return isComplexPrompt(prompt);
    }

    // ════════════════════════════════════════════════════════════════
    //  辅助方法
    // ════════════════════════════════════════════════════════════════

    private boolean isLocalNode(String backendName) {
        return "fast_model".equals(backendName);
    }

    private LlmProvider resolveProvider(String backend) {
        LlmProvider provider = backendProviders.get(backend);
        if (provider != null) return provider;

        // Fallback to any available provider
        var first = backendProviders.entrySet().stream().findFirst();
        if (first.isPresent()) {
            String fallback = first.get().getKey();
            log.warn("[LLM Router] 后端 '{}' 未找到，回退至 '{}'", backend, fallback);
            return first.get().getValue();
        }
        // 降级到 NoopLlmProvider（借鉴 Langflow Noop 服务设计）
        log.warn("[LLM Router] 未找到可用的 Provider: {}。降级到 NoopLlmProvider。", backend);
        return noopProvider;
    }

    private LlmProvider firstAvailable(ComputeCore core) {
        List<LlmProvider> providers = coreProviders.getOrDefault(core, List.of());
        return providers.isEmpty() ? null : providers.get(0);
    }

    private String findProviderName(LlmProvider provider) {
        for (Map.Entry<String, LlmProvider> entry : backendProviders.entrySet()) {
            if (entry.getValue() == provider) return entry.getKey();
        }
        return null;
    }

    private String buildRouteReason(ComputeAffinity affinity, ComputeCore core, String prompt) {
        if (affinity == ComputeAffinity.AUTO) {
            return isSimplePrompt(prompt) ? "AUTO:simple→E" : isComplexPrompt(prompt) ? "AUTO:complex→P" : "AUTO:medium→E";
        }
        return affinity.name() + "→" + core.name();
    }

    // ════════════════════════════════════════════════════════════════
    //  调度统计与监控
    // ════════════════════════════════════════════════════════════════

    /** 路由决策记录 */
    public record RoutingDecision(
            ComputeCore targetCore,
            String backendName,
            String reason
    ) {}

    public long pCoreDispatches() { return pCoreDispatches.get(); }
    public long eCoreDispatches() { return eCoreDispatches.get(); }
    public long turboBoosts() { return turboBoosts.get(); }
    public long downgrades() { return downgrades.get(); }

    /**
     * 打印调度统计报告。
     */
    public String getStatsReport() {
        long total = pCoreDispatches.get() + eCoreDispatches.get();
        double pCorePct = total > 0 ? (pCoreDispatches.get() * 100.0 / total) : 0;
        double eCorePct = total > 0 ? (eCoreDispatches.get() * 100.0 / total) : 0;

        return """
                ┌─ big.LITTLE Scheduling Stats ──────────────────────
                │  P_CORE dispatches : %d (%.1f%%)
                │  E_CORE dispatches : %d (%.1f%%)
                │  Turbo Boosts     : %d (E→P escalations)
                │  Downgrades       : %d (P→E savings)
                │  Total dispatches : %d
                │  Registered cores : P=%d, E=%d
                └─────────────────────────────────────────────────"""
                .formatted(
                        pCoreDispatches.get(), pCorePct,
                        eCoreDispatches.get(), eCorePct,
                        turboBoosts.get(), downgrades.get(),
                        total,
                        coreProviders.getOrDefault(ComputeCore.P_CORE, List.of()).size(),
                        coreProviders.getOrDefault(ComputeCore.E_CORE, List.of()).size());
    }
}
