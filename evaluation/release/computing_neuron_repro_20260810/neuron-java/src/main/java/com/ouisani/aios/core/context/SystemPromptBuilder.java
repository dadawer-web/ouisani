package com.ouisani.aios.core.context;

import com.ouisani.aios.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 系统提示词构建器 — 对标 Claude Code 的 context.ts。
 * <p>
 * 构建完整的系统提示词，包含：
 * - Agent 角色定义
 * - 工具描述和使用指南
 * - CLAUDE.md 项目指令
 * - Git 状态
 * - 当前日期时间
 * - 工作目录信息
 * <p>
 * OS 类比：相当于 Linux 的 /proc/cmdline + /proc/version — 内核启动参数。
 */
public class SystemPromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptBuilder.class);
    private static final int MAX_GIT_STATUS_LENGTH = 2000;

    /**
     * 构建完整的系统提示词。
     */
    public static String build(String workingDir, String extraContext) {
        StringBuilder sb = new StringBuilder();

        // ── 1. 核心身份 ──
        sb.append("# AIOS Agent\n\n");
        sb.append("You are an AIOS agent — an intelligent coding assistant with access to tools for file operations, ");
        sb.append("code search, web access, and sub-agent delegation.\n\n");

        // ── 2. 工具描述 ──
        sb.append("## Available Tools\n\n");
        sb.append(ToolRegistry.instance().toolsDescription());

        // ── 3. 工具调用格式 ──
        sb.append("## Tool Call Format\n");
        sb.append("To call a tool, use XML tags with the tool name and JSON parameters:\n");
        sb.append("<tool_name>{\"param\":\"value\"}</tool_name>\n");
        sb.append("You may call multiple tools in one response. After receiving tool results, continue your reasoning.\n\n");

        // ── 4. CLAUDE.md 项目指令 ──
        List<ClaudeMdLoader.MemoryFileInfo> claudeMds = ClaudeMdLoader.loadAll(workingDir);
        String claudeMdContent = ClaudeMdLoader.formatAsPrompt(claudeMds);
        if (!claudeMdContent.isEmpty()) {
            sb.append("## Project Instructions\n\n");
            sb.append(claudeMdContent);
        }

        // ── 5. Git 状态 ──
        String gitStatus = getGitStatus(workingDir);
        if (gitStatus != null && !gitStatus.isEmpty()) {
            sb.append("## Git Status\n```\n").append(gitStatus).append("\n```\n\n");
        }

        // ── 6. 环境信息 ──
        sb.append("## Environment\n");
        sb.append("- Working Directory: ").append(workingDir).append("\n");
        sb.append("- Date: ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n");
        sb.append("- OS: ").append(System.getProperty("os.name")).append("\n\n");

        // ── 7. 额外上下文 ──
        if (extraContext != null && !extraContext.isEmpty()) {
            sb.append("## Additional Context\n\n");
            sb.append(extraContext).append("\n\n");
        }

        // ── 8. 行为准则 ──
        sb.append("## Guidelines\n");
        sb.append("- Prefer absolute paths over relative paths\n");
        sb.append("- Use dedicated file tools (file_read/file_edit/file_write) instead of bash for file operations\n");
        sb.append("- Use grep/glob for searching before reading files\n");
        sb.append("- For complex tasks, consider delegating to a sub-agent using the agent tool\n");
        sb.append("- Always verify your changes by reading the file after editing\n");

        String prompt = sb.toString();

        // ── P5: CacheAligner 前缀稳定化检测 — 借鉴 Headroom cache_aligner ──
        // 纯检测不改写：检测 prompt 中的动态内容（UUID/时间戳/JWT/哈希），
        // 这些会破坏 LLM provider 的 KV cache 前缀匹配。
        // 注意：上面的 LocalDateTime.now() 就是动态内容，每次请求都不同！
        try {
            com.ouisani.aios.core.compact.CacheAligner.instance().detectVolatileContent(prompt);
        } catch (Exception e) {
            log.debug("[SystemPromptBuilder] CacheAligner 检测失败: {}", e.getMessage());
        }

        // ── P4: OutputTokenReducer 啰嗦度转向 — 借鉴 Headroom verbosity_steerer ──
        // 在 system prompt 尾部追加啰嗦度指令（追加在尾部而非头部，保护 KV cache 前缀）
        try {
            prompt = com.ouisani.aios.core.compact.OutputTokenReducer.instance()
                    .applyVerbositySteering(prompt);
        } catch (Exception e) {
            log.debug("[SystemPromptBuilder] Verbosity steering 失败: {}", e.getMessage());
        }

        return prompt;
    }

    public static String build(String workingDir) {
        return build(workingDir, "");
    }

    /**
     * 获取 Git 状态 — 对标 getGitStatus()。
     */
    private static String getGitStatus(String workingDir) {
        try {
            StringBuilder result = new StringBuilder();

            // git branch
            result.append(runGitCommand("git branch --show-current", workingDir)).append("\n");

            // git status
            String status = runGitCommand("git status --short", workingDir);
            if (!status.isEmpty()) {
                result.append(status).append("\n");
            }

            // git log
            result.append(runGitCommand("git log --oneline -5", workingDir));

            String output = result.toString().trim();
            return output.length() > MAX_GIT_STATUS_LENGTH
                    ? output.substring(0, MAX_GIT_STATUS_LENGTH) + "..."
                    : output;
        } catch (Exception e) {
            log.debug("[SystemPromptBuilder] Git 状态不可用: {}", e.getMessage());
            return null;
        }
    }

    private static String runGitCommand(String command, String workingDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.directory(new java.io.File(workingDir));
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            process.destroyForcibly();
            return output.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }
}
