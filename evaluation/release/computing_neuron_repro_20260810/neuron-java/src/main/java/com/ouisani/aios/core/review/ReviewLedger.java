package com.ouisani.aios.core.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Review 持久化账本 — 镜像 {@link com.ouisani.aios.core.provenance.ProvenanceHook} 的设计：
 * 静态 + best-effort + {@link FileChannel} 追加 + 内存缓冲（最近 1024 条）。
 * <p>
 * 每次 {@link ReviewGate} 裁决后追加一条 {@link ReviewRecord} 到 {@code .aios/review.jsonl}。
 * <p>
 * <b>Best-effort</b>：所有异常 catch，<b>永不中断 agent 主流程</b>（同 ProvenanceHook 原则：
 * recording must never break the chat flow）。
 * <p>
 * <b>不经 SyscallDispatcher/BpfManager</b>：直接 {@link FileChannel} 写，因此不触发
 * PRIVILEGE_ESCALATION 令牌校验（同 ProvenanceHook.appendRecord 路径）。
 *
 * @see ReviewRecord
 * @see ReviewGate
 */
public final class ReviewLedger {

    private static final Logger log = LoggerFactory.getLogger(ReviewLedger.class);

    /** Review JSONL 文件路径（与 .aios/provenance.jsonl 并列） */
    private static volatile Path reviewFile = Paths.get(".aios", "review.jsonl");

    /** 全局启用开关 — 默认启用 */
    private static volatile boolean enabled = true;

    /** 内存缓冲（最近 N 条）— 支持快速 listByAgent/listByTargetPath 查询 */
    private static final List<ReviewRecord> recentBuffer = new ArrayList<>();
    private static final int BUFFER_CAPACITY = 1024;
    private static final Object bufferLock = new Object();

    private ReviewLedger() {}

    // ════════════════════════════════════════════════════════════════
    //  主入口 — ReviewGate 裁决后调用
    // ════════════════════════════════════════════════════════════════

    /**
     * 追加一条 review 记录。Best-effort：永不抛出。
     */
    public static void append(ReviewRecord record) {
        if (!enabled || record == null) {
            return;
        }
        try {
            appendRecord(record);
        } catch (Throwable t) {
            log.warn("[ReviewLedger] 记录失败 (agent={}): {}", record.agentId(), t.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  查询 — 支持后续「记忆查看器」UI
    // ════════════════════════════════════════════════════════════════

    /**
     * 按 agentId 查询所有 review 记录（从内存缓冲）。
     */
    public static List<ReviewRecord> listByAgent(String agentId) {
        if (agentId == null) return List.of();
        synchronized (bufferLock) {
            List<ReviewRecord> result = new ArrayList<>();
            for (ReviewRecord r : recentBuffer) {
                if (agentId.equals(r.agentId())) {
                    result.add(r);
                }
            }
            return result;
        }
    }

    /**
     * 按 targetPath 查询所有 review 记录（从内存缓冲）— 关联 provenance 记录。
     */
    public static List<ReviewRecord> listByTargetPath(String targetPath) {
        if (targetPath == null) return List.of();
        synchronized (bufferLock) {
            List<ReviewRecord> result = new ArrayList<>();
            for (ReviewRecord r : recentBuffer) {
                if (targetPath.equals(r.targetPath())) {
                    result.add(r);
                }
            }
            return result;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  配置 — 启用/禁用 + 文件路径（测试用）
    // ════════════════════════════════════════════════════════════════

    public static void setEnabled(boolean enabled) {
        ReviewLedger.enabled = enabled;
        log.info("[ReviewLedger] enabled={}", enabled);
    }

    public static void setReviewFile(Path file) {
        ReviewLedger.reviewFile = file;
        log.info("[ReviewLedger] file={}", file);
    }

    /**
     * 获取 Review JSONL 文件路径（Phase 6：供 {@link com.ouisani.aios.core.provenance.ProvenanceQuery} 跨 session 回读）。
     */
    public static Path reviewFile() {
        return reviewFile;
    }

    /**
     * 重置所有内存状态 — 仅测试使用。不影响已写入的 JSONL 文件。
     */
    public static void resetForTesting() {
        synchronized (bufferLock) {
            recentBuffer.clear();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  内部 — JSONL 追加（同 ProvenanceHook.appendRecord 模式）
    // ════════════════════════════════════════════════════════════════

    private static void appendRecord(ReviewRecord record) throws IOException {
        // 1. 追加到内存缓冲
        synchronized (bufferLock) {
            if (recentBuffer.size() >= BUFFER_CAPACITY) {
                // FIFO 淘汰最旧的 1/4
                recentBuffer.subList(0, BUFFER_CAPACITY / 4).clear();
            }
            recentBuffer.add(record);
        }

        // 2. 追加到 JSONL 文件（持久化）
        Path file = reviewFile;
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        String line = record.toJsonLine() + "\n";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(file,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            ch.write(java.nio.ByteBuffer.wrap(bytes));
        }
    }
}
