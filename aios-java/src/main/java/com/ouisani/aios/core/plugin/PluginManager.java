package com.ouisani.aios.core.plugin;

import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.llm.VectorMath;
import com.ouisani.aios.core.mcp.McpClientRegistry;
import com.ouisani.aios.core.sandbox.GraalWasmSandbox;
import com.ouisani.aios.core.syscall.SyscallDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic Tool Registration Center — the AIOS kernel's module loader.
 * <p>
 * Manages the lifecycle of loadable tool modules (kernel modules),
 * supporting three sources:
 * <ul>
 *   <li><b>WASM plugins</b> — local bytecode loaded via {@code insmod}</li>
 *   <li><b>MCP server tools</b> — remote tools discovered via JSON-RPC</li>
 *   <li><b>Native tools</b> — built-in kernel syscalls (always available)</li>
 * </ul>
 * <p>
 * <h3>OS Analogy: insmod / rmmod / modprobe</h3>
 * <ul>
 *   <li>{@code sys_insmod(query)} → {@code modprobe}: semantic search for a tool
 *       and load it into the Agent's context (like loading a kernel module)</li>
 *   <li>{@code sys_rmmod(toolName)} → {@code rmmod}: unload a tool from the
 *       Agent's context to free token budget (like unloading a module)</li>
 *   <li>{@code semanticSearch(query)} → {@code modprobe -l}: fuzzy semantic
 *       matching across all available tools (like listing available modules)</li>
 * </ul>
 * <p>
 * <h3>Token Economy</h3>
 * Each loaded tool consumes tokens in the LLM's context window.
 * The {@code insmod/rmmod} mechanism enables Agents to load tools
 * on demand and unload them when done — "按需加载、用完即弃".
 *
 * @see ToolDefinition
 * @see AgentToolContext
 * @see McpClientRegistry
 */
public final class PluginManager {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    // ── Singleton ──

    private static final class Holder {
        static final PluginManager INSTANCE = new PluginManager();
    }

    public static PluginManager getInstance() {
        return Holder.INSTANCE;
    }

    // ── State ──

    /** WASM bytecode registry: toolName → bytecode. */
    private final ConcurrentHashMap<String, byte[]> pluginRegistry = new ConcurrentHashMap<>();

    /** Global tool catalog: all discoverable tools (WASM + MCP + Native). */
    private final ConcurrentHashMap<String, ToolDefinition> toolCatalog = new ConcurrentHashMap<>();

    /** Tool embedding index: toolName → embedding vector (for semantic search). */
    private final ConcurrentHashMap<String, float[]> toolEmbeddings = new ConcurrentHashMap<>();

    /** Per-agent tool contexts: agentId → active tool chain. */
    private final ConcurrentHashMap<String, AgentToolContext> agentContexts = new ConcurrentHashMap<>();

    private GraalWasmSandbox sandbox;
    private LlmProvider llmProvider;

    private PluginManager() {}

    // ════════════════════════════════════════════════════════════════
    //  Configuration
    // ════════════════════════════════════════════════════════════════

    public void configure(GraalWasmSandbox sandbox) {
        this.sandbox = sandbox;
        log.info("[PluginManager] Configured with GraalWasmSandbox");
    }

    public void configureLlm(LlmProvider llmProvider) {
        this.llmProvider = llmProvider;
        log.info("[PluginManager] Configured with LlmProvider: {}", llmProvider.name());
    }

    // ════════════════════════════════════════════════════════════════
    //  Plugin Scan (WASM auto-discovery)
    // ════════════════════════════════════════════════════════════════

