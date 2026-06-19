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
 * 磁盘交换管理器（kswapd）— AIOS 的磁盘换出/换入引擎。
 * <p>
 * 类比 Linux 的 kswapd 内核线程和 swap 分区：当 ZRAM 压缩后
 * 仍无法将 Agent 控制在 Token 硬限制内时，SwapManager 将
 * 冷上下文页面换出到磁盘文件，替换为短指针字符串，同时
 * 释放 JVM 堆内存和 Token 预算。需要时可按需换入恢复。
 *
 * <h3>OS 类比</h3>
 * <table>
 *   <tr><th>Linux</th><th>AIOS SwapManager</th><th>说明</th></tr>
 *   <tr><td>kswapd</td><td>SwapManager</td><td>后台换出守护进程</td></tr>
 *   <tr><td>swap 分区</td><td>/var/swap/</td><td>磁盘交换区</td></tr>
 *   <tr><td>swap_out</td><td>swapOut()</td><td>页面换出</td></tr>
 *   <tr><td>swap_in</td><td>swapIn()</td><td>页面换入</td></tr>
 *   <tr><td>swap entry</td><td>SWAPPED_OUT_TO_DISK 指针</td><td>交换条目</td></tr>
 * </table>
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
            log.info("[SwapManager] Swap 目录已就绪: {}", SWAP_DIR);
        } catch (IOException e) {
            log.error("[SwapManager] 创建 Swap 目录失败: {}", e.getMessage());
        }
    }

    /**
     * 将冷上下文条目换出到磁盘。
     *
     * @param agentId     Agent 标识
     * @param coldContext 要换出的冷上下文字符串列表
     * @return 短指针字符串，如 {@code <SWAPPED_OUT_TO_DISK: page_id_101>}
     */
    public String swapOut(String agentId, List<String> coldContext) {
        if (coldContext == null || coldContext.isEmpty()) {
            log.warn("[SwapManager] swapOut 调用时上下文为空 agent={}", agentId);
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
            log.info("[SwapManager] swapOut: Agent={}, pageId={}, entries={}, bytesOnDisk={}",
                    agentId, pageId, coldContext.size(), bytesOnDisk);
            return pointer;
        } catch (IOException e) {
            log.error("[SwapManager] swapOut 失败: Agent={}, pageId={}, error={}",
                    agentId, pageId, e.getMessage());
            return "";
        }
    }

    /**
     * 从磁盘换入恢复上下文 — 类比 Page Fault 的换入。
     *
     * @param pageId 页面标识（从指针字符串中提取）
     * @return 原始上下文字符串列表，失败返回空列表
     */
    public List<String> swapIn(String pageId) {
        Path swapFile = pageRegistry.remove(pageId);
        if (swapFile == null) {
            log.warn("[SwapManager] swapIn: pageId={} 未找到", pageId);
            return List.of();
        }

        try {
            String content = Files.readString(swapFile);
            Files.deleteIfExists(swapFile);
            List<String> restored = List.of(content.split("\n"));
            log.info("[SwapManager] swapIn: pageId={}, entries={}, fileDeleted=true", pageId, restored.size());
            return restored;
        } catch (IOException e) {
            log.error("[SwapManager] swapIn 失败: pageId={}, error={}", pageId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 从指针字符串中提取页面 ID。
     *
     * @param pointer 指针字符串，如 {@code <SWAPPED_OUT_TO_DISK: page_id_101>}
     * @return 提取的页面 ID，无效指针返回 null
     */
    public static String extractPageId(String pointer) {
        if (pointer == null || !pointer.startsWith(POINTER_PREFIX) || !pointer.endsWith(POINTER_SUFFIX)) {
            return null;
        }
        return pointer.substring(POINTER_PREFIX.length(), pointer.length() - POINTER_SUFFIX.length());
    }

    /** 判断字符串是否为换出指针。 */
    public static boolean isSwapPointer(String text) {
        return text != null && text.startsWith(POINTER_PREFIX) && text.endsWith(POINTER_SUFFIX);
    }

    /** 当前换出到磁盘的页面数量。 */
    public int swappedPageCount() {
        return pageRegistry.size();
    }

    /** 交换文件占用的磁盘总字节数。 */
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
