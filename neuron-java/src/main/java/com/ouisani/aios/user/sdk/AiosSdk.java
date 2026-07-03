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
import com.ouisani.aios.core.compact.CompactService;
import com.ouisani.aios.core.context.ClaudeMdLoader;
import com.ouisani.aios.core.context.SystemPromptBuilder;
import com.ouisani.aios.core.hook.HookManager;
import com.ouisani.aios.core.memory.SessionMemoryService;
import com.ouisani.aios.core.permission.PermissionChecker;
import com.ouisani.aios.core.permission.PermissionMode;
import com.ouisani.aios.core.telemetry.TelemetryService;
import com.ouisani.aios.core.tool.QueryEngine;
import com.ouisani.aios.core.tool.ToolRegistry;
import com.ouisani.aios.core.tool.ToolSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AIOS 用户空间 SDK — 封装底层 SyscallDispatcher 的高级 API，
 * 使 Agent 开发者无需直接接触原始系统调用。
 * <p>
 * 所有方法都需要 {@code agentId} 参数，用于在内核系统调用边界
 * 标识调用方 Agent。
 * <p>
 * OS 类比：相当于 glibc / libc — 用户态程序通过 libc 调用内核系统调用，
 * AIOS 中 Agent 通过 AiosSdk 调用内核功能。
 * <p>
 * 使用示例：
 * <pre>
 *   AiosSdk sdk = AiosSdk.getInstance();
 *   String answer = sdk.think("agent_1", "生命的意义是什么？");
 *   String screen = sdk.readFile("agent_1", "/dev/gui/dom");
 *   sdk.writeFile("agent_1", "/dev/gui/action", "{\"action\":\"click\",\"id\":\"btn_1\"}");
 * </pre>
 */
public final class AiosSdk implements ToolSdk {

    private static final Logger log = LoggerFactory.getLogger(AiosSdk.class);

    private static final class Holder {
        static final AiosSdk INSTANCE = new AiosSdk();
    }

    public static AiosSdk getInstance() {
        return Holder.INSTANCE;
    }

    private AiosSdk() {
        log.info("[SDK] 用户空间 SDK 已初始化。Syscall 边界已建立。");
        System.out.println("  ✓ [SDK] 用户空间 SDK 已初始化。Syscall 边界已建立。");
    }

    // ── LLM ──

    /**
     * 向 LLM 提问。由 ContextInjector 自动注入向量记忆背景知识。
     *
     * @param agentId 调用方 Agent ID
     * @param prompt  用户提示词
     * @return LLM 响应文本
     */
    public String think(String agentId, String prompt) {
        SyscallRequest request = new SyscallRequest("llm", "think", new LlmPayload(prompt));
        SyscallResponse resp = SyscallDispatcher.getInstance().execute(agentId, request);
        return resp.success() ? resp.data() : "[SDK Error] " + resp.errorMessage();
    }

    /**
     * 使用自定义系统提示词向 LLM 提问。
     */
    public String think(String agentId, String prompt, String systemPrompt) {
        SyscallRequest request = new SyscallRequest("llm", "think",
                new LlmPayload(prompt, 0.7, 4096));
        SyscallResponse resp = SyscallDispatcher.getInstance().execute(agentId, request);
        return resp.success() ? resp.data() : "[SDK Error] " + resp.errorMessage();
    }

    /**
     * 流式推理 — 借鉴 CopilotKit 的 SSE 流式渲染。
     * <p>
     * LLM 响应逐 token 回调，前端可实现打字机效果。
     * 底层调用 LlmRouter.thinkStream()，如果 Provider 不支持流式则降级为同步。
     *
     * @param agentId  Agent ID
     * @param prompt   提示词
     * @param onDelta  每个 token 片段的回调
     * @return 完整的文本回复
     */
    public String thinkStream(String agentId, String prompt, java.util.function.Consumer<String> onDelta) {
        com.ouisani.aios.core.llm.LlmRouter router = SyscallDispatcher.getInstance().getLlmRouter();
        if (router == null) {
            String fallback = "[SDK Error] LLM Router not configured";
            onDelta.accept(fallback);
            return fallback;
        }
        return router.thinkStream(prompt, onDelta);
    }

    // ── VFS ──