    public void scanAndLoadPlugins(String pluginDir) {
        Path dir = Path.of(pluginDir);
        if (!Files.isDirectory(dir)) {
            log.warn("[PluginManager] Plugin directory does not exist: {}", pluginDir);
            return;
        }

        int discovered = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.wasm")) {
            for (Path wasmFile : stream) {
                String fileName = wasmFile.getFileName().toString();
                String toolName = "tool." + fileName.substring(0, fileName.length() - ".wasm".length());

                try {
                    byte[] bytecode = Files.readAllBytes(wasmFile);
                    pluginRegistry.put(toolName, bytecode);

                    // Register in the global tool catalog
                    ToolDefinition def = new ToolDefinition(
                            toolName,
                            "WASM plugin: " + toolName,
                            Map.of("input", Map.of("type", "string", "description", "JSON input for " + toolName)),
                            ToolDefinition.ToolType.WASM,
                            0,
                            "wasm:" + toolName
                    );
                    registerToolDefinition(def);
                    discovered++;

                    log.info("[PluginManager] Auto-discovered: {} ({} bytes)", toolName, bytecode.length);
                } catch (IOException e) {
                    log.error("[PluginManager] Failed to read plugin: {} — {}", wasmFile, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("[PluginManager] Failed to scan plugin directory: {}", e.getMessage());
            return;
        }

        log.info("[PluginManager] Plugin scan complete: {} plugin(s) from '{}'", discovered, pluginDir);
        System.out.printf("  \u001B[36m[PluginManager] Scan complete: %d WASM plugin(s) registered + indexed%n\u001B[0m", discovered);
    }

    // ════════════════════════════════════════════════════════════════
    //  Tool Catalog: Global Registry of All Available Tools
    // ════════════════════════════════════════════════════════════════

    /**
     * Register a tool definition in the global catalog.
     * <p>
     * This makes the tool discoverable via semantic search and
     * loadable via sys_insmod.
     */
    public void registerToolDefinition(ToolDefinition def) {
        toolCatalog.put(def.name(), def);

        // Generate embedding for semantic search
        if (llmProvider != null) {
            try {
                String searchText = def.name() + " " + def.description();
                float[] embedding = llmProvider.embed(searchText);
                if (embedding != null) {
                    toolEmbeddings.put(def.name(), embedding);
                }
            } catch (Exception e) {
                log.debug("[PluginManager] Embedding failed for tool '{}': {}", def.name(), e.getMessage());
            }
        }

        log.debug("[PluginManager] Tool catalog entry: {} [{}]", def.name(), def.type());
    }

    /**
     * Remove a tool definition from the global catalog.
     */
    public void unregisterToolDefinition(String toolName) {
        toolCatalog.remove(toolName);
        toolEmbeddings.remove(toolName);
    }

    /**
     * Get a tool definition from the catalog.
     */
    public ToolDefinition getToolDefinition(String toolName) {
        return toolCatalog.get(toolName);
    }

    /**
     * List all available tools in the catalog.
     */
    public Set<String> availableTools() {
        return Collections.unmodifiableSet(toolCatalog.keySet());
    }

    // ════════════════════════════════════════════════════════════════
    //  Semantic Search (modprobe -l): Fuzzy tool discovery
    // ════════════════════════════════════════════════════════════════

    /**
     * Semantic search for tools matching a natural language query.
     * <p>
     * This is the core of the {@code sys_insmod} mechanism: when an
     * Agent says "I need a tool to search the web", this method
     * converts the query to a vector and finds the most similar
     * tool in the catalog.
     * <p>
     * If no LLM provider is available, falls back to keyword matching.
     *
     * @param query natural language tool requirement
     * @param topK  maximum number of results
     * @return list of matching tool definitions, sorted by relevance
     */
    public List<ToolDefinition> semanticSearch(String query, int topK) {
        if (query == null || query.isBlank()) return List.of();

        // ── Vector-based semantic search ──
        if (llmProvider != null && !toolEmbeddings.isEmpty()) {
            try {
                float[] queryVec = llmProvider.embed(query);
                if (queryVec != null) {
                    List<Map.Entry<ToolDefinition, Double>> scored = new ArrayList<>();

                    for (Map.Entry<String, float[]> entry : toolEmbeddings.entrySet()) {
                        String toolName = entry.getKey();
                        float[] toolVec = entry.getValue();
                        ToolDefinition def = toolCatalog.get(toolName);

                        if (def != null && toolVec != null) {
                            double similarity = VectorMath.cosineSimilarity(queryVec, toolVec);
                            scored.add(Map.entry(def, similarity));
                        }
                    }

                    scored.sort(Map.Entry.<ToolDefinition, Double>comparingByValue().reversed());

                    List<ToolDefinition> results = new ArrayList<>();
                    for (int i = 0; i < Math.min(topK, scored.size()); i++) {
                        results.add(scored.get(i).getKey());
                    }

                    log.info("[PluginManager] Semantic search: query='{}', results={}", query, results.size());
                    return results;
                }
            } catch (Exception e) {
                log.warn("[PluginManager] Semantic search failed, falling back to keyword: {}", e.getMessage());
            }
        }

        // ── Fallback: keyword matching ──
        return keywordSearch(query, topK);
    }

    /**
     * Keyword-based fallback search.
     */
    private List<ToolDefinition> keywordSearch(String query, int topK) {
        String lowerQuery = query.toLowerCase();
        List<ToolDefinition> results = new ArrayList<>();

        for (ToolDefinition def : toolCatalog.values()) {
            String searchText = (def.name() + " " + def.description()).toLowerCase();
            // Simple token overlap scoring
            String[] queryTokens = lowerQuery.split("[\\s,，.。;；]+");
            int matches = 0;
            for (String token : queryTokens) {
                if (token.length() > 1 && searchText.contains(token)) {
                    matches++;
                }
            }
            if (matches > 0) {
                results.add(def);
            }
        }

        results.sort((a, b) -> {
            String aText = (a.name() + " " + a.description()).toLowerCase();
            String bText = (b.name() + " " + b.description()).toLowerCase();
            int aMatches = 0, bMatches = 0;
            for (String token : lowerQuery.split("[\\s,，.。;；]+")) {
                if (token.length() > 1) {
                    if (aText.contains(token)) aMatches++;
                    if (bText.contains(token)) bMatches++;
                }
            }
            return bMatches - aMatches;
        });

        return results.subList(0, Math.min(topK, results.size()));
    }

    // ════════════════════════════════════════════════════════════════
    //  MCP Bridge: Sync MCP tools into the global catalog
    // ════════════════════════════════════════════════════════════════

    /**
     * Sync all tools from a registered MCP server into the global catalog.
     * <p>
     * This bridges the MCP protocol into the PluginManager's unified
     * tool discovery system. After syncing, MCP tools are discoverable
     * via semantic search and loadable via sys_insmod — just like
     * local WASM plugins.
     *
     * @param serverName the MCP server name
     */
    public void syncMcpTools(String serverName) {
        McpClientRegistry mcp = McpClientRegistry.getInstance();
        if (!mcp.hasServer(serverName)) {
            log.warn("[PluginManager] MCP server '{}' not registered", serverName);
            return;
        }

        try {
            Object toolsResult = mcp.listTools(serverName);
            // Parse the tools/list response and register each tool
            // The response format is: {"tools": [{"name": "...", "description": "...", "inputSchema": {...}}]}
            if (toolsResult instanceof Map) {
                Object toolsList = ((Map<?, ?>) toolsResult).get("tools");
                if (toolsList instanceof List) {
                    for (Object toolObj : (List<?>) toolsList) {
                        if (toolObj instanceof Map) {
                            Map<?, ?> toolMap = (Map<?, ?>) toolObj;
                            String toolName = "mcp." + serverName + "." + toolMap.get("name");
                            String description = toolMap.get("description") != null
                                    ? toolMap.get("description").toString() : "MCP tool: " + toolName;

                            @SuppressWarnings("unchecked")
                            Map<String, Object> inputSchema = toolMap.get("inputSchema") != null
                                    ? (Map<String, Object>) toolMap.get("inputSchema")
                                    : Map.of();

                            ToolDefinition def = new ToolDefinition(
                                    toolName, description, inputSchema,
                                    ToolDefinition.ToolType.MCP, 0,
                                    "mcp:" + serverName
                            );
                            registerToolDefinition(def);
                            log.info("[PluginManager] MCP tool synced: {} [{}]", toolName, serverName);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[PluginManager] Failed to sync MCP tools from '{}': {}", serverName, e.getMessage());
        }
    }

    /**
     * Sync tools from all registered MCP servers.
     */
    public void syncAllMcpTools() {
        McpClientRegistry mcp = McpClientRegistry.getInstance();
        for (String serverName : mcp.serverNames()) {
            syncMcpTools(serverName);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  insmod / rmmod: Per-Agent Tool Loading
    // ════════════════════════════════════════════════════════════════

    /**
     * sys_insmod: Load a tool into an Agent's active context.
     * <p>
     * This is the AIOS equivalent of {@code insmod}:
     * <ol>
     *   <li>Search the global catalog for tools matching the query</li>
     *   <li>Select the best match</li>
     *   <li>Load it into the Agent's {@link AgentToolContext}</li>
     *   <li>Return the tool's JSON Schema for injection into the LLM prompt</li>
     * </ol>
     *
     * @param agentId the requesting Agent's ID
     * @param query   natural language tool requirement (e.g., "我需要一个能搜索网页的工具")
     * @return the loaded ToolDefinition, or null if no match found
     */
    public ToolDefinition insmod(String agentId, String query) {
        // Get or create the agent's tool context
        AgentToolContext ctx = agentContexts.computeIfAbsent(agentId,
                id -> new AgentToolContext(id));

        // Semantic search for matching tools
        List<ToolDefinition> matches = semanticSearch(query, 3);

        if (matches.isEmpty()) {
            log.warn("[PluginManager] insmod: no tools found for query '{}' (agent={})", query, agentId);
            return null;
        }

        // Try to load the best match
        ToolDefinition best = matches.get(0);

        if (ctx.insmod(best)) {
            log.info("[PluginManager] insmod: agent={} loaded '{}' [type={}, source={}]",
                    agentId, best.name(), best.type(), best.source());
            return best;
        } else {
            log.warn("[PluginManager] insmod: agent={} failed to load '{}' (budget exceeded?)",
                    agentId, best.name());
            return null;
        }
    }

    /**
     * sys_insmod: Load a specific tool by name into an Agent's context.
     */
    public ToolDefinition insmodByName(String agentId, String toolName) {
        AgentToolContext ctx = agentContexts.computeIfAbsent(agentId,
                id -> new AgentToolContext(id));

        ToolDefinition def = toolCatalog.get(toolName);
        if (def == null) {
            log.warn("[PluginManager] insmod: tool '{}' not found in catalog", toolName);
            return null;
        }

        if (ctx.insmod(def)) {
            return def;
        }
        return null;
    }

    /**
     * sys_rmmod: Unload a tool from an Agent's active context.
     *
     * @param agentId  the requesting Agent's ID
     * @param toolName the tool to unload
     * @return true if unloaded successfully
     */
    public boolean rmmod(String agentId, String toolName) {
        AgentToolContext ctx = agentContexts.get(agentId);
        if (ctx == null) return false;

        boolean removed = ctx.rmmod(toolName);
        if (removed) {
            log.info("[PluginManager] rmmod: agent={} unloaded '{}'", agentId, toolName);
        }
        return removed;
    }

    // ════════════════════════════════════════════════════════════════
    //  Agent Context Management
    // ════════════════════════════════════════════════════════════════

    /**
     * Get the tool context for an Agent.
     */
    public AgentToolContext getAgentContext(String agentId) {
        return agentContexts.computeIfAbsent(agentId,
                id -> new AgentToolContext(id));
    }

    /**
     * Remove an Agent's tool context (on process exit).
     */
    public void removeAgentContext(String agentId) {
        AgentToolContext ctx = agentContexts.remove(agentId);
        if (ctx != null) {
            ctx.unloadAll();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  WASM Plugin Execution (existing API, preserved)
    // ════════════════════════════════════════════════════════════════

    public String executePlugin(String action, String parameters) throws Exception {
        byte[] bytecode = pluginRegistry.get(action);
        if (bytecode == null) {
            throw new IllegalArgumentException("Plugin not registered: " + action);
        }

        if (sandbox == null) {
            throw new IllegalStateException("GraalWasmSandbox not configured in PluginManager");
        }

        log.info("[PluginManager] Executing plugin '{}' ({} bytes)", action, bytecode.length);
        return sandbox.executeCode(bytesToHex(bytecode), "main");
    }

    public boolean hasPlugin(String action) {
        return pluginRegistry.containsKey(action);
    }

    public java.util.Set<String> registeredPlugins() {
        return pluginRegistry.keySet();
    }

    public void registerPlugin(String toolName, byte[] bytecode) {
        pluginRegistry.put(toolName, bytecode);
        // Also register in the tool catalog
        ToolDefinition def = new ToolDefinition(
                toolName,
                "WASM plugin: " + toolName,
                Map.of("input", Map.of("type", "string", "description", "JSON input")),
                ToolDefinition.ToolType.WASM, 0, "wasm:" + toolName
        );
        registerToolDefinition(def);
        log.info("[PluginManager] Manually registered: {} ({} bytes)", toolName, bytecode.length);
    }

    /**
     * Unregister a WASM plugin (rmmod from the global registry).
     */
    public void unregisterPlugin(String action) {
        pluginRegistry.remove(action);
        unregisterToolDefinition(action);
        log.info("[PluginManager] Unregistered plugin: {}", action);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
