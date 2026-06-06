package com.ouisani.aios.user.sdk;

import com.ouisani.aios.core.syscall.SyscallDispatcher;
import com.ouisani.aios.core.syscall.SyscallRequest;
import com.ouisani.aios.core.syscall.SyscallResponse;
import com.ouisani.aios.core.syscall.schema.LlmPayload;
import com.ouisani.aios.core.syscall.schema.RawPayload;
import com.ouisani.aios.core.syscall.schema.StoragePayload;
import com.ouisani.aios.core.syscall.schema.ToolPayload;
import com.ouisani.aios.core.ipc.SemanticMemoryBlock;
import com.ouisani.aios.core.ipc.SharedMemoryManager;
import com.ouisani.aios.core.ipc.SignalType;
import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
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
        SyscallRequest request = new SyscallRequest("llm", "think", new LlmPayload(prompt));
        SyscallResponse resp = SyscallDispatcher.getInstance().execute(agentId, request);
        return resp.success() ? resp.data() : "[SDK Error] " + resp.errorMessage();
    }

    /**
     * Ask the LLM with a custom system prompt.
     */
    public String think(String agentId, String prompt, String systemPrompt) {
        SyscallRequest request = new SyscallRequest("llm", "think",
                new LlmPayload(prompt, 0.7, 4096));
        SyscallResponse resp = SyscallDispatcher.getInstance().execute(agentId, request);
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
        SyscallRequest request = new SyscallRequest("vfs", "read", new RawPayload(Map.of("path", path)));
        SyscallResponse resp = SyscallDispatcher.getInstance().execute(agentId, request);
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
        SyscallRequest request = new SyscallRequest("vfs", "write",
                new RawPayload(Map.of("path", path, "data", data != null ? data : "")));
        SyscallResponse resp = SyscallDispatcher.getInstance().execute(agentId, request);
        if (!resp.success()) {
            log.warn("[SDK] writeFile failed: agent={}, path={}, error={}", agentId, path, resp.errorMessage());
        }
    }

    // ── Storage (typed payload) ──

    /**
     * Read from a storage path using the standardized StoragePayload.
     */
    public String storageRead(String agentId, String path) {
        SyscallRequest request = new SyscallRequest("storage", "read", StoragePayload.read(path));
        SyscallResponse resp = SyscallDispatcher.getInstance().execute(agentId, request);
        return resp.success() ? resp.data() : "[SDK Error] " + resp.errorMessage();
    }

    /**
     * Write to a storage path using the standardized StoragePayload.
     */
    public void storageWrite(String agentId, String path, String data) {
        SyscallRequest request = new SyscallRequest("storage", "write", StoragePayload.write(path, data));
        SyscallResponse resp = SyscallDispatcher.getInstance().execute(agentId, request);
        if (!resp.success()) {
            log.warn("[SDK] storageWrite failed: agent={}, path={}, error={}", agentId, path, resp.errorMessage());
        }
    }

    // ── Handles ──

    public int openHandle(String agentId, String path) {
        SyscallRequest request = new SyscallRequest("handle", "open", new RawPayload(Map.of("path", path)));
        SyscallResponse resp = SyscallDispatcher.getInstance().execute(agentId, request);
        if (resp.success() && resp.data() != null) {
            try { return Integer.parseInt(resp.data().trim()); }
            catch (NumberFormatException e) { log.warn("[SDK] Invalid handle ID: {}", resp.data()); }
        }
        return -1;
    }

    public String readHandle(String agentId, int handleId) {
        SyscallRequest request = new SyscallRequest("handle", "read", new RawPayload(Map.of("handleId", handleId)));
        SyscallResponse resp = SyscallDispatcher.getInstance().execute(agentId, request);
        return resp.success() ? resp.data() : "[SDK Error] " + resp.errorMessage();
    }

    public void closeHandle(String agentId, int handleId) {
        SyscallRequest request = new SyscallRequest("handle", "close", new RawPayload(Map.of("handleId", handleId)));
        SyscallDispatcher.getInstance().execute(agentId, request);
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
        log.info("[SDK] Agent '{}' calling tool: {} with {} args", agentId, toolName, args != null ? args.size() : 0);
        SyscallRequest request = new SyscallRequest("tool", toolName, new ToolPayload(toolName, args));
        return SyscallDispatcher.getInstance().execute(agentId, request);
    }

    // ── Raw Syscall ──

    /**
     * Dispatch a raw syscall with agent identity.
     */
    public SyscallResponse dispatch(String agentId, String action, Map<String, Object> params) {
        SyscallRequest request = new SyscallRequest(action, params);
        return SyscallDispatcher.getInstance().execute(agentId, request);
    }

    // ════════════════════════════════════════════════════════════════
    //  Shared Memory (SHM IPC) — Neural mmap()
    // ════════════════════════════════════════════════════════════════

    /**
     * Write a string value to a semantic memory block.
     * <p>
     * This is the neural equivalent of {@code mmap() + memcpy()}:
     * the calling agent writes data into a shared memory region
     * that other agents can read without message passing.
     *
     * @param agentId the calling Agent's ID
     * @param blockId the semantic block identifier
     * @param key     the key
     * @param value   the value
     * @return the new version number of the block
     */
    public long shmWrite(String agentId, String blockId, String key, String value) {
        long version = SharedMemoryManager.instance().putSemanticString(blockId, key, value);
        log.debug("[SDK] shmWrite: agent={}, block={}, key={}, version={}", agentId, blockId, key, version);
        return version;
    }

    /**
     * Read a string value from a semantic memory block.
     *
     * @param agentId the calling Agent's ID
     * @param blockId the semantic block identifier
     * @param key     the key
     * @return the value, or null if not found
     */
    public String shmRead(String agentId, String blockId, String key) {
        String value = SharedMemoryManager.instance().getSemanticString(blockId, key);
        log.debug("[SDK] shmRead: agent={}, block={}, key={}, found={}", agentId, blockId, key, value != null);
        return value;
    }

    /**
     * Write a vector embedding to a semantic memory block.
     * <p>
     * This enables "subconscious" knowledge transfer: the writing agent
     * encodes its understanding as a high-dimensional vector, and the
     * reading agent can access it without any text exchange.
     *
     * @param agentId  the calling Agent's ID
     * @param blockId  the semantic block identifier
     * @param key      the vector key
     * @param embedding the float array
     * @return the new version number
     */
    public long shmWriteVector(String agentId, String blockId, String key, float[] embedding) {
        long version = SharedMemoryManager.instance().putSemanticVector(blockId, key, embedding);
        log.debug("[SDK] shmWriteVector: agent={}, block={}, key={}, dims={}, version={}",
                agentId, blockId, key, embedding.length, version);
        return version;
    }

    /**
     * Read a vector embedding from a semantic memory block.
     */
    public float[] shmReadVector(String agentId, String blockId, String key) {
        return SharedMemoryManager.instance().getSemanticVector(blockId, key);
    }

    /**
     * Get or create a SemanticMemoryBlock.
     */
    public SemanticMemoryBlock shmGetBlock(String blockId) {
        return SharedMemoryManager.instance().getOrCreateSemanticBlock(blockId);
    }

    /**
     * Get an existing SemanticMemoryBlock (returns null if not found).
     */
    public SemanticMemoryBlock shmGetBlockIfExists(String blockId) {
        return SharedMemoryManager.instance().getSemanticBlock(blockId);
    }

    // ════════════════════════════════════════════════════════════════
    //  Signal IPC — Hardware-Level Interrupt
    // ════════════════════════════════════════════════════════════════

    /**
     * Send a signal to an agent identified by its PID.
     * <p>
     * This is the AIOS equivalent of {@code kill(pid, signal)}.
     * The signal is enqueued in the target agent's signal queue
     * and will be processed on its next scheduling cycle.
     *
     * @param targetPid the target agent's PID
     * @param signal    the signal type
     */
    public void sendSignal(int targetPid, SignalType signal) {
        TaskScheduler scheduler = VfsManager.instance().getTaskScheduler();
        if (scheduler == null) {
            log.warn("[SDK] Signal send failed: TaskScheduler not configured");
            return;
        }
        AgentTask targetTask = scheduler.getTask(targetPid);
        if (targetTask != null) {
            targetTask.sendSignal(signal);
            log.info("[SDK] Signal sent: signal={}, targetPid={}", signal, targetPid);
        } else {
            log.warn("[SDK] Signal send failed: targetPid={} not found", targetPid);
        }
    }

    /**
     * Send a SIG_CONTEXT_UPDATE signal to an agent by PID.
     * <p>
     * This is the primary IPC mechanism for "shared memory + interrupt"
     * communication. After writing to a SemanticMemoryBlock, the writer
     * agent calls this method to notify the reader agent.
     *
     * @param targetPid the target agent's PID
     */
    public void sendContextUpdate(int targetPid) {
        sendSignal(targetPid, SignalType.SIG_CONTEXT_UPDATE);
    }

    /**
     * Broadcast a SIG_CONTEXT_UPDATE to all agents in a cgroup.
     * <p>
     * This is the AIOS equivalent of {@code kill(-pgid, signal)}:
     * it sends the signal to all agents in the specified process group.
     *
     * @param cgroup the cgroup name (e.g., "agents")
     * @param signal the signal type
     */
    public void broadcastSignal(String cgroup, SignalType signal) {
        TaskScheduler scheduler = VfsManager.instance().getTaskScheduler();
        if (scheduler == null) {
            log.warn("[SDK] Signal broadcast failed: TaskScheduler not configured");
            return;
        }
        int count = 0;
        for (AgentTask task : scheduler.activeTasks().values()) {
            if (cgroup.equals(task.cgroup())) {
                task.sendSignal(signal);
                count++;
            }
        }
        log.info("[SDK] Signal broadcast: signal={}, cgroup={}, recipients={}", signal, cgroup, count);
    }

    // ════════════════════════════════════════════════════════════════
    //  Dynamic Module Loading (insmod / rmmod)
    // ════════════════════════════════════════════════════════════════

    /**
     * sys_insmod: Load a tool into the Agent's active context by semantic query.
     * <p>
     * This is the AIOS equivalent of {@code insmod} / {@code modprobe}:
     * the Agent describes what it needs in natural language, and the
     * kernel finds and loads the best-matching tool.
     * <p>
     * Example: {@code sdk.insmod(agentId, "我需要一个能搜索网页的工具")}
     *
     * @param agentId the calling Agent's ID
     * @param query   natural language tool requirement
     * @return the SyscallResponse containing the loaded tool's JSON Schema
     */
    public SyscallResponse insmod(String agentId, String query) {
        SyscallRequest request = new SyscallRequest("tool", "call",
                new ToolPayload("kernel.insmod", Map.of("query", query)));
        return SyscallDispatcher.getInstance().execute(agentId, request);
    }

    /**
     * sys_insmod: Load a specific tool by name.
     *
     * @param agentId  the calling Agent's ID
     * @param toolName the exact tool name to load
     * @return the SyscallResponse
     */
    public SyscallResponse insmodByName(String agentId, String toolName) {
        SyscallRequest request = new SyscallRequest("tool", "call",
                new ToolPayload("kernel.insmod", Map.of("tool_name", toolName)));
        return SyscallDispatcher.getInstance().execute(agentId, request);
    }

    /**
     * sys_rmmod: Unload a tool from the Agent's active context.
     *
     * @param agentId  the calling Agent's ID
     * @param toolName the tool to unload
     * @return the SyscallResponse
     */
    public SyscallResponse rmmod(String agentId, String toolName) {
        SyscallRequest request = new SyscallRequest("tool", "call",
                new ToolPayload("kernel.rmmod", Map.of("tool_name", toolName)));
        return SyscallDispatcher.getInstance().execute(agentId, request);
    }

    /**
     * sys_lsmod: List all tools currently loaded in the Agent's context.
     */
    public SyscallResponse lsmod(String agentId) {
        SyscallRequest request = new SyscallRequest("tool", "call",
                new ToolPayload("kernel.lsmod"));
        return SyscallDispatcher.getInstance().execute(agentId, request);
    }

    /**
     * sys_modprobe: Search for available tools without loading them.
     *
     * @param agentId the calling Agent's ID
     * @param query   natural language tool requirement
     * @return the SyscallResponse listing matching tools
     */
    public SyscallResponse modprobe(String agentId, String query) {
        SyscallRequest request = new SyscallRequest("tool", "call",
                new ToolPayload("kernel.modprobe", Map.of("query", query)));
        return SyscallDispatcher.getInstance().execute(agentId, request);
    }

    /**
     * Get the Agent's active tool context (for direct access to loaded tools).
     */
    public com.ouisani.aios.core.plugin.AgentToolContext getToolContext(String agentId) {
        return com.ouisani.aios.core.plugin.PluginManager.getInstance().getAgentContext(agentId);
    }
}
