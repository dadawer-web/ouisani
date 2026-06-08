package com.ouisani.aios.user.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ouisani.aios.core.llm.InstructionDecoder;
import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.llm.LlmRouter;
import com.ouisani.aios.core.network.AppGateway;
import com.ouisani.aios.core.syscall.SyscallDispatcher;
import com.ouisani.aios.core.syscall.SyscallRequest;
import com.ouisani.aios.core.syscall.SyscallResponse;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import com.ouisani.aios.user.apps.omnifactory.OmniMotherAgent;
import com.ouisani.aios.user.apps.omnifactory.TopologyCompiler;
import com.ouisani.aios.user.apps.omnifactory.WorkflowManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 意图路由器 — LLM 语义路由，零硬编码匹配。
 * <p>
 * 使用 LLM 将自然语言意图分类为枚举类型，再通过 switch-case 分发到对应的系统组件。
 * 绝不使用 startsWith/equals/正则表达式等硬编码匹配。
 * <p>
 * 支持的意图枚举：
 * <ul>
 *   <li>{@code SYSTEM_COMMAND} — 系统命令（进程管理、状态查询等），路由到 SyscallDispatcher</li>
 *   <li>{@code WORKFLOW_DEPLOY} — 工作流部署（创建/编译/部署智能体网络），路由到 TopologyCompiler + OmniMotherAgent</li>
 *   <li>{@code SEMANTIC_SEARCH} — 语义搜索（知识查询、文档检索），路由到 AppGateway</li>
 *   <li>{@code CHAT} — 通用对话（闲聊、解释、建议），路由到 LlmRouter 直接回复</li>
 * </ul>
 *
 * <h3>OS 类比</h3>
 * 相当于 Linux 的中断向量表 — 硬件中断号 → 对应的 ISR 处理函数，
 * 但这里用 LLM 替代了硬件中断控制器，用语义理解替代了电信号。
 */
public final class IntentRouter {

    private static final Logger log = LoggerFactory.getLogger(IntentRouter.class);

    /**
     * 意图枚举 — LLM 路由分类的唯一切面。
     * <p>
     * LLM 必须输出这四个枚举之一，不允许其他输出。
     */
    public enum IntentType {
        /** 系统命令 — 进程管理、状态查询、文件操作等 */
        SYSTEM_COMMAND,
        /** 工作流部署 — 创建/编译/部署智能体网络 */
        WORKFLOW_DEPLOY,
        /** 语义搜索 — 知识查询、文档检索、互联网搜索 */
        SEMANTIC_SEARCH,
        /** 通用对话 — 闲聊、解释、建议 */
        CHAT
    }

    /** LLM 语义路由的系统提示词 — 强制输出纯枚举名 */
    private static final String ROUTING_PROMPT =
            "分析用户输入，输出用户的意图类型，必须是以下枚举之一：\n" +
            "[SYSTEM_COMMAND, WORKFLOW_DEPLOY, SEMANTIC_SEARCH, CHAT]\n\n" +
            "定义：\n" +
            "- SYSTEM_COMMAND: 系统管理命令（查看进程、杀进程、查内存、查注册表、系统状态等）\n" +
            "- WORKFLOW_DEPLOY: 创建、部署、编译智能体工作流（创建工作流、部署爬虫、生成DAG等）\n" +
            "- SEMANTIC_SEARCH: 搜索知识、查询文档、互联网检索\n" +
            "- CHAT: 通用对话、闲聊、解释、建议\n\n" +
            "极其重要：只输出枚举名，不要输出任何其他文字！";

