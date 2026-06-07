package com.ouisani.aios.core.memory.providers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zep 云端记忆后端 — 集成 Zep 长期记忆服务器。
 * <p>
 * Zep 提供事实提取、知识图谱构建和带自动摘要的时序记忆管理。
 * <p>
 * <b>当前状态：</b>HTTP 集成待完成，目前以 Mock 模式运行，
 * 使用本地内存存储。未来将对接 Zep REST API。
 *
 * @see MemoryProvider
 */
public class ZepProvider implements MemoryProvider {

    private static final Logger log = LoggerFactory.getLogger(ZepProvider.class);

    /** 本地 Mock 存储：agentId → 记忆条目列表 */
    private final ConcurrentHashMap<String, List<String>> mockStore = new ConcurrentHashMap<>();

    // TODO: Configure Zep API endpoint and API key via SemanticRegistry
    // private String apiEndpoint = "https://api.getzep.com/api/v1";
    // private String apiKey;

    @Override
    public boolean store(String agentId, String memoryContent) {
        log.info("[Zep] Syncing with external vector database... operation=store, agent='{}', contentLen={}",
                agentId, memoryContent != null ? memoryContent.length() : 0);

        // TODO: Replace with actual HTTP call to Zep API
        // POST /api/v1/memory { "user_id": agentId, "messages": [{"content": memoryContent}] }
        List<String> entries = mockStore.computeIfAbsent(agentId, k -> new java.util.ArrayList<>());
        entries.add(memoryContent);

        log.info("[Zep] Store completed (mock): agent='{}', totalPages={}", agentId, entries.size());
        return true;
    }

    @Override
    public String retrieve(String agentId, String query) {
        log.info("[Zep] Syncing with external vector database... operation=retrieve, agent='{}', query='{}'",
                agentId, query);

        // TODO: Replace with actual HTTP call to Zep API
        // GET /api/v1/memory?user_id=agentId
        List<String> entries = mockStore.get(agentId);
        if (entries == null || entries.isEmpty()) {
            log.info("[Zep] No memories found for agent='{}'", agentId);
            return "";
        }

        // Simple keyword matching mock
        StringBuilder result = new StringBuilder();
        String lowerQuery = query.toLowerCase();
        for (String entry : entries) {
            if (entry.toLowerCase().contains(lowerQuery)) {
                result.append(entry).append("\n");
            }
        }

        log.info("[Zep] Retrieve completed (mock): agent='{}', resultLen={}", agentId, result.length());
        return result.toString().trim();
    }

    @Override
    public void clear(String agentId) {
        log.info("[Zep] Syncing with external vector database... operation=clear, agent='{}'", agentId);

        // TODO: Replace with actual HTTP call to Zep API
        // DELETE /api/v1/memory?user_id=agentId
        List<String> removed = mockStore.remove(agentId);
        int count = removed != null ? removed.size() : 0;
        log.info("[Zep] Clear completed (mock): agent='{}', entriesRemoved={}", agentId, count);
    }

    @Override
    public String providerName() {
        return "Zep";
    }
}
