package com.ouisani.aios.operator.session;

import java.util.*;

/**
 * 会话压缩服务 — 对标 OpenClaw 的 Compaction 模块。
 * <p>
 * 当 LLM 上下文 token 数接近窗口上限时，将早期历史压缩为摘要，
 * 保留近期消息，从而控制上下文窗口。
 * <p>
 * 核心流程：
 * <ol>
 *   <li>shouldCompact — 判断是否需要压缩</li>
 *   <li>prepareCompaction — 找到切割点，准备压缩输入</li>
 *   <li>compact — 调用 LLM 生成摘要（由调用方实现）</li>
 *   <li>appendCompaction — 将摘要写入会话</li>
 * </ol>
 */
public class CompactionService {

    /** 压缩设置 */
    public record CompactionSettings(
            boolean enabled,
            long reserveTokens,
            long keepRecentTokens
    ) {
        public static final CompactionSettings DEFAULT = new CompactionSettings(true, 16384, 20000);
    }

    /** 压缩准备结果 */
    public record CompactionPreparation(
            List<AgentMessage> messagesToSummarize,
            List<AgentMessage> turnPrefixMessages,
            String cutPointEntryId,
            String firstKeptEntryId,
            long tokensBefore,
            boolean isSplitTurn,
            CompactionDetails fileOps,
            String previousSummary
    ) {}

    /** 压缩结果 */
    public record CompactionResult(
            String summary,
            String firstKeptEntryId,
            long tokensBefore,
            CompactionDetails details
    ) {}

    /**
     * 判断是否需要压缩。
     * <p>
     * 当 contextTokens > contextWindow - reserveTokens 时触发。
     */
    public static boolean shouldCompact(long contextTokens, long contextWindow, CompactionSettings settings) {
        if (!settings.enabled()) return false;
        return contextTokens > (contextWindow - settings.reserveTokens());
    }

    /**
     * 估算消息列表的 token 数。
     * <p>
     * 保守启发式：4 字符 ≈ 1 token。
     */
    public static long estimateTokens(List<AgentMessage> messages) {
        long total = 0;
        for (AgentMessage msg : messages) {
            total += msg.estimateTokens();
        }
        return total;
    }

    /**
     * 准备压缩 — 找到切割点，收集需要摘要化的消息。
     * <p>
     * 对标 OpenClaw 的 prepareCompaction。
     *
     * @param pathEntries 从 leaf 到 root 的路径条目
     * @param settings    压缩设置
     * @return 压缩准备结果，如果不需要压缩返回 null
     */
    public static CompactionPreparation prepareCompaction(
            List<SessionEntry> pathEntries, CompactionSettings settings) {

        if (pathEntries.isEmpty()) return null;

        // 查找上一个 compaction 的位置
        String previousSummary = null;
        CompactionDetails accumulatedFileOps = new CompactionDetails();
        int boundaryStart = 0;

        for (int i = 0; i < pathEntries.size(); i++) {
            SessionEntry e = pathEntries.get(i);
            if (e.type() == SessionEntry.Type.COMPACTION) {
                boundaryStart = i + 1;
                previousSummary = e.summary();
                if (e.details() != null) {
                    accumulatedFileOps = accumulatedFileOps.merge(e.details());
                }
            }
        }

        // 估算当前 token 数
        List<AgentMessage> allMessages = extractMessages(pathEntries);
        long tokensBefore = estimateTokens(allMessages);

        // 找到切割点
        int cutIndex = findCutPoint(pathEntries, boundaryStart, settings.keepRecentTokens());
        if (cutIndex <= boundaryStart) return null; // 没有足够的历史可压缩

        // 收集需要摘要化的消息
        List<AgentMessage> messagesToSummarize = new ArrayList<>();
        List<AgentMessage> turnPrefixMessages = new ArrayList<>();
        boolean isSplitTurn = false;
        String cutPointEntryId = pathEntries.get(cutIndex).id();
        String firstKeptEntryId = pathEntries.get(cutIndex).id();

        // 检查切割点是否在 assistant turn 中间
        SessionEntry cutEntry = pathEntries.get(cutIndex);
        if (cutEntry.type() == SessionEntry.Type.MESSAGE
                && cutEntry.message().role() == AgentMessage.Role.ASSISTANT) {
            isSplitTurn = true;
            // 收集 turn 前缀消息（从 turn 开始到切割点的 assistant 消息）
            int turnStart = findTurnStartIndex(pathEntries, cutIndex);
            for (int i = turnStart; i <= cutIndex; i++) {
                SessionEntry te = pathEntries.get(i);
                if (te.type() == SessionEntry.Type.MESSAGE) {
                    turnPrefixMessages.add(te.message());
                }
            }
            // 切割点调整为 turn 开始前
            firstKeptEntryId = turnStart > 0 ? pathEntries.get(turnStart).id() : null;
        }

        // 收集切割点之前的消息
        for (int i = boundaryStart; i < cutIndex; i++) {
            SessionEntry e = pathEntries.get(i);
            if (e.type() == SessionEntry.Type.MESSAGE) {
                messagesToSummarize.add(e.message());
            } else if (e.type() == SessionEntry.Type.BRANCH_SUMMARY && e.summary() != null) {
                messagesToSummarize.add(AgentMessage.branchSummary(e.summary()));
            }
        }

        // 收集文件操作
        CompactionDetails fileOps = extractFileOps(pathEntries, boundaryStart, cutIndex);
        fileOps = accumulatedFileOps.merge(fileOps);

        return new CompactionPreparation(
                messagesToSummarize, turnPrefixMessages, cutPointEntryId,
                firstKeptEntryId, tokensBefore, isSplitTurn, fileOps, previousSummary
        );
    }

