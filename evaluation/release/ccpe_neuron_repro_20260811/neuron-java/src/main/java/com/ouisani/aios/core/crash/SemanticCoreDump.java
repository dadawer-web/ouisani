package com.ouisani.aios.core.crash;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 语义核心转储 — Agent 崩溃时的完整认知快照。
 * <p>
 * 当进程崩溃时，不要只打印 Java StackTrace。SemanticCoreDump 收集 Agent 的
 * "认知快照"，就像 Linux 的 core dump 收集进程的内存映像一样。
 *
 * <h3>OS 类比: Linux Core Dump + Windows Minidump</h3>
 * <ul>
 *   <li>Linux core dump → 内存映像 + 寄存器状态 + 信号信息</li>
 *   <li>Windows Minidump → 线程栈 + 模块列表 + 内存区域</li>
 *   <li>AIOS SemanticCoreDump → 认知状态 + 上下文历史 + 缓存记忆 + 句柄 + 插件</li>
 * </ul>
 *
 * <h3>核心转储内容</h3>
 * <ol>
 *   <li>{@code crashInfo} — 崩溃元数据（异常类型、消息、栈追踪、时间戳）</li>
 *   <li>{@code cognitiveSnapshot} — 认知快照（最后在想什么：缓存记忆页摘要）</li>
 *   <li>{@code contextHistory} — 最后 5 轮对话上下文</li>
 *   <li>{@code openHandles} — Agent 打开的 VFS 文件句柄列表</li>
 *   <li>{@code loadedPlugins} — Agent 挂载的插件列表</li>
 *   <li>{@code cgroupUsage} — Token 消耗统计</li>
 *   <li>{@code securityContext} — 安全令牌信息</li>
 *   <li>{@code taskMetadata} — AgentTask 元数据（PID、优先级、Gas 等）</li>
 * </ol>
 *
 * @see SemanticCrashAnalyzer
 */
