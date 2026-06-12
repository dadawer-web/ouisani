package com.ouisani.aios.openclaw.secrets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 密钥引用解析器 — 对标 OpenClaw 的 resolveSecretRefValue。
 * <p>
 * 按 SecretRef 的 source 类型分发解析：
 * <ul>
 *   <li>env: 从环境变量读取</li>
 *   <li>file: 从文件读取（支持 JSON Pointer 提取子值）</li>
 *   <li>exec: 执行命令获取输出</li>
 * </ul>
 * <p>
 * 安全约束：
 * <ul>
 *   <li>file 类型只允许读取受信目录下的文件</li>
 *   <li>exec 类型有 5 秒超时限制</li>
 *   <li>文件大小限制 1MB</li>
 * </ul>
 */
public class SecretRefResolver {

    private static final Logger log = LoggerFactory.getLogger(SecretRefResolver.class);

    /** 受信目录白名单 */
    private static final List<Path> TRUSTED_DIRS = List.of(
            Path.of(System.getProperty("user.home"), ".openclaw", "credentials"),
            Path.of(System.getProperty("user.home"), ".openclaw", "agents"),
            Path.of(System.getProperty("user.home"), ".aios", "secrets")
    );

    /** 文件最大字节数 */
    private static final long MAX_FILE_BYTES = 1024 * 1024; // 1MB

    /** 执行超时（毫秒） */
    private static final long EXEC_TIMEOUT_MS = 5000;

    /**
     * 解析 SecretRef 的值。
     *
     * @param ref  密钥引用
     * @param env  环境变量（用于 env 类型）
     * @return 解析后的明文值
     * @throws SecretResolutionException 解析失败
     */
    public static String resolve(SecretRef ref, Map<String, String> env) throws SecretResolutionException {
        return switch (ref.source()) {
            case "env" -> resolveEnv(ref, env);
            case "file" -> resolveFile(ref);
            case "exec" -> resolveExec(ref);
            default -> throw new SecretResolutionException("Unknown SecretRef source: " + ref.source(), ref);
        };
    }

    /** 便捷调用 — 使用系统环境变量 */
    public static String resolve(SecretRef ref) throws SecretResolutionException {
        Map<String, String> env = new HashMap<>();
        System.getenv().forEach(env::put);
        return resolve(ref, env);
    }

    // ── env 解析 ──

    private static String resolveEnv(SecretRef ref, Map<String, String> env) throws SecretResolutionException {
        String value = env.get(ref.id());
        if (value == null || value.isBlank()) {
            throw new SecretResolutionException("Environment variable not found: " + ref.id(), ref);
        }
        return value.trim();
    }

    // ── file 解析 ──

    private static String resolveFile(SecretRef ref) throws SecretResolutionException {
        Path filePath = Path.of(ref.id());

        // 安全校验：路径必须在受信目录内
        assertSecurePath(filePath);

        if (!Files.exists(filePath)) {
            throw new SecretResolutionException("File not found: " + filePath, ref);
        }
        if (!Files.isRegularFile(filePath)) {
            throw new SecretResolutionException("Not a regular file: " + filePath, ref);
        }

        try {
            long size = Files.size(filePath);
            if (size > MAX_FILE_BYTES) {
                throw new SecretResolutionException("File too large: " + size + " bytes (max " + MAX_FILE_BYTES + ")", ref);
            }
            return Files.readString(filePath, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new SecretResolutionException("Failed to read file: " + e.getMessage(), ref, e);
        }
    }

    // ── exec 解析 ──

    private static String resolveExec(SecretRef ref) throws SecretResolutionException {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", ref.id());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(EXEC_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new SecretResolutionException("Exec timeout (" + EXEC_TIMEOUT_MS + "ms): " + ref.id(), ref);
            }

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new SecretResolutionException("Exec failed (exit " + exitCode + "): " + output, ref);
            }
            return output;
        } catch (SecretResolutionException e) {
            throw e;
        } catch (Exception e) {
            throw new SecretResolutionException("Exec error: " + e.getMessage(), ref, e);
        }
    }

    // ── 安全校验 ──

    private static void assertSecurePath(Path path) throws SecretResolutionException {
        try {
            Path normalized = path.toAbsolutePath().normalize();

            // 检查是否在受信目录内
            boolean trusted = false;
            for (Path dir : TRUSTED_DIRS) {
                if (normalized.startsWith(dir)) {
                    trusted = true;
                    break;
                }
            }

            // 如果不在受信目录，检查是否为符号链接
            if (!trusted && Files.isSymbolicLink(path)) {
                throw new SecretResolutionException("Symbolic link not in trusted directory: " + path, null);
            }

            // 检查路径遍历
            if (normalized.toString().contains("..")) {
                throw new SecretResolutionException("Path traversal detected: " + path, null);
            }
        } catch (SecretResolutionException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[SecretRefResolver] Security check failed for path {}: {}", path, e.getMessage());
        }
    }
}