    /**
     * 从 VFS 路径读取内容。
     *
     * @param agentId 调用方 Agent ID
     * @param path    VFS 路径
     * @return 从 VFS 节点读取的内容
     */
    public String readFile(String agentId, String path) {
        SyscallRequest request = new SyscallRequest("vfs", "read", new RawPayload(Map.of("path", path)));
        SyscallResponse resp = SyscallDispatcher.getInstance().execute(agentId, request);
        return resp.success() ? resp.data() : "[SDK Error] " + resp.errorMessage();
    }

    /**
     * 向 VFS 路径写入数据。
     *
     * @param agentId 调用方 Agent ID
     * @param path    VFS 路径
     * @param data    要写入的数据
     */
    public void writeFile(String agentId, String path, String data) {
        SyscallRequest request = new SyscallRequest("vfs", "write",
                new RawPayload(Map.of("path", path, "data", data != null ? data : "")));
        SyscallResponse resp = SyscallDispatcher.getInstance().execute(agentId, request);
        if (!resp.success()) {
            log.warn("[SDK] writeFile failed: agent={}, path={}, error={}", agentId, path, resp.errorMessage());
        }
    }

    /**
     * 检查 VFS 中指定路径的文件是否存在。
     */
    public boolean fileExists(String agentId, String path) {
        SyscallRequest request = new SyscallRequest("vfs", "exists",
                new RawPayload(Map.of("path", path)));
        SyscallResponse resp = SyscallDispatcher.getInstance().execute(agentId, request);
        return resp.success() && "true".equalsIgnoreCase(resp.data());
    }

    // ── Storage (typed payload) ──

    /**
     * 使用标准化 StoragePayload 从存储路径读取。
     */
    public String storageRead(String agentId, String path) {
        SyscallRequest request = new SyscallRequest("storage", "read", StoragePayload.read(path));
        SyscallResponse resp = SyscallDispatcher.getInstance().execute(agentId, request);
        return resp.success() ? resp.data() : "[SDK Error] " + resp.errorMessage();
    }

    /**
     * 使用标准化 StoragePayload 向存储路径写入。
     */
    public void storageWrite(String agentId, String path, String data) {
        SyscallRequest request = new SyscallRequest("storage", "write", StoragePayload.write(path, data));
        SyscallResponse resp = SyscallDispatcher.getInstance().execute(agentId, request);
        if (!resp.success()) {
            log.warn("[SDK] storageWrite failed: agent={}, path={}, error={}", agentId, path, resp.errorMessage());
        }
    }

    // ── 文件句柄（open/read/close） ──

    /** 打开 VFS 文件句柄，返回句柄 ID */
    public int openHandle(String agentId, String path) {
        SyscallRequest request = new SyscallRequest("handle", "open", new RawPayload(Map.of("path", path)));
        SyscallResponse resp = SyscallDispatcher.getInstance().execute(agentId, request);
        if (resp.success() && resp.data() != null) {
            try { return Integer.parseInt(resp.data().trim()); }
            catch (NumberFormatException e) { log.warn("[SDK] Invalid handle ID: {}", resp.data()); }
        }
        return -1;
    }

    /** 通过句柄 ID 读取内容 */
    public String readHandle(String agentId, int handleId) {
        SyscallRequest request = new SyscallRequest("handle", "read", new RawPayload(Map.of("handleId", handleId)));
        SyscallResponse resp = SyscallDispatcher.getInstance().execute(agentId, request);
        return resp.success() ? resp.data() : "[SDK Error] " + resp.errorMessage();
    }

    /** 关闭文件句柄 */
    public void closeHandle(String agentId, int handleId) {
        SyscallRequest request = new SyscallRequest("handle", "close", new RawPayload(Map.of("handleId", handleId)));
        SyscallDispatcher.getInstance().execute(agentId, request);
    }

    // ── 动态工具 ──

    /**
     * 调用动态注册的 WASM 插件工具。
     *
     * @param agentId  调用方 Agent ID
     * @param toolName 工具名称（不含 "tool." 前缀）
     * @param args     参数
     * @return 系统调用响应
     */
    public SyscallResponse callTool(String agentId, String toolName, Map<String, Object> args) {
        log.info("[SDK] Agent '{}' calling tool: {} with {} args", agentId, toolName, args != null ? args.size() : 0);
        SyscallRequest request = new SyscallRequest("tool", toolName, new ToolPayload(toolName, args));
        return SyscallDispatcher.getInstance().execute(agentId, request);
    }