public record SemanticCoreDump(

        /** 崩溃元数据 */
        CrashInfo crashInfo,

        /** 认知快照：SemanticCacheManager 中该 Agent 最后在想的记忆页摘要 */
        CognitiveSnapshot cognitiveSnapshot,

        /** 最后 5 轮对话上下文 */
        List<String> contextHistory,

        /** Agent 打开的 VFS 文件句柄列表 */
        List<HandleSnapshot> openHandles,

        /** Agent 挂载的插件列表 */
        List<String> loadedPlugins,

        /** Token 消耗统计 (cgroup) */
        CgroupUsage cgroupUsage,

        /** 安全令牌信息 */
        String securityContext,

        /** AgentTask 元数据 */
        TaskMetadata taskMetadata

) {
    /**
     * 崩溃元数据
     */
    public record CrashInfo(
            String agentId,
            String timestamp,
            String exceptionClass,
            String exceptionMessage,
            String stackTrace,
            CrashCategory category
    ) {}

    /**
     * 崩溃分类
     */
    public enum CrashCategory {
        /** Token 配额耗尽 */
        TOKEN_OOM,
        /** 系统调用异常 */
        SYSCALL_FAULT,
        /** 安全策略拦截 */
        SECURITY_VIOLATION,
        /** 逻辑死循环（无限重试/反思） */
        LOGIC_DEADLOCK,
        /** 严重幻觉（输出不可解析） */
        SEVERE_HALLUCINATION,
        /** 运行时异常 */
        RUNTIME_EXCEPTION,
        /** 致命错误（OutOfMemoryError/StackOverflowError） */
        FATAL_ERROR,
        /** 未知崩溃 */
        UNKNOWN
    }

    /**
     * 认知快照 — Agent 最后在想什么
     */
    public record CognitiveSnapshot(
            /** 缓存中的记忆条目数 */
            int cacheEntryCount,
            /** 最后 3 条缓存记忆的摘要 */
            List<String> recentCacheSummaries,
            /** Agent 最后的思考逻辑（从 contextHistory 提取） */
            String lastThinking
    ) {}

    /**
     * VFS 句柄快照
     */
    public record HandleSnapshot(
            int handle,
            String vfsPath,
            long openedAtMs
    ) {}

    /**
     * Token 消耗统计
     */
    public record CgroupUsage(
            String cgroupNode,
            long tokenQuota,
            long tokenConsumed,
            long tokenRemaining,
            int usagePercent
    ) {}

    /**
     * AgentTask 元数据
     */
    public record TaskMetadata(
            int pid,
            String taskType,
            String processPriority,
            int gasUsed,
            int gasLimit,
            int budget,
            String status
    ) {}

    /**
     * 将核心转储序列化为 JSON，写入 /var/crash/dump_{pid}.aios
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");

        // crashInfo
        sb.append("  \"crashInfo\": {\n");
        sb.append("    \"agentId\": \"").append(esc(crashInfo.agentId)).append("\",\n");
        sb.append("    \"timestamp\": \"").append(esc(crashInfo.timestamp)).append("\",\n");
        sb.append("    \"exceptionClass\": \"").append(esc(crashInfo.exceptionClass)).append("\",\n");
        sb.append("    \"exceptionMessage\": \"").append(esc(crashInfo.exceptionMessage)).append("\",\n");
        sb.append("    \"category\": \"").append(esc(crashInfo.category.name())).append("\",\n");
        sb.append("    \"stackTrace\": \"").append(esc(crashInfo.stackTrace)).append("\"\n");
        sb.append("  },\n");

        // cognitiveSnapshot
        sb.append("  \"cognitiveSnapshot\": {\n");
        if (cognitiveSnapshot != null) {
            sb.append("    \"cacheEntryCount\": ").append(cognitiveSnapshot.cacheEntryCount).append(",\n");
            sb.append("    \"recentCacheSummaries\": [");
            if (cognitiveSnapshot.recentCacheSummaries != null) {
                for (int i = 0; i < cognitiveSnapshot.recentCacheSummaries.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(esc(cognitiveSnapshot.recentCacheSummaries.get(i))).append("\"");
                }
            }
            sb.append("],\n");
            sb.append("    \"lastThinking\": \"").append(esc(cognitiveSnapshot.lastThinking)).append("\"\n");
        } else {
            sb.append("    \"cacheEntryCount\": 0,\n");
            sb.append("    \"recentCacheSummaries\": [],\n");
            sb.append("    \"lastThinking\": \"(unavailable)\"\n");
        }
        sb.append("  },\n");

        // contextHistory
        sb.append("  \"contextHistory\": [");
        if (contextHistory != null) {
            for (int i = 0; i < contextHistory.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\n    \"").append(esc(contextHistory.get(i))).append("\"");
            }
        }
        if (contextHistory != null && !contextHistory.isEmpty()) sb.append("\n  ");
        sb.append("],\n");

        // openHandles
        sb.append("  \"openHandles\": [");
        if (openHandles != null) {
            for (int i = 0; i < openHandles.size(); i++) {
                if (i > 0) sb.append(",");
                HandleSnapshot h = openHandles.get(i);
                sb.append("\n    {\"handle\": \"0x").append(Integer.toHexString(h.handle).toUpperCase())
                  .append("\", \"path\": \"").append(esc(h.vfsPath))
                  .append("\", \"openedAt\": ").append(h.openedAtMs).append("}");
            }
        }
        if (openHandles != null && !openHandles.isEmpty()) sb.append("\n  ");
        sb.append("],\n");

        // loadedPlugins
        sb.append("  \"loadedPlugins\": [");
        if (loadedPlugins != null) {
            for (int i = 0; i < loadedPlugins.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(esc(loadedPlugins.get(i))).append("\"");
            }
        }
        sb.append("],\n");

        // cgroupUsage
        sb.append("  \"cgroupUsage\": ");
        if (cgroupUsage != null) {
            sb.append("{\n");
            sb.append("    \"node\": \"").append(esc(cgroupUsage.cgroupNode)).append("\",\n");
            sb.append("    \"quota\": ").append(cgroupUsage.tokenQuota).append(",\n");
            sb.append("    \"consumed\": ").append(cgroupUsage.tokenConsumed).append(",\n");
            sb.append("    \"remaining\": ").append(cgroupUsage.tokenRemaining).append(",\n");
            sb.append("    \"usagePercent\": ").append(cgroupUsage.usagePercent).append("\n");
            sb.append("  },\n");
        } else {
            sb.append("null,\n");
        }

        // securityContext
        sb.append("  \"securityContext\": \"").append(esc(securityContext != null ? securityContext : "unknown")).append("\",\n");

        // taskMetadata
        sb.append("  \"taskMetadata\": ");
        if (taskMetadata != null) {
            sb.append("{\n");
            sb.append("    \"pid\": ").append(taskMetadata.pid).append(",\n");
            sb.append("    \"type\": \"").append(esc(taskMetadata.taskType)).append("\",\n");
            sb.append("    \"priority\": \"").append(esc(taskMetadata.processPriority)).append("\",\n");
            sb.append("    \"gasUsed\": ").append(taskMetadata.gasUsed).append(",\n");
            sb.append("    \"gasLimit\": ").append(taskMetadata.gasLimit).append(",\n");
            sb.append("    \"budget\": ").append(taskMetadata.budget).append(",\n");
            sb.append("    \"status\": \"").append(esc(taskMetadata.status)).append("\"\n");
            sb.append("  }\n");
        } else {
            sb.append("null\n");
        }

        sb.append("}");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t");
    }
}
