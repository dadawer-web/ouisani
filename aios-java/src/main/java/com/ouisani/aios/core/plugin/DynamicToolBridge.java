package com.ouisani.aios.core.plugin;

import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.llm.LlmRouterHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 动态工具桥接器 (The Dynamic Linker) — 上下文感知的自动工具挂载。
 * <p>
 * OS 类比: Linux 的动态链接器 (ld.so) — 程序不需要在编译时链接所有共享库，
 * 而是在运行时根据需要动态加载 .so 文件。DynamicToolBridge 做的是同样的事：
 * 不把 1000 个工具的 Prompt 一次性塞给 Agent，而是根据当前对话语义，
 * 自动将相关工具"挂载（Mount）"进上下文。
 * <p>
 * 核心机制：
 * <ol>
 *   <li><b>意图嗅探</b> — 分析最近的对话内容，提取工具需求信号</li>
 *   <li><b>语义匹配</b> — 通过向量搜索在全局工具目录中找到最相关的工具</li>
 *   <li><b>自动 insmod</b> — 将匹配的工具自动挂载到 Agent 的 ToolContext</li>
 *   <li><b>LRU 驱逐</b> — 自动卸载长时间未使用的冷工具，释放 Token 预算</li>
 *   <li><b>Schema 注入</b> — 生成已挂载工具的 Function Calling Schema，注入 LLM prompt</li>
 * </ol>
 * <p>
 * 对标 VCPToolBox 的动态工具发现 + Claude Code 的按需工具加载。
 *
 * @see PluginManager
 * @see AgentToolContext
 */
public class DynamicToolBridge {
    private static final Logger log = LoggerFactory.getLogger(DynamicToolBridge.class);

    /** 单例 */
    private static final DynamicToolBridge INSTANCE = new DynamicToolBridge();

    /** 自动挂载的最大工具数（单次意图嗅探） */
    private static final int MAX_AUTO_MOUNT = 3;

    /** 语义匹配阈值 — 余弦相似度低于此值不自动挂载 */
    private static final double SIMILARITY_THRESHOLD = 0.5;

    /** LRU 驱逐：工具最大空闲时间（毫秒），超过则自动 rmmod */
    private static final long TOOL_IDLE_TIMEOUT_MS = 10 * 60 * 1000; // 10 分钟

    /** LRU 驱逐：检查间隔 */
    private static final long EVICTION_CHECK_INTERVAL_MS = 60 * 1000; // 1 分钟

    /** 工具使用追踪：toolName → 最后使用时间 */
    private final ConcurrentHashMap<String, Long> toolLastUsed = new ConcurrentHashMap<>();

    /** 工具使用追踪：toolName → 使用次数 */
    private final ConcurrentHashMap<String, AtomicInteger> toolUseCount = new ConcurrentHashMap<>();

    /** 上次驱逐检查时间 */
    private volatile long lastEvictionCheck = System.currentTimeMillis();

    private DynamicToolBridge() {}

    public static DynamicToolBridge getInstance() {
        return INSTANCE;
    }

    // ════════════════════════════════════════════════════════════════
    //  核心方法：上下文感知的自动挂载
    // ════════════════════════════════════════════════════════════════