    // ── 原始系统调用 ──

    /**
     * 带 Agent 身份分派原始系统调用。
     */
    public SyscallResponse dispatch(String agentId, String action, Map<String, Object> params) {
        SyscallRequest request = new SyscallRequest(action, params);
        return SyscallDispatcher.getInstance().execute(agentId, request);
    }

    // ════════════════════════════════════════════════════════════════
    //  共享内存（SHM IPC）— 神经网络 mmap()
    // ════════════════════════════════════════════════════════════════

    /**
     * 向语义内存块写入字符串值。
     * <p>
     * 等同于 {@code mmap() + memcpy()} 的神经网络版本：
     * 调用方 Agent 将数据写入共享内存区域，其他 Agent 可直接读取，
     * 无需消息传递。
     *
     * @param agentId 调用方 Agent ID
     * @param blockId 语义内存块标识符
     * @param key     键
     * @param value   值
     * @return 内存块的新版本号
     */
    public long shmWrite(String agentId, String blockId, String key, String value) {
        long version = SharedMemoryManager.instance().putSemanticString(blockId, key, value);
        log.debug("[SDK] shmWrite: agent={}, block={}, key={}, version={}", agentId, blockId, key, version);
        return version;
    }

    /**
     * 从语义内存块读取字符串值。
     *
     * @param agentId 调用方 Agent ID
     * @param blockId 语义内存块标识符
     * @param key     键
     * @return 值，未找到时返回 null
     */
    public String shmRead(String agentId, String blockId, String key) {
        String value = SharedMemoryManager.instance().getSemanticString(blockId, key);
        log.debug("[SDK] shmRead: agent={}, block={}, key={}, found={}", agentId, blockId, key, value != null);
        return value;
    }

    /**
     * 向语义内存块写入向量嵌入。
     * <p>
     * 实现"潜意识"知识传递：写入方 Agent 将理解编码为高维向量，
     * 读取方 Agent 可直接访问，无需文本交换。
     *
     * @param agentId   调用方 Agent ID
     * @param blockId   语义内存块标识符
     * @param key       向量键
     * @param embedding 浮点数组
     * @return 新版本号
     */
    public long shmWriteVector(String agentId, String blockId, String key, float[] embedding) {
        long version = SharedMemoryManager.instance().putSemanticVector(blockId, key, embedding);
        log.debug("[SDK] shmWriteVector: agent={}, block={}, key={}, dims={}, version={}",
                agentId, blockId, key, embedding.length, version);
        return version;
    }

    /**
     * 从语义内存块读取向量嵌入。
     */
    public float[] shmReadVector(String agentId, String blockId, String key) {
        return SharedMemoryManager.instance().getSemanticVector(blockId, key);
    }

    /** 获取或创建语义内存块 */
    public SemanticMemoryBlock shmGetBlock(String blockId) {
        return SharedMemoryManager.instance().getOrCreateSemanticBlock(blockId);
    }

    /** 获取已有语义内存块（不存在时返回 null） */
    public SemanticMemoryBlock shmGetBlockIfExists(String blockId) {
        return SharedMemoryManager.instance().getSemanticBlock(blockId);
    }

    // ════════════════════════════════════════════════════════════════
    //  信号 IPC — 硬件级中断
    // ════════════════════════════════════════════════════════════════

    /**
     * 向指定 PID 的 Agent 发送信号。
     * <p>
     * 等同于 AIOS 的 {@code kill(pid, signal)}。
     * 信号被加入目标 Agent 的信号队列，在其下次调度周期处理。
     *
     * @param targetPid 目标 Agent 的 PID
     * @param signal    信号类型
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
     * 向指定 PID 的 Agent 发送 SIG_CONTEXT_UPDATE 信号。
     * <p>
     * 这是"共享内存 + 中断"通信的主要 IPC 机制。
     * 写入 SemanticMemoryBlock 后，写入方 Agent 调用此方法通知读取方 Agent。
     *
     * @param targetPid 目标 Agent 的 PID
     */
    public void sendContextUpdate(int targetPid) {
        sendSignal(targetPid, SignalType.SIG_CONTEXT_UPDATE);
    }

