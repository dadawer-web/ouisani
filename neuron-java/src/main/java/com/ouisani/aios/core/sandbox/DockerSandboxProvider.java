package com.ouisani.aios.core.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 云原生沙箱后端 — 启动真实 Docker 容器执行代码。
 * <p>
 * 代码写入宿主机临时文件，通过 {@code docker run --rm -v ...}
 * 挂载到一次性容器中执行，捕获 stdout 作为执行结果返回。
 * <p>
 * 安全加固参数（P2 加固）：
 * <ul>
 *   <li>{@code --network none} — 无网络访问（气隙隔离）</li>
 *   <li>{@code -m 256m} — 内存硬限制 256MB</li>
 *   <li>{@code --cpus=0.5} — CPU 配额半核</li>
 *   <li>{@code --pids-limit 64} — 防止 fork 炸弹</li>
 *   <li>{@code --read-only} — 只读根文件系统</li>
 *   <li>{@code --cap-drop ALL} — 丢弃所有 Linux capabilities（无 CAP_NET_ADMIN/CAP_SYS_ADMIN 等）</li>
 *   <li>{@code --security-opt no-new-privileges} — 禁止子进程通过 setuid 提权</li>
 *   <li>{@code --security-opt seccomp=<profile>} — seccomp 黑名单拦截容器逃逸 syscall
 *       （unshare/pivot_root/mount/ptrace/...，profile 见 {@code resources/aios-seccomp.json}）</li>
 * </ul>
 * <p>
 * <b>临时文件安全</b>：每次调用生成唯一临时脚本文件（{@code Files.createTempFile}），
 * try-finally 清理 — 避免固定路径的竞态与跨调用泄漏。
 *
 * @see SandboxProvider
 */
