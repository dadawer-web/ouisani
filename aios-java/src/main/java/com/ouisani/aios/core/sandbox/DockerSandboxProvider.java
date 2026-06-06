package com.ouisani.aios.core.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Cloud-native sandbox provider that spawns real Docker containers.
 * <p>
 * Code is written to a temporary file on the host, then mounted into
 * a disposable container via {@code docker run --rm -v ...}.
 * The container's stdout is captured and returned as the execution result.
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
        log.info("[Cloud Sandbox] Spawning physical Docker container for Agent...");
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.printf("  ║  [Cloud Sandbox] Docker Image : %s%n", dockerImage);
        System.out.printf("  ║  [Cloud Sandbox] Entrypoint   : %s%n", entrypoint != null ? entrypoint : "default");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");

        // Write code to host temp file
        Path scriptPath = Path.of(HOST_SCRIPT_PATH);
        Files.writeString(scriptPath, code, StandardCharsets.UTF_8);
        log.debug("[Cloud Sandbox] Code written to {}", HOST_SCRIPT_PATH);

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

        log.info("[Cloud Sandbox] Executing: {}", String.join(" ", command));
        System.out.printf("  ├─ [Cloud Sandbox] CMD: %s%n", String.join(" ", command));
        System.out.println("  ├─ [Sandbox Security] Spawned isolated container with network bridged off and resource hard-capped.");

        ProcessBuilder pb = new ProcessBuilder(command)
                .redirectErrorStream(true);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            log.error("[Cloud Sandbox] Failed to start Docker process: {}", e.getMessage());
            System.err.printf("  🚨 [Cloud Sandbox] Docker execution failed: %s%n", e.getMessage());
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
            log.warn("[Cloud Sandbox] Container exited with code {}: {}", exitCode, output.trim());
            System.err.printf("  ⚠ [Cloud Sandbox] Exit code %d%n", exitCode);
            System.err.printf("  ⚠ [Cloud Sandbox] Output: %s%n", output.trim());
        } else {
            log.info("[Cloud Sandbox] Container completed successfully");
            System.out.printf("  └─ [Cloud Sandbox] Execution complete (exit=0)%n");
        }

        // Clean up host temp file
        try {
            Files.deleteIfExists(scriptPath);
        } catch (IOException e) {
            log.warn("[Cloud Sandbox] Failed to delete temp script: {}", e.getMessage());
        }

        return output;
    }

    @Override
    public String providerName() {
        return "Docker:" + dockerImage;
    }
}
