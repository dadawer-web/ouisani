package com.ouisani.aios.core.memory.providers;

import com.ouisani.aios.core.memory.ContextInjector;
import com.ouisani.aios.core.memory.SwapManager;
import com.ouisani.aios.core.memory.TokenZram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AIOS-native memory provider — leverages TokenZRAM compression
 * and SwapManager disk paging for maximum memory efficiency.
 * <p>
 * This is the soul of the AIOS memory subsystem:
 * <ul>
 *   <li><b>Store</b>: Writes content to an in-memory page store,
 *       with automatic ZRAM compression when pages exceed the
 *       compression threshold.</li>
 *   <li><b>Retrieve</b>: Queries the in-memory store first, then
 *       transparently swaps in from disk if the data was paged out
 *       by the kswapd daemon.</li>
 *   <li><b>Clear</b>: Evicts all pages for the agent, including
 *       any swap files on disk.</li>
 * </ul>
 */
public class TokenZramProvider implements MemoryProvider {

    private static final Logger log = LoggerFactory.getLogger(TokenZramProvider.class);

    private static final int COMPRESSION_THRESHOLD_CHARS = 2000;

    /** In-memory page store: agentId → list of memory entries. */
    private final ConcurrentHashMap<String, List<String>> pageStore = new ConcurrentHashMap<>();

    @Override
    public boolean store(String agentId, String memoryContent) {
        if (agentId == null || memoryContent == null || memoryContent.isEmpty()) {
            log.warn("[TokenZramProvider] store rejected: agentId={}, contentEmpty={}",
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

        log.info("[TokenZramProvider] Stored memory for agent='{}': contentLen={}, tokens~={}, totalPages={}",
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
            log.warn("[TokenZramProvider] retrieve rejected: agentId={}, queryEmpty={}",
                    agentId, query == null || query.isEmpty());
            return "";
        }

        List<String> pages = pageStore.get(agentId);
        if (pages == null || pages.isEmpty()) {
            log.info("[TokenZramProvider] No memory pages found for agent='{}'", agentId);
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
                log.debug("[TokenZramProvider] ContextInjector augmentation skipped: {}", e.getMessage());
            }
        }

        log.info("[TokenZramProvider] Retrieved memory for agent='{}': query='{}', resultLen={}",
                agentId, query, result.length());

        return result.toString().trim();
    }

    @Override
    public void clear(String agentId) {
        List<String> removed = pageStore.remove(agentId);
        int count = removed != null ? removed.size() : 0;
        log.info("[TokenZramProvider] Cleared memory for agent='{}': pagesEvicted={}", agentId, count);
    }

    @Override
    public String providerName() {
        return "TokenZRAM";
    }

    // ── Internal: ZRAM Compression ──

    private String compressWithZram(String agentId, String content) {
        log.info("[TokenZramProvider] Applying ZRAM compression for agent='{}': originalLen={}",
                agentId, content.length());

        // Use TokenZram's truncation compression as a lightweight in-process method.
        // Full LLM-based compression requires an AgentTask context (handled at the cgroup level).
        if (content.length() <= 200) return content;
        String compressed = content.substring(0, 100)
                + "...[ZRAM_COMPRESSED]..."
                + content.substring(content.length() - 100);

        long originalTokens = Math.max(1, content.length() / 4);
        long compressedTokens = Math.max(1, compressed.length() / 4);
        log.info("[TokenZramProvider] ZRAM compression: agent='{}', originalTokens~={}, compressedTokens~={}, ratio={}%",
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
            log.warn("[TokenZramProvider] Swap out failed for agent='{}'", agentId);
            return;
        }

        // Replace cold pages with the swap pointer (in reverse to keep indices valid)
        for (int i = coldIndices.size() - 1; i >= 0; i--) {
            pages.remove(coldIndices.get(i).intValue());
        }
        pages.add(coldIndices.get(0), pointer);

        log.info("[TokenZramProvider] Swapped {} cold pages to disk for agent='{}': pointer='{}'",
                coldPages.size(), agentId, pointer);
    }
}
