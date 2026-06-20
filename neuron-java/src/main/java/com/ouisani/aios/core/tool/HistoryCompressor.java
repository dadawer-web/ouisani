package com.ouisani.aios.core.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 三层历史压缩器 — 借鉴 Agent Zero 的 bulks/topics/current 三级历史压缩机制。
 * <p>
 * 对话历史分三级管理：
 * <ul>
 *   <li><b>recent</b>（最新）— 保留完整消息，不压缩</li>
 *   <li><b>mid</b>（中间）— 摘要压缩，保留关键信息</li>
 *   <li><b>old</b>（最旧）— 合并成 bulk 摘要，只保留要点</li>
 * </ul>
 * <p>
 * 当对话历史超过 token 预算时，自动触发压缩：
 * <ol>
 *   <li>先将 recent 区最旧的消息移入 mid 区</li>
 *   <li>mid 区超限时，将最旧的消息摘要后移入 old 区</li>
 *   <li>old 区超限时，将多个摘要合并成一个 bulk</li>
 * </ol>
 * <p>
 * OS 类比：类似 Linux 内核的页面回收机制 — 活跃页保留在活跃链表，
 * 不活跃页被换出到交换区，最久未访问的页被丢弃。
 */
public class HistoryCompressor {
    private static final Logger log = LoggerFactory.getLogger(HistoryCompressor.class);

    /** 一条对话消息 */
    public record Message(String role, String content, long timestamp) {
        /** 估算 token 数（粗略：4 字符 ≈ 1 token） */
        public int estimatedTokens() {
            return Math.max(1, content.length() / 4);
        }
    }

    /** 压缩后的摘要消息 */
    public record Summary(String content, int originalMessageCount, long timestamp) {}

    // ── 三级历史 ──
    private final List<Message> recent = new ArrayList<>();    // 最新，保留完整
    private final List<Message> mid = new ArrayList<>();       // 中间，摘要
    private final List<Summary> old = new ArrayList<>();       // 最旧，bulk 摘要

    // ── 配置 ──
    private final int tokenBudget;          // 总 token 预算
    private final int recentMaxMessages;     // recent 区最大消息数
    private final int midMaxMessages;        // mid 区最大消息数
    private final int oldMaxSummaries;       // old 区最大摘要数
    private final int largeMessageThreshold; // 大消息阈值（字符数）

    // ── 摘要生成器（函数式接口，避免硬依赖 LLM） ──
    private final SummaryGenerator summaryGenerator;

    @FunctionalInterface
    public interface SummaryGenerator {
        /** 将多条消息摘要成一条 */
        String summarize(List<Message> messages, String hint);
    }

    public HistoryCompressor(int tokenBudget, SummaryGenerator summaryGenerator) {
        this.tokenBudget = tokenBudget;
        this.summaryGenerator = summaryGenerator;
        // 分配：recent 50%, mid 30%, old 20%
        this.recentMaxMessages = Math.max(5, tokenBudget / 2000);
        this.midMaxMessages = Math.max(3, tokenBudget / 4000);
        this.oldMaxSummaries = Math.max(2, tokenBudget / 8000);
        this.largeMessageThreshold = 2000; // 超过 2000 字符的消息视为大消息
    }

    /**
     * 添加一条消息到 recent 区，然后检查是否需要压缩。
     */
    public synchronized void addMessage(String role, String content) {
        recent.add(new Message(role, content, System.currentTimeMillis()));
        compressIfNeeded();
    }

