package com.ouisani.aios.core.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent Tool Context — the per-process tool chain (active modules).
 * <p>
 * In a traditional OS, each process has its own set of open file
 * descriptors and loaded shared libraries. In AIOS, each Agent
 * process has its own {@link AgentToolContext} — an isolated list
 * of actively loaded tools whose JSON Schemas are injected into
 * the LLM's context window.
 * <p>
 * <h3>OS Analogy: Process Address Space</h3>
 * Just as each process has its own virtual address space with
 * different memory-mapped libraries, each Agent has its own tool
 * context with different loaded modules. This isolation ensures:
 * <ul>
 *   <li>Token efficiency — only load tools relevant to the current task</li>
 *   <li>Security — agents can't access tools they haven't loaded</li>
 *   <li>Flexibility — agents can insmod/rmmod at runtime</li>
 * </ul>
 * <p>
 * <h3>Token Budget</h3>
 * Each loaded tool consumes tokens (its JSON Schema). The context
 * tracks the total token cost and can enforce a budget — analogous
 * to how the kernel tracks RSS (Resident Set Size) per process.
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
            log.debug("[ToolCtx] Agent {}: tool '{}' already loaded", agentId, tool.name());
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
        System.out.printf("  \u001B[36m[insmod] Agent %s ← tool '%s' loaded (cost=%d, total=%d/%d)\u001B[0m%n",
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
        log.debug("[ToolCtx] Agent {}: rmmod '{}' — tool not found in active context", agentId, toolName);
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
