package com.ouisani.aios.core.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 工具上下文 — 每进程工具链（活跃模块集合）。
 * <p>
 * 在传统 OS 中，每个进程拥有自己的文件描述符表和已加载共享库。
 * 在 AIOS 中，每个 Agent 进程拥有自己的 {@link AgentToolContext}——
 * 一个隔离的已加载工具列表，其 JSON Schema 被注入到 LLM 的上下文窗口中。
 * <p>
 * <h3>OS 类比: 进程地址空间</h3>
 * 就像每个进程拥有不同的虚拟地址空间和不同的内存映射库，
 * 每个 Agent 拥有不同的工具上下文和不同的已加载模块。这种隔离确保：
 * <ul>
 *   <li>Token 效率 — 只加载与当前任务相关的工具</li>
 *   <li>安全性 — Agent 无法访问未加载的工具</li>
 *   <li>灵活性 — Agent 可在运行时 insmod/rmmod</li>
 * </ul>
 * <p>
 * <h3>Token 预算</h3>
 * 每个已加载的工具消耗 Token（其 JSON Schema）。上下文跟踪总 Token 开销
 * 并可强制执行预算——类似于内核跟踪每个进程的 RSS（常驻内存集大小）。
 *
 * @see ToolDefinition
 * @see PluginManager
 */
public final class AgentToolContext {

    private static final Logger log = LoggerFactory.getLogger(AgentToolContext.class);

    /** Maximum total token cost for all loaded tools (default: 2000). */
    private static final int DEFAULT_TOKEN_BUDGET = 2000;

    private final String agentId;
    private final ConcurrentHashMap<String, ToolDefinition> activeTools = new ConcurrentHashMap<>();
    private final int tokenBudget;
    private volatile int tokenCostUsed = 0;

    public AgentToolContext(String agentId) {
        this(agentId, DEFAULT_TOKEN_BUDGET);
    }

    public AgentToolContext(String agentId, int tokenBudget) {
        this.agentId = agentId;
        this.tokenBudget = tokenBudget;
    }

    // ════════════════════════════════════════════════════════════════
    //  insmod: Load a tool into the active context
    // ════════════════════════════════════════════════════════════════

    /**
     * Load a tool into this agent's active context (insmod).
     *
     * @param tool the tool definition to load
     * @return true if loaded successfully, false if budget exceeded or already loaded
     */
    public boolean insmod(ToolDefinition tool) {
        if (tool == null) return false;

        // Check if already loaded
        if (activeTools.containsKey(tool.name())) {
            log.debug("[ToolCtx] Agent {}: 工具已加载 '{}'", agentId, tool.name());
            return true; // idempotent
        }

        // Check token budget
        if (tokenCostUsed + tool.tokenCost() > tokenBudget) {
            log.warn("[ToolCtx] Agent {}: token budget exceeded (used={}, cost={}, budget={}) for tool '{}'",
                    agentId, tokenCostUsed, tool.tokenCost(), tokenBudget, tool.name());
            return false;
        }

        activeTools.put(tool.name(), tool);
        tokenCostUsed += tool.tokenCost();

        log.info("[ToolCtx] Agent {}: insmod '{}' [type={}, cost={}, total={}/{}]",
                agentId, tool.name(), tool.type(), tool.tokenCost(), tokenCostUsed, tokenBudget);
        System.out.printf("  \u001B[36m[insmod] Agent %s ← 工具 '%s' 已加载 (cost=%d, total=%d/%d)\u001B[0m%n",
                agentId, tool.name(), tool.tokenCost(), tokenCostUsed, tokenBudget);

        return true;
    }

    // ════════════════════════════════════════════════════════════════
    //  rmmod: Unload a tool from the active context
    // ════════════════════════════════════════════════════════════════