    /** Syscall 翻译的系统提示词 — 将自然语言翻译为结构化系统调用 */
    private static final String SYSCALL_PROMPT =
            "你是一个顶级的 AIOS (通用人工智能操作系统) 内核命令翻译器。" +
            "不要使用任何客套话，不要回答普通问题，你只输出合法的 JSON 格式的 SyscallRequest。" +
            "【极其重要的系统设定】：" +
            "1. 我们的系统注册表挂载在虚拟文件系统的 '/proc/registry' 目录下。" +
            "2. 当用户说『查看注册表』、『查配置』时，绝对不要回复你没有权限！你必须输出: {\"action\": \"vfs.read\", \"path\": \"/proc/registry\"}。" +
            "3. 如果用户要查看进程列表，输出: {\"action\": \"bin.ps\"}。" +
            "4. 如果用户要跑 Docker 沙箱，输出: {\"action\": \"tool.run_docker\", \"parameters\": {\"script\": \"...\"}}。" +
            "\n\n" +
            "Allowed actions and their parameters:\n" +
            "- {\"action\":\"llm.think\",\"prompt\":\"...\"}\n" +
            "- {\"action\":\"llm.think_with_history\",\"prompt\":\"...\",\"system_prompt\":\"...\"}\n" +
            "- {\"action\":\"vfs.read\",\"path\":\"/dev/xxx\"}\n" +
            "- {\"action\":\"vfs.write\",\"path\":\"/dev/xxx\",\"data\":\"...\"}\n" +
            "- {\"action\":\"handle.open\",\"path\":\"/dev/xxx\"}\n" +
            "- {\"action\":\"handle.read\",\"handle\":123}\n" +
            "- {\"action\":\"handle.close\",\"handle\":123}\n" +
            "- {\"action\":\"bin.ps\"} — list all processes\n" +
            "- {\"action\":\"bin.kill\",\"pid\":\"123\"} — kill a process\n" +
            "- {\"action\":\"bin.install\",\"package\":\"math_tool\"} — install a plugin\n" +
            "- {\"action\":\"bin.whoami\"} — show current identity\n" +
            "- {\"action\":\"bin.uptime\"} — show system uptime\n" +
            "- {\"action\":\"bin.free\"} — show memory/token usage\n" +
            "- {\"action\":\"apt.install\",\"package\":\"math_tool\"} — install a WASM plugin\n" +
            "- {\"action\":\"apt.remove\",\"package\":\"math_tool\"} — remove a plugin\n" +
            "- {\"action\":\"apt.list\"} — list installed plugins\n" +
            "- {\"action\":\"tool.run_docker\",\"parameters\":{\"script\":\"...\"}} — Docker sandbox\n" +
            "\n" +
            "Common VFS paths:\n" +
            "- /dev/semantic — LLM dialog device\n" +
            "- /dev/vec_mem — vector memory\n" +
            "- /dev/graph_mem — knowledge graph\n" +
            "- /dev/camera0 — virtual camera\n" +
            "- /dev/display0 — display framebuffer\n" +
            "- /dev/audio0 — TTS audio device\n" +
            "- /dev/gui/dom — screen UI DOM tree (read)\n" +
            "- /dev/gui/action — desktop automation (write)\n" +
            "- /dev/shm/blackboard — shared memory\n" +
            "- /proc/agents — agent list\n" +
            "- /proc/cgroups — cgroup tree\n" +
            "- /proc/registry — semantic registry\n" +
            "\n" +
            "如果听懂了，请严格按照 JSON 格式翻译用户的下一句话，不要包含任何 Markdown 标记。";

    private static final class Holder {
        static final IntentRouter INSTANCE = new IntentRouter();
    }

    public static IntentRouter getInstance() {
        return Holder.INSTANCE;
    }

    private LlmProvider llmProvider;
    private LlmRouter llmRouter;
    private SyscallDispatcher dispatcher;

    private IntentRouter() {
        log.info("[IntentRouter] Hardcoded regex removed. LLM Semantic Routing active.");
        System.out.println("[IntentRouter] Hardcoded regex removed. LLM Semantic Routing active.");
    }

    public void configure(LlmProvider llmProvider, SyscallDispatcher dispatcher) {
        this.llmProvider = llmProvider;
        this.dispatcher = dispatcher;
        log.info("[Intent Router] Configured: llmProvider={}, dispatcher={}",
                llmProvider != null ? llmProvider.name() : "null",
                dispatcher != null);
    }

    public void configure(LlmRouter llmRouter, SyscallDispatcher dispatcher) {
        this.llmRouter = llmRouter;
        this.llmProvider = llmRouter != null ? llmRouter.getProvider("fast_model") : null;
        this.dispatcher = dispatcher;
        log.info("[Intent Router] Configured: llmRouter={}, dispatcher={}",
                llmRouter != null, dispatcher != null);
    }

