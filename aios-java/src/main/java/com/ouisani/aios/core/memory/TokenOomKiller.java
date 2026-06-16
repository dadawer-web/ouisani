package com.ouisani.aios.core.memory;

import com.ouisani.aios.operator.session.AgentMessage;
import com.ouisani.aios.core.llm.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Token OOM Killer — 大模型上下文内存的终极守护者。
 * <p>
 * OS 类比: Linux 的 OOM Killer — 当物理内存耗尽时，
 * 内核按策略逐级回收内存，从最轻量的 Page Cache 驱逐到最激进的进程杀除。
 * <p>
 * 本守护进程监控 Agent 的上下文 Token 用量，当逼近模型上下文窗口极限时，
 * 按 7 层递进策略逐级压缩，确保 Agent 永远不会因为上下文溢出而崩溃。
 *
 * @see OomCompressionLevel
 */
public class TokenOomKiller {
    private static final Logger log = LoggerFactory.getLogger(TokenOomKiller.class);

    // 假设当前模型的最大上下文是 128K，我们设定 100K 为触发 OOM Killer 的高水位线 (High Watermark)
    private static final int HIGH_WATERMARK = 100000;

    /**
     * 评估当前的记忆链，如果超出水位线，则执行多层压缩。
     * @param history 当前完整的上下文历史
     * @param cheapModel 用于执行语义总结的廉价大模型 (如 gpt-4o-mini)
     * @return 压缩后健康、安全的上下文历史
     */
    public static List<AgentMessage> ensureMemoryHealth(List<AgentMessage> history, LlmProvider cheapModel) {
        int currentTokens = estimateTokens(history);
        if (currentTokens < HIGH_WATERMARK) {
            return history; // 内存健康，直接放行
        }

        log.warn("[OOM Killer] Memory High Watermark breached! Current: {} tokens. Initiating compaction sequence...", currentTokens);
        List<AgentMessage> compressedHistory = new ArrayList<>(history);

        for (OomCompressionLevel level : OomCompressionLevel.values()) {
            log.info("[OOM Killer] Engaging Level: {}", level.name());

            compressedHistory = applyCompression(level, compressedHistory, cheapModel);
            currentTokens = estimateTokens(compressedHistory);

            if (currentTokens < HIGH_WATERMARK) {
                log.info("[OOM Killer] Memory stabilized at Level {}! New token count: {}", level.name(), currentTokens);
                return compressedHistory;
            }
        }

        throw new RuntimeException("Kernel Panic: OOM Killer exhausted all 7 levels but memory is still overflowing!");
    }

    private static List<AgentMessage> applyCompression(OomCompressionLevel level, List<AgentMessage> history, LlmProvider cheapModel) {
        switch (level) {
            case TOOL_OUTPUT_TRUNCATION:
                // L1: 遍历历史，如果某条消息是长文本工具返回(如网页源码)，且长度>5000字符，直接切断并加上 "...[Truncated by OS]"
                return pruneToolOutputs(history);

            case REMOVE_AI_SLOPS:
                // L2: 删除对话历史中大模型那些不包含具体工具调用的废话回复
                return removeAiSlops(history);

            case SWAP_OUT_HISTORY:
                // L3: 将对话的前 30% 压入 TokenZram (基于特定算法的折叠)
                return swapOutHistory(history);

            case SEMANTIC_COMPRESS:
                // L4: 核心黑魔法：调用 cheapModel，把前 50% 的对话总结为一段致密的摘要
                return semanticSquash(history, cheapModel);

            case AGGRESSIVE_TRUNCATE:
                // L5: 最暴力的手段：抛弃除第一条(System指令)和最后两条之外的所有记录
                return aggressiveTruncate(history);

            case EXTRACT_TO_LONG_TERM:
                // L6: 将当前所有线索提取进向量库，清空除 System Prompt 外的所有内存
                return extractToLongTerm(history);

            case KERNEL_PANIC:
                // L7: 内核恐慌 — 抛出异常让上层建筑决定生死
                throw new RuntimeException("Kernel Panic: Token OOM - all compression levels exhausted!");

            default:
                return history;
        }
    }

    /**
     * L1: 清理缓存 — 截断超长工具输出。
     */
    private static List<AgentMessage> pruneToolOutputs(List<AgentMessage> history) {
        List<AgentMessage> result = new ArrayList<>();
        for (AgentMessage msg : history) {
            if (msg.role() == AgentMessage.Role.TOOL_RESULT && msg.text() != null && msg.text().length() > 5000) {
                String truncated = msg.text().substring(0, 2000) + "\n...[Truncated by OS, original length: "
                        + msg.text().length() + " chars]";
                result.add(AgentMessage.toolResult(msg.toolCallId(), truncated));
            } else {
                result.add(msg);
            }
        }
        log.debug("[OOM Killer] L1 Tool output truncation: {} -> {} messages", history.size(), result.size());
        return result;
    }

    /**
     * L2: 淘汰僵尸进程 — 删除不包含工具调用的短 AI 回复（废话）。
     */
    private static List<AgentMessage> removeAiSlops(List<AgentMessage> history) {
        List<AgentMessage> result = new ArrayList<>();
        for (AgentMessage msg : history) {
            // 保留：非 assistant 消息、包含工具调用的消息、超过 200 字符的实质性回复
            if (msg.role() != AgentMessage.Role.ASSISTANT
                    || (msg.text() != null && msg.text().length() > 200)
                    || (msg.toolCallId() != null && !msg.toolCallId().isBlank())) {
                result.add(msg);
            }
        }
        log.debug("[OOM Killer] L2 Remove AI slops: {} -> {} messages", history.size(), result.size());
        return result;
    }