    /**
     * 找到切割点 — 从末尾向前累计 token，直到 >= keepRecentTokens。
     */
    private static int findCutPoint(List<SessionEntry> entries, int startIndex, long keepRecentTokens) {
        long accumulated = 0;
        int cutIndex = entries.size() - 1;

        for (int i = entries.size() - 1; i >= startIndex; i--) {
            SessionEntry e = entries.get(i);
            if (!e.participatesInContext()) continue;

            // toolResult 不能作为切割点
            if (e.type() == SessionEntry.Type.MESSAGE
                    && e.message().role() == AgentMessage.Role.TOOL_RESULT) {
                continue;
            }

            accumulated += estimateTokensForEntry(e);
            cutIndex = i;

            if (accumulated >= keepRecentTokens) {
                break;
            }
        }

        return cutIndex;
    }

    /**
     * 找到 turn 的起始位置（user 消息或 compaction/branch_summary 之后的第一个消息）。
     */
    private static int findTurnStartIndex(List<SessionEntry> entries, int assistantIndex) {
        for (int i = assistantIndex; i >= 0; i--) {
            SessionEntry e = entries.get(i);
            if (e.type() == SessionEntry.Type.MESSAGE
                    && e.message().role() == AgentMessage.Role.USER) {
                return i;
            }
            if (e.type() == SessionEntry.Type.COMPACTION
                    || e.type() == SessionEntry.Type.BRANCH_SUMMARY) {
                return i + 1;
            }
        }
        return 0;
    }

    private static List<AgentMessage> extractMessages(List<SessionEntry> entries) {
        List<AgentMessage> messages = new ArrayList<>();
        for (SessionEntry e : entries) {
            if (e.type() == SessionEntry.Type.MESSAGE) {
                messages.add(e.message());
            } else if (e.type() == SessionEntry.Type.BRANCH_SUMMARY && e.summary() != null) {
                messages.add(AgentMessage.branchSummary(e.summary()));
            } else if (e.type() == SessionEntry.Type.COMPACTION && e.summary() != null) {
                messages.add(AgentMessage.compactionSummary(e.summary()));
            }
        }
        return messages;
    }

    private static long estimateTokensForEntry(SessionEntry e) {
        return switch (e.type()) {
            case MESSAGE -> e.message().estimateTokens();
            case COMPACTION -> (long) Math.ceil((double) (e.summary() != null ? e.summary().length() : 0) / 4);
            case BRANCH_SUMMARY -> (long) Math.ceil((double) (e.summary() != null ? e.summary().length() : 0) / 4);
            case CUSTOM_MESSAGE -> (long) Math.ceil((double) (e.content() != null ? e.content().length() : 0) / 4);
            default -> 0;
        };
    }

    private static CompactionDetails extractFileOps(List<SessionEntry> entries, int start, int end) {
        List<String> readFiles = new ArrayList<>();
        List<String> modifiedFiles = new ArrayList<>();
        for (int i = start; i < end && i < entries.size(); i++) {
            SessionEntry e = entries.get(i);
            if (e.type() == SessionEntry.Type.MESSAGE && e.message().text() != null) {
                String text = e.message().text();
                // 简单启发式：检测文件操作
                if (text.contains("file_read") || text.contains("FileReadTool")) {
                    extractFilePaths(text, readFiles);
                }
                if (text.contains("file_write") || text.contains("file_edit") || text.contains("FileWriteTool")) {
                    extractFilePaths(text, modifiedFiles);
                }
            }
        }
        return new CompactionDetails(readFiles, modifiedFiles);
    }

    /** 从文本中提取文件路径的简单启发式 */
    private static void extractFilePaths(String text, List<String> target) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(?:path|file)\\s*[:=]\\s*[\"']?([^\"'\\s,}]+)");
        java.util.regex.Matcher m = p.matcher(text);
        while (m.find()) {
            String path = m.group(1);
            if (!target.contains(path)) target.add(path);
        }
    }
}
