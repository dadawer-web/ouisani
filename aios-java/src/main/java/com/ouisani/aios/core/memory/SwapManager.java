package com.ouisani.aios.core.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Disk-based swap manager (kswapd) for AIOS.
 * <p>
 * When ZRAM compression is still not enough to bring an Agent under its
 * hard Token limit, SwapManager evicts cold context pages to disk and
 * replaces them with a short pointer string, freeing both JVM heap and
 * Token budget. Pages can be swapped back in on demand.
 */
public class SwapManager {

    private static final Logger log = LoggerFactory.getLogger(SwapManager.class);

    private static final String SWAP_DIR = "/var/swap";
    private static final String POINTER_PREFIX = "<SWAPPED_OUT_TO_DISK: ";
    private static final String POINTER_SUFFIX = ">";

    private static final class Holder {
        static final SwapManager INSTANCE = new SwapManager();
    }

    public static SwapManager instance() {
        return Holder.INSTANCE;
    }

    private final AtomicLong pageIdCounter = new AtomicLong(0);
    private final ConcurrentHashMap<String, Path> pageRegistry = new ConcurrentHashMap<>();

    private SwapManager() {
        try {
            Files.createDirectories(Path.of(SWAP_DIR));
            log.info("[SwapManager] Swap directory ready: {}", SWAP_DIR);
        } catch (IOException e) {
            log.error("[SwapManager] Failed to create swap directory: {}", e.getMessage());
        }
    }

    /**
     * Evict cold context entries to disk.
     *
     * @param agentId      the Agent identifier
     * @param coldContext  list of cold context strings to swap out
     * @return a short pointer string like {@code <SWAPPED_OUT_TO_DISK: page_id_101>}
     */
    public String swapOut(String agentId, List<String> coldContext) {
        if (coldContext == null || coldContext.isEmpty()) {
            log.warn("[SwapManager] swapOut called with empty context for agent={}", agentId);
            return "";
        }

        long pageNum = pageIdCounter.incrementAndGet();
        String pageId = "page_" + agentId + "_" + pageNum;
        Path swapFile = Path.of(SWAP_DIR, "agent_" + agentId + "_" + System.currentTimeMillis() + ".page");

        StringBuilder serialized = new StringBuilder();
        for (int i = 0; i < coldContext.size(); i++) {
            serialized.append(coldContext.get(i));
            if (i < coldContext.size() - 1) {
                serialized.append('\n');
            }
        }

        try {
            Files.writeString(swapFile, serialized.toString());
            pageRegistry.put(pageId, swapFile);

            String pointer = POINTER_PREFIX + pageId + POINTER_SUFFIX;
            long bytesOnDisk = Files.size(swapFile);
            log.info("[SwapManager] swapOut: agent={}, pageId={}, entries={}, bytesOnDisk={}",
                    agentId, pageId, coldContext.size(), bytesOnDisk);
            return pointer;
        } catch (IOException e) {
            log.error("[SwapManager] swapOut failed: agent={}, pageId={}, error={}",
                    agentId, pageId, e.getMessage());
            return "";
        }
    }

    /**
     * Restore context from disk by pointer.
     *
     * @param pageId the page identifier (extracted from a pointer string)
     * @return the original list of context strings, or empty list on failure
     */
    public List<String> swapIn(String pageId) {
        Path swapFile = pageRegistry.remove(pageId);
        if (swapFile == null) {
            log.warn("[SwapManager] swapIn: pageId={} not found in registry", pageId);
            return List.of();
        }

        try {
            String content = Files.readString(swapFile);
            Files.deleteIfExists(swapFile);
            List<String> restored = List.of(content.split("\n"));
            log.info("[SwapManager] swapIn: pageId={}, entries={}, fileDeleted=true", pageId, restored.size());
            return restored;
        } catch (IOException e) {
            log.error("[SwapManager] swapIn failed: pageId={}, error={}", pageId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Extract the page ID from a pointer string.
     *
     * @param pointer a string like {@code <SWAPPED_OUT_TO_DISK: page_id_101>}
     * @return the extracted page ID, or null if not a valid pointer
     */
    public static String extractPageId(String pointer) {
        if (pointer == null || !pointer.startsWith(POINTER_PREFIX) || !pointer.endsWith(POINTER_SUFFIX)) {
            return null;
        }
        return pointer.substring(POINTER_PREFIX.length(), pointer.length() - POINTER_SUFFIX.length());
    }

    /**
     * Check if a string is a swap pointer.
     */
    public static boolean isSwapPointer(String text) {
        return text != null && text.startsWith(POINTER_PREFIX) && text.endsWith(POINTER_SUFFIX);
    }

    /**
     * Get the number of pages currently swapped out to disk.
     */
    public int swappedPageCount() {
        return pageRegistry.size();
    }

    /**
     * Get total bytes used on disk by swap files.
     */
    public long totalSwapBytes() {
        return pageRegistry.values().stream()
                .mapToLong(p -> {
                    try {
                        return Files.size(p);
                    } catch (IOException e) {
                        return 0;
                    }
                })
                .sum();
    }
}
