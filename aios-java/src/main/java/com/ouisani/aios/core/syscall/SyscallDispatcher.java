package com.ouisani.aios.core.syscall;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.llm.LlmRouter;
import com.ouisani.aios.core.memory.ContextInjector;
import com.ouisani.aios.core.plugin.PluginManager;
import com.ouisani.aios.core.security.ObjectManager;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import com.ouisani.aios.user.bin.AiosApt;
import com.ouisani.aios.user.bin.CoreUtils;
import com.ouisani.aios.vfs.MutableFileNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Central Syscall Dispatcher — the sole gateway between Agents and the AIOS kernel.
 * <p>
 * Routes {@link SyscallRequest}s to the appropriate backend service
 * (LlmRouter, VfsManager, ObjectManager, etc.) and wraps results
 * into {@link SyscallResponse}s. All routing is logged via ETW
 * for zero-overhead observability.
 *
 * <h3>Supported actions:</h3>
 * <ul>
 *   <li>{@code llm.think} — invoke LLM with a prompt</li>
 *   <li>{@code llm.think_with_history} — invoke LLM with conversation history</li>
 *   <li>{@code vfs.read} — read from a VFS path</li>
 *   <li>{@code vfs.write} — write to a VFS path</li>
 *   <li>{@code handle.open} — open a VFS handle via ObjectManager</li>
 *   <li>{@code handle.read} — read via a handle</li>
 *   <li>{@code handle.close} — close a handle</li>
 *   <li>{@code tool.*} — dynamically registered WASM plugin (via PluginManager)</li>
 *   <li>{@code coreutils.ps} — list all processes</li>
 *   <li>{@code coreutils.kill} — kill a process by PID</li>
 *   <li>{@code coreutils.whoami} — show current agent identity</li>
 *   <li>{@code coreutils.uptime} — show system uptime</li>
 *   <li>{@code coreutils.free} — show token/memory usage</li>
 *   <li>{@code apt.install} — install a WASM plugin</li>
 *   <li>{@code apt.remove} — remove a WASM plugin</li>
 *   <li>{@code apt.list} — list installed plugins</li>
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

    private SyscallDispatcher() {}

    public void configure(LlmRouter llmRouter, VfsManager vfsManager, ObjectManager objectManager) {
        this.llmRouter = llmRouter;
        this.vfsManager = vfsManager;
        this.objectManager = objectManager;
        log.info("[Syscall Dispatcher] Configured: llmRouter={}, vfsManager={}, objectManager={}",
                llmRouter != null, vfsManager != null, objectManager != null);
    }

    /**
     * Execute a system call on behalf of an Agent.
     *
     * @param agentId the agent issuing the syscall
     * @param request the syscall request
     * @return the syscall response
     */
    public SyscallResponse execute(String agentId, SyscallRequest request) {
        long startNanos = System.nanoTime();

        log.info("[Syscall Dispatcher] Intercepted action '{}' from Agent '{}'",
                request.action(), agentId);

        SemanticEtw.getInstance().logEvent("SYSCALL", "ENTER",
                "agent=" + agentId + " action=" + request.action());

        SyscallResponse response;
        try {
            // Dynamic tool.* routing: forward to PluginManager for WASM execution
            if (request.action().startsWith("tool.")) {
                response = handleToolPlugin(agentId, request);
            } else if (request.action().startsWith("coreutils.")) {
                response = handleCoreUtils(agentId, request);
            } else if (request.action().startsWith("apt.")) {
                response = handleApt(agentId, request);
            } else if (request.action().startsWith("bin.")) {
                response = handleBin(agentId, request);
            } else {
                response = switch (request.action()) {
                    case "llm.think" -> handleLlmThink(agentId, request);
                    case "llm.think_with_history" -> handleLlmThinkWithHistory(agentId, request);
                    case "vfs.read" -> handleVfsRead(agentId, request);
                    case "vfs.write" -> handleVfsWrite(agentId, request);
                    case "handle.open" -> handleOpen(agentId, request);
                    case "handle.read" -> handleRead(agentId, request);
                    case "handle.close" -> handleClose(agentId, request);
                    default -> throw new SyscallException(request.action());
                };
            }
        } catch (SyscallException e) {
            response = SyscallResponse.fail(e);
        } catch (SecurityException e) {
            response = SyscallResponse.fail("SECURITY: " + e.getMessage());
        } catch (Exception e) {
            response = SyscallResponse.fail(e);
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        SemanticEtw.getInstance().logEvent("SYSCALL", "EXIT",
                "agent=" + agentId + " action=" + request.action()
                + " success=" + response.success()
                + " latencyMs=" + elapsedMs);

        log.info("[Syscall Dispatcher] Completed action '{}' for Agent '{}': success={}, latency={}ms",
                request.action(), agentId, response.success(), elapsedMs);

        return response;
    }

    // ── LLM Syscalls ──

    private SyscallResponse handleLlmThink(String agentId, SyscallRequest request) {
        if (llmRouter == null) {
            return SyscallResponse.fail("LLM router not configured");
        }

        String prompt = request.paramString("prompt");
        String systemPrompt = request.paramString("system_prompt");

        if (prompt == null || prompt.isEmpty()) {
            return SyscallResponse.fail("Missing parameter: prompt");
        }

        try {
            // Transparent context injection: augment prompt with Vector Memory
            String augmentedPrompt = ContextInjector.getInstance().augmentPrompt(prompt);

            String result = llmRouter.think(augmentedPrompt, systemPrompt != null ? systemPrompt : "");
            return SyscallResponse.ok(result);
        } catch (Exception e) {
            return SyscallResponse.fail(e);
        }
    }

    private SyscallResponse handleLlmThinkWithHistory(String agentId, SyscallRequest request) {
        if (llmRouter == null) {
            return SyscallResponse.fail("LLM router not configured");
        }

        String prompt = request.paramString("prompt");
        String systemPrompt = request.paramString("system_prompt");

        if (prompt == null) {
            return SyscallResponse.fail("Missing parameter: prompt");
        }

        try {
            // Transparent context injection: augment prompt with Vector Memory
            String augmentedPrompt = ContextInjector.getInstance().augmentPrompt(prompt);

            var messages = java.util.List.of(new LlmProvider.ChatMessage("user", augmentedPrompt));
            String result = llmRouter.thinkWithHistory(messages, systemPrompt != null ? systemPrompt : "");
            return SyscallResponse.ok(result);
        } catch (Exception e) {
            return SyscallResponse.fail(e);
        }
    }

    // ── VFS Syscalls ──

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
        // SDK sends "data", but also accept "payload" for backward compatibility
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
                // Auto-create a MutableFileNode if the path doesn't exist
                // (like creating a file in a directory)
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

    // ── Dynamic Tool Plugin Syscalls ──

    private SyscallResponse handleToolPlugin(String agentId, SyscallRequest request) {
        PluginManager pluginManager = PluginManager.getInstance();
        String action = request.action();

        if (!pluginManager.hasPlugin(action)) {
            return SyscallResponse.fail("Plugin not registered: " + action);
        }

        // Serialize parameters as JSON string for the WASM module
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
        String subAction = request.action().substring("coreutils.".length());
        try {
            String result = CoreUtils.dispatch(subAction, request.params());
            return SyscallResponse.ok(result);
        } catch (Exception e) {
            return SyscallResponse.fail("CoreUtils error: " + e.getMessage());
        }
    }

    // ── APT (Package Manager) Syscalls ──

    private SyscallResponse handleApt(String agentId, SyscallRequest request) {
        String subAction = request.action().substring("apt.".length());
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

    // ── bin.* Unified User-Space Binary Syscalls ──

    private SyscallResponse handleBin(String agentId, SyscallRequest request) {
        String subAction = request.action().substring("bin.".length());
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
}
