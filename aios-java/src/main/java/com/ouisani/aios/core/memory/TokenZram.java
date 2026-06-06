package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.cgroup.CgroupNode;
import com.ouisani.aios.core.llm.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class TokenZram {

    private static final Logger log = LoggerFactory.getLogger(TokenZram.class);

    /**
     * After ZRAM compression, if the cgroup is still above this ratio of its
     * hard limit, trigger disk swap-out (kswapd).
     */
    private static final double SWAP_THRESHOLD_RATIO = 0.95;

    private static final class Holder {
        static final TokenZram INSTANCE = new TokenZram();
    }

    private volatile LlmProvider llmProvider;

    /** ZRAM 压缩存储：handle → 压缩后的二进制数据 */
    private final ConcurrentHashMap<String, byte[]> zramStore = new ConcurrentHashMap<>();

    /** ZRAM 元数据：handle → ZramEntry */
    private final ConcurrentHashMap<String, ZramEntry> zramMeta = new ConcurrentHashMap<>();

    private TokenZram() {}

    public static TokenZram instance() {
        return Holder.INSTANCE;
    }

    public void configureLlmProvider(LlmProvider provider) {
        this.llmProvider = provider;
    }

    public void compressMemory(AgentTask task, CgroupNode cgroup) {
        List<String> history = task.contextHistory();
        if (history.size() < 2) {
            log.info("[Token ZRAM] Agent#{} has insufficient history ({} entries), skip compression",
                    task.pid(), history.size());
            return;
        }

        int splitPoint = history.size() / 2;

        List<String> coldEntries = new ArrayList<>(history.subList(0, splitPoint));

        StringBuilder coldData = new StringBuilder();
        for (int i = 0; i < coldEntries.size(); i++) {
            coldData.append(coldEntries.get(i));
            if (i < coldEntries.size() - 1) coldData.append("\n");
        }

        long coldTokens = Math.max(1, coldData.length() / 4);

        String compressed;
        if (llmProvider != null && llmProvider.isAvailable()) {
            log.info("[Token ZRAM] Compressing Agent#{} cold memory ({} entries, ~{} tokens)...",
                    task.pid(), splitPoint, coldTokens);
            try {
                compressed = llmProvider.think(
                        "Please summarize the following context compactly, preserving all key information:\n" + coldData,
                        "System: Compression Engine");
            } catch (Exception e) {
                log.warn("[Token ZRAM] LLM compression failed, using truncation: {}", e.getMessage());
                compressed = truncateCompress(coldData.toString());
            }
        } else {
            log.info("[Token ZRAM] No LLM available, using truncation compression for Agent#{}", task.pid());
            compressed = truncateCompress(coldData.toString());
        }

        String zramBlock = "<ZRAM_COMPRESSED>" + compressed + "</ZRAM_COMPRESSED>";
        long compressedTokens = Math.max(1, zramBlock.length() / 4);
        long savedTokens = coldTokens - compressedTokens;

        task.replaceHistoryRange(0, splitPoint, zramBlock);

        if (savedTokens > 0 && cgroup != null) {
            long refunded = cgroup.refundTokens(savedTokens);
            log.info("[Token ZRAM] Compressed Agent#{}: cold={} entries (~{} tokens), compressed=~{} tokens, refunded={} to cgroup '{}'",
                    task.pid(), splitPoint, coldTokens, compressedTokens, refunded, cgroup.name());
        } else {
            log.info("[Token ZRAM] Agent#{} compressed but no tokens saved (cold={}, compressed={})",
                    task.pid(), coldTokens, compressedTokens);
        }

        // --- Kswapd: if still above hard limit after compression, swap out to disk ---
        if (cgroup != null && isStillOverHardLimit(cgroup)) {
            swapOutToDisk(task, cgroup);
        }
    }

    /**
     * Check whether the cgroup is still consuming above the swap threshold
     * ratio of its hard limit after ZRAM compression.
     */
    private boolean isStillOverHardLimit(CgroupNode cgroup) {
        long quota = cgroup.tokenQuota();
        long consumed = cgroup.tokenConsumed();
        long swapThreshold = (long) (quota * SWAP_THRESHOLD_RATIO);
        boolean over = consumed > swapThreshold;
        if (over) {
            log.warn("[Token ZRAM] Cgroup '{}' still at {}/{} ({}%) after compression, triggering kswapd swap-out",
                    cgroup.name(), consumed, quota, (consumed * 100 / quota));
        }
        return over;
    }

    /**
     * Evict the oldest (non-pointer) history entries to disk via SwapManager,
     * replacing them with a short swap pointer to free Token budget.
     */
    private void swapOutToDisk(AgentTask task, CgroupNode cgroup) {
        List<String> history = task.contextHistory();

        // Collect swappable entries: skip entries that are already ZRAM blocks or swap pointers
        List<String> swappable = new ArrayList<>();
        List<Integer> swappableIndices = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            String entry = history.get(i);
            if (!entry.startsWith("<ZRAM_COMPRESSED>") && !SwapManager.isSwapPointer(entry)) {
                swappable.add(entry);
                swappableIndices.add(i);
            }
        }

        if (swappable.isEmpty()) {
            log.warn("[Token ZRAM] kswapd: Agent#{} has no swappable entries, cannot swap out", task.pid());
            return;
        }

        // Swap out the first half of swappable entries
        int swapCount = Math.max(1, swappable.size() / 2);
        List<String> toSwap = swappable.subList(0, swapCount);

        String pointer = SwapManager.instance().swapOut(String.valueOf(task.pid()), toSwap);

        if (pointer.isEmpty()) {
            log.error("[Token ZRAM] kswapd: swapOut failed for Agent#{}", task.pid());
            return;
        }

        // Calculate tokens freed by the swap
        long swappedTokens = 0;
        for (String entry : toSwap) {
            swappedTokens += Math.max(1, entry.length() / 4);
        }
        long pointerTokens = Math.max(1, pointer.length() / 4);
        long netSaved = swappedTokens - pointerTokens;

        // Replace the swapped entries in history with the pointer
        // We replace from the end to keep indices valid
        for (int i = swapCount - 1; i >= 0; i--) {
            int histIdx = swappableIndices.get(i);
            task.contextHistory().remove(histIdx);
        }
        task.contextHistory().add(swappableIndices.get(0), pointer);

        if (netSaved > 0) {
            long refunded = cgroup.refundTokens(netSaved);
            log.info("[Token ZRAM] kswapd: Agent#{} swapped {} entries to disk, pointer='{}', freed={} tokens, refunded={} to cgroup '{}'",
                    task.pid(), swapCount, pointer, netSaved, refunded, cgroup.name());
        } else {
            log.info("[Token ZRAM] kswapd: Agent#{} swapped {} entries to disk, pointer='{}'",
                    task.pid(), swapCount, pointer);
        }
    }

    private String truncateCompress(String text) {
        if (text.length() <= 200) return text;
        return text.substring(0, 100) + "...[TRUNCATED]..." + text.substring(text.length() - 100);
    }

    // ════════════════════════════════════════════════════════════════
    //  SOM 集成：语义对象压缩存储与解压恢复
    // ════════════════════════════════════════════════════════════════

    /**
     * 将语义对象的完整内容压缩存入 ZRAM — 类比 Linux ZRAM 的页面压缩。
     * <p>
     * 当 SomWindowController 折叠一个 SemanticObject 时，原始数据
     * 通过 GZIP 压缩后存入 zramStore。返回一个句柄（handle），
     * 后续可通过 {@link #decompressFromZram} 解压恢复。
     *
     * @param pid      Agent PID
     * @param objectId 语义对象 ID
     * @param content  完整内容
     * @return ZRAM 句柄
     */
    public String compressToZram(int pid, String objectId, String content) {
        String handle = "zram://" + pid + "/" + objectId;

        try {
            // GZIP 压缩
            byte[] rawBytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
                gzip.write(rawBytes);
            }
            byte[] compressed = baos.toByteArray();

            // 存入 ZRAM
            zramStore.put(handle, compressed);
            zramMeta.put(handle, new ZramEntry(
                    handle, pid, objectId,
                    rawBytes.length, compressed.length,
                    System.currentTimeMillis()
            ));

            double ratio = (double) compressed.length / rawBytes.length * 100;
            log.info("[TokenZram] Compressed: handle={}, original={}B, compressed={}B, ratio={:.1f}%",
                    handle, rawBytes.length, compressed.length, ratio);

            return handle;

        } catch (IOException e) {
            log.error("[TokenZram] Compression failed: {}", e.getMessage());
            // 回退：直接存储未压缩数据
            byte[] rawBytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            zramStore.put(handle, rawBytes);
            zramMeta.put(handle, new ZramEntry(handle, pid, objectId,
                    rawBytes.length, rawBytes.length, System.currentTimeMillis()));
            return handle;
        }
    }

    /**
     * 从 ZRAM 解压恢复语义对象的完整内容 — 类比 Page Fault 的换入。
     * <p>
     * 当 LLM 在推理中需要展开某个"语义指针"时，
     * 调用此方法从 ZRAM 中解压恢复完整内容。
     *
     * @param handle ZRAM 句柄
     * @return 解压后的完整内容，如果句柄无效返回 null
     */
    public String decompressFromZram(String handle) {
        byte[] compressed = zramStore.get(handle);
        if (compressed == null) {
            log.warn("[TokenZram] Handle not found: {}", handle);
            return null;
        }

        try {
            // GZIP 解压
            ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
            try (GZIPInputStream gzip = new GZIPInputStream(bais)) {
                byte[] decompressed = gzip.readAllBytes();
                return new String(decompressed, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            // 可能是未压缩的数据，直接返回
            return new String(compressed, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /**
     * 删除 ZRAM 中的压缩数据。
     */
    public boolean removeFromZram(String handle) {
        byte[] removed = zramStore.remove(handle);
        zramMeta.remove(handle);
        return removed != null;
    }

    /**
     * 获取 ZRAM 存储统计。
     */
    public int zramEntryCount() {
        return zramStore.size();
    }

    public long zramTotalOriginalBytes() {
        return zramMeta.values().stream().mapToLong(e -> e.originalSize).sum();
    }

    public long zramTotalCompressedBytes() {
        return zramMeta.values().stream().mapToLong(e -> e.compressedSize).sum();
    }

    public double zramCompressionRatio() {
        long original = zramTotalOriginalBytes();
        long compressed = zramTotalCompressedBytes();
        return original > 0 ? (double) compressed / original : 0;
    }

    /**
     * ZRAM 条目元数据。
     */
    record ZramEntry(
            String handle,
            int pid,
            String objectId,
            int originalSize,
            int compressedSize,
            long timestamp
    ) {}
}