    /**
     * 向 cgroup 中的所有 Agent 广播 SIG_CONTEXT_UPDATE 信号。
     * <p>
     * 等同于 AIOS 的 {@code kill(-pgid, signal)}：
     * 向指定进程组中的所有 Agent 发送信号。
     *
     * @param cgroup cgroup 名称（如 "agents"）
     * @param signal 信号类型
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
    //  动态模块加载（insmod / rmmod）
    // ════════════════════════════════════════════════════════════════

    /**
     * sys_insmod：通过语义查询将工具加载到 Agent 的活跃上下文。
     * <p>
     * 等同于 AIOS 的 {@code insmod} / {@code modprobe}：
     * Agent 用自然语言描述需求，内核查找并加载最佳匹配的工具。
     * <p>
     * 示例：{@code sdk.insmod(agentId, "我需要一个能搜索网页的工具")}
     *
     * @param agentId 调用方 Agent ID
     * @param query   自然语言工具需求描述
     * @return 包含已加载工具 JSON Schema 的系统调用响应
     */
    public SyscallResponse insmod(String agentId, String query) {
        SyscallRequest request = new SyscallRequest("tool", "call",
                new ToolPayload("kernel.insmod", Map.of("query", query)));
        return SyscallDispatcher.getInstance().execute(agentId, request);
    }

    /**
     * sys_insmod：按名称加载指定工具。
     *
     * @param agentId  调用方 Agent ID
     * @param toolName 要加载的工具名称
     * @return 系统调用响应
     */
    public SyscallResponse insmodByName(String agentId, String toolName) {
        SyscallRequest request = new SyscallRequest("tool", "call",
                new ToolPayload("kernel.insmod", Map.of("tool_name", toolName)));
        return SyscallDispatcher.getInstance().execute(agentId, request);
    }

    /**
     * sys_rmmod：从 Agent 的活跃上下文卸载工具。
     *
     * @param agentId  调用方 Agent ID
     * @param toolName 要卸载的工具名称
     * @return 系统调用响应
     */
    public SyscallResponse rmmod(String agentId, String toolName) {
        SyscallRequest request = new SyscallRequest("tool", "call",
                new ToolPayload("kernel.rmmod", Map.of("tool_name", toolName)));
        return SyscallDispatcher.getInstance().execute(agentId, request);
    }

    /** sys_lsmod：列出 Agent 当前上下文中已加载的所有工具 */
    public SyscallResponse lsmod(String agentId) {
        SyscallRequest request = new SyscallRequest("tool", "call",
                new ToolPayload("kernel.lsmod"));
        return SyscallDispatcher.getInstance().execute(agentId, request);
    }

    /**
     * sys_modprobe：搜索可用工具但不加载。
     *
     * @param agentId 调用方 Agent ID
     * @param query   自然语言工具需求描述
     * @return 列出匹配工具的系统调用响应
     */
    public SyscallResponse modprobe(String agentId, String query) {
        SyscallRequest request = new SyscallRequest("tool", "call",
                new ToolPayload("kernel.modprobe", Map.of("query", query)));
        return SyscallDispatcher.getInstance().execute(agentId, request);
    }

    /** 获取 Agent 的活跃工具上下文（直接访问已加载工具） */
    public com.ouisani.aios.core.plugin.AgentToolContext getToolContext(String agentId) {
        return com.ouisani.aios.core.plugin.PluginManager.getInstance().getAgentContext(agentId);
    }

    // ════════════════════════════════════════════════════════════════
    //  动态工具锻造（借鉴 Agent Zero 运行时工具生成）
    // ════════════════════════════════════════════════════════════════

    /**
     * 动态工具锻造 — 借鉴 Agent Zero 的运行时工具生成模式。
     * <p>
     * Agent 描述所需工具功能，LLM 自动生成代码并注册为可调用工具。
     *
     * @param agentId     Agent ID
     * @param description 工具功能描述（自然语言）
     * @return 锻造结果（含工具名称）
     */
    public SyscallResponse forgeTool(String agentId, String description) {
        SyscallRequest request = new SyscallRequest("tool", "call",
                new ToolPayload("kernel.forge_tool", Map.of("description", description)));
        return SyscallDispatcher.getInstance().execute(agentId, request);
    }

