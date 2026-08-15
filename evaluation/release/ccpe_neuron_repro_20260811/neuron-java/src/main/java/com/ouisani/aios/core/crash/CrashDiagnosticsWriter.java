package com.ouisani.aios.core.crash;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.cgroup.TokenOomException;
import com.ouisani.aios.core.security.ObjectManager;
import com.ouisani.aios.core.syscall.SyscallException;
import com.ouisani.aios.vfs.MutableFileNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 崩溃诊断输出工具 — 负责蓝屏打印、VFS 转储写入及崩溃分析辅助方法。
 * <p>
 * 从 {@link SemanticCrashAnalyzer} 抽离以保持单文件可维护性（ratchet budget）。
 * 所有方法均为 {@code static} 且包级可见，由 SemanticCrashAnalyzer 直接调用。
 */
final class CrashDiagnosticsWriter {

    private static final Logger log = LoggerFactory.getLogger(CrashDiagnosticsWriter.class);

    private CrashDiagnosticsWriter() {}

    // ════════════════════════════════════════════════════════════════
    //  辅助方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 分类崩溃类型。
     */
    static SemanticCoreDump.CrashCategory classifyCrash(Throwable throwable) {
        if (throwable instanceof TokenOomException) return SemanticCoreDump.CrashCategory.TOKEN_OOM;
        if (throwable instanceof SyscallException) return SemanticCoreDump.CrashCategory.SYSCALL_FAULT;
        if (throwable instanceof SecurityException) return SemanticCoreDump.CrashCategory.SECURITY_VIOLATION;

        String msg = throwable.getMessage() != null ? throwable.getMessage().toLowerCase() : "";
        if (msg.contains("deadlock") || msg.contains("infinite") || msg.contains("loop")) {
            return SemanticCoreDump.CrashCategory.LOGIC_DEADLOCK;
        }
        if (msg.contains("hallucination") || msg.contains("unparseable") || msg.contains("invalid json")) {
            return SemanticCoreDump.CrashCategory.SEVERE_HALLUCINATION;
        }

        if (throwable instanceof OutOfMemoryError || throwable instanceof StackOverflowError) {
            return SemanticCoreDump.CrashCategory.FATAL_ERROR;
        }
        if (throwable instanceof Error) return SemanticCoreDump.CrashCategory.FATAL_ERROR;
        if (throwable instanceof RuntimeException) return SemanticCoreDump.CrashCategory.RUNTIME_EXCEPTION;

        return SemanticCoreDump.CrashCategory.UNKNOWN;
    }

    /**
     * 打印语义蓝屏。
     */
    static void printBlueScreen(String agentId, Throwable throwable,
                                SemanticCoreDump.CrashCategory category) {
        System.err.println();
        System.err.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.err.println("  ║                                                              ║");
        System.err.println("  ║   *** SEMANTIC KERNEL PANIC ***                              ║");
        System.err.println("  ║                                                              ║");
        System.err.printf("  ║   Agent: %-50s ║%n", agentId);
        System.err.printf("  ║   Category: %-47s ║%n", category);
        System.err.printf("  ║   Exception: %-46s ║%n", throwable.getClass().getSimpleName());
        System.err.printf("  ║   Message: %-49s ║%n",
                truncate(throwable.getMessage() != null ? throwable.getMessage() : "(none)", 49));
        System.err.println("  ║                                                              ║");
        System.err.println("  ║   The agent has been SUSPENDED.                              ║");
        System.err.println("  ║   A semantic core dump is being generated...                 ║");
        System.err.println("  ║                                                              ║");
        System.err.println("  ╚══════════════════════════════════════════════════════════════╝");
        System.err.println();
    }

    /**
     * 打印 LLM 诊断结果。
     */
    static void printDiagnosis(String agentId, String diagnosis) {
        System.err.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.err.println("  ║  ROOT CAUSE ANALYSIS (LLM Diagnosis)                        ║");
        System.err.printf("  ║  Agent: %-50s ║%n", agentId);
        System.err.println("  ╠══════════════════════════════════════════════════════════════╣");
        for (String line : diagnosis.split("\n")) {
            System.err.printf("  ║  %s%n", truncate(line, 60));
        }
        System.err.println("  ╚══════════════════════════════════════════════════════════════╝");
        System.err.println();
    }

    /**
     * 本地诊断（无 LLM 时的回退方案）。
     */
    static void printLocalDiagnosis(SemanticCoreDump dump) {
        System.err.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.err.println("  ║  LOCAL ANALYSIS (No LLM available)                          ║");
        System.err.println("  ╠══════════════════════════════════════════════════════════════╣");
        System.err.printf("  ║  Category: %-47s ║%n", dump.crashInfo().category());
        System.err.printf("  ║  Exception: %-46s ║%n", dump.crashInfo().exceptionClass());
        System.err.printf("  ║  Message: %-49s ║%n", truncate(dump.crashInfo().exceptionMessage(), 49));

        if (dump.cognitiveSnapshot() != null) {
            System.err.printf("  ║  Cache entries: %-42d ║%n", dump.cognitiveSnapshot().cacheEntryCount());
            System.err.printf("  ║  Last thinking: %-42s ║%n",
                    truncate(dump.cognitiveSnapshot().lastThinking(), 42));
        }

        if (dump.cgroupUsage() != null) {
            System.err.printf("  ║  Token usage: %d/%d (%d%%)%n",
                    dump.cgroupUsage().tokenConsumed(), dump.cgroupUsage().tokenQuota(),
                    dump.cgroupUsage().usagePercent());
        }

        System.err.println("  ╚══════════════════════════════════════════════════════════════╝");
        System.err.println();
    }

