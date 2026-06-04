package com.ouisani.aios.user.sdk;

import com.ouisani.aios.core.syscall.SyscallDispatcher;
import com.ouisani.aios.core.syscall.SyscallRequest;
import com.ouisani.aios.core.syscall.SyscallResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * AIOS User Space SDK — elegant high-level API that wraps the
 * low-level SyscallDispatcher so Agent developers never need to
 * touch raw syscalls.
 * <p>
 * All methods require an {@code agentId} parameter to identify the
 * calling Agent in the kernel's syscall boundary.
 * <p>
 * Usage:
 * <pre>
 *   AiosSdk sdk = AiosSdk.getInstance();
 *   String answer = sdk.think("agent_1", "What is the meaning of life?");
 *   String screen = sdk.readFile("agent_1", "/dev/gui/dom");
 *   sdk.writeFile("agent_1", "/dev/gui/action", "{\"action\":\"click\",\"id\":\"btn_1\"}");
 * </pre>
 */
public final class AiosSdk {

    private static final Logger log = LoggerFactory.getLogger(AiosSdk.class);

    private static final class Holder {
        static final AiosSdk INSTANCE = new AiosSdk();
    }

    public static AiosSdk getInstance() {
        return Holder.INSTANCE;
    }

    private AiosSdk() {
        log.info("[SDK] User-space SDK initialized. Syscall boundary established.");
        System.out.println("  ✓ [SDK] User-space SDK initialized. Syscall boundary established.");
    }

    // ── LLM ──

    /**
     * Ask the LLM a question. Transparently augmented by ContextInjector
     * with Vector Memory background knowledge.
     *
     * @param agentId the calling Agent's ID
     * @param prompt  the user prompt
     * @return the LLM response text
     */
    public String think(String agentId, String prompt) {
        SyscallResponse resp = dispatch(agentId, "llm.think", Map.of("prompt", prompt));
        return resp.success() ? resp.data() : "[SDK Error] " + resp.errorMessage();
    }

    /**
     * Ask the LLM with a custom system prompt.
     */
    public String think(String agentId, String prompt, String systemPrompt) {
        SyscallResponse resp = dispatch(agentId, "llm.think", Map.of(
                "prompt", prompt,
                "system_prompt", systemPrompt != null ? systemPrompt : ""
        ));
        return resp.success() ? resp.data() : "[SDK Error] " + resp.errorMessage();
    }

    // ── VFS ──

    /**
     * Read from a VFS path.
     *
     * @param agentId the calling Agent's ID
     * @param path    the VFS path
     * @return the content read from the VFS node
     */
    public String readFile(String agentId, String path) {
        SyscallResponse resp = dispatch(agentId, "vfs.read", Map.of("path", path));
        return resp.success() ? resp.data() : "[SDK Error] " + resp.errorMessage();
    }

    /**
     * Write data to a VFS path.
     *
     * @param agentId the calling Agent's ID
     * @param path    the VFS path
     * @param data    the data to write
     */
    public void writeFile(String agentId, String path, String data) {
        SyscallResponse resp = dispatch(agentId, "vfs.write", Map.of(
                "path", path,
                "data", data != null ? data : ""
        ));
        if (!resp.success()) {
            log.warn("[SDK] writeFile failed: agent={}, path={}, error={}", agentId, path, resp.errorMessage());
        }
    }

    // ── Handles ──

    public int openHandle(String agentId, String path) {
        SyscallResponse resp = dispatch(agentId, "handle.open", Map.of("path", path));
        if (resp.success() && resp.data() != null) {
            try { return Integer.parseInt(resp.data().trim()); }
            catch (NumberFormatException e) { log.warn("[SDK] Invalid handle ID: {}", resp.data()); }
        }
        return -1;
    }

    public String readHandle(String agentId, int handleId) {
        SyscallResponse resp = dispatch(agentId, "handle.read", Map.of("handleId", handleId));
        return resp.success() ? resp.data() : "[SDK Error] " + resp.errorMessage();
    }

    public void closeHandle(String agentId, int handleId) {
        dispatch(agentId, "handle.close", Map.of("handleId", handleId));
    }

    // ── Dynamic Tools ──

    /**
     * Call a dynamically registered WASM plugin tool.
     *
     * @param agentId  the calling Agent's ID
     * @param toolName the tool name (without "tool." prefix)
     * @param args     the arguments
     * @return the syscall response
     */
    public SyscallResponse callTool(String agentId, String toolName, Map<String, Object> args) {
        String action = "tool." + toolName;
        log.info("[SDK] Agent '{}' calling tool: {} with {} args", agentId, action, args != null ? args.size() : 0);
        return dispatch(agentId, action, args != null ? args : Map.of());
    }

    // ── Raw Syscall ──

    /**
     * Dispatch a raw syscall with agent identity.
     */
    public SyscallResponse dispatch(String agentId, String action, Map<String, Object> params) {
        SyscallRequest request = new SyscallRequest(action, params);
        return SyscallDispatcher.getInstance().execute(agentId, request);
    }
}
