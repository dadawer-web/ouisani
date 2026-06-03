package com.ouisani.aios.core.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LlmRouter implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    private static final int SMART_THRESHOLD = 500;
    private static final List<String> SMART_KEYWORDS = List.of("代码", "分析", "Bug", "bug", "debug", "Debug", "代码审查", "重构", "refactor");

    private final ConcurrentHashMap<String, LlmProvider> backendProviders = new ConcurrentHashMap<>();

    public void registerProvider(String name, LlmProvider provider) {
        backendProviders.put(name, provider);
        System.out.printf("  🔀 [LLM Router] Registered backend: '%s' → %s%n", name, provider.name());
        log.info("[LLM Router] Registered provider: name={}, provider={}", name, provider.name());
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
        String backend = route(prompt);
        LlmProvider provider = backendProviders.get(backend);

        if (provider == null) {
            // Fallback: 尝试任意可用后端
            var first = backendProviders.entrySet().stream().findFirst();
            if (first.isPresent()) {
                Map.Entry<String, LlmProvider> e = first.get();
                backend = e.getKey();
                provider = e.getValue();
                log.warn("[LLM Router] Routed backend '{}' not found, falling back to '{}'", route(prompt), backend);
            } else {
                throw new RuntimeException("No LLM backend registered in router");
            }
        }

        System.out.printf("  🔀 [LLM Router] Routing prompt (length: %d) to backend: %s%n", prompt.length(), backend);
        log.info("[LLM Router] Route: promptLen={}, backend={}, provider={}", prompt.length(), backend, provider.name());

        return provider.think(prompt, systemPrompt);
    }

    @Override
    public String think(String prompt) {
        return think(prompt, "");
    }

    @Override
    public String thinkWithHistory(List<ChatMessage> messages, String systemPrompt) {
        // 使用最后一条用户消息做路由决策
        String lastUserMsg = messages.stream()
                .filter(m -> "user".equals(m.role()))
                .map(ChatMessage::content)
                .reduce((first, second) -> second)
                .orElse("");

        String backend = route(lastUserMsg);
        LlmProvider provider = backendProviders.get(backend);

        if (provider == null) {
            var first = backendProviders.entrySet().stream().findFirst();
            if (first.isPresent()) {
                Map.Entry<String, LlmProvider> e = first.get();
                backend = e.getKey();
                provider = e.getValue();
            } else {
                throw new RuntimeException("No LLM backend registered in router");
            }
        }

        System.out.printf("  🔀 [LLM Router] Routing history chat (%d messages) to backend: %s%n", messages.size(), backend);
        log.info("[LLM Router] Route history: messages={}, backend={}", messages.size(), backend);

        return provider.thinkWithHistory(messages, systemPrompt);
    }

    @Override
    public float[] embed(String text) {
        // Embedding 委托给第一个可用后端
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

    /**
     * 路由策略：长 prompt 或包含关键字 → smart_model，否则 → fast_model
     */
    private String route(String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return "fast_model";
        }

        if (prompt.length() > SMART_THRESHOLD) {
            log.debug("[LLM Router] Smart route: prompt length {} > threshold {}", prompt.length(), SMART_THRESHOLD);
            return "smart_model";
        }

        for (String keyword : SMART_KEYWORDS) {
            if (prompt.contains(keyword)) {
                log.debug("[LLM Router] Smart route: keyword '{}' detected", keyword);
                return "smart_model";
            }
        }

        return "fast_model";
    }
}