    /**
     * 将核心转储写入 VFS: /var/crash/dump_{pid}.aios
     * <p>
     * 防元崩溃保护：如果 toJson() 本身抛出异常（如字段提取触发了二次崩溃），
     * 写入一条最小化的 fallback 转储，确保崩溃处理主流程不会被打断。
     */
    static void writeDumpToVfs(String agentId, SemanticCoreDump coreDump) {
        String crashPath = "/var/crash/dump_" + agentId + ".aios";
        try {
            String dumpJson;
            try {
                dumpJson = coreDump.toJson();
            } catch (Exception toJsonError) {
                // toJson 自身崩溃 — 写入最小化 fallback
                log.warn("[CrashAnalyzer] coreDump.toJson() 失败，写入最小回退: {}",
                        toJsonError.getMessage());
                dumpJson = "{\"crashInfo\":{\"agentId\":\"" + escJson(agentId)
                        + "\",\"exceptionClass\":\"" + escJson(coreDump.crashInfo().exceptionClass())
                        + "\",\"exceptionMessage\":\"toJson() failed: "
                        + escJson(toJsonError.getClass().getSimpleName()) + "\"}}";
            }

            // 防止超大 JSON 写入 VFS 导致 OOM
            if (dumpJson.length() > 100_000) {
                dumpJson = dumpJson.substring(0, 100_000) + "\n... (truncated at 100KB)";
            }

            var nodeOpt = VfsManager.instance().resolve(crashPath);
            if (nodeOpt.isPresent()) {
                nodeOpt.get().write(dumpJson);
            } else {
                MutableFileNode dumpNode = new MutableFileNode(crashPath);
                dumpNode.write(dumpJson);
                VfsManager.instance().mount("/var/crash", "dump_" + agentId + ".aios", dumpNode);
            }
            log.info("[CrashAnalyzer] 核心转储已写入 {}", crashPath);
            System.out.printf("  [CrashAnalyzer] Core dump written to %s (%d bytes)%n",
                    crashPath, dumpJson.length());
        } catch (Exception e) {
            log.warn("[CrashAnalyzer] Failed to write core dump to VFS: {}", e.getMessage());
        }
    }

    static String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t");
    }

    /**
     * 清理 Agent 资源 — 关闭句柄、解绑 cgroup。
     */
    static void cleanupAgentResources(String agentId) {
        try {
            // 关闭所有 VFS 句柄
            int closed = ObjectManager.instance().closeAllHandlesForAgent(agentId);
            if (closed > 0) {
                log.info("[CrashAnalyzer] Closed {} VFS handles for crashed agent={}", closed, agentId);
            }
        } catch (Exception e) {
            log.warn("[CrashAnalyzer] Resource cleanup failed for agent={}: {}", agentId, e.getMessage());
        }
    }

    static String readDiagnosis(String agentId) {
        try {
            String path = "/var/crash/diagnosis_" + agentId + ".aios";
            var nodeOpt = VfsManager.instance().resolve(path);
            return nodeOpt.map(VfsNode::read).orElse("(no diagnosis file)");
        } catch (Exception e) {
            return "(diagnosis read failed)";
        }
    }

    static void writeToFile(String vfsPath, String content) {
        try {
            var nodeOpt = VfsManager.instance().resolve(vfsPath);
            if (nodeOpt.isPresent()) {
                nodeOpt.get().write(content);
            } else {
                String name = vfsPath.substring(vfsPath.lastIndexOf('/') + 1);
                String dir = vfsPath.substring(0, vfsPath.lastIndexOf('/'));
                MutableFileNode node = new MutableFileNode(vfsPath);
                node.write(content);
                VfsManager.instance().mount(dir, name, node);
            }
        } catch (Exception e) {
            log.warn("[CrashAnalyzer] Failed to write to {}: {}", vfsPath, e.getMessage());
        }
    }

    static int resolvePid(String agentId) {
        try {
            return Integer.parseInt(agentId);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static AgentTask resolveTask(int pid) {
        if (pid < 0) return null;
        try {
            TaskScheduler scheduler = VfsManager.instance().getTaskScheduler();
            return scheduler != null ? scheduler.getTask(pid) : null;
        } catch (Exception e) {
            return null;
        }
    }

    static String extractLastContextFromTask(String agentId) {
        int pid = resolvePid(agentId);
        AgentTask task = resolveTask(pid);
        if (task == null) return "(no task context)";

        var history = task.contextHistory();
        if (history == null || history.isEmpty()) return "(no context history)";
        int size = history.size();
        int from = Math.max(0, size - 3);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < size; i++) {
            if (i > from) sb.append(" | ");
            String entry = history.get(i);
            sb.append(entry.length() > 200 ? entry.substring(0, 200) + "..." : entry);
        }
        return sb.toString();
    }

    static String stackTraceToString(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        String trace = sw.toString();
        if (trace.length() > 4000) {
            trace = trace.substring(0, 4000) + "...(truncated)";
        }
        return trace;
    }

    static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}
