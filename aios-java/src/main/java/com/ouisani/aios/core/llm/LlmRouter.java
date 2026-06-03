package com.ouisani.aios.core.llm;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.NumaAffinity;
import com.ouisani.aios.core.TaskScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LlmRouter implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    private static final int SMART_THRESHOLD = 500;
    private static final List<String> SMART_KEYWORDS = List.of(
            "代码", "分析", "Bug", "bug", "debug", "Debug", "代码审查", "重构", "refactor"
    );

    /** Budget threshold for cross-node (remote) model access. */
    private static final int REMOTE_BUDGET_THRESHOLD = 100;

    /** Backend names treated as local NUMA nodes. */
    private static final String LOCAL_NODE = "fast_model";
    /** Backend names treated as remote NUMA nodes. */
    private static final String REMOTE_NODE = "smart_model";

    private final ConcurrentHashMap<String, LlmProvider> backendProviders = new ConcurrentHashMap<>();

    public void registerProvider(String name, LlmProvider provider) {
        backendProviders.put(name, provider);
        log.info("[LLM Router] Registered backend: '{}' → {} (NUMA node: {})",
                name, provider.name(), isLocalNode(name) ? "LOCAL" : "REMOTE");
    }

    public void unregisterProvider(String name) {
        backendProviders.remove(name);
        log.info("[LLM Router] Unregistered provider: name={}", name);
    }

    public Map<String, LlmProvider> getBackends() {
        return Collections.unmodifiableMap(backendProviders);
    }

    @Override
    public String name() {
        return "llm-router";
    }

    @Override
    public String think(String prompt, String systemPrompt) {
        String backend = numaAwareRoute(prompt);
        LlmProvider provider = resolveProvider(backend);
        log.info("[LLM Router] Route: promptLen={}, backend={}, provider={}",
                prompt.length(), backend, provider.name());
        return provider.think(prompt, systemPrompt);
    }

    @Override
    public String think(String prompt) {
        return think(prompt, "");
    }

    @Override
    public String thinkWithHistory(List<ChatMessage> messages, String systemPrompt) {
        String lastUserMsg = messages.stream()
                .filter(m -> "user".equals(m.role()))
                .map(ChatMessage::content)
                .reduce((first, second) -> second)
                .orElse("");

        String backend = numaAwareRoute(lastUserMsg);
        LlmProvider provider = resolveProvider(backend);
        log.info("[LLM Router] Route history: messages={}, backend={}", messages.size(), backend);
        return provider.thinkWithHistory(messages, systemPrompt);
    }

    @Override
    public float[] embed(String text) {
        var first = backendProviders.entrySet().stream().findFirst();
        if (first.isPresent()) {
            return first.get().getValue().embed(text);
        }
        return mockEmbed(text);
    }

    @Override
    public boolean isAvailable() {
        return !backendProviders.isEmpty();
    }

    // ────────────────────────────────────────────────────────────
    //  NUMA-Aware Routing
    // ────────────────────────────────────────────────────────────

    /**
     * NUMA-aware routing strategy:
     * <ol>
     *   <li>LOCAL_ONLY → always route to local node, regardless of prompt.</li>
     *   <li>PREFER_LOCAL → route to remote only when prompt is complex
     *       (length > threshold or contains smart keywords).</li>
     *   <li>ANY → allow remote routing, but only if budget > 100.
     *       If budget insufficient, throw {@link NumaOomException}.</li>
     * </ol>
     */
    private String numaAwareRoute(String prompt) {
        AgentTask currentTask = TaskScheduler.CURRENT_TASK.get();
        NumaAffinity affinity = (currentTask != null) ? currentTask.affinity() : NumaAffinity.PREFER_LOCAL;
        int budget = (currentTask != null) ? currentTask.budget() : Integer.MAX_VALUE;

        boolean wantsRemote = shouldRouteRemote(prompt);

        return switch (affinity) {
            case LOCAL_ONLY -> {
                if (wantsRemote) {
                    log.info("[NUMA Scheduler] Task {} pinned to LOCAL node due to affinity constraints "
                            + "(LOCAL_ONLY, promptLen={})",
                            currentTask != null ? currentTask.pid() : "?", prompt.length());
                }
                yield LOCAL_NODE;
            }

            case PREFER_LOCAL -> {
                if (wantsRemote) {
                    log.info("[NUMA Scheduler] Task {} routed to REMOTE node (PREFER_LOCAL, complex prompt, promptLen={})",
                            currentTask != null ? currentTask.pid() : "?", prompt.length());
                    yield REMOTE_NODE;
                }
                yield LOCAL_NODE;
            }

            case ANY -> {
                if (wantsRemote) {
                    if (budget > REMOTE_BUDGET_THRESHOLD) {
                        log.info("[NUMA Scheduler] Task {} routed to REMOTE node (ANY, budget={})",
                                currentTask != null ? currentTask.pid() : "?", budget);
                        yield REMOTE_NODE;
                    } else {
                        log.warn("[NUMA Scheduler] Task {} REMOTE routing DENIED: budget={} <= threshold={} "
                                + "(NumaOomException)",
                                currentTask != null ? currentTask.pid() : "?", budget, REMOTE_BUDGET_THRESHOLD);
                        throw new NumaOomException(budget, REMOTE_BUDGET_THRESHOLD);
                    }
                }
                yield LOCAL_NODE;
            }
        };
    }

    /**
     * Determine if the prompt is complex enough to warrant remote (smart) model.
     */
    private boolean shouldRouteRemote(String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return false;
        }
        if (prompt.length() > SMART_THRESHOLD) {
            return true;
        }
        for (String keyword : SMART_KEYWORDS) {
            if (prompt.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean isLocalNode(String backendName) {
        return LOCAL_NODE.equals(backendName);
    }

    private LlmProvider resolveProvider(String backend) {
        LlmProvider provider = backendProviders.get(backend);
        if (provider != null) {
            return provider;
        }
        // Fallback to any available provider
        var first = backendProviders.entrySet().stream().findFirst();
        if (first.isPresent()) {
            String fallback = first.get().getKey();
            log.warn("[LLM Router] Backend '{}' not found, falling back to '{}'", backend, fallback);
            return first.get().getValue();
        }
        throw new RuntimeException("No LLM backend registered in router");
    }
}
