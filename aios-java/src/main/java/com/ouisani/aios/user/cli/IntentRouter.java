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

    private static final String SYSTEM_PROMPT = """
            You are the AIOS Kernel Intent Parser. The user will input natural language.
            You MUST translate it into a single JSON object representing a system call.
            
            Allowed actions and their parameters:
            - {"action":"llm.think","prompt":"..."}
            - {"action":"llm.think_with_history","prompt":"...","system_prompt":"..."}
            - {"action":"vfs.read","path":"/dev/xxx"}
            - {"action":"vfs.write","path":"/dev/xxx","payload":"..."}
            - {"action":"handle.open","path":"/dev/xxx"}
            - {"action":"handle.read","handle":123}
            - {"action":"handle.close","handle":123}
            
            Common VFS paths:
            - /dev/semantic — LLM dialog device
            - /dev/vec_mem_101 — vector memory
            - /dev/graph_mem — knowledge graph
            - /dev/camera0 — virtual camera
            - /dev/fb0 — display framebuffer
            - /dev/shm/blackboard — shared memory
            - /proc/agents — agent list
            - /proc/cgroups — cgroup tree
            - /proc/registry — semantic registry
            
            Output ONLY the JSON object. No explanation, no markdown, no extra text.
            """;

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
    }
}
