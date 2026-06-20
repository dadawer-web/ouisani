package com.ouisani.aios.core.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话记忆服务 — 对标 Claude Code 的 SessionMemory。
 * <p>
 * 在自动压缩时提取关键信息，确保压缩后上下文不丢失：
 * - 双重阈值触发（token 增长 + 工具调用次数）
 * - 10 个固定 section 的 Markdown 模板
 * - Section 大小分析与截断
 * <p>
 * OS 类比：相当于 Linux 的 kexec — 内核切换时保留关键内存页。
 */
public class SessionMemoryService {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryService.class);

    // ── 配置常量 ──
    private static final int MINIMUM_MESSAGE_TOKENS_TO_INIT = 10000;
    private static final int MINIMUM_TOKENS_BETWEEN_UPDATE = 5000;
    private static final int TOOL_CALLS_BETWEEN_UPDATES = 3;
    private static final int MAX_SECTION_TOKENS = 2000;
    private static final int MAX_TOTAL_TOKENS = 12000;

    /** 记忆 Section 定义 */
    public enum Section {
        SESSION_TITLE("Session Title", "Brief title describing the current session's purpose"),
        CURRENT_STATE("Current State", "What is the current state of the task? What has been accomplished?"),
        TASK_SPECIFICATION("Task Specification", "What was the original task? What are the requirements?"),
        FILES_AND_FUNCTIONS("Files and Functions", "Key files and functions involved in the task"),
        WORKFLOW("Workflow", "Step-by-step description of the approach taken"),
        ERRORS_AND_CORRECTIONS("Errors & Corrections", "Errors encountered and how they were fixed"),
        CODEBASE_DOCUMENTATION("Codebase and System Documentation", "Important documentation discovered during the session"),
        LEARNINGS("Learnings", "Key learnings and insights from this session"),
        KEY_RESULTS("Key Results", "Important results, outputs, or decisions"),
        WORKLOG("Worklog", "Chronological log of major actions taken");

        private final String title;
        private final String description;

        Section(String title, String description) {
            this.title = title;
            this.description = description;
        }

        public String title() { return title; }
        public String description() { return description; }
    }

    /** 记忆内容 */
    public static class SessionMemory {
        private final Map<Section, String> sections = new LinkedHashMap<>();
        private final AtomicLong lastExtractedTokens = new AtomicLong(0);
        private final AtomicInteger toolCallsSinceUpdate = new AtomicInteger(0);
        private volatile long lastUpdatedAt = 0;

        public SessionMemory() {
            for (Section section : Section.values()) {
                sections.put(section, "");
            }
        }

        public String getSection(Section section) { return sections.getOrDefault(section, ""); }

        public void setSection(Section section, String content) {
            sections.put(section, content != null ? content : "");
        }

        public Map<Section, String> getAllSections() { return Collections.unmodifiableMap(sections); }

        public long getLastExtractedTokens() { return lastExtractedTokens.get(); }
        public void setLastExtractedTokens(long tokens) { lastExtractedTokens.set(tokens); }

        public int getToolCallsSinceUpdate() { return toolCallsSinceUpdate.get(); }
        public void incrementToolCalls() { toolCallsSinceUpdate.incrementAndGet(); }
        public void resetToolCalls() { toolCallsSinceUpdate.set(0); }

        public long getLastUpdatedAt() { return lastUpdatedAt; }
        public void setLastUpdatedAt(long timestamp) { this.lastUpdatedAt = timestamp; }
    }

    /**
     * 判断是否应该提取记忆 — 双重阈值机制。
     */
    public static boolean shouldExtractMemory(SessionMemory memory, long currentTokens) {
        long tokenGrowth = currentTokens - memory.getLastExtractedTokens();
        int toolCalls = memory.getToolCallsSinceUpdate();

        // Token 增长阈值 AND 工具调用次数阈值
        boolean tokenThreshold = tokenGrowth >= MINIMUM_TOKENS_BETWEEN_UPDATE;
        boolean toolThreshold = toolCalls >= TOOL_CALLS_BETWEEN_UPDATES;

        // 至少需要达到初始 token 量
        if (currentTokens < MINIMUM_MESSAGE_TOKENS_TO_INIT) return false;

        return tokenThreshold && toolThreshold;
    }

    /**
     * 格式化记忆为 Markdown — 对标 Markdown 模板系统。
     */
    public static String formatAsMarkdown(SessionMemory memory) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Session Memory\n\n");

        for (Map.Entry<Section, String> entry : memory.getAllSections().entrySet()) {
            String content = entry.getValue();
            if (content != null && !content.isEmpty()) {
                sb.append("## ").append(entry.getKey().title()).append("\n\n");
                sb.append(content).append("\n\n");
            }
        }

        return sb.toString();
    }

    /**
     * 截断记忆以适应 Token 限制 — 对标 truncateSessionMemoryForCompact()。
     */
    public static String truncateForCompact(SessionMemory memory, int maxTokens) {
        String full = formatAsMarkdown(memory);
        int maxChars = maxTokens * 4; // 粗略估算：1 token ≈ 4 chars

        if (full.length() <= maxChars) return full;

        // 按 Section 优先级截断：Worklog 最先被截断，Session Title 最后
        StringBuilder sb = new StringBuilder();
        sb.append("# Session Memory\n\n");
        int remaining = maxChars - sb.length();

        for (Section section : Section.values()) {
            String content = memory.getSection(section);
            if (content == null || content.isEmpty()) continue;

            String sectionHeader = "## " + section.title() + "\n\n";
            int sectionBudget = Math.min(remaining, MAX_SECTION_TOKENS * 4);

            if (sectionBudget <= sectionHeader.length()) continue;

            sb.append(sectionHeader);
            int contentBudget = sectionBudget - sectionHeader.length();

            if (content.length() <= contentBudget) {
                sb.append(content).append("\n\n");
            } else {
                // 在行边界截断
                String truncated = content.substring(0, contentBudget);
                int lastNewline = truncated.lastIndexOf('\n');
                if (lastNewline > contentBudget / 2) {
                    truncated = truncated.substring(0, lastNewline);
                }
                sb.append(truncated).append("\n... [truncated]\n\n");
            }

            remaining = maxChars - sb.length();
            if (remaining <= 0) break;
        }

        return sb.toString();
    }

    /**
     * 构建记忆提取 Prompt — 供 LLM 填充各 Section。
     */
    public static String buildExtractionPrompt(SessionMemory memory, String conversationHistory) {
        StringBuilder sb = new StringBuilder();
        sb.append("Analyze the following conversation and extract key information into a structured memory format.\n\n");
        sb.append("For each section below, provide a concise summary. If a section is not applicable, leave it empty.\n\n");

        for (Section section : Section.values()) {
            sb.append("### ").append(section.title()).append("\n");
            sb.append(section.description()).append("\n\n");
        }

        sb.append("--- CONVERSATION ---\n\n");
        sb.append(conversationHistory);
        sb.append("\n\n--- END ---\n\n");
        sb.append("Output each section with its title as a markdown heading, followed by the content.\n");

        return sb.toString();
    }
}