    /**
     * 构建完整的对话历史文本（用于注入 Prompt）。
     * 格式：[旧摘要] + [中间摘要] + [最近完整消息]
     */
    public synchronized String buildHistoryText() {
        StringBuilder sb = new StringBuilder();

        // old 区：bulk 摘要
        if (!old.isEmpty()) {
            sb.append("## Previous Conversation Summary\n");
            for (Summary s : old) {
                sb.append("- ").append(s.content()).append("\n");
            }
            sb.append("\n");
        }

        // mid 区：摘要消息
        if (!mid.isEmpty()) {
            sb.append("## Recent Context (Summarized)\n");
            for (Message m : mid) {
                sb.append("[").append(m.role()).append("]: ").append(m.content()).append("\n");
            }
            sb.append("\n");
        }

        // recent 区：完整消息
        if (!recent.isEmpty()) {
            sb.append("## Current Conversation\n");
            for (Message m : recent) {
                sb.append("[").append(m.role()).append("]: ").append(m.content()).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 获取当前总 token 估算值。
     */
    public synchronized int estimatedTokens() {
        int total = 0;
        for (Message m : recent) total += m.estimatedTokens();
        for (Message m : mid) total += m.estimatedTokens();
        for (Summary s : old) total += Math.max(1, s.content().length() / 4);
        return total;
    }

    /**
     * 检查是否需要压缩，如果需要则执行压缩。
     * <p>
     * 压缩策略（按优先级）：
     * <ol>
     *   <li>recent 区超限 → 最旧消息移入 mid 区</li>
     *   <li>mid 区超限 → 最旧消息摘要后移入 old 区</li>
     *   <li>old 区超限 → 多个摘要合并成一个 bulk</li>
     *   <li>大消息截断 → 超过阈值的消息截断</li>
     * </ol>
     */
    private void compressIfNeeded() {
        boolean changed = true;
        int iterations = 0;
        while (changed && iterations < 10) {
            changed = false;
            iterations++;

            int total = estimatedTokens();

            // 未超预算，无需压缩
            if (total <= tokenBudget && recent.size() <= recentMaxMessages) {
                break;
            }

            // 策略 1：recent 区超限 → 最旧消息移入 mid 区
            if (recent.size() > recentMaxMessages) {
                Message oldest = recent.remove(0);
                mid.add(oldest);
                log.debug("[HistoryCompressor] recent→mid: 消息移入中间区 ({} chars)", oldest.content().length());
                changed = true;
            }

            // 策略 2：mid 区超限 → 最旧消息摘要后移入 old 区
            if (mid.size() > midMaxMessages) {
                Message oldest = mid.remove(0);
                List<Message> toSummarize = new ArrayList<>();
                toSummarize.add(oldest);

                String summaryText;
                if (summaryGenerator != null) {
                    summaryText = summaryGenerator.summarize(toSummarize, "Summarize this conversation message concisely");
                } else {
                    // 无摘要器时，简单截断
                    summaryText = truncate(oldest.content(), 200);
                }

                old.add(new Summary(summaryText, 1, System.currentTimeMillis()));
                log.debug("[HistoryCompressor] mid→old: 消息已摘要 ({}→{} chars)",
                        oldest.content().length(), summaryText.length());
                changed = true;
            }

            // 策略 3：old 区超限 → 合并最旧的摘要
            if (old.size() > oldMaxSummaries) {
                Summary oldest1 = old.remove(0);
                Summary oldest2 = old.remove(0);
                String merged = oldest1.content() + " | " + oldest2.content();
                old.add(0, new Summary(merged, oldest1.originalMessageCount() + oldest2.originalMessageCount(), System.currentTimeMillis()));
                log.debug("[HistoryCompressor] old bulk merge: 合并 {} 条摘要", 2);
                changed = true;
            }

            // 策略 4：大消息截断
            for (int i = 0; i < recent.size(); i++) {
                Message m = recent.get(i);
                if (m.content().length() > largeMessageThreshold) {
                    String truncated = truncate(m.content(), largeMessageThreshold);
                    recent.set(i, new Message(m.role(), truncated, m.timestamp()));
                    log.debug("[HistoryCompressor] 大消息截断: {}→{} chars", m.content().length(), truncated.length());
                    changed = true;
                }
            }
        }
    }

    /** 截断文本，保留首尾 */
    private String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        int headLen = maxChars * 2 / 3;
        int tailLen = maxChars - headLen;
        return text.substring(0, headLen)
                + "\n...[truncated " + (text.length() - maxChars) + " chars]...\n"
                + text.substring(text.length() - tailLen);
    }

    // ── 统计信息 ──
    public synchronized int recentCount() { return recent.size(); }
    public synchronized int midCount() { return mid.size(); }
    public synchronized int oldCount() { return old.size(); }

    public synchronized void clear() {
        recent.clear();
        mid.clear();
        old.clear();
    }
}