    /**
     * 根据对话上下文自动挂载相关工具。
     * <p>
     * 这是 DynamicToolBridge 的核心入口，应在每次 LLM 调用前调用。
     * <p>
     * 流程：
     * 1. 从 recentMessages 中提取意图信号
     * 2. 对意图信号做语义搜索，匹配全局工具目录
     * 3. 将匹配的工具自动 insmod 到 Agent 的 ToolContext
     * 4. 执行 LRU 驱逐，卸载超时的冷工具
     *
     * @param agentId        Agent 标识
     * @param recentMessages 最近的对话消息（用于意图嗅探）
     * @return 本次自动挂载的工具列表（可能为空）
     */
    public List<ToolDefinition> autoMountByContext(String agentId, List<String> recentMessages) {
        if (recentMessages == null || recentMessages.isEmpty()) return List.of();

        PluginManager pm = PluginManager.getInstance();

        // 1. 提取意图信号
        String intentSignal = extractIntentSignal(recentMessages);
        if (intentSignal == null || intentSignal.isBlank()) {
            log.debug("[DynBridge] 未检测到 Agent '{}' 的明确意图信号", agentId);
            return List.of();
        }

        log.debug("[DynBridge] Agent '{}' 的意图信号: '{}'", agentId,
                intentSignal.length() > 80 ? intentSignal.substring(0, 80) + "..." : intentSignal);

        // 2. 语义搜索匹配工具
        List<ToolDefinition> candidates = pm.semanticSearch(intentSignal, MAX_AUTO_MOUNT);
        if (candidates.isEmpty()) {
            log.debug("[DynBridge] 未找到匹配意图 '{}' 的工具", intentSignal);
            return List.of();
        }

        // 3. 自动 insmod（跳过已加载的、低于阈值的）
        AgentToolContext ctx = pm.getAgentContext(agentId);
        List<ToolDefinition> mounted = new ArrayList<>();

        for (ToolDefinition candidate : candidates) {
            // 跳过已加载的工具
            if (ctx.hasTool(candidate.name())) {
                log.debug("[DynBridge] 工具 '{}' 已加载，跳过", candidate.name());
                continue;
            }

            // 检查语义匹配度（如果有嵌入向量）
            if (!passesSimilarityThreshold(intentSignal, candidate)) {
                log.debug("[DynBridge] 工具 '{}' 低于相似度阈值，跳过", candidate.name());
                continue;
            }

            // 执行 insmod
            if (ctx.insmod(candidate)) {
                mounted.add(candidate);
                toolLastUsed.put(candidate.name(), System.currentTimeMillis());
                toolUseCount.computeIfAbsent(candidate.name(), k -> new AtomicInteger()).incrementAndGet();
                log.info("[DynBridge] 工具已自动挂载 '{}'，Agent '{}' (意图: '{}')",
                        candidate.name(), agentId,
                        intentSignal.length() > 40 ? intentSignal.substring(0, 40) + "..." : intentSignal);
            } else {
                log.warn("[DynBridge] 自动挂载 '{}' 失败 (预算超限?)", candidate.name());
            }
        }

        // 4. LRU 驱逐检查
        maybeEvictColdTools(agentId, ctx);

        return mounted;
    }

    /**
     * 根据自然语言查询自动挂载工具（显式触发版）。
     * <p>
     * 当 Agent 主动表达工具需求时调用（如 "我需要搜索网页"）。
     *
     * @param agentId Agent 标识
     * @param query   工具需求描述
     * @return 挂载的工具列表
     */
    public List<ToolDefinition> autoMountByQuery(String agentId, String query) {
        if (query == null || query.isBlank()) return List.of();

        PluginManager pm = PluginManager.getInstance();
        AgentToolContext ctx = pm.getAgentContext(agentId);
        List<ToolDefinition> candidates = pm.semanticSearch(query, MAX_AUTO_MOUNT);
        List<ToolDefinition> mounted = new ArrayList<>();

        for (ToolDefinition candidate : candidates) {
            if (ctx.hasTool(candidate.name())) continue;
            if (ctx.insmod(candidate)) {
                mounted.add(candidate);
                toolLastUsed.put(candidate.name(), System.currentTimeMillis());
                toolUseCount.computeIfAbsent(candidate.name(), k -> new AtomicInteger()).incrementAndGet();
            }
        }

        return mounted;
    }

    // ════════════════════════════════════════════════════════════════
    //  Schema 注入：生成已挂载工具的 Function Calling Prompt
    // ════════════════════════════════════════════════════════════════

    /**
     * 生成已挂载工具的 Function Calling Schema，用于注入 LLM prompt。
     * <p>
     * 这是闭环的关键：只有通过 DynamicToolBridge 挂载的工具，
     * 其 Schema 才会出现在 LLM 的 system prompt 中。
     * 未挂载的工具对 LLM 不可见，节省 Token。
     *
     * @param agentId Agent 标识
     * @return Function Calling JSON 数组字符串
     */
    public String getMountedToolsSchema(String agentId) {
        PluginManager pm = PluginManager.getInstance();
        AgentToolContext ctx = pm.getAgentContext(agentId);
        return ctx.toFunctionCallingSchema();
    }

