package com.ouisani.aios.core.crash;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.llm.LlmProvider;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;

public class SemanticCrashAnalyzer {

    private static final class Holder {
        static final SemanticCrashAnalyzer INSTANCE = new SemanticCrashAnalyzer();
    }

    private volatile LlmProvider llmProvider;

    private SemanticCrashAnalyzer() {}

    public static SemanticCrashAnalyzer instance() {
        return Holder.INSTANCE;
    }

    public void configureLlmProvider(LlmProvider provider) {
        this.llmProvider = provider;
    }

    public void generateCoreDump(String agentId, Throwable throwable, String lastContext) {
        String stackTrace = stackTraceToString(throwable);
        String exceptionClass = throwable.getClass().getName();
        String errorMessage = throwable.getMessage() != null ? throwable.getMessage() : "(no message)";
        String timestamp = Instant.now().toString();

        String dumpJson = buildDumpJson(agentId, exceptionClass, errorMessage, stackTrace, lastContext, timestamp);

        String crashPath = "/var/crash/core_dump_" + agentId + ".json";
        try {
            VfsManager.instance().resolve(crashPath).ifPresent(node -> node.write(dumpJson));
            System.out.printf("  [CrashAnalyzer] Core dump written to %s%n", crashPath);
        } catch (Exception e) {
            System.out.printf("  [CrashAnalyzer] Failed to write core dump to VFS: %s%n", e.getMessage());
        }

        System.err.println();
        System.err.println("  🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨");
        System.err.println("  🚨  KERNEL PANIC — Agent '" + agentId + "' CRASHED!");
        System.err.println("  🚨  Exception: " + exceptionClass + ": " + errorMessage);
        System.err.println("  🚨  Time: " + timestamp);
        System.err.println("  🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨");
        System.err.println();

        if (llmProvider != null) {
            System.err.println("  ── [CrashAnalyzer] Waking up Kernel Debugger LLM... ──");
            try {
                String diagnosis = llmProvider.think(
                        "You are an expert Java/WASM kernel debugger. Analyze the following Semantic Core Dump. "
                                + "Explain WHY the crash occurred, and provide a code-level fix.\n\nDump:\n" + dumpJson,
                        "System: AIOS Kernel Panic Analyzer");

                System.err.println();
                System.err.println("  ╔══════════════════════════════════════════════════════════════════╗");
                System.err.println("  ║  🔬 ROOT CAUSE ANALYSIS (LLM Diagnosis)                        ║");
                System.err.println("  ╚══════════════════════════════════════════════════════════════════╝");
                for (String line : diagnosis.split("\n")) {
                    System.err.println("  │  " + line);
                }
                System.err.println("  ╚══════════════════════════════════════════════════════════════════╝");
                System.err.println();
            } catch (Exception e) {
                System.err.printf("  [CrashAnalyzer] LLM diagnosis failed: %s%n", e.getMessage());
                printLocalAnalysis(exceptionClass, errorMessage, stackTrace, lastContext);
            }
        } else {
            System.err.println("  ── [CrashAnalyzer] No LLM available, performing local analysis ──");
            printLocalAnalysis(exceptionClass, errorMessage, stackTrace, lastContext);
        }
    }

    private void printLocalAnalysis(String exceptionClass, String errorMessage,
                                     String stackTrace, String lastContext) {
        System.err.println();
        System.err.println("  ╔══════════════════════════════════════════════════════════════════╗");
        System.err.println("  ║  🔬 LOCAL ANALYSIS (No LLM)                                    ║");
        System.err.println("  ╠══════════════════════════════════════════════════════════════════╣");
        System.err.printf("  ║  Exception: %s%n", exceptionClass);
        System.err.printf("  ║  Message:   %s%n", errorMessage);
        System.err.println("  ║");
        System.err.println("  ║  Stack Trace (top 5 frames):");

        String[] lines = stackTrace.split("\n");
        int count = Math.min(5, lines.length);
        for (int i = 0; i < count; i++) {
            System.err.printf("  ║    %s%n", lines[i].strip());
        }

        if (lastContext != null && !lastContext.isBlank()) {
            System.err.println("  ║");
            System.err.println("  ║  Last Agent Context:");
            String ctx = lastContext.length() > 200 ? lastContext.substring(0, 200) + "..." : lastContext;
            System.err.printf("  ║    %s%n", ctx);
        }

        System.err.println("  ╚══════════════════════════════════════════════════════════════════╝");
        System.err.println();
    }

    private String buildDumpJson(String agentId, String exceptionClass, String errorMessage,
                                  String stackTrace, String lastContext, String timestamp) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"agentId\": \"").append(escape(agentId)).append("\",\n");
        sb.append("  \"timestamp\": \"").append(timestamp).append("\",\n");
        sb.append("  \"exception\": {\n");
        sb.append("    \"class\": \"").append(escape(exceptionClass)).append("\",\n");
        sb.append("    \"message\": \"").append(escape(errorMessage)).append("\"\n");
        sb.append("  },\n");
        sb.append("  \"stackTrace\": \"").append(escape(stackTrace)).append("\",\n");
        sb.append("  \"lastContext\": \"").append(escape(lastContext != null ? lastContext : "")).append("\"\n");
        sb.append("}");
        return sb.toString();
    }

    private String stackTraceToString(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        String trace = sw.toString();
        if (trace.length() > 4000) {
            trace = trace.substring(0, 4000) + "...(truncated)";
        }
        return trace;
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t");
    }
}
