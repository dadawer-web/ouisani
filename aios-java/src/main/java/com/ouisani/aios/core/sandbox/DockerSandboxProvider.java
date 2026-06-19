package com.ouisani.aios.core.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 云原生沙箱后端 — 启动真实 Docker 容器执行代码。
 * <p>
 * 代码写入宿主机临时文件，通过 {@code docker run --rm -v ...}
 * 挂载到一次性容器中执行，捕获 stdout 作为执行结果返回。
 * <p>
 * 安全加固参数：
 * <ul>
 *   <li>{@code --network none} — 无网络访问（气隙隔离）</li>
 *   <li>{@code -m 256m} — 内存硬限制 256MB</li>
 *   <li>{@code --cpus=0.5} — CPU 配额半核</li>
 *   <li>{@code --pids-limit 64} — 防止 fork 炸弹</li>
 *   <li>{@code --read-only} — 只读根文件系统</li>
 * </ul>
 *
 * @see SandboxProvider
 */
public class DockerSandboxProvider implements SandboxProvider {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxProvider.class);
    private static final String HOST_SCRIPT_PATH = "/tmp/aios_agent_script.py";

    private final String dockerImage;

    public DockerSandboxProvider() {
        this("python:3.10");
    }

    public DockerSandboxProvider(String dockerImage) {
        this.dockerImage = dockerImage;
    }

    @Override
    public String executeCode(String code, String entrypoint) throws Exception {
        log.info("[Cloud Sandbox] 正在为 Agent 启动物理 Docker 容器...");
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.printf("  ║  [Cloud Sandbox] Docker 镜像 : %s%n", dockerImage);
        System.out.printf("  ║  [Cloud Sandbox] 命令         : %s%n", entrypoint != null ? entrypoint : "default");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");

        // Write code to host temp file
        Path scriptPath = Path.of(HOST_SCRIPT_PATH);
        Files.writeString(scriptPath, code, StandardCharsets.UTF_8);
        log.debug("[Cloud Sandbox] 代码已写入 {}", HOST_SCRIPT_PATH);

        // Determine container script path and command based on entrypoint/language
        String containerScriptPath = "/script.py";
        String execCommand = "python";
        if (entrypoint != null && entrypoint.endsWith(".sh")) {
            containerScriptPath = "/script.sh";
            execCommand = "bash";
        }

        // Build the docker run command with hardened security parameters:
        // --network none    : No network access (air-gapped)
        // -m 256m           : Memory hard cap at 256MB
        // --memory-swap 256m: No swap beyond memory limit
        // --cpus=0.5        : CPU quota at half a core
        // --pids-limit 64   : Prevent fork bombs
        // --read-only       : Read-only root filesystem (only /tmp mount is writable)
        List<String> command = List.of(
                "docker", "run", "--rm",
                "--network", "none",
                "-m", "256m",
                "--memory-swap", "256m",
                "--cpus", "0.5",
                "--pids-limit", "64",
                "--read-only",
                "-v", HOST_SCRIPT_PATH + ":" + containerScriptPath + ":ro",
                "--tmpfs", "/tmp:size=64m,noexec",
                dockerImage,
                execCommand, containerScriptPath
        );

        log.info("[Cloud Sandbox] 正在执行: {}", String.join(" ", command));
        System.out.printf("  ├─ [Cloud Sandbox] CMD: %s%n", String.join(" ", command));
        System.out.println("  ├─ [Sandbox Security] 已启动隔离容器，网络已桥接关闭，资源硬限制。");

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

        // Clean up host temp file
        try {
            Files.deleteIfExists(scriptPath);
        } catch (IOException e) {
            log.warn("[Cloud Sandbox] 删除临时脚本失败: {}", e.getMessage());
        }

        return output;
    }

    @Override
    public String providerName() {
        return "Docker:" + dockerImage;
    }
}