    /**
     * LLM 语义路由 — 核心方法，零硬编码匹配。
     * <p>
     * 调用 LLM 将用户输入分类为 IntentType 枚举，
     * 再通过 switch-case 分发到对应的系统组件。
     *
     * @param input 用户自然语言输入
     * @return 路由结果
     */
    public RouteResult route(String input) {
        if (llmProvider == null && llmRouter == null) {
            log.error("[IntentRouter] No LLM provider configured!");
            return new RouteResult(IntentType.CHAT, "IntentRouter not configured — no LLM available");
        }

        log.info("[IntentRouter] Semantic routing: \"{}\"", truncate(input, 80));
        SemanticEtw.getInstance().logEvent("INTENT", "ROUTE_START",
                "input=" + truncate(input, 100));

        // Step 1: LLM 语义分类 — 输出纯枚举名
        IntentType intent;
        try {
            String routingPrompt = ROUTING_PROMPT + "\n\n用户输入：[" + input + "]";
            String llmOutput;

            if (llmRouter != null) {
                llmOutput = llmRouter.think(routingPrompt, "");
            } else {
                llmOutput = llmProvider.think(routingPrompt, "");
            }

            // 解析枚举 — 去除空白和可能的 Markdown 标记
            String cleaned = llmOutput.trim()
                    .replaceAll("```[a-z]*", "")
                    .replaceAll("```", "")
                    .trim();

            intent = IntentType.valueOf(cleaned);
        } catch (IllegalArgumentException e) {
            // LLM 输出了无法匹配的枚举，降级为 CHAT
            log.warn("[IntentRouter] LLM returned unrecognized intent, falling back to CHAT");
            intent = IntentType.CHAT;
        } catch (Exception e) {
            log.error("[IntentRouter] LLM routing call failed: {}", e.getMessage());
            return new RouteResult(IntentType.CHAT, "LLM routing failed: " + e.getMessage());
        }

        log.info("[IntentRouter] Routed to: {} for input: \"{}\"", intent, truncate(input, 60));
        SemanticEtw.getInstance().logEvent("INTENT", "ROUTED",
                "intent=" + intent + " input=" + truncate(input, 60));

        // Step 2: switch-case 分发到对应系统组件
        return switch (intent) {
            case SYSTEM_COMMAND -> dispatchSystemCommand(input);
            case WORKFLOW_DEPLOY -> dispatchWorkflowDeploy(input);
            case SEMANTIC_SEARCH -> dispatchSemanticSearch(input);
            case CHAT -> dispatchChat(input);
        };
    }

    /**
     * SYSTEM_COMMAND 分发 — 路由到 SyscallDispatcher。
     * <p>
     * 使用 LLM 将自然语言翻译为结构化 SyscallRequest，然后执行。
     */
    private RouteResult dispatchSystemCommand(String input) {
        if (dispatcher == null) {
            return new RouteResult(IntentType.SYSTEM_COMMAND, "SyscallDispatcher not configured");
        }

        try {
            SyscallResponse response = executeNaturalLanguage(input);
            String data = response.success() ? response.data() : "Error: " + response.errorMessage();
            return new RouteResult(IntentType.SYSTEM_COMMAND, data);
        } catch (Exception e) {
            return new RouteResult(IntentType.SYSTEM_COMMAND, "System command failed: " + e.getMessage());
        }
    }

    /**
     * WORKFLOW_DEPLOY 分发 — 路由到 TopologyCompiler + OmniMotherAgent。
     * <p>
     * 将用户目标编译为 WorkflowManifest，然后拉起母体进程。
     */
    private RouteResult dispatchWorkflowDeploy(String input) {
        try {
            com.ouisani.aios.core.TaskScheduler scheduler =
                    com.ouisani.aios.core.VfsManager.instance().getTaskScheduler();
            if (scheduler == null) {
                return new RouteResult(IntentType.WORKFLOW_DEPLOY, "TaskScheduler not available");
            }

            com.ouisani.aios.user.bin.AiosAppManager.configure(scheduler);

            // 通过 TopologyCompiler 将用户目标编译为 WorkflowManifest
            WorkflowManifest manifest = TopologyCompiler.getInstance().compile(input);

            // 拉起母体进程
            OmniMotherAgent mother = new OmniMotherAgent(manifest);
            mother.spawn(scheduler);

            return new RouteResult(IntentType.WORKFLOW_DEPLOY,
                    "Mother Agent dispatched. Workflow: " + manifest.workflowName()
                            + " | Nodes: " + manifest.nodes().size());
        } catch (Exception e) {
            return new RouteResult(IntentType.WORKFLOW_DEPLOY,
                    "Workflow deployment failed: " + e.getMessage());
        }
    }