    /**
     * L3: 压入 ZRAM — 将对话的前 30% 折叠存储。
     */
    private static List<AgentMessage> swapOutHistory(List<AgentMessage> history) {
        if (history.size() <= 3) return history;

        int cutPoint = (int) Math.ceil(history.size() * 0.3);
        // 将前 30% 折叠为一条摘要占位符
        StringBuilder swapped = new StringBuilder();
        swapped.append("[Context Swapped to ZRAM - ").append(cutPoint).append(" messages folded]\n");
        for (int i = 0; i < cutPoint; i++) {
            AgentMessage msg = history.get(i);
            String preview = msg.text() != null && msg.text().length() > 100
                    ? msg.text().substring(0, 100) + "..." : (msg.text() != null ? msg.text() : "");
            swapped.append("[").append(msg.role()).append("] ").append(preview).append("\n");
        }

        List<AgentMessage> result = new ArrayList<>();
        result.add(AgentMessage.compactionSummary(swapped.toString()));
        result.addAll(history.subList(cutPoint, history.size()));

        log.debug("[OOM Killer] L3 Swap out: folded {} messages into 1 placeholder", cutPoint);
        return result;
    }

    /**
     * L4: 语义合并 — 调用廉价大模型将前 50% 对话总结为高密度语义块。
     */
    private static List<AgentMessage> semanticSquash(List<AgentMessage> history, LlmProvider cheapModel) {
        if (history.size() <= 3) return history;

        int cutPoint = (int) Math.ceil(history.size() * 0.5);

        // 拼接前 50% 的对话文本
        StringBuilder toCompress = new StringBuilder();
        for (int i = 0; i < cutPoint; i++) {
            AgentMessage msg = history.get(i);
            toCompress.append("[").append(msg.role()).append("]: ");
            if (msg.text() != null) {
                toCompress.append(msg.text().length() > 500
                        ? msg.text().substring(0, 500) + "..." : msg.text());
            }
            toCompress.append("\n\n");
        }

        try {
            String summary = cheapModel.think(
                    "Summarize the following conversation history into a dense, information-rich paragraph. "
                            + "Preserve all key decisions, tool calls, results, and error states. "
                            + "Do NOT lose any factual information.",
                    toCompress.toString()
            );
            List<AgentMessage> result = new ArrayList<>();
            result.add(AgentMessage.compactionSummary(summary));
            result.addAll(history.subList(cutPoint, history.size()));
            log.debug("[OOM Killer] L4 Semantic compress: {} messages -> 1 summary ({} chars)",
                    cutPoint, summary.length());
            return result;
        } catch (Exception e) {
            log.warn("[OOM Killer] L4 Semantic compress failed (cheap model unavailable), falling through: {}", e.getMessage());
            return history;
        }
    }

    /**
     * L5: 激进截断 — 只保留第一条(System指令)和最后两条对话。
     */
    private static List<AgentMessage> aggressiveTruncate(List<AgentMessage> history) {
        if (history.size() <= 3) return history;
        List<AgentMessage> survival = new ArrayList<>();
        survival.add(history.get(0)); // 保留系统设定
        survival.add(history.get(history.size() - 2));
        survival.add(history.get(history.size() - 1));
        log.debug("[OOM Killer] L5 Aggressive truncate: {} -> 3 messages", history.size());
        return survival;
    }

    /**
     * L6: 提取长牛记忆 — 将所有线索提取进向量库，清空除 System Prompt 外的所有内存。
     */
    private static List<AgentMessage> extractToLongTerm(List<AgentMessage> history) {
        // 将全部对话内容提取到 MemoryDir 向量库
        try {
            StringBuilder allContext = new StringBuilder();
            for (AgentMessage msg : history) {
                if (msg.text() != null && !msg.text().isBlank()) {
                    allContext.append("[").append(msg.role()).append("]: ").append(msg.text()).append("\n\n");
                }
            }
            // 写入 MemoryDir 作为长期记忆
            com.ouisani.aios.core.memory.MemoryDir.instance().save(
                    new com.ouisani.aios.core.memory.MemoryDir.MemoryEntry(
                            "oom-extract-" + System.currentTimeMillis(),
                            com.ouisani.aios.core.memory.MemoryDir.MemoryType.PROJECT,
                            allContext.toString(),
                            System.currentTimeMillis(),
                            new String[]{"oom", "emergency-extract"}
                    )
            );
            log.info("[OOM Killer] L6 Extract to long-term: {} chars saved to MemoryDir", allContext.length());
        } catch (Exception e) {
            log.warn("[OOM Killer] L6 Extract failed: {}", e.getMessage());
        }

        // 只保留第一条消息（通常是系统提示词）
        if (history.isEmpty()) return history;
        List<AgentMessage> result = new ArrayList<>();
        result.add(history.get(0));
        result.add(AgentMessage.compactionSummary(
                "[Emergency Context Extraction] All prior conversation has been extracted to long-term memory. "
                        + "The agent must rely on its persistent knowledge base for continuity."));
        return result;
    }

    /**
     * 估算 Token 数量 — 使用 AgentMessage 自带的 estimateTokens 方法。
     */
    private static int estimateTokens(List<AgentMessage> messages) {
        return messages.stream().mapToInt(AgentMessage::estimateTokens).sum();
    }
}
