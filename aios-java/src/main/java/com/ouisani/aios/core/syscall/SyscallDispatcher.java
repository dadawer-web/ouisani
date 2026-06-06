package com.ouisani.aios.core.syscall;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.llm.LlmRouter;
import com.ouisani.aios.core.memory.ContextInjector;
import com.ouisani.aios.core.memory.MemoryManager;
import com.ouisani.aios.core.mcp.McpClientRegistry;
import com.ouisani.aios.core.plugin.AgentToolContext;
import com.ouisani.aios.core.plugin.PluginManager;
import com.ouisani.aios.core.plugin.ToolDefinition;
import com.ouisani.aios.core.sandbox.DockerSandboxProvider;
import com.ouisani.aios.core.sandbox.rpa.HostRpaManager;
import com.ouisani.aios.core.security.BpfManager;
import com.ouisani.aios.core.security.ObjectManager;
import com.ouisani.aios.core.security.SyscallFilter;
import com.ouisani.aios.core.syscall.schema.LlmPayload;
import com.ouisani.aios.core.syscall.schema.MemoryPayload;
import com.ouisani.aios.core.syscall.schema.RawPayload;
import com.ouisani.aios.core.syscall.schema.StoragePayload;
import com.ouisani.aios.core.syscall.schema.SyscallPayload;
import com.ouisani.aios.core.syscall.schema.ToolPayload;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import com.ouisani.aios.user.bin.AiosApt;
import com.ouisani.aios.user.bin.CoreUtils;
import com.ouisani.aios.vfs.DeviceOfflineException;
import com.ouisani.aios.vfs.MutableFileNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central Syscall Dispatcher — the sole gateway between Agents and the AIOS kernel.
 * <p>
 * Routes {@link SyscallRequest}s by {@code namespace} to the appropriate backend
 * service, with strict type-safety enforcement: each namespace requires its
 * corresponding {@link SyscallPayload} subtype. Mismatched payloads trigger
 * a kernel panic exception.
 *
 * <h3>Standard Namespace Routing (ABI v2):</h3>
 * <ul>
 *   <li>{@code llm} → {@link LlmPayload} → {@link LlmRouter}</li>
 *   <li>{@code storage} → {@link StoragePayload} → {@link VfsManager}</li>
 *   <li>{@code tool} → {@link ToolPayload} → {@link PluginManager} / {@link DockerSandboxProvider}</li>
 *   <li>{@code memory} → {@link MemoryPayload} → MemoryProvider (pending)</li>
 * </ul>
 *
 * <h3>Legacy Namespace Routing (backward compat):</h3>
 * <ul>
 *   <li>{@code vfs}, {@code handle}, {@code coreutils}, {@code apt}, {@code bin}</li>
 * </ul>
 */