    /**
     * 生成已挂载工具的 Markdown 描述，用于注入 system prompt。
     *
     * @param agentId Agent 标识
     * @return Markdown 格式的工具列表
     */
    public String getMountedToolsDescription(String agentId) {
        PluginManager pm = PluginManager.getInstance();
        AgentToolContext ctx = pm.getAgentContext(agentId);

        if (ctx.toolCount() == 0) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## Dynamically Loaded Tools (via DynamicToolBridge)\n");
        sb.append("The following tools have been auto-mounted based on your current task context.\n");
        sb.append("Use them when needed. Unused tools will be automatically unloaded.\n\n");

        for (ToolDefinition tool : ctx.loadedTools()) {
            sb.append("### ").append(tool.name()).append("\n");
            sb.append(tool.description()).append("\n");
            sb.append("Parameters: ").append(tool.parameters()).append("\n\n");
        }

        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════════
    //  工具使用追踪
    // ════════════════════════════════════════════════════════════════

    /**
     * 标记工具被使用（在工具执行后调用）。
     */
    public void markToolUsed(String toolName) {
        toolLastUsed.put(toolName, System.currentTimeMillis());
        toolUseCount.computeIfAbsent(toolName, k -> new AtomicInteger()).incrementAndGet();
    }

    /**
     * 获取工具使用统计。
     */
    public Map<String, Integer> getToolUsageStats() {
        Map<String, Integer> stats = new LinkedHashMap<>();
        toolUseCount.entrySet().stream()
                .sorted(Map.Entry.<String, AtomicInteger>comparingByValue(
                        (a, b) -> Integer.compare(b.get(), a.get())))
                .forEach(e -> stats.put(e.getKey(), e.getValue().get()));
        return stats;
    }

    // ════════════════════════════════════════════════════════════════
    //  内部方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 从最近的对话消息中提取意图信号。
     * <p>
     * 策略：取最后几条消息，拼接为意图描述文本。
     * 如果消息太长，截取最后 500 字符（最近的意图最相关）。
     */
    private String extractIntentSignal(List<String> recentMessages) {
        if (recentMessages.isEmpty()) return null;

        // 取最后 3 条消息（最近的意图最相关）
        int start = Math.max(0, recentMessages.size() - 3);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < recentMessages.size(); i++) {
            String msg = recentMessages.get(i);
            if (msg != null && !msg.isBlank()) {
                sb.append(msg).append(" ");
            }
        }

        String signal = sb.toString().trim();
        // 截取最后 500 字符
        if (signal.length() > 500) {
            signal = signal.substring(signal.length() - 500);
        }
        return signal.isBlank() ? null : signal;
    }

    /**
     * 检查工具与意图的语义相似度是否超过阈值。
     * <p>
     * 如果无法计算（无嵌入向量），默认通过（保守策略：宁可多挂载也不漏挂）。
     */
    private boolean passesSimilarityThreshold(String intentSignal, ToolDefinition candidate) {
        LlmProvider provider = LlmRouterHolder.getProvider("fast_model");
        if (provider == null) provider = LlmRouterHolder.getProvider("openai");
        if (provider == null) return true; // 无 LLM 时保守放行

        try {
            float[] intentVec = provider.embed(intentSignal);
            PluginManager pm = PluginManager.getInstance();
            // 通过 PluginManager 获取工具嵌入（需要反射或新增方法）
            // 简化实现：直接重新嵌入工具描述
            String toolText = candidate.name() + " " + candidate.description();
            float[] toolVec = provider.embed(toolText);

            if (intentVec == null || toolVec == null) return true;

            double similarity = com.ouisani.aios.core.llm.VectorMath.cosineSimilarity(intentVec, toolVec);
            return similarity >= SIMILARITY_THRESHOLD;
        } catch (Exception e) {
            log.debug("[DynBridge] 相似度检查失败，默认放行: {}", e.getMessage());
            return true; // 出错时保守放行
        }
    }

    /**
     * LRU 驱逐：检查并卸载长时间未使用的冷工具。
     * <p>
     * 类比 Linux 的 kswapd 后台内存回收——定期扫描，
     * 将长时间未访问的页面换出。这里将长时间未使用的工具 rmmod，
     * 释放 Token 预算给更需要的工具。
     */
    private void maybeEvictColdTools(String agentId, AgentToolContext ctx) {
        long now = System.currentTimeMillis();

        // 检查间隔控制
        if (now - lastEvictionCheck < EVICTION_CHECK_INTERVAL_MS) return;
        lastEvictionCheck = now;

        List<String> toEvict = new ArrayList<>();
        for (ToolDefinition tool : ctx.loadedTools()) {
            Long lastUsed = toolLastUsed.get(tool.name());
            if (lastUsed == null) {
                // 从未记录使用，可能是自动挂载后从未调用
                lastUsed = 0L;
            }
            if (now - lastUsed > TOOL_IDLE_TIMEOUT_MS) {
                toEvict.add(tool.name());
            }
        }

        for (String toolName : toEvict) {
            ctx.rmmod(toolName);
            toolLastUsed.remove(toolName);
            log.info("[DynBridge] LRU 已驱逐冷工具 '{}'，Agent '{}' (空闲 > {}ms)",
                        toolName, agentId, TOOL_IDLE_TIMEOUT_MS);
        }

        if (!toEvict.isEmpty()) {
            log.info("[DynBridge] LRU 驱逐: 已为 Agent '{}' 驱逐 {} 个冷工具", agentId, toEvict.size());
        }
    }
}