public class DockerSandboxProvider implements SandboxProvider {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxProvider.class);

    /** seccomp profile 释放到宿主机后的路径（懒加载，JVM 退出时清理）。 */
    private static volatile Path seccompProfilePath;
    private static final Object SECCOMP_LOCK = new Object();

    private final String dockerImage;

    public DockerSandboxProvider() {
        this("python:3.10");
    }

    public DockerSandboxProvider(String dockerImage) {
        this.dockerImage = dockerImage;
    }

    /**
     * 懒加载 seccomp profile：把打包在 jar 内的 {@code /aios-seccomp.json}
     * 释放到宿主机临时文件，供 {@code docker --security-opt seccomp=<path>} 引用。
     * <p>
     * 双重检查锁定保证只释放一次；释放后的文件 {@code deleteOnExit} 由 JVM 退出时清理。
     *
     * @return 释放后的宿主机路径；资源缺失时返回 null（调用方降级跳过 seccomp，仅 warn）
     */
    private static Path seccompProfilePath() {
        Path p = seccompProfilePath;
        if (p != null) return p;
        synchronized (SECCOMP_LOCK) {
            if (seccompProfilePath != null) return seccompProfilePath;
            try (InputStream in = DockerSandboxProvider.class
                    .getResourceAsStream("/aios-seccomp.json")) {
                if (in == null) {
                    log.warn("[Cloud Sandbox] aios-seccomp.json 未找到，跳过 seccomp 加固");
                    return null;
                }
                Path tmp = Files.createTempFile("aios-seccomp-", ".json");
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                tmp.toFile().deleteOnExit();
                seccompProfilePath = tmp;
                log.info("[Cloud Sandbox] seccomp profile 已释放至 {}", tmp);
                return tmp;
            } catch (IOException e) {
                throw new UncheckedIOException("释放 seccomp profile 失败", e);
            }
        }
    }

    @Override
    public String executeCode(String code, String entrypoint) throws Exception {
        log.info("[Cloud Sandbox] 正在为 Agent 启动物理 Docker 容器...");
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.printf("  ║  [Cloud Sandbox] Docker 镜像 : %s%n", dockerImage);
        System.out.printf("  ║  [Cloud Sandbox] 命令         : %s%n", entrypoint != null ? entrypoint : "default");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");

        // Determine container script path and command based on entrypoint/language
        String containerScriptPath = "/script.py";
        String execCommand = "python";
        if (entrypoint != null && entrypoint.endsWith(".sh")) {
            containerScriptPath = "/script.sh";
            execCommand = "bash";
        }

        // 每次调用生成唯一临时脚本文件，避免固定路径的竞态与跨调用泄漏
        String suffix = (entrypoint != null && entrypoint.endsWith(".sh")) ? ".sh" : ".py";
        Path scriptPath = Files.createTempFile("aios-script-", suffix);
        try {
            Files.writeString(scriptPath, code, StandardCharsets.UTF_8);
            log.debug("[Cloud Sandbox] 代码已写入临时文件 {}", scriptPath);

            // Build the docker run command with hardened security parameters.
            // 用 ArrayList 而非 List.of（immutable），以便条件性追加 seccomp profile。
            List<String> command = new ArrayList<>(List.of(
                    "docker", "run", "--rm",
                    "--network", "none",
                    "-m", "256m",
                    "--memory-swap", "256m",
                    "--cpus", "0.5",
                    "--pids-limit", "64",
                    "--read-only",
                    "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges",
                    "-v", scriptPath + ":" + containerScriptPath + ":ro",
                    "--tmpfs", "/tmp:size=64m,noexec",
                    dockerImage,
                    execCommand, containerScriptPath
            ));

            // seccomp profile（资源成功释放时追加；缺失则降级跳过）
            Path seccomp = seccompProfilePath();
            if (seccomp != null) {
                int idx = command.indexOf("no-new-privileges");
                command.add(idx + 1, "seccomp=" + seccomp.toAbsolutePath());
                command.add(idx + 1, "--security-opt");
            }

            log.info("[Cloud Sandbox] 正在执行: {}", String.join(" ", command));
            System.out.printf("  ├─ [Cloud Sandbox] CMD: %s%n", String.join(" ", command));
            System.out.println("  ├─ [Sandbox Security] 已启动隔离容器：cap-drop ALL + no-new-privileges + seccomp，网络关闭，资源硬限制。");

            ProcessBuilder pb = new ProcessBuilder(command)
                    .redirectErrorStream(true);

            Process process;
            try {
                process = pb.start();
            } catch (IOException e) {
                log.error("[Cloud Sandbox] 启动 Docker 进程失败: {}", e.getMessage());
                System.err.printf("  🚨 [Cloud Sandbox] Docker 执行失败: %s%n", e.getMessage());
                throw new RuntimeException("Docker execution failed: " + e.getMessage(), e);
            }

            // Read stdout (merged with stderr via redirectErrorStream)
            String output;
            try {
                output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                process.destroyForcibly();
                throw new RuntimeException("Failed to read Docker output: " + e.getMessage(), e);
            }

            int exitCode;
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new RuntimeException("Docker execution interrupted", e);
            }

            if (exitCode != 0) {
                log.warn("[Cloud Sandbox] 容器退出码 {}: {}", exitCode, output.trim());
                System.err.printf("  ⚠ [Cloud Sandbox] 退出码 %d%n", exitCode);
                System.err.printf("  ⚠ [Cloud Sandbox] 输出: %s%n", output.trim());
            } else {
                log.info("[Cloud Sandbox] 容器执行成功");
                System.out.printf("  └─ [Cloud Sandbox] 执行完成 (exit=0)%n");
            }

            return output;
        } finally {
            // Clean up per-invocation host temp script
            try {
                Files.deleteIfExists(scriptPath);
            } catch (IOException e) {
                log.warn("[Cloud Sandbox] 删除临时脚本失败: {}", e.getMessage());
            }
        }
    }

    @Override
    public String providerName() {
        return "Docker:" + dockerImage;
    }
}