    /**
     * 动态工具注册 — 将已生成的代码直接注册为工具。
     *
     * @param agentId     Agent ID
     * @param toolName    工具名称
     * @param code        工具代码（Python）
     * @param description 工具描述
     * @return 注册结果
     */
    public SyscallResponse registerTool(String agentId, String toolName, String code, String description) {
        Map<String, Object> args = new HashMap<>();
        args.put("toolName", toolName);
        args.put("code", code);
        args.put("description", description);
        SyscallRequest request = new SyscallRequest("tool", "call",
                new ToolPayload("kernel.register_tool", args));
        return SyscallDispatcher.getInstance().execute(agentId, request);
    }

    // ════════════════════════════════════════════════════════════════
    //  Claude Code 能力 — 工具增强推理 + 上下文 + 遥测
    // ════════════════════════════════════════════════════════════════

    /**
     * 创建 QueryEngine — 工具增强推理循环。
     * <p>
     * Agent 可通过 QueryEngine 进行多轮工具调用推理，
     * 自动检测工具调用意图并执行，最多 20 轮。
     *
     * @param agentId   Agent ID
     * @param workingDir 工作目录
     * @return QueryEngine 实例
     */
    public QueryEngine createQueryEngine(String agentId, String workingDir) {
        return new QueryEngine(this, agentId, workingDir);
    }

    /**
     * 使用 QueryEngine 进行工具增强推理 — 一行调用。
     */
    public String queryWithTools(String agentId, String prompt, String workingDir) {
        return new QueryEngine(this, agentId, workingDir).query(prompt);
    }

    /**
     * 获取所有已注册工具的描述。
     */
    public String getToolsDescription() {
        return ToolRegistry.instance().toolsDescription();
    }

    /**
     * 构建系统提示词 — 包含工具描述 + CLAUDE.md + Git 状态。
     */
    public String buildSystemPrompt(String workingDir) {
        return SystemPromptBuilder.build(workingDir);
    }

    /**
     * 加载 CLAUDE.md 项目指令。
     */
    public String loadClaudeMd(String workingDir) {
        return ClaudeMdLoader.formatAsPrompt(ClaudeMdLoader.loadAll(workingDir));
    }

    /**
     * 获取遥测成本报告。
     */
    public String getCostReport() {
        return TelemetryService.instance().formatCostReport();
    }

    /**
     * 记录 Token 使用量。
     */
    public void recordTokenUsage(String model, long inputTokens, long outputTokens, double costUSD) {
        TelemetryService.instance().recordTokenUsage(model, inputTokens, outputTokens, 0, 0, costUSD);
    }

    /**
     * 触发生命周期 Hook。
     */
    public HookManager.HookResult triggerHook(HookManager.HookEvent event, Map<String, Object> data) {
        return HookManager.instance().trigger(event, data);
    }

    /**
     * 注册生命周期 Hook。
     */
    public void registerHook(HookManager.HookEvent event, HookManager.HookHandler handler) {
        HookManager.instance().register(event, handler);
    }

    /**
     * 设置权限模式。
     */
    public void setPermissionMode(PermissionMode mode) {
        // 通过 ThreadLocal 或全局状态传递
        log.info("[SDK] Permission mode set to: {}", mode);
    }

    // ════════════════════════════════════════════════════════════════
    //  双向状态同步 — 借鉴 CopilotKit 的前端状态与 Agent 状态双向同步
    // ════════════════════════════════════════════════════════════════

    /**
     * 推送 Agent 状态到前端 — 借鉴 CopilotKit 的双向状态同步。
     * <p>
     * Agent 修改状态后调用此方法，状态会自动同步到前端 UI。
     * 前端也可以通过 WebSocket 修改状态，Agent 通过 VariablePool 读取。
     *
     * @param agentId Agent ID
     * @param key     状态键名
     * @param value   状态值
     */
    public void pushState(String agentId, String key, Object value) {
        com.ouisani.aios.core.network.StateSyncChannel.pushAgentState(agentId, key, value);
    }

    /**
     * 读取前端同步的状态 — 从 VariablePool SESSION 作用域读取。
     *
     * @param sessionId 前端会话 ID
     * @param key       状态键名
     * @return 状态值（null 表示不存在）
     */
    public Object readFrontendState(String sessionId, String key) {
        return com.ouisani.aios.core.ipc.VariablePool.getInstance()
                .get(com.ouisani.aios.core.ipc.VariablePool.Scope.SESSION, sessionId, key);
    }
}