    /**
     * Unload a tool from this agent's active context (rmmod).
     *
     * @param toolName the tool to unload
     * @return true if unloaded, false if not found
     */
    public boolean rmmod(String toolName) {
        ToolDefinition removed = activeTools.remove(toolName);
        if (removed != null) {
            tokenCostUsed -= removed.tokenCost();
            log.info("[ToolCtx] Agent {}: rmmod '{}' [type={}, cost={}, total={}/{}]",
                    agentId, toolName, removed.type(), removed.tokenCost(), tokenCostUsed, tokenBudget);
            System.out.printf("  \u001B[33m[rmmod] Agent %s ← tool '%s' unloaded (freed=%d, total=%d/%d)\u001B[0m%n",
                    agentId, toolName, removed.tokenCost(), tokenCostUsed, tokenBudget);
            return true;
        }
        log.debug("[ToolCtx] Agent {}: rmmod '{}' — 工具在活跃上下文中未找到", agentId, toolName);
        return false;
    }

    // ════════════════════════════════════════════════════════════════
    //  Query
    // ════════════════════════════════════════════════════════════════

    /**
     * Check if a tool is loaded in this agent's context.
     */
    public boolean hasTool(String toolName) {
        return activeTools.containsKey(toolName);
    }

    /**
     * Get a loaded tool definition.
     */
    public ToolDefinition getTool(String toolName) {
        return activeTools.get(toolName);
    }

    /**
     * Get all loaded tool names.
     */
    public Set<String> loadedToolNames() {
        return Collections.unmodifiableSet(activeTools.keySet());
    }

    /**
     * Get all loaded tool definitions.
     */
    public Collection<ToolDefinition> loadedTools() {
        return Collections.unmodifiableCollection(activeTools.values());
    }

    /**
     * Get the number of loaded tools.
     */
    public int toolCount() {
        return activeTools.size();
    }

    /**
     * Get total token cost used.
     */
    public int tokenCostUsed() {
        return tokenCostUsed;
    }

    /**
     * Get the token budget.
     */
    public int tokenBudget() {
        return tokenBudget;
    }

    /**
     * Unload all tools (rmmod -a).
     */
    public void unloadAll() {
        int count = activeTools.size();
        activeTools.clear();
        tokenCostUsed = 0;
        log.info("[ToolCtx] Agent {}: rmmod -a (unloaded {} tools)", agentId, count);
    }

    // ════════════════════════════════════════════════════════════════
    //  Context Generation: Build the Function Calling prompt fragment
    // ════════════════════════════════════════════════════════════════

    /**
     * Generate the JSON array of function definitions for the LLM prompt.
     * <p>
     * This is the key output: when an Agent makes an LLM call, the
     * system injects this fragment into the prompt so the LLM knows
     * which tools are available. Only loaded tools are included —
     * this is the core token-saving mechanism.
     * <p>
     * <pre>
     * // Example output:
     * [
     *   {"name":"web_search","description":"Search the web","parameters":{...}},
     *   {"name":"image_gen","description":"Generate an image","parameters":{...}}
     * ]
     * </pre>
     */
    public String toFunctionCallingSchema() {
        if (activeTools.isEmpty()) return "[]";

        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (ToolDefinition tool : activeTools.values()) {
            if (!first) sb.append(",");
            sb.append(tool.toFunctionSchema());
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Generate a human-readable summary of loaded tools (for /proc-style diagnostics).
     */
    public String toProcStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("Agent: ").append(agentId).append("\n");
        sb.append("Loaded Tools: ").append(activeTools.size()).append("\n");
        sb.append("Token Budget: ").append(tokenCostUsed).append("/").append(tokenBudget).append("\n");
        sb.append("─".repeat(60)).append("\n");
        for (ToolDefinition tool : activeTools.values()) {
            sb.append(String.format("  %-30s %-8s cost=%-4d %s%n",
                    tool.name(), tool.type(), tool.tokenCost(),
                    tool.description().length() > 40
                            ? tool.description().substring(0, 40) + "..."
                            : tool.description()));
        }
        return sb.toString();
    }
}
