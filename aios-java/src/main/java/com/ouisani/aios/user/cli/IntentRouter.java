package com.ouisani.aios.user.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ouisani.aios.core.llm.InstructionDecoder;
import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.syscall.SyscallDispatcher;
import com.ouisani.aios.core.syscall.SyscallRequest;
import com.ouisani.aios.core.syscall.SyscallResponse;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Intent Router — translates natural language into AIOS system calls.
 * <p>
 * Uses an LLM with a strict system prompt to parse user intent into
 * a structured {@link SyscallRequest}, then dispatches it through
 * the {@link SyscallDispatcher}.
 *
 * <h3>Supported intent mappings:</h3>
 * <ul>
 *   <li>"read the camera" → {@code {"action":"vfs.read","path":"/dev/camera0"}}</li>
 *   <li>"ask the AI about X" → {@code {"action":"llm.think","prompt":"X"}}</li>
 *   <li>"write hello to shared memory" → {@code {"action":"vfs.write","path":"/dev/shm/blackboard","payload":"..."}}</li>
 * </ul>
 */
public final class IntentRouter {

    private static final Logger log = LoggerFactory.getLogger(IntentRouter.class);

    private static final String SYSTEM_PROMPT =
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
            "- /proc/registry — semantic registry (用户查配置/注册表时必须用这个！)\n" +
            "\n" +
            "Intent mapping examples:\n" +
            "- \"列出所有进程\" or \"show processes\" → {\"action\":\"bin.ps\"}\n" +
            "- \"杀掉进程123\" or \"kill process 123\" → {\"action\":\"bin.kill\",\"pid\":\"123\"}\n" +
            "- \"安装math工具\" or \"install math plugin\" → {\"action\":\"bin.install\",\"package\":\"math_tool\"}\n" +
            "- \"系统运行了多久\" or \"system uptime\" → {\"action\":\"bin.uptime\"}\n" +
            "- \"查看内存\" or \"show memory\" → {\"action\":\"bin.free\"}\n" +
            "- \"我是谁\" or \"who am I\" → {\"action\":\"bin.whoami\"}\n" +
            "- \"查看注册表\" or \"查配置\" → {\"action\":\"vfs.read\",\"path\":\"/proc/registry\"}\n" +
            "- \"读取屏幕\" or \"read screen\" → {\"action\":\"vfs.read\",\"path\":\"/dev/gui/dom\"}\n" +
            "- \"点击按钮\" or \"click button\" → {\"action\":\"vfs.write\",\"path\":\"/dev/gui/action\",\"data\":\"{\\\"action\\\":\\\"click\\\",\\\"id\\\":\\\"btn_1\\\"}\"}\n" +
            "\n" +
            "如果听懂了，请严格按照 JSON 格式翻译用户的下一句话，不要包含任何 Markdown 标记。";

    private static final class Holder {
        static final IntentRouter INSTANCE = new IntentRouter();
    }

    public static IntentRouter getInstance() {
        return Holder.INSTANCE;
    }

    private LlmProvider llmProvider;
    private SyscallDispatcher dispatcher;

    private IntentRouter() {}

    public void configure(LlmProvider llmProvider, SyscallDispatcher dispatcher) {
        this.llmProvider = llmProvider;
        this.dispatcher = dispatcher;
        log.info("[Intent Router] Configured: llmProvider={}, dispatcher={}",
                llmProvider != null ? llmProvider.name() : "null",
                dispatcher != null);
    }

    /**
     * Translate natural language into a syscall and execute it.
     *
     * @param userInput the user's natural language input
     * @return the syscall response, or null on failure
     */
    public SyscallResponse executeNaturalLanguage(String userInput) {
        if (llmProvider == null || dispatcher == null) {
            log.error("[Intent Router] Not configured! Call configure() first.");
            return SyscallResponse.fail("IntentRouter not configured");
        }

        log.info("[Intent Router] Processing natural language: \"{}\"", userInput);
        SemanticEtw.getInstance().logEvent("INTENT", "PARSE",
                "input=" + userInput.substring(0, Math.min(userInput.length(), 100)));

        // Step 1: Ask LLM to translate intent to JSON
        String llmOutput;
        try {
            llmOutput = llmProvider.think(userInput, SYSTEM_PROMPT);
        } catch (Exception e) {
            log.error("[Intent Router] LLM call failed: {}", e.getMessage());
            return SyscallResponse.fail("LLM call failed: " + e.getMessage());
        }

        // Step 2: Parse LLM output into a structured intent
        IntentDto intent;
        try {
            intent = InstructionDecoder.decodeJson(llmOutput, IntentDto.class, llmProvider);
        } catch (Exception e) {
            log.error("[Intent Router] Failed to decode LLM output: {}", e.getMessage());
            return SyscallResponse.fail("Intent decode failed: " + e.getMessage());
        }

        if (intent.action == null || intent.action.isEmpty()) {
            log.warn("[Intent Router] LLM returned empty action");
            return SyscallResponse.fail("Empty action in parsed intent");
        }

        // Step 3: Convert IntentDto to SyscallRequest
        Map<String, Object> params = new HashMap<>();
        if (intent.path != null) params.put("path", intent.path);
        if (intent.prompt != null) params.put("prompt", intent.prompt);
        if (intent.system_prompt != null) params.put("system_prompt", intent.system_prompt);
        if (intent.payload != null) params.put("payload", intent.payload);
        if (intent.handle != null) params.put("handle", intent.handle);
        if (intent.pid != null) params.put("pid", intent.pid);
        if (intent.packageName != null) params.put("package", intent.packageName);

        SyscallRequest request = new SyscallRequest(intent.action, params);

        log.info("[Intent Router] Translated natural language \"{}\" into Syscall: action={}, target={}",
                truncate(userInput, 60), intent.action,
                intent.path != null ? intent.path : intent.prompt != null ? truncate(intent.prompt, 40) : "?");

        SemanticEtw.getInstance().logEvent("INTENT", "DISPATCH",
                "action=" + intent.action + " params=" + params.keySet());

        // Step 4: Execute via SyscallDispatcher
        SyscallResponse response = dispatcher.execute("root_user", request);

        log.info("[Intent Router] Syscall result: success={}, action={}",
                response.success(), intent.action);

        return response;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "?";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * Intermediate DTO for LLM JSON output parsing.
     * All fields optional — only the relevant ones will be populated.
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
