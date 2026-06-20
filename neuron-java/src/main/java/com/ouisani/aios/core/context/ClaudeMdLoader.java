package com.ouisani.aios.core.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CLAUDE.md 文件加载器 — 对标 Claude Code 的 claudemd.ts。
 * <p>
 * 分层加载策略（优先级从高到低）：
 * 1. Managed — 企业托管指令
 * 2. User — ~/.claude/CLAUDE.md
 * 3. Project — 从 CWD 向上遍历至 root 的 CLAUDE.md
 * 4. Local — .claude/CLAUDE.md (项目本地)
 * 5. Additional — --add-dir 指定的额外目录
 * <p>
 * OS 类比：相当于 Linux 的 sysctl 配置加载 — /etc/sysctl.conf > /etc/sysctl.d/*.conf > .local
 */
public class ClaudeMdLoader {

    private static final Logger log = LoggerFactory.getLogger(ClaudeMdLoader.class);
    private static final int MAX_INCLUDE_DEPTH = 5;
    private static final int MAX_CONTENT_LENGTH = 50000;

    /** 记忆文件信息 */
    public record MemoryFileInfo(
            String path,
            MemoryType type,
            String content,
            String parent,
            List<String> globs
    ) {}

    public enum MemoryType {
        MANAGED, USER, PROJECT, LOCAL, ADDITIONAL
    }

    private static final Map<String, List<MemoryFileInfo>> cache = new ConcurrentHashMap<>();

    /**
     * 获取所有 CLAUDE.md 文件内容 — 对标 getMemoryFiles()。
     */
    public static List<MemoryFileInfo> loadAll(String workingDir) {
        if (cache.containsKey(workingDir)) return cache.get(workingDir);

        List<MemoryFileInfo> files = new ArrayList<>();

        // 1. User CLAUDE.md
        String userHome = System.getProperty("user.home");
        loadFile(files, Path.of(userHome, ".claude", "CLAUDE.md"), MemoryType.USER, null);

        // 2. Project CLAUDE.md — 从 CWD 向上遍历
        loadProjectFiles(files, Path.of(workingDir));

        // 3. Local CLAUDE.md
        loadFile(files, Path.of(workingDir, ".claude", "CLAUDE.md"), MemoryType.LOCAL, null);

        cache.put(workingDir, files);
        return files;
    }

    /**
     * 格式化为系统提示文本 — 对标 getClaudeMds()。
     */
    public static String formatAsPrompt(List<MemoryFileInfo> files) {
        if (files.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("# Project Instructions (CLAUDE.md)\n\n");

        for (MemoryFileInfo file : files) {
            if (file.content() == null || file.content().isBlank()) continue;
            sb.append("## ").append(file.type()).append(": ").append(file.path()).append("\n\n");
            sb.append(file.content()).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 清除缓存。
     */
    public static void invalidateCache() {
        cache.clear();
    }

    // ── 内部方法 ──

    private static void loadFile(List<MemoryFileInfo> files, Path path, MemoryType type, String parent) {
        if (!Files.exists(path) || !Files.isRegularFile(path)) return;

        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.length() > MAX_CONTENT_LENGTH) {
                content = content.substring(0, MAX_CONTENT_LENGTH) + "\n... [truncated]";
            }

            // 处理 @include 引用
            content = processIncludes(content, path.getParent(), 0);

            files.add(new MemoryFileInfo(path.toString(), type, content, parent, List.of()));
            log.debug("[ClaudeMdLoader] Loaded {} file: {}", type, path);
        } catch (IOException e) {
            log.warn("[ClaudeMdLoader] Failed to read {}: {}", path, e.getMessage());
        }
    }

    private static void loadProjectFiles(List<MemoryFileInfo> files, Path startDir) {
        Path dir = startDir;
        List<MemoryFileInfo> projectFiles = new ArrayList<>();

        while (dir != null) {
            loadFile(projectFiles, dir.resolve("CLAUDE.md"), MemoryType.PROJECT, null);
            dir = dir.getParent();
            if (dir != null && dir.getNameCount() == 0) break;
        }

        // 反转顺序：root → CWD（越靠近 CWD 优先级越高）
        Collections.reverse(projectFiles);
        files.addAll(projectFiles);
    }

    private static String processIncludes(String content, Path baseDir, int depth) {
        if (depth >= MAX_INCLUDE_DEPTH) return content;

        // 简化的 @include 处理：查找 @./path 或 @~/path 引用
        StringBuilder result = new StringBuilder();
        for (String line : content.split("\n")) {
            if (line.trim().startsWith("@./") || line.trim().startsWith("@~/")) {
                String includePath = line.trim().substring(1);
                Path resolved = includePath.startsWith("~/")
                        ? Path.of(System.getProperty("user.home")).resolve(includePath.substring(2))
                        : baseDir.resolve(includePath);

                try {
                    if (Files.exists(resolved)) {
                        String included = Files.readString(resolved, StandardCharsets.UTF_8);
                        result.append(processIncludes(included, resolved.getParent(), depth + 1));
                    }
                } catch (IOException e) {
                    result.append("[Failed to include: ").append(includePath).append("]");
                }
            } else {
                result.append(line).append("\n");
            }
        }
        return result.toString();
    }
}