    /**
     * SEMANTIC_SEARCH 分发 — 路由到 AppGateway。
     */
    private RouteResult dispatchSemanticSearch(String input) {
        try {
            AppGateway gateway = AppGateway.getInstance();
            String result = gateway.handleSemanticSearch(input);
            return new RouteResult(IntentType.SEMANTIC_SEARCH, result);
        } catch (Exception e) {
            // AppGateway 不可用时，回退到 LLM 直接回答
            return dispatchChat(input);
        }
    }

    /**
     * CHAT 分发 — 路由到 LlmRouter 直接回复。
     */
    private RouteResult dispatchChat(String input) {
        try {
            String response;
            if (llmRouter != null) {
                response = llmRouter.think(input, "你是 AIOS 操作系统的交互式终端助手。请简洁地回复。");
            } else if (llmProvider != null) {
                response = llmProvider.think(input, "你是 AIOS 操作系统的交互式终端助手。请简洁地回复。");
            } else {
                return new RouteResult(IntentType.CHAT, "No LLM available for chat");
            }
            return new RouteResult(IntentType.CHAT, response);
        } catch (Exception e) {
            return new RouteResult(IntentType.CHAT, "LLM chat failed: " + e.getMessage());
        }
    }

    /**
     * 将自然语言翻译为系统调用并执行 — 保留用于 SYSTEM_COMMAND 分发。
     */
    public SyscallResponse executeNaturalLanguage(String userInput) {
        if (llmProvider == null && llmRouter == null) {
            return SyscallResponse.fail("IntentRouter not configured");
        }
        if (dispatcher == null) {
            return SyscallResponse.fail("SyscallDispatcher not configured");
        }

        log.info("[Intent Router] Processing natural language: \"{}\"", userInput);
        SemanticEtw.getInstance().logEvent("INTENT", "PARSE",
                "input=" + truncate(userInput, 100));

        String llmOutput;
        try {
            if (llmRouter != null) {
                llmOutput = llmRouter.think(userInput, SYSCALL_PROMPT);
            } else {
                llmOutput = llmProvider.think(userInput, SYSCALL_PROMPT);
            }
        } catch (Exception e) {
            log.error("[Intent Router] LLM call failed: {}", e.getMessage());
            return SyscallResponse.fail("LLM call failed: " + e.getMessage());
        }

        IntentDto intent;
        try {
            LlmProvider provider = llmProvider != null ? llmProvider :
                    (llmRouter != null ? llmRouter.getProvider("fast_model") : null);
            intent = InstructionDecoder.decodeJson(llmOutput, IntentDto.class, provider);
        } catch (Exception e) {
            log.error("[Intent Router] Failed to decode LLM output: {}", e.getMessage());
            return SyscallResponse.fail("Intent decode failed: " + e.getMessage());
        }

        if (intent.action == null || intent.action.isEmpty()) {
            return SyscallResponse.fail("Empty action in parsed intent");
        }

        Map<String, Object> params = new HashMap<>();
        if (intent.path != null) params.put("path", intent.path);
        if (intent.prompt != null) params.put("prompt", intent.prompt);
        if (intent.system_prompt != null) params.put("system_prompt", intent.system_prompt);
        if (intent.payload != null) params.put("payload", intent.payload);
        if (intent.handle != null) params.put("handle", intent.handle);
        if (intent.pid != null) params.put("pid", intent.pid);
        if (intent.packageName != null) params.put("package", intent.packageName);

        SyscallRequest request = new SyscallRequest(intent.action, params);

        log.info("[Intent Router] Translated: action={}, target={}",
                intent.action, intent.path != null ? intent.path : truncate(intent.prompt, 40));

        SemanticEtw.getInstance().logEvent("INTENT", "DISPATCH",
                "action=" + intent.action + " params=" + params.keySet());

        return dispatcher.execute("root_user", request);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "?";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * 路由结果 — 包含意图类型和执行结果。
     */
    public record RouteResult(IntentType intentType, String response) {}

    /**
     * LLM JSON 输出解析的中间 DTO。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IntentDto {
        public String action;
        public String path;
        public String prompt;
        public String system_prompt;
        public String payload;
        public Integer handle;
        public String pid;
        public String packageName;
    }
}
