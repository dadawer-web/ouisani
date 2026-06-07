package com.ouisani.aios.core.lsp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * LSP 管理器 — 对标 Claude Code 的 LSP Server Manager。
 * <p>
 * 管理多个 LSP 服务器实例，按文件扩展名路由请求：
 * - 服务器生命周期管理（状态机：stopped→starting→running→stopped）
 * - 文件同步（open/change/save/close）
 * - 诊断收集与去重
 * - 崩溃恢复与限流
 * <p>
 * OS 类比：相当于 Linux 的设备驱动管理器 — 每种语言一个驱动（LSP 服务器）。
 */
public class LspManager {

    private static final Logger log = LoggerFactory.getLogger(LspManager.class);
    private static final LspManager INSTANCE = new LspManager();

    /** LSP 服务器状态 */
    public enum ServerState { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

    /** LSP 诊断 */
    public record Diagnostic(
            String filePath,
            int line,
            int column,
            String severity,  // error, warning, info, hint
            String message,
            String source
    ) {}

    /** LSP 服务器配置 */
    public record LspServerConfig(
            String name,
            List<String> command,
            List<String> extensions,
            int maxRestarts,
            long startupTimeoutMs
    ) {
        public LspServerConfig(String name, List<String> command, List<String> extensions) {
            this(name, command, extensions, 3, 30000);
        }
    }

    /** LSP 服务器实例 */
    public static class LspServerInstance {
        private final String name;
        private final LspServerConfig config;
        private volatile ServerState state = ServerState.STOPPED;
        private volatile Process process;
        private int crashCount = 0;
        private final List<Diagnostic> diagnostics = Collections.synchronizedList(new ArrayList<>());

        public LspServerInstance(String name, LspServerConfig config) {
            this.name = name;
            this.config = config;
        }

        public String name() { return name; }
        public ServerState state() { return state; }
        public void setState(ServerState s) { this.state = s; }
        public List<Diagnostic> diagnostics() { return Collections.unmodifiableList(diagnostics); }
        public void addDiagnostic(Diagnostic d) { diagnostics.add(d); }
        public void clearDiagnostics() { diagnostics.clear(); }
    }

    private final Map<String, LspServerInstance> servers = new ConcurrentHashMap<>();
    private final Map<String, String> extensionToServer = new ConcurrentHashMap<>();

    private LspManager() {}

    public static LspManager instance() { return INSTANCE; }

    /**
     * 注册 LSP 服务器配置。
     */
    public void registerServer(LspServerConfig config) {
        LspServerInstance instance = new LspServerInstance(config.name(), config);
        servers.put(config.name(), instance);

        for (String ext : config.extensions()) {
            extensionToServer.put(ext, config.name());
        }

        log.info("[LspManager] Registered server: {} for extensions: {}", config.name(), config.extensions());
    }

    /**
     * 启动 LSP 服务器。
     */
    public boolean startServer(String name) {
        LspServerInstance instance = servers.get(name);
        if (instance == null) return false;

        if (instance.state() == ServerState.RUNNING) return true;

        if (instance.crashCount >= instance.config.maxRestarts()) {
            log.warn("[LspManager] Server {} exceeded max restarts ({})", name, instance.config.maxRestarts());
            instance.setState(ServerState.ERROR);
            return false;
        }

        try {
            instance.setState(ServerState.STARTING);

            ProcessBuilder pb = new ProcessBuilder(instance.config.command());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            instance.process = process;

            // 等待初始化
            boolean started = process.waitFor(instance.config.startupTimeoutMs(), TimeUnit.MILLISECONDS);
            if (started && process.isAlive()) {
                instance.setState(ServerState.RUNNING);
                log.info("[LspManager] Server {} started (PID: {})", name, process.pid());
                return true;
            } else {
                instance.setState(ServerState.ERROR);
                instance.crashCount++;
                process.destroyForcibly();
                log.warn("[LspManager] Server {} failed to start", name);
                return false;
            }
        } catch (Exception e) {
            instance.setState(ServerState.ERROR);
            instance.crashCount++;
            log.error("[LspManager] Server {} start error: {}", name, e.getMessage());
            return false;
        }
    }

    /**
     * 停止 LSP 服务器。
     */
    public void stopServer(String name) {
        LspServerInstance instance = servers.get(name);
        if (instance == null || instance.process == null) return;

        instance.setState(ServerState.STOPPING);
        instance.process.destroyForcibly();
        instance.setState(ServerState.STOPPED);
        log.info("[LspManager] Server {} stopped", name);
    }

    /**
     * 获取文件的诊断信息。
     */
    public List<Diagnostic> getDiagnostics(String filePath) {
        List<Diagnostic> result = new ArrayList<>();
        for (LspServerInstance server : servers.values()) {
            for (Diagnostic d : server.diagnostics()) {
                if (d.filePath().equals(filePath)) {
                    result.add(d);
                }
            }
        }
        // 按严重程度排序
        result.sort((a, b) -> severityOrder(a.severity()) - severityOrder(b.severity()));
        return result.stream().limit(30).toList(); // 每文件最多 30 条
    }

    /**
     * 获取所有诊断信息。
     */
    public List<Diagnostic> getAllDiagnostics() {
        List<Diagnostic> result = new ArrayList<>();
        for (LspServerInstance server : servers.values()) {
            result.addAll(server.diagnostics());
        }
        return result;
    }

    /**
     * 按扩展名查找服务器。
     */
    public Optional<LspServerInstance> getServerForFile(String filePath) {
        int dotIdx = filePath.lastIndexOf('.');
        if (dotIdx < 0) return Optional.empty();
        String ext = filePath.substring(dotIdx);
        String serverName = extensionToServer.get(ext);
        return Optional.ofNullable(serverName != null ? servers.get(serverName) : null);
    }

    private int severityOrder(String severity) {
        return switch (severity.toLowerCase()) {
            case "error" -> 0;
            case "warning" -> 1;
            case "info" -> 2;
            case "hint" -> 3;
            default -> 4;
        };
    }

    public Collection<LspServerInstance> allServers() {
        return Collections.unmodifiableCollection(servers.values());
    }

    public void stopAll() {
        servers.keySet().forEach(this::stopServer);
    }
}
