package com.ouisani.aios.core.sandbox;

import com.ouisani.aios.core.VfsManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 本地执行后端 — 在宿主机文件系统直接执行的原生 BackendBase 实现。
 * <p>
 * 把现有 BashTool / GrepTool / GlobTool / FileReadTool / FileWriteTool /
 * FileEditTool 中散落的「VFS 路径翻译 + ProcessBuilder + 宿主机文件 I/O」逻辑
 * 集中收敛到七个原语，工具代码只依赖 {@link BackendBase} 接口，不再感知后端类型。
 * <p>
 * <b>路径策略</b>：
 * <ul>
 *   <li>{@code read_file} / {@code write_file} / {@code file_exists} / {@code list_dir}
 *       优先走 {@link VfsManager}（命名空间隔离、WAL、Provenance 追溯、OverlayFS），
 *       VFS 中不存在时回退到宿主机真实文件系统（与原 FileReadTool 行为一致）；</li>
 *   <li>{@code exec_shell} 在执行前先经 {@link VfsManager#translateVfsPathsInCommand}
 *       把 VFS 路径前缀翻译为宿主机物理路径，再交给 {@code bash -c} 执行。</li>
 * </ul>
 * <p>
 * <b>环境变量注入</b>：默认注入非交互式 shell 环境（{@code DEBIAN_FRONTEND=noninteractive}
 * 等），并通过 {@link ExecOptions#env()} 支持调用方追加（如 PYTHONPATH）。
 * <p>
 * OS 类比：相当于 Linux 内核默认的 ext4 + bash 组合 — 没有容器化、没有云沙箱，
 * 但满足 {@link BackendBase} 契约，未来 {@code DockerBackend} / {@code E2BBackend}
 * 可直接替换而工具无感知。
 *
 * @see BackendBase
 * @see VfsManager
 */
public final class LocalBackend implements BackendBase {

    private static final Logger log = LoggerFactory.getLogger(LocalBackend.class);

    /** 单例 — 本地后端无状态，全局复用即可。 */
    private static final LocalBackend INSTANCE = new LocalBackend();

    public static LocalBackend instance() {
        return INSTANCE;
    }

    private LocalBackend() {}

    // ════════════════════════════════════════════════════════════════
    //  文件 I/O 原语
    // ════════════════════════════════════════════════════════════════

    @Override
    public boolean write_file(String path, String content) {
        if (path == null || path.isBlank()) return false;
        if (content == null) content = "";

        VfsManager vfs = VfsManager.instance();
        // 路径分类：VFS 托管路径（有物理映射或已是 VFS 节点）走 VfsManager；
        //           其余视为宿主机路径，直接写物理文件系统。
        boolean vfsManaged = (vfs.findPhysicalWorkspace(path) != null) || vfs.exists(path);
        if (vfsManaged) {
            try {
                return vfs.writeText(path, content);
            } catch (Exception e) {
                log.warn("[LocalBackend] write_file 通过 VFS 失败 '{}': {}", path, e.getMessage());
                return false;
            }
        }
        // 宿主机路径直写 — 自动创建父目录
        try {
            Path p = Paths.get(path);
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Files.writeString(p, content, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            log.error("[LocalBackend] write_file 宿主机直写失败 '{}': {}", path, e.getMessage());
            return false;
        }
    }

    @Override
    public String read_file(String path) {
        if (path == null || path.isBlank()) return null;
        // 1. 优先从 VFS 读取（与原 FileReadTool 一致）
        VfsManager vfs = VfsManager.instance();
        try {
            if (vfs.exists(path)) {
                String content = vfs.readText(path);
                if (content != null) return content;
            }
        } catch (Exception e) {
            log.debug("[LocalBackend] VFS read 失败 '{}', 回退宿主机: {}", path, e.getMessage());
        }
        // 2. VFS 中不存在 → 回退宿主机真实文件系统
        try {
            Path hostPath = Paths.get(path);
            if (Files.exists(hostPath) && Files.isRegularFile(hostPath)) {
                return Files.readString(hostPath, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("[LocalBackend] 宿主机 read 失败 '{}': {}", path, e.getMessage());
        }
        return null;
    }

    @Override
    public boolean file_exists(String path) {
        if (path == null || path.isBlank()) return false;
        try {
            if (VfsManager.instance().exists(path)) return true;
        } catch (Exception ignored) {
            // VFS 未初始化等异常 → 回退宿主机
        }
        return Files.exists(Paths.get(path));
    }

    @Override
    public List<String> list_dir(String dirPath) {
        if (dirPath == null || dirPath.isBlank()) return List.of();
        List<String> entries = new ArrayList<>();

        // 1. VFS：列出该前缀下的直接子项
        try {
            VfsManager vfs = VfsManager.instance();
            if (vfs.exists(dirPath)) {
                String prefix = dirPath.equals("/") ? "/" : dirPath + "/";
                for (String p : vfs.listFilesUnder(dirPath)) {
                    // listFilesUnder 返回完整路径，提取直接子项
                    String rel = p.startsWith(prefix) ? p.substring(prefix.length()) : p;
                    String name = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : rel;
                    if (!name.isEmpty() && !entries.contains(name)) {
                        entries.add(name);
                    }
                }
            }
        } catch (Exception ignored) {
            // VFS 未初始化 → 回退宿主机
        }

        // 2. 宿主机：补全 VFS 未覆盖的物理文件
        File dir = new File(dirPath);
        if (dir.isDirectory()) {
            String[] children = dir.list();
            if (children != null) {
                for (String c : children) {
                    if (!entries.contains(c)) entries.add(c);
                }
            }
        }
        return entries;
    }

    @Override
    public boolean delete_path(String path) {
        if (path == null || path.isBlank()) return false;
        boolean vfsOk = false;
        try {
            // VFS 卸载节点（不删物理文件）
            vfsOk = VfsManager.instance().unmount(path);
        } catch (Exception ignored) {
            // VFS 未初始化等
        }
        // 宿主机删除物理文件
        boolean hostOk = false;
        try {
            hostOk = Files.deleteIfExists(Paths.get(path));
        } catch (IOException e) {
            log.warn("[LocalBackend] delete_path 宿主机删除失败 '{}': {}", path, e.getMessage());
        }
        return vfsOk || hostOk;
    }

    @Override
    public String join_path(String base, String... child) {
        if (base == null) base = "";
        if (child == null || child.length == 0) return base;
        return Path.of(base, child).toString();
    }

    // ════════════════════════════════════════════════════════════════
    //  Shell 执行原语
    // ════════════════════════════════════════════════════════════════

    @Override
    public ExecResult exec_shell(String command, ExecOptions options) {
        if (command == null || command.isBlank()) {
            return ExecResult.error("Empty command");
        }
        if (options == null) options = ExecOptions.DEFAULT;

        try {
            // ── VFS 路径翻译：LLM 经 VFS 写文件（/factory/xxx.py），bash 在宿主机执行需翻译为物理路径 ──
            String translated = VfsManager.instance().translateVfsPathsInCommand(command);
            if (!translated.equals(command)) {
                log.debug("[LocalBackend] VFS 路径已翻译: '{}' → '{}'", command, translated);
            }

            ProcessBuilder pb = new ProcessBuilder("bash", "-c", translated);
            if (options.workingDir() != null && !options.workingDir().isBlank()) {
                pb.directory(new File(options.workingDir()));
            }
            if (options.redirectErrorStream()) {
                pb.redirectErrorStream(true);
            }

            // ── 非交互式 shell 默认环境（防 sudo/apt 等命令等待输入死锁） ──
            Map<String, String> env = pb.environment();
            env.put("DEBIAN_FRONTEND", "noninteractive");
            env.put("APT_KEY_DONT_WARN_ON_DANGEROUS_USAGE", "1");
            env.put("PIP_NO_INPUT", "1");
            // 调用方追加的环境变量（如 PYTHONPATH）
            for (Map.Entry<String, String> e : options.env().entrySet()) {
                env.put(e.getKey(), e.getValue());
            }

            Process process;
            try {
                process = pb.start();
            } catch (IOException e) {
                log.error("[LocalBackend] 启动进程失败: {}", e.getMessage());
                return ExecResult.error("Failed to start process: " + e.getMessage());
            }

            // ── 异步读取 stdout —— 防止同步 readLine() 阻塞到进程退出导致超时失效 ──
            // 经典 ProcessBuilder 陷阱：同步读 stdout 会阻塞到 EOF（进程退出），
            // 对无输出的长进程（如 sleep 5）超时永远不会触发。
            // 用虚拟线程异步读取，主线程同时 waitFor 超时，互不阻塞。
            StringBuilder output = new StringBuilder();
            Thread readerThread = Thread.startVirtualThread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                } catch (IOException ignored) {
                    // 进程被 destroyForcibly 时会抛 IOException，正常忽略
                }
            });

            boolean finished = process.waitFor(options.timeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                // 等待 reader 线程退出（进程被 kill 后 stdout 关闭，readLine 返回 null）
                try {
                    readerThread.join(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                String partial = truncate(output.toString(), options.maxOutputLength());
                return ExecResult.timeout(partial);
            }

            // 进程已退出，等待 reader 线程读完剩余输出
            try {
                readerThread.join(5000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            int exitCode = process.exitValue();
            String result = truncate(output.toString(), options.maxOutputLength());
            return ExecResult.failure(exitCode, result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExecResult.error("Execution interrupted: " + e.getMessage());
        } catch (Exception e) {
            return ExecResult.error("Execution failed: " + e.getMessage());
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (maxLen <= 0 || s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "\n... [truncated at " + maxLen + " chars]";
    }

    @Override
    public String backendName() {
        return "local";
    }
}
