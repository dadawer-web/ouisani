package com.ouisani.aios.core.lsp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LSP 管理器 — 对标 Claude Code 的 src/services/lsp/ 模块。
 * <p>
 * 管理多种语言的 LSP 服务器实例，提供代码智能功能：
 * - 服务器生命周期管理（启动/停止）
 * - 诊断信息获取
 * - 跳转到定义
 * - 查找引用
 * - 代码补全
 * <p>
 * OS 类比：相当于 Linux 的设备驱动管理器 — 每种语言一个驱动（LSP 服务器）。
 */
public class LspManager {

    private static final Logger log = LoggerFactory.getLogger(LspManager.class);
    private static final LspManager INSTANCE = new LspManager();

    /** LSP 诊断信息 */
    public record LspDiagnostic(
            String filePath,
            int line,
            String severity,  // error, warning, info, hint
            String message
    ) {}

    /** LSP 位置信息 */
    public record LspLocation(
            String filePath,
            int line,
            int col
    ) {}

    /** 支持的语言及其对应的 LSP 服务器启动命令 */
    private static final Map<String, List<String>> LANGUAGE_COMMANDS = Map.of(
            "python", List.of("pylsp"),
            "java", List.of("jdtls"),
            "typescript", List.of("typescript-language-server", "--stdio"),
            "go", List.of("gopls"),
            "rust", List.of("rust-analyzer")
    );

    /** 活跃的 LSP 服务器进程，键为语言名称 */
    private final ConcurrentHashMap<String, Process> activeServers = new ConcurrentHashMap<>();

    /** 各语言对应的工作区根路径 */
    private final ConcurrentHashMap<String, String> workspaceRoots = new ConcurrentHashMap<>();

    /** 各服务器收集的诊断信息 */
    private final ConcurrentHashMap<String, List<LspDiagnostic>> diagnosticsMap = new ConcurrentHashMap<>();

    private LspManager() {}

    /**
     * 获取单例实例。
     */
    public static LspManager instance() {
        return INSTANCE;
    }

    /**
     * 启动指定语言的 LSP 服务器。
     *
     * @param language     语言名称（python, java, typescript, go, rust）
     * @param workspaceRoot 工作区根路径
     * @return 是否启动成功
     */
    public boolean startServer(String language, String workspaceRoot) {
        if (!LANGUAGE_COMMANDS.containsKey(language)) {
            log.warn("[LspManager] 不支持的语言: {}", language);
            return false;
        }

        // 如果已有该语言的服务器在运行，先停止
        if (activeServers.containsKey(language)) {
            log.info("[LspManager] 语言 {} 的服务器已在运行，先停止", language);
            stopServer(language);
        }

        List<String> command = LANGUAGE_COMMANDS.get(language);
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(workspaceRoot));
            pb.redirectErrorStream(true);
            Process process = pb.start();

            activeServers.put(language, process);
            workspaceRoots.put(language, workspaceRoot);
            diagnosticsMap.put(language, Collections.synchronizedList(new ArrayList<>()));

            log.info("[LspManager] 已启动 {} LSP 服务器 (PID: {}), 工作区: {}", language, process.pid(), workspaceRoot);
            return true;
        } catch (IOException e) {
            log.error("[LspManager] 启动 {} LSP 服务器失败: {}", language, e.getMessage());
            return false;
        }
    }

    /**
     * 停止指定语言的 LSP 服务器。
     *
     * @param language 语言名称
     */
    public void stopServer(String language) {
        Process process = activeServers.remove(language);
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            log.info("[LspManager] 已停止 {} LSP 服务器", language);
        }
        workspaceRoots.remove(language);
        diagnosticsMap.remove(language);
    }

    /**
     * 获取指定文件的诊断信息。
     *
     * @param filePath 文件路径
     * @return 该文件的所有诊断信息列表
     */
    public List<LspDiagnostic> getDiagnostics(String filePath) {
        List<LspDiagnostic> result = new ArrayList<>();
        for (List<LspDiagnostic> diagnostics : diagnosticsMap.values()) {
            for (LspDiagnostic d : diagnostics) {
                if (d.filePath().equals(filePath)) {
                    result.add(d);
                }
            }
        }
        // 按严重程度排序：error > warning > info > hint
        result.sort((a, b) -> severityOrder(a.severity()) - severityOrder(b.severity()));
        return result;
    }

    /**
     * 跳转到定义。
     *
     * @param filePath 文件路径
     * @param line     行号（从 0 开始）
     * @param col      列号（从 0 开始）
     * @return 定义位置，未找到则返回 null
     */
    public LspLocation goToDefinition(String filePath, int line, int col) {
        String language = detectLanguage(filePath);
        if (language == null || !activeServers.containsKey(language)) {
            log.debug("[LspManager] 无法跳转到定义：语言 {} 的服务器未运行", language);
            return null;
        }

        log.debug("[LspManager] 请求跳转到定义: {}:{}:{}", filePath, line, col);
        // TODO: 通过 LSP 协议发送 textDocument/definition 请求并解析响应
        // 当前为框架实现，后续需对接真实的 LSP JSON-RPC 通信
        return null;
    }

    /**
     * 查找所有引用。
     *
     * @param filePath 文件路径
     * @param line     行号（从 0 开始）
     * @param col      列号（从 0 开始）
     * @return 引用位置列表
     */
    public List<LspLocation> findReferences(String filePath, int line, int col) {
        String language = detectLanguage(filePath);
        if (language == null || !activeServers.containsKey(language)) {
            log.debug("[LspManager] 无法查找引用：语言 {} 的服务器未运行", language);
            return List.of();
        }

        log.debug("[LspManager] 请求查找引用: {}:{}:{}", filePath, line, col);
        // TODO: 通过 LSP 协议发送 textDocument/references 请求并解析响应
        // 当前为框架实现，后续需对接真实的 LSP JSON-RPC 通信
        return List.of();
    }

    /**
     * 获取代码补全建议。
     *
     * @param filePath 文件路径
     * @param line     行号（从 0 开始）
     * @param col      列号（从 0 开始）
     * @return 补全项列表（当前返回标签列表）
     */
    public List<String> getCompletions(String filePath, int line, int col) {
        String language = detectLanguage(filePath);
        if (language == null || !activeServers.containsKey(language)) {
            log.debug("[LspManager] 无法获取补全：语言 {} 的服务器未运行", language);
            return List.of();
        }

        log.debug("[LspManager] 请求代码补全: {}:{}:{}", filePath, line, col);
        // TODO: 通过 LSP 协议发送 textDocument/completion 请求并解析响应
        // 当前为框架实现，后续需对接真实的 LSP JSON-RPC 通信
        return List.of();
    }

    /**
     * 根据文件路径检测语言类型。
     */
    private String detectLanguage(String filePath) {
        if (filePath.endsWith(".py")) return "python";
        if (filePath.endsWith(".java")) return "java";
        if (filePath.endsWith(".ts") || filePath.endsWith(".tsx")) return "typescript";
        if (filePath.endsWith(".go")) return "go";
        if (filePath.endsWith(".rs")) return "rust";
        return null;
    }

    /**
     * 严重程度排序权重。
     */
    private int severityOrder(String severity) {
        return switch (severity.toLowerCase()) {
            case "error" -> 0;
            case "warning" -> 1;
            case "info" -> 2;
            case "hint" -> 3;
            default -> 4;
        };
    }

    /**
     * 停止所有 LSP 服务器。
     */
    public void stopAll() {
        for (String language : List.copyOf(activeServers.keySet())) {
            stopServer(language);
        }
    }
}
