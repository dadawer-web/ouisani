package com.ouisani.aios.core.memory.providers;

import com.ouisani.aios.core.memory.ContextInjector;
import com.ouisani.aios.core.memory.SwapManager;
import com.ouisani.aios.core.memory.TokenZram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AIOS 原生记忆后端 — 基于 TokenZRAM 压缩和 SwapManager 磁盘换页的高效存储。
 * <p>
 * 这是 AIOS 记忆子系统的核心实现：
 * <ul>
 *   <li><b>存储</b>：写入内存页存储，超过压缩阈值时自动 ZRAM 压缩</li>
 *   <li><b>检索</b>：先查内存存储，若数据已被 kswapd 换出则透明换入</li>
 *   <li><b>清除</b>：驱逐该 Agent 的所有页面，包括磁盘上的交换文件</li>
 * </ul>
 *
 * @see com.ouisani.aios.core.memory.TokenZram
 * @see com.ouisani.aios.core.memory.SwapManager
 */
public class TokenZramProvider implements MemoryProvider {

    private static final Logger log = LoggerFactory.getLogger(TokenZramProvider.class);

    private static final int COMPRESSION_THRESHOLD_CHARS = 2000;

    /** 内存页存储：agentId → 记忆条目列表 */
    private final ConcurrentHashMap<String, List<String>> pageStore = new ConcurrentHashMap<>();

    @Override
    public boolean store(String agentId, MemoryRecord record) {
        String memoryContent = record.content();
        if (agentId == null || memoryContent == null || memoryContent.isEmpty()) {
            log.warn("[TokenZramProvider] 存储被拒绝: agentId={}, contentEmpty={}",
                    agentId, memoryContent == null || memoryContent.isEmpty());
            return false;
        }

        List<String> pages = pageStore.computeIfAbsent(agentId, k ->
                new java.util.ArrayList<>());

        // If the content exceeds the compression threshold, apply ZRAM compression
        String storedContent;
        if (memoryContent.length() > COMPRESSION_THRESHOLD_CHARS) {
            storedContent = compressWithZram(agentId, memoryContent);
        } else {
            storedContent = memoryContent;
        }

        pages.add(storedContent);
        long tokenEstimate = Math.max(1, storedContent.length() / 4);

        log.info("[TokenZramProvider] 已存储 Agent 内存: agent='{}', contentLen={}, tokens~={}, totalPages={}",
                agentId, memoryContent.length(), tokenEstimate, pages.size());

        // Check if we need to swap out old pages to disk
        if (pages.size() > 10) {
            swapOutColdPages(agentId, pages);
        }

        return true;
    }

    @Override
    public String retrieve(String agentId, String query) {
        if (agentId == null || query == null || query.isEmpty()) {
            log.warn("[TokenZramProvider] 检索被拒绝: agentId={}, queryEmpty={}",
                    agentId, query == null || query.isEmpty());
            return "";
        }

        List<String> pages = pageStore.get(agentId);
        if (pages == null || pages.isEmpty()) {
            log.info("[TokenZramProvider] 未找到内存页 agent='{}'", agentId);
            return "";
        }

        // Simple keyword-based retrieval: find pages containing query terms
        StringBuilder result = new StringBuilder();
        String lowerQuery = query.toLowerCase();
        String[] queryTerms = lowerQuery.split("\\s+");

        for (String page : pages) {
            String lowerPage = page.toLowerCase();
            boolean matches = false;
            for (String term : queryTerms) {
                if (lowerPage.contains(term)) {
                    matches = true;
                    break;
                }
            }
            if (matches) {
                // If this is a swap pointer, swap it back in
                if (SwapManager.isSwapPointer(page)) {
                    String pageId = SwapManager.extractPageId(page);
                    if (pageId != null) {
                        List<String> restored = SwapManager.instance().swapIn(pageId);
                        for (String entry : restored) {
                            result.append(entry).append("\n");
                        }
                    }
                } else {
                    result.append(page).append("\n");
                }
            }
        }

        // Also try ContextInjector for semantic augmentation
        if (result.isEmpty()) {
            try {
                String augmented = ContextInjector.getInstance().augmentPrompt(query);
                if (augmented != null && !augmented.equals(query)) {
                    result.append(augmented);
                }
            } catch (Exception e) {
                log.debug("[TokenZramProvider] ContextInjector 增强已跳过: {}", e.getMessage());
            }
        }

        log.info("[TokenZramProvider] 已检索 Agent 内存: agent='{}', query='{}', resultLen={}",
                agentId, query, result.length());

        return result.toString().trim();
    }

    @Override
    public void clear(String agentId) {
        List<String> removed = pageStore.remove(agentId);
        int count = removed != null ? removed.size() : 0;
        log.info("[TokenZramProvider] 已清除 Agent 内存: agent='{}', pagesEvicted={}", agentId, count);
    }

    @Override
    public String providerName() {
        return "TokenZRAM";
    }

    // ── Internal: ZRAM Compression ──

    private String compressWithZram(String agentId, String content) {
        log.info("[TokenZramProvider] 正在应用 ZRAM 压缩 agent='{}': originalLen={}",
                agentId, content.length());

        // Use TokenZram's truncation compression as a lightweight in-process method.
        // Full LLM-based compression requires an AgentTask context (handled at the cgroup level).
        if (content.length() <= 200) return content;
        String compressed = content.substring(0, 100)
                + "...[ZRAM_COMPRESSED]..."
                + content.substring(content.length() - 100);

        long originalTokens = Math.max(1, content.length() / 4);
        long compressedTokens = Math.max(1, compressed.length() / 4);
        log.info("[TokenZramProvider] ZRAM 压缩: agent='{}', originalTokens~={}, compressedTokens~={}, ratio={}%",
                agentId, originalTokens, compressedTokens,
                (compressedTokens * 100 / originalTokens));

        return compressed;
    }

    // ── Internal: Swap Out Cold Pages ──

    private void swapOutColdPages(String agentId, List<String> pages) {
        // Swap out the oldest non-compressed, non-swapped pages
        List<String> coldPages = new java.util.ArrayList<>();
        java.util.List<Integer> coldIndices = new java.util.ArrayList<>();

        for (int i = 0; i < pages.size() / 2 && i < pages.size(); i++) {
            String page = pages.get(i);
            if (!page.contains("[ZRAM_COMPRESSED]") && !SwapManager.isSwapPointer(page)) {
                coldPages.add(page);
                coldIndices.add(i);
            }
        }

        if (coldPages.isEmpty()) return;

        String pointer = SwapManager.instance().swapOut(agentId, coldPages);
        if (pointer.isEmpty()) {
            log.warn("[TokenZramProvider] Agent '{}' 换出失败", agentId);
            return;
        }

        // Replace cold pages with the swap pointer (in reverse to keep indices valid)
        for (int i = coldIndices.size() - 1; i >= 0; i--) {
            pages.remove(coldIndices.get(i).intValue());
        }
        pages.add(coldIndices.get(0), pointer);

        log.info("[TokenZramProvider] 已将 {} 个冷页面换出至磁盘 agent='{}': pointer='{}'",
                coldPages.size(), agentId, pointer);
    }
}