public final class SyscallDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SyscallDispatcher.class);

    private static final class Holder {
        static final SyscallDispatcher INSTANCE = new SyscallDispatcher();
    }

    public static SyscallDispatcher getInstance() {
        return Holder.INSTANCE;
    }

    private LlmRouter llmRouter;
    private VfsManager vfsManager;
    private ObjectManager objectManager;

    /** Seccomp/eBPF-style syscall firewall filter chain. */
    private final List<SyscallFilter> filters = new CopyOnWriteArrayList<>();

    private SyscallDispatcher() {}

    public void configure(LlmRouter llmRouter, VfsManager vfsManager, ObjectManager objectManager) {
        this.llmRouter = llmRouter;
        this.vfsManager = vfsManager;
        this.objectManager = objectManager;
        log.info("[Syscall Dispatcher] Configured: llmRouter={}, vfsManager={}, objectManager={}",
                llmRouter != null, vfsManager != null, objectManager != null);

        // ── Kernel ABI v2: Namespace-based ABI Router ──
        System.out.println("[Kernel Dispatcher] ABI Router upgraded. Now listening on standard namespaces.");
        log.info("[Kernel Dispatcher] ABI Router upgraded. Now listening on standard namespaces.");

        // ── MCP routing protocol engaged ──
        System.out.println("[Kernel Dispatcher] MCP routing protocol engaged. The AIOS ecosystem is now limitless.");
        log.info("[Kernel Dispatcher] MCP routing protocol engaged. The AIOS ecosystem is now limitless.");

        // ── Semantic eBPF: 注册 BpfManager 到 Seccomp 过滤器链 ──
        // BpfManager 实现了 SyscallFilter 接口，在每次 Syscall 执行前
        // 进行意图拦截（Prompt 注入检测、VFS 破坏性写入保护、权限提升拦截等）
        addFilter(BpfManager.instance());
        log.info("[Kernel Dispatcher] Semantic eBPF probe registered. Intent interception active.");
        System.out.println("[Kernel Dispatcher] Semantic eBPF probe registered. Intent interception active.");
    }

    /**
     * Register a syscall filter into the Seccomp firewall chain.
     * Filters are executed in registration order.
     */
    public void addFilter(SyscallFilter filter) {
        filters.add(filter);
        log.info("[Syscall Dispatcher] Seccomp filter registered: {} (total={})",
                filter.getClass().getSimpleName(), filters.size());
    }

    // ════════════════════════════════════════════════════════════════
    //  CORE EXECUTE — Namespace-based ABI Router
    // ════════════════════════════════════════════════════════════════

    /**
     * Execute a system call on behalf of an Agent.
     * <p>
     * The kernel ABI router dispatches based on {@code request.namespace()}:
     * <ul>
     *   <li>{@code llm} — strict LlmPayload, routes to LlmRouter</li>
     *   <li>{@code storage} — strict StoragePayload, routes to VfsManager</li>
     *   <li>{@code tool} — strict ToolPayload, routes to PluginManager or DockerSandboxProvider</li>
     *   <li>{@code memory} — strict MemoryPayload, routes to MemoryProvider</li>
     *   <li>others — legacy fallback routing</li>
     * </ul>
     *
     * @param agentId the agent issuing the syscall
     * @param request the syscall request
     * @return the syscall response
     */
    public SyscallResponse execute(String agentId, SyscallRequest request) {
        long startNanos = System.nanoTime();

        String fullAction = request.fullAction();
        log.info("[Syscall Dispatcher] Intercepted namespace='{}' action='{}' from Agent '{}'",
                request.namespace(), request.action(), agentId);

        // ── Seccomp/eBPF Firewall: pre-filter chain ──
        for (SyscallFilter filter : filters) {
            try {
                filter.preFilter(agentId, request);
            } catch (SecurityException e) {
                SemanticEtw.getInstance().logEvent("SECURITY", "BPF_INTERCEPT",
                        "agent=" + agentId + " action=" + fullAction
                        + " filter=" + filter.getClass().getSimpleName()
                        + " reason=" + e.getMessage());
                log.warn("[Security BPF] Malicious syscall intercepted! agent={}, action={}, filter={}",
                        agentId, fullAction, filter.getClass().getSimpleName());
                return SyscallResponse.fail("SECURITY: " + e.getMessage());
            }
        }

        SemanticEtw.getInstance().logEvent("SYSCALL", "ENTER",
                "agent=" + agentId + " namespace=" + request.namespace() + " action=" + fullAction);

        SyscallResponse response;
        try {
            response = switch (request.namespace()) {
                case "llm"     -> routeLlm(agentId, request);
                case "storage" -> routeStorage(agentId, request);
                case "tool"    -> routeTool(agentId, request);
                case "memory"  -> routeMemory(agentId, request);
                default        -> routeLegacy(agentId, request);
            };
        } catch (SyscallException e) {
            response = SyscallResponse.fail(e);
        } catch (SecurityException e) {
            response = SyscallResponse.fail("SECURITY: " + e.getMessage());
        } catch (Exception e) {
            response = SyscallResponse.fail(e);
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        SemanticEtw.getInstance().logEvent("SYSCALL", "EXIT",
                "agent=" + agentId + " action=" + fullAction
                + " success=" + response.success()
                + " latencyMs=" + elapsedMs);

        log.info("[Syscall Dispatcher] Completed action '{}' for Agent '{}': success={}, latency={}ms",
                fullAction, agentId, response.success(), elapsedMs);

        return response;
    }

    // ════════════════════════════════════════════════════════════════
    //  NAMESPACE ROUTERS — Strict Type-Safe Dispatch
    // ════════════════════════════════════════════════════════════════

    // ── LLM Namespace ──────────────────────────────────────────────

    private SyscallResponse routeLlm(String agentId, SyscallRequest request) {
        SyscallPayload payload = request.payload();

        // Strict type check: only LlmPayload is legal in the "llm" namespace
        if (!(payload instanceof LlmPayload llm)) {
            throw new SyscallException(request.fullAction(),
                    "Kernel Panic: Invalid memory segment for payload — expected LlmPayload, got "
                    + payload.getClass().getSimpleName());
        }

        if (llmRouter == null) {
            return SyscallResponse.fail("LLM router not configured");
        }

        String prompt = llm.prompt();
        if (prompt == null || prompt.isEmpty()) {
            return SyscallResponse.fail("LlmPayload.prompt must not be empty");
        }

        try {
            // Transparent context injection: augment prompt with Vector Memory
            String augmentedPrompt = ContextInjector.getInstance().augmentPrompt(prompt);

            String result = switch (request.action()) {
                case "think" -> llmRouter.think(augmentedPrompt, "");
                case "think_with_history" -> {
                    var messages = List.of(new LlmProvider.ChatMessage("user", augmentedPrompt));
                    yield llmRouter.thinkWithHistory(messages, "");
                }
                default -> throw new SyscallException(request.fullAction());
            };

            log.info("[Dispatcher] LLM namespace: action='{}', promptLen={}, temp={}, maxTokens={}",
                    request.action(), prompt.length(), llm.temperature(), llm.maxTokens());

            return SyscallResponse.ok(result);
        } catch (SyscallException e) {
            throw e;
        } catch (Exception e) {
            return SyscallResponse.fail(e);
        }
    }

    // ── Storage Namespace ──────────────────────────────────────────

    private SyscallResponse routeStorage(String agentId, SyscallRequest request) {
        SyscallPayload payload = request.payload();

        // Strict type check: only StoragePayload is legal in the "storage" namespace
        if (!(payload instanceof StoragePayload storage)) {
            throw new SyscallException(request.fullAction(),
                    "Kernel Panic: Invalid memory segment for payload — expected StoragePayload, got "
                    + payload.getClass().getSimpleName());
        }

        if (vfsManager == null) {
            return SyscallResponse.fail("VFS manager not configured");
        }

        String path = storage.path();
        String mode = storage.mode();

        log.info("[Dispatcher] Storage namespace: path='{}', mode='{}'", path, mode);

        try {
            return switch (mode) {
                case "read" -> {
                    var nodeOpt = vfsManager.resolve(path);
                    if (nodeOpt.isEmpty()) {
                        yield SyscallResponse.fail("Path not found: " + path);
                    }
                    String content = nodeOpt.get().read();
                    yield SyscallResponse.ok(content);
                }
                case "write" -> {
                    String data = storage.data();
                    if (data == null) {
                        yield SyscallResponse.fail("Storage write requires non-null data");
                    }
                    var nodeOpt = vfsManager.resolve(path);
                    if (nodeOpt.isEmpty()) {
                        MutableFileNode newNode = new MutableFileNode(path);
                        newNode.write(data);
                        vfsManager.mount(extractDirPath(path), extractFileName(path), newNode);
                        log.debug("[VFS] Auto-created file node: {}", path);
                        yield SyscallResponse.ok();
                    }
                    boolean ok = nodeOpt.get().write(data);
                    yield ok ? SyscallResponse.ok() : SyscallResponse.fail("Write rejected by node");
                }
                case "append" -> {
                    String data = storage.data();
                    if (data == null) {
                        yield SyscallResponse.fail("Storage append requires non-null data");
                    }
                    var nodeOpt = vfsManager.resolve(path);
                    if (nodeOpt.isEmpty()) {
                        MutableFileNode newNode = new MutableFileNode(path);
                        newNode.write(data);
                        vfsManager.mount(extractDirPath(path), extractFileName(path), newNode);
                        yield SyscallResponse.ok();
                    }
                    String existing = nodeOpt.get().read();
                    boolean ok = nodeOpt.get().write(existing != null ? existing + data : data);
                    yield ok ? SyscallResponse.ok() : SyscallResponse.fail("Append rejected by node");
                }
                default -> SyscallResponse.fail("Unknown storage mode: " + mode);
            };
        } catch (DeviceOfflineException e) {
            log.warn("[Dispatcher] Device offline for Agent '{}': path={}, device={}", agentId, path, e.deviceId());
            return SyscallResponse.fail("Device offline: " + e.deviceId() + " at " + e.devicePath()
                    + ". The remote host has disconnected. Please retry later or use a different device.");
        } catch (Exception e) {
            return SyscallResponse.fail(e);
        }
    }

    private SyscallResponse routeTool(String agentId, SyscallRequest request) {
        SyscallPayload payload = request.payload();

        // Strict type check: only ToolPayload is legal in the "tool" namespace
        if (!(payload instanceof ToolPayload tool)) {
            throw new SyscallException(request.fullAction(),
                    "Kernel Panic: Invalid memory segment for payload — expected ToolPayload, got "
                    + payload.getClass().getSimpleName());
        }

        String toolName = tool.toolName();
        Map<String, Object> args = tool.arguments();

        log.info("[Dispatcher] Tool namespace: toolName='{}', args={}", toolName, args.keySet());

        try {
            // ── Dynamic Module Loading: sys_insmod / sys_rmmod ──
            if ("kernel.insmod".equals(toolName)) {
                return executeInsmod(agentId, args);
            }
            if ("kernel.rmmod".equals(toolName)) {
                return executeRmmod(agentId, args);
            }
            if ("kernel.lsmod".equals(toolName)) {
                return executeLsmod(agentId);
            }
            if ("kernel.modprobe".equals(toolName)) {
                return executeModprobe(agentId, args);
            }

            // MCP universal tool bus: external capabilities via Model Context Protocol
            if (isMcpTool(toolName)) {
                return executeMcpTool(agentId, toolName, args);
            }

            // RPA physical driver: host GUI actuator and vision
            if (isRpaTool(toolName)) {
                return executeRpaTool(agentId, toolName, args);
            }

            // Docker sandbox: if toolName indicates a docker/container operation
            if (isDockerTool(toolName)) {
                return executeDockerTool(agentId, toolName, args);
            }

            // WASM plugin: delegate to PluginManager
            return executeWasmTool(agentId, toolName, args);
        } catch (Exception e) {
            return SyscallResponse.fail("Tool execution failed: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  sys_insmod / sys_rmmod / sys_lsmod / sys_modprobe
    //  Dynamic Kernel Module Loading (insmod/rmmod)
    // ════════════════════════════════════════════════════════════════

    /**
     * sys_insmod: Load a tool into the Agent's active context.
     * <p>
     * Arguments:
     * <ul>
     *   <li>{@code query} (String) — natural language tool requirement, e.g., "我需要一个能搜索网页的工具"</li>
     *   <li>{@code tool_name} (String, optional) — exact tool name to load (bypasses semantic search)</li>
     * </ul>
     * <p>
     * Returns the loaded tool's JSON Schema in the response data.
     */
    private SyscallResponse executeInsmod(String agentId, Map<String, Object> args) {
        PluginManager pm = PluginManager.getInstance();

        // Check for direct tool name first
        String toolName = args.get("tool_name") instanceof String s ? s : null;
        if (toolName != null && !toolName.isBlank()) {
            ToolDefinition def = pm.insmodByName(agentId, toolName);
            if (def != null) {
                return SyscallResponse.ok("[insmod] Tool '" + def.name()
                        + "' loaded. Schema: " + def.toFunctionSchema());
            }
            return SyscallResponse.fail("[insmod] Tool '" + toolName + "' not found in catalog. Available: "
                    + pm.availableTools());
        }

        // Semantic search by natural language query
        String query = args.get("query") instanceof String s ? s : null;
        if (query == null || query.isBlank()) {
            return SyscallResponse.fail("[insmod] Missing 'query' or 'tool_name' argument");
        }

        ToolDefinition def = pm.insmod(agentId, query);
        if (def != null) {
            return SyscallResponse.ok("[insmod] Tool '" + def.name() + "' loaded (matched by: '"
                    + query + "'). Schema: " + def.toFunctionSchema());
        }

        return SyscallResponse.fail("[insmod] No tool found matching: '" + query
                + "'. Available: " + pm.availableTools());
    }

    /**
     * sys_rmmod: Unload a tool from the Agent's active context.
     * <p>
     * Arguments:
     * <ul>
     *   <li>{@code tool_name} (String) — the tool to unload</li>
     * </ul>
     */
    private SyscallResponse executeRmmod(String agentId, Map<String, Object> args) {
        String toolName = args.get("tool_name") instanceof String s ? s : null;
        if (toolName == null || toolName.isBlank()) {
            return SyscallResponse.fail("[rmmod] Missing 'tool_name' argument");
        }

        PluginManager pm = PluginManager.getInstance();
        boolean removed = pm.rmmod(agentId, toolName);
        if (removed) {
            return SyscallResponse.ok("[rmmod] Tool '" + toolName + "' unloaded. Token budget freed.");
        }
        return SyscallResponse.fail("[rmmod] Tool '" + toolName + "' not found in active context.");
    }

    /**
     * sys_lsmod: List all tools currently loaded in the Agent's context.
     */
    private SyscallResponse executeLsmod(String agentId) {
        PluginManager pm = PluginManager.getInstance();
        AgentToolContext ctx = pm.getAgentContext(agentId);

        if (ctx.toolCount() == 0) {
            return SyscallResponse.ok("[lsmod] No tools loaded. Use kernel.insmod to load tools.");
        }

        return SyscallResponse.ok("[lsmod] " + ctx.toolCount() + " tools loaded ("
                + ctx.tokenCostUsed() + "/" + ctx.tokenBudget() + " tokens):\n"
                + ctx.toProcStatus());
    }

    /**
     * sys_modprobe: Search for available tools without loading them.
     * <p>
     * Arguments:
     * <ul>
     *   <li>{@code query} (String) — natural language tool requirement</li>
     * </ul>
     */
    private SyscallResponse executeModprobe(String agentId, Map<String, Object> args) {
        String query = args.get("query") instanceof String s ? s : null;
        if (query == null || query.isBlank()) {
            return SyscallResponse.fail("[modprobe] Missing 'query' argument");
        }

        PluginManager pm = PluginManager.getInstance();
        java.util.List<ToolDefinition> matches = pm.semanticSearch(query, 5);

        if (matches.isEmpty()) {
            return SyscallResponse.ok("[modprobe] No tools found matching: '" + query + "'");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[modprobe] ").append(matches.size()).append(" tool(s) found for '").append(query).append("':\n");
        for (int i = 0; i < matches.size(); i++) {
            ToolDefinition t = matches.get(i);
            sb.append(String.format("  %d. %-30s [%s] cost=%d — %s%n",
                    i + 1, t.name(), t.type(), t.tokenCost(),
                    t.description().length() > 60 ? t.description().substring(0, 60) + "..." : t.description()));
        }
        sb.append("\nUse kernel.insmod with tool_name to load a specific tool.");

        return SyscallResponse.ok(sb.toString());
    }

    private boolean isMcpTool(String toolName) {
        return toolName.startsWith("mcp.");
    }

    /**
     * Execute an MCP tool call via the universal tool bus.
     * <p>
     * Tool name format: {@code mcp.{serverName}.{mcpToolName}}
     * <p>
     * Example: {@code mcp.weather.get_forecast} →
     * serverName = "weather", mcpToolName = "get_forecast"
     * <p>
     * Errors are returned as valid SyscallResponse (not exceptions) so
     * the LLM can see the failure and attempt self-repair.
     */
    private SyscallResponse executeMcpTool(String agentId, String toolName, Map<String, Object> args) {
        // Parse: mcp.{serverName}.{mcpToolName}
        String[] parts = toolName.split("\\.", 3);
        if (parts.length < 3) {
            return SyscallResponse.fail(
                    "MCP tool name format invalid: expected 'mcp.{serverName}.{toolName}', got '" + toolName + "'");
        }

        String serverName = parts[1];
        String mcpToolName = parts[2];

        McpClientRegistry registry = McpClientRegistry.getInstance();

        if (!registry.hasServer(serverName)) {
            return SyscallResponse.fail(
                    "MCP server '" + serverName + "' not registered. Available: " + registry.serverNames());
        }

        log.info("[Dispatcher] MCP routing: agent='{}', server='{}', tool='{}', args={}",
                agentId, serverName, mcpToolName, args.keySet());

        try {
            Object result = registry.callTool(serverName, mcpToolName, args);

            String resultStr;
            if (result == null) {
                resultStr = "";
            } else if (result instanceof String s) {
                resultStr = s;
            } else {
                resultStr = result.toString();
            }

            log.info("[Dispatcher] MCP tool '{}/{}' returned successfully for Agent '{}': resultLen={}",
                    serverName, mcpToolName, agentId, resultStr.length());

            return SyscallResponse.ok(resultStr);
        } catch (Exception e) {
            log.warn("[Dispatcher] MCP tool '{}/{}' failed for Agent '{}': {}",
                    serverName, mcpToolName, agentId, e.getMessage());
            // Return error as valid SyscallResponse — let the LLM self-repair
            return SyscallResponse.fail(
                    "MCP tool '" + serverName + "/" + mcpToolName + "' call failed: " + e.getMessage());
        }
    }

    private boolean isRpaTool(String toolName) {
        return toolName.startsWith("rpa.");
    }

    private SyscallResponse executeRpaTool(String agentId, String toolName, Map<String, Object> args) {
        HostRpaManager rpa = HostRpaManager.getInstance();

        // ── Security: HEADLESS mode check and physical pointer warning ──
        boolean headless = java.awt.GraphicsEnvironment.isHeadless();
        if (headless) {
            log.error("[Kernel Security] RPA tool '{}' rejected: system is in HEADLESS mode", toolName);
            return SyscallResponse.fail("RPA tools are not available in HEADLESS mode");
        }

        log.warn("[Kernel Security] WARNING: Agent {} is manipulating the host physical pointer!", agentId);
        System.out.println("[Kernel Security] WARNING: Agent " + agentId + " is manipulating the host physical pointer!");

        if (!rpa.isAvailable()) {
            return SyscallResponse.fail("RPA subsystem not available — Robot initialization failed");
        }

        return switch (toolName) {
            case "rpa.screenshot" -> {
                String base64 = rpa.takeScreenshotBase64();
                log.info("[Dispatcher] RPA screenshot captured for Agent '{}': base64Len={}", agentId, base64.length());
                yield SyscallResponse.ok(base64);
            }
            case "rpa.mouse_move" -> {
                int x = toInt(args.get("x"), -1);
                int y = toInt(args.get("y"), -1);
                if (x < 0 || y < 0) {
                    yield SyscallResponse.fail("rpa.mouse_move requires integer 'x' and 'y' arguments");
                }
                rpa.mouseMove(x, y);
                log.info("[Dispatcher] RPA mouse_move for Agent '{}': ({}, {})", agentId, x, y);
                yield SyscallResponse.ok("Mouse moved to (" + x + ", " + y + ")");
            }
            case "rpa.click" -> {
                rpa.mouseClick();
                log.info("[Dispatcher] RPA click for Agent '{}'", agentId);
                yield SyscallResponse.ok("Mouse clicked");
            }
            case "rpa.right_click" -> {
                rpa.mouseRightClick();
                log.info("[Dispatcher] RPA right_click for Agent '{}'", agentId);
                yield SyscallResponse.ok("Right-clicked");
            }
            case "rpa.click_at" -> {
                int x = toInt(args.get("x"), -1);
                int y = toInt(args.get("y"), -1);
                if (x < 0 || y < 0) {
                    yield SyscallResponse.fail("rpa.click_at requires integer 'x' and 'y' arguments");
                }
                rpa.mouseClickAt(x, y);
                log.info("[Dispatcher] RPA click_at for Agent '{}': ({}, {})", agentId, x, y);
                yield SyscallResponse.ok("Clicked at (" + x + ", " + y + ")");
            }
            case "rpa.scroll" -> {
                int amount = toInt(args.get("amount"), 1);
                rpa.mouseScroll(amount);
                log.info("[Dispatcher] RPA scroll for Agent '{}': amount={}", agentId, amount);
                yield SyscallResponse.ok("Scrolled by " + amount);
            }
            case "rpa.type" -> {
                String text = args.get("text") != null ? args.get("text").toString() : null;
                if (text == null || text.isEmpty()) {
                    yield SyscallResponse.fail("rpa.type requires a 'text' argument");
                }
                rpa.keyboardType(text);
                log.info("[Dispatcher] RPA type for Agent '{}': textLen={}", agentId, text.length());
                yield SyscallResponse.ok("Typed " + text.length() + " characters");
            }
            case "rpa.key_combo" -> {
                int keyCode = toInt(args.get("keyCode"), -1);
                int modifiers = toInt(args.get("modifiers"), 0);
                if (keyCode < 0) {
                    yield SyscallResponse.fail("rpa.key_combo requires a 'keyCode' argument");
                }
                rpa.keyCombo(modifiers, keyCode);
                log.info("[Dispatcher] RPA key_combo for Agent '{}': keyCode={}, modifiers={}", agentId, keyCode, modifiers);
                yield SyscallResponse.ok("Key combo executed");
            }
            default -> SyscallResponse.fail("Unknown RPA tool: " + toolName);
        };
    }

    private static int toInt(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(value.toString()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    private boolean isDockerTool(String toolName) {
        return toolName.startsWith("run_docker")
                || toolName.startsWith("docker_")
                || toolName.equals("container_exec");
    }

    private SyscallResponse executeDockerTool(String agentId, String toolName, Map<String, Object> args) {
        DockerSandboxProvider dockerSandbox = new DockerSandboxProvider();

        String script = args.get("script") != null ? args.get("script").toString() : "";
        String entrypoint = args.get("entrypoint") != null ? args.get("entrypoint").toString() : "main";

        if (script.isEmpty()) {
            return SyscallResponse.fail("Docker tool requires a 'script' argument");
        }

        try {
            String result = dockerSandbox.executeCode(script, entrypoint);
            log.info("[Dispatcher] Docker tool '{}' executed for Agent '{}'", toolName, agentId);
            return SyscallResponse.ok(result);
        } catch (Exception e) {
            return SyscallResponse.fail("Docker execution failed: " + e.getMessage());
        }
    }

    private SyscallResponse executeWasmTool(String agentId, String toolName, Map<String, Object> args) {
        PluginManager pluginManager = PluginManager.getInstance();
        String pluginAction = "tool." + toolName;

        if (!pluginManager.hasPlugin(pluginAction)) {
            return SyscallResponse.fail("Plugin not registered: " + pluginAction);
        }

        String paramsJson = serializeParams(args);

        try {
            String result = pluginManager.executePlugin(pluginAction, paramsJson);
            log.info("[Dispatcher] WASM plugin '{}' executed for Agent '{}'", pluginAction, agentId);
            return SyscallResponse.ok(result);
        } catch (Exception e) {
            return SyscallResponse.fail("WASM plugin execution failed: " + e.getMessage());
        }
    }

    // ── Memory Namespace ───────────────────────────────────────────

    private SyscallResponse routeMemory(String agentId, SyscallRequest request) {
        SyscallPayload payload = request.payload();

        // Strict type check: only MemoryPayload is legal in the "memory" namespace
        if (!(payload instanceof MemoryPayload mem)) {
            throw new SyscallException(request.fullAction(),
                    "Kernel Panic: Invalid memory segment for payload — expected MemoryPayload, got "
                    + payload.getClass().getSimpleName());
        }

        log.info("[Dispatcher] Routing to Memory Subsystem... operation='{}', queryLen={}",
                mem.operation(), mem.query() != null ? mem.query().length() : 0);

        // Delegate to the unified MemoryManager
        return MemoryManager.getInstance().processMemorySyscall(agentId, mem);
    }

    // ════════════════════════════════════════════════════════════════
    //  LEGACY NAMESPACE ROUTER — Backward Compatibility
    // ════════════════════════════════════════════════════════════════

    private SyscallResponse routeLegacy(String agentId, SyscallRequest request) {
        String fullAction = request.fullAction();

        if (fullAction.startsWith("tool.")) {
            return handleToolPlugin(agentId, request);
        } else if (fullAction.startsWith("coreutils.")) {
            return handleCoreUtils(agentId, request);
        } else if (fullAction.startsWith("apt.")) {
            return handleApt(agentId, request);
        } else if (fullAction.startsWith("jit.")) {
            return handleJit(agentId, request);
        } else if (fullAction.startsWith("bin.")) {
            return handleBin(agentId, request);
        } else {
            return switch (fullAction) {
                case "vfs.read" -> handleVfsRead(agentId, request);
                case "vfs.write" -> handleVfsWrite(agentId, request);
                case "vfs.rollback" -> handleVfsRollback(agentId, request);
                case "vfs.snapshot" -> handleVfsSnapshot(agentId, request);
                case "handle.open" -> handleOpen(agentId, request);
                case "handle.read" -> handleRead(agentId, request);
                case "handle.close" -> handleClose(agentId, request);
                default -> throw new SyscallException(fullAction);
            };
        }
    }

    // ── VFS Syscalls (legacy) ──

    private SyscallResponse handleVfsRead(String agentId, SyscallRequest request) {
        if (vfsManager == null) {
            return SyscallResponse.fail("VFS manager not configured");
        }

        String path = request.paramString("path");
        if (path == null || path.isEmpty()) {
            return SyscallResponse.fail("Missing parameter: path");
        }

        try {
            var nodeOpt = vfsManager.resolve(path);
            if (nodeOpt.isEmpty()) {
                return SyscallResponse.fail("Path not found: " + path);
            }
            String content = nodeOpt.get().read();
            return SyscallResponse.ok(content);
        } catch (Exception e) {
            return SyscallResponse.fail(e);
        }
    }

    private SyscallResponse handleVfsWrite(String agentId, SyscallRequest request) {
        if (vfsManager == null) {
            return SyscallResponse.fail("VFS manager not configured");
        }

        String path = request.paramString("path");
        String payload = request.paramString("data");
        if (payload == null) {
            payload = request.paramString("payload");
        }
        if (path == null || path.isEmpty()) {
            return SyscallResponse.fail("Missing parameter: path");
        }
        if (payload == null) {
            return SyscallResponse.fail("Missing parameter: data");
        }

        try {
            var nodeOpt = vfsManager.resolve(path);
            if (nodeOpt.isEmpty()) {
                MutableFileNode newNode = new MutableFileNode(path);
                newNode.write(payload);
                vfsManager.mount(extractDirPath(path), extractFileName(path), newNode);
                log.debug("[VFS] Auto-created file node: {}", path);
                return SyscallResponse.ok();
            }
            boolean ok = nodeOpt.get().write(payload);
            return ok ? SyscallResponse.ok() : SyscallResponse.fail("Write rejected by node");
        } catch (Exception e) {
            return SyscallResponse.fail(e);
        }
    }

    // ── VFS Snapshot & Rollback Syscalls ──

    private SyscallResponse handleVfsSnapshot(String agentId, SyscallRequest request) {
        if (vfsManager == null) {
            return SyscallResponse.fail("VFS manager not configured");
        }

        String path = request.paramString("path");
        String label = request.paramString("label");
        if (path == null || path.isEmpty()) {
            return SyscallResponse.fail("Missing parameter: path");
        }

        try {
            var nodeOpt = vfsManager.resolve(path);
            if (nodeOpt.isEmpty()) {
                return SyscallResponse.fail("Path not found: " + path);
            }

            if (nodeOpt.get() instanceof com.ouisani.aios.vfs.ShadowCopyNode shadow) {
                long timestamp = shadow.createSnapshot(label);
                return SyscallResponse.ok("Snapshot created: timestamp=" + timestamp
                        + " label=" + (label != null ? label : "auto")
                        + " cowPages=" + shadow.cowPageCount());
            } else {
                // 自动包装为 ShadowCopyNode
                com.ouisani.aios.vfs.ShadowCopyNode shadowNode =
                        new com.ouisani.aios.vfs.ShadowCopyNode(path, nodeOpt.get());
                long timestamp = shadowNode.createSnapshot(label);
                // 替换 VFS 中的节点
                vfsManager.mount(extractDirPath(path), extractFileName(path), shadowNode);
                return SyscallResponse.ok("Snapshot created (auto-wrapped): timestamp=" + timestamp
                        + " cowPages=" + shadowNode.cowPageCount());
            }
        } catch (Exception e) {
            return SyscallResponse.fail(e);
        }
    }

    private SyscallResponse handleVfsRollback(String agentId, SyscallRequest request) {
        if (vfsManager == null) {
            return SyscallResponse.fail("VFS manager not configured");
        }

        String path = request.paramString("path");
        String timestampStr = request.paramString("timestamp");
        String label = request.paramString("label");
        if (path == null || path.isEmpty()) {
            return SyscallResponse.fail("Missing parameter: path");
        }

        try {
            var nodeOpt = vfsManager.resolve(path);
            if (nodeOpt.isEmpty()) {
                return SyscallResponse.fail("Path not found: " + path);
            }

            if (!(nodeOpt.get() instanceof com.ouisani.aios.vfs.ShadowCopyNode shadow)) {
                return SyscallResponse.fail("Path is not a ShadowCopyNode — create a snapshot first");
            }

            boolean success;
            if (label != null && !label.isEmpty()) {
                success = shadow.rollbackToLabel(label);
            } else if (timestampStr != null && !timestampStr.isEmpty()) {
                long timestamp = Long.parseLong(timestampStr);
                success = shadow.rollback(timestamp);
            } else {
                success = shadow.rollbackToLatest();
            }

            if (success) {
                return SyscallResponse.ok("Rollback successful: path=" + path
                        + " cowPages=" + shadow.cowPageCount()
                        + " snapshots=" + shadow.snapshotCount());
            } else {
                return SyscallResponse.fail("Rollback failed: no matching snapshot found");
            }
        } catch (NumberFormatException e) {
            return SyscallResponse.fail("Invalid timestamp format");
        } catch (Exception e) {
            return SyscallResponse.fail(e);
        }
    }

    private static String extractDirPath(String path) {
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash <= 0) return "/";
        return path.substring(0, lastSlash);
    }

    private static String extractFileName(String path) {
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0) return path;
        return path.substring(lastSlash + 1);
    }

    // ── Dynamic Tool Plugin Syscalls (legacy) ──

    private SyscallResponse handleToolPlugin(String agentId, SyscallRequest request) {
        PluginManager pluginManager = PluginManager.getInstance();
        String action = request.fullAction();

        if (!pluginManager.hasPlugin(action)) {
            return SyscallResponse.fail("Plugin not registered: " + action);
        }

        String paramsJson = serializeParams(request.params());

        try {
            String result = pluginManager.executePlugin(action, paramsJson);
            log.info("[Syscall Dispatcher] Plugin '{}' executed successfully for Agent '{}'", action, agentId);
            return SyscallResponse.ok(result);
        } catch (Exception e) {
            return SyscallResponse.fail("Plugin execution failed: " + e.getMessage());
        }
    }

    // ── CoreUtils Syscalls ──

    private SyscallResponse handleCoreUtils(String agentId, SyscallRequest request) {
        String subAction = request.fullAction().substring("coreutils.".length());
        try {
            String result = CoreUtils.dispatch(subAction, request.params());
            return SyscallResponse.ok(result);
        } catch (Exception e) {
            return SyscallResponse.fail("CoreUtils error: " + e.getMessage());
        }
    }

    // ── APT (Package Manager) Syscalls ──

    private SyscallResponse handleApt(String agentId, SyscallRequest request) {
        String subAction = request.fullAction().substring("apt.".length());
        try {
            String result = switch (subAction) {
                case "install" -> {
                    String pkg = request.paramString("package");
                    if (pkg == null || pkg.isEmpty()) yield "Missing parameter: package";
                    AiosApt.install(pkg);
                    yield "Package '" + pkg + "' installed successfully";
                }
                case "remove" -> {
                    String pkg = request.paramString("package");
                    if (pkg == null || pkg.isEmpty()) yield "Missing parameter: package";
                    AiosApt.remove(pkg);
                    yield "Package '" + pkg + "' removed";
                }
                case "list" -> AiosApt.list();
                default -> "Unknown apt command: " + subAction;
            };
            return SyscallResponse.ok(result);
        } catch (Exception e) {
            return SyscallResponse.fail("APT error: " + e.getMessage());
        }
    }

    // ── JIT (Just-In-Time Compilation) Syscalls ──

    private SyscallResponse handleJit(String agentId, SyscallRequest request) {
        String subAction = request.fullAction().substring("jit.".length());
        try {
            String result = switch (subAction) {
                case "compile" -> {
                    String sourceCode = request.paramString("source");
                    String language = request.paramString("language");
                    if (sourceCode == null || sourceCode.isEmpty())
                        yield "Missing parameter: source";
                    if (language == null || language.isEmpty())
                        language = "java";

                    com.ouisani.aios.core.sandbox.CompilerBridge.CompilationResult compileResult =
                            com.ouisani.aios.core.sandbox.CompilerBridge.instance()
                                    .compile(sourceCode, language);

                    if (compileResult.success()) {
                        yield "JIT compilation successful: id=" + compileResult.compileId()
                                + " lang=" + compileResult.language()
                                + " output=" + compileResult.outputPath()
                                + (compileResult.isMock() ? " (MOCK: " + compileResult.mockReason() + ")" : "");
                    } else {
                        yield "JIT compilation failed: " + compileResult.errorMessage();
                    }
                }
                case "execute" -> {
                    String compileId = request.paramString("compile_id");
                    if (compileId == null || compileId.isEmpty())
                        yield "Missing parameter: compile_id";

                    com.ouisani.aios.core.sandbox.CompilerBridge.CompilationResult compileResult =
                            com.ouisani.aios.core.sandbox.CompilerBridge.instance().getResult(compileId);
                    if (compileResult == null)
                        yield "Compilation result not found: " + compileId;

                    // 在 Ring 3 沙箱中执行
                    com.ouisani.aios.core.sandbox.GraalWasmSandbox sandbox =
                            new com.ouisani.aios.core.sandbox.GraalWasmSandbox();
                    sandbox.initContext();
                    com.ouisani.aios.core.sandbox.GraalWasmSandbox.SandboxExecutionResult execResult =
                            sandbox.executeJitArtifact(compileResult);

                    if (execResult.success()) {
                        yield "Execution result: " + execResult.result();
                    } else {
                        yield "Execution failed: " + execResult.error();
                    }
                }
                case "stats" -> com.ouisani.aios.core.sandbox.CompilerBridge.instance().getStatsReport();
                default -> "Unknown jit command: " + subAction;
            };
            return SyscallResponse.ok(result);
        } catch (Exception e) {
            return SyscallResponse.fail("JIT error: " + e.getMessage());
        }
    }

    // ── bin.* Unified User-Space Binary Syscalls ──

    private SyscallResponse handleBin(String agentId, SyscallRequest request) {
        String subAction = request.fullAction().substring("bin.".length());
        try {
            String result = switch (subAction) {
                case "ps" -> CoreUtils.ps();
                case "kill" -> CoreUtils.kill(request.paramString("pid"));
                case "whoami" -> CoreUtils.whoami();
                case "uptime" -> CoreUtils.uptime();
                case "free" -> CoreUtils.free();
                case "install" -> {
                    String pkg = request.paramString("package");
                    if (pkg == null || pkg.isEmpty()) yield "Missing parameter: package";
                    AiosApt.install(pkg);
                    yield "Package '" + pkg + "' installed successfully";
                }
                default -> "Unknown bin command: " + subAction;
            };
            log.info("[User Space] Core utilities and package manager linked to Intent Router. bin.{} dispatched", subAction);
            return SyscallResponse.ok(result);
        } catch (Exception e) {
            return SyscallResponse.fail("bin error: " + e.getMessage());
        }
    }

    // ── Handle Syscalls ──

    private SyscallResponse handleOpen(String agentId, SyscallRequest request) {
        if (objectManager == null) {
            return SyscallResponse.fail("Object manager not configured");
        }

        String path = request.paramString("path");
        if (path == null || path.isEmpty()) {
            return SyscallResponse.fail("Missing parameter: path");
        }

        try {
            int handle = objectManager.openHandle(agentId, path);
            return SyscallResponse.ok(String.valueOf(handle));
        } catch (Exception e) {
            return SyscallResponse.fail(e);
        }
    }

    private SyscallResponse handleRead(String agentId, SyscallRequest request) {
        if (objectManager == null) {
            return SyscallResponse.fail("Object manager not configured");
        }

        Integer handle = request.paramInt("handle", -1);
        if (handle < 0) {
            return SyscallResponse.fail("Missing or invalid parameter: handle");
        }

        try {
            VfsNode node = objectManager.getNodeByHandle(handle);
            String content = node.read();
            return SyscallResponse.ok(content);
        } catch (Exception e) {
            return SyscallResponse.fail(e);
        }
    }

    private SyscallResponse handleClose(String agentId, SyscallRequest request) {
        if (objectManager == null) {
            return SyscallResponse.fail("Object manager not configured");
        }

        Integer handle = request.paramInt("handle", -1);
        if (handle < 0) {
            return SyscallResponse.fail("Missing or invalid parameter: handle");
        }

        try {
            boolean closed = objectManager.closeHandle(handle);
            return closed ? SyscallResponse.ok() : SyscallResponse.fail("Handle already closed or invalid");
        } catch (Exception e) {
            return SyscallResponse.fail(e);
        }
    }

    // ── Utility ──

    private String serializeParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : params.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
            Object v = entry.getValue();
            if (v instanceof Number) {
                sb.append(v);
            } else if (v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append("\"").append(escapeJson(v.toString())).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
