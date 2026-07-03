package com.ouisani.aios.core.security;

import com.ouisani.aios.core.VfsManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 安全策略加载器 — 从 VFS 配置文件加载声明式安全策略。
 * <p>
 * 配置文件路径：/vfs/config/zone_policies.yaml
 * <p>
 * 配置文件格式（简化 YAML 解析，不依赖外部 YAML 库）：
 * <pre>
 * zones:
 *   WORK:
 *     allowed_path_prefixes:
 *       - /workspace/
 *       - /tmp/
 *     denied_path_substrings:
 *       - ../
 *       - /etc/passwd
 *     denied_commands:
 *       - rm -rf /
 *       - chmod 777
 *     max_depth: 5
 *     max_requests: 100
 *     read_only: false
 *   SECRETS:
 *     allowed_path_prefixes:
 *       - /secrets/
 *     denied_commands:
 *       - cat
 *       - ls
 *     read_only: true
 * </pre>
 *
 * @see ZonePolicy
 */
public class ZonePolicyLoader {

    private static final Logger log = LoggerFactory.getLogger(ZonePolicyLoader.class);

    private static final String CONFIG_PATH = "/vfs/config/zone_policies.yaml";

    private static volatile boolean loaded = false;

    /**
     * 从 VFS 加载安全策略配置。
     * 如果配置文件不存在，使用默认策略。
     */
    public static synchronized void load() {
        VfsManager vfs = VfsManager.instance();
        String content = vfs.readText(CONFIG_PATH);

        if (content == null || content.isEmpty()) {
            log.info("[ZonePolicyLoader] 配置文件不存在，使用默认策略: {}", CONFIG_PATH);
            loaded = true;
            return;
        }

        try {
            Map<String, ZonePolicy> policies = parseSimpleYaml(content);
            for (Map.Entry<String, ZonePolicy> entry : policies.entrySet()) {
                ZonePolicy.register(entry.getKey(), entry.getValue());
            }
            log.info("[ZonePolicyLoader] 已加载 {} 个 zone 策略", policies.size());
        } catch (Exception e) {
            log.error("[ZonePolicyLoader] 配置解析失败，使用默认策略: {}", e.getMessage());
        }

        loaded = true;
    }

    /**
     * 确保策略已加载（懒加载）。
     */
    public static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    /**
     * 重新加载策略（热更新）。
     */
    public static void reload() {
        loaded = false;
        load();
    }

    /**
     * 简化版 YAML 解析器 — 不依赖外部库，支持基本的 zone 配置格式。
     * <p>
     * 解析逻辑：
     * 1. 找到 "zones:" 行
     * 2. 每个 2 空格缩进的行是一个 zone 名
     * 3. 每个 4 空格缩进的行是 zone 的属性
     * 4. 列表项以 "  - " 开头
     */
    private static Map<String, ZonePolicy> parseSimpleYaml(String content) {
        Map<String, ZonePolicy> result = new LinkedHashMap<>();
        String[] lines = content.split("\n");

        String currentZone = null;
        Set<String> allowedHosts = new LinkedHashSet<>();
        Set<String> allowedPathPrefixes = new LinkedHashSet<>();
        Set<String> deniedPathSubstrings = new LinkedHashSet<>();
        Set<String> deniedCommands = new LinkedHashSet<>();
        int maxDepth = 10;
        int maxRequests = 1000;
        boolean readOnly = false;
        String currentList = null; // "hosts" | "paths" | "denied_paths" | "commands" | null

        for (String line : lines) {
            // 跳过空行和注释
            if (line.trim().isEmpty() || line.trim().startsWith("#")) continue;

            // 检测 zone 名（2 空格缩进，非列表项，以冒号结尾）
            if (line.startsWith("  ") && !line.startsWith("   -") && !line.startsWith("    ")
                    && line.trim().endsWith(":") && !line.startsWith("zones:")) {
                // 保存前一个 zone
                if (currentZone != null) {
                    result.put(currentZone, new ZonePolicy(currentZone, allowedHosts, allowedPathPrefixes,
                            deniedPathSubstrings, deniedCommands, maxDepth, maxRequests, readOnly));
                }
                currentZone = line.trim().replace(":", "");
                // 重置
                allowedHosts = new LinkedHashSet<>();
                allowedPathPrefixes = new LinkedHashSet<>();
                deniedPathSubstrings = new LinkedHashSet<>();
                deniedCommands = new LinkedHashSet<>();
                maxDepth = 10;
                maxRequests = 1000;
                readOnly = false;
                currentList = null;
                continue;
            }

            if (currentZone == null) continue;

            String trimmed = line.trim();

            // 检测列表项
            if (trimmed.startsWith("- ")) {
                String value = trimmed.substring(2).trim();
                if (currentList != null) {
                    switch (currentList) {
                        case "hosts" -> allowedHosts.add(value);
                        case "paths" -> allowedPathPrefixes.add(value);
                        case "denied_paths" -> deniedPathSubstrings.add(value);
                        case "commands" -> deniedCommands.add(value);
                    }
                }
                continue;
            }

            // 检测属性
            if (trimmed.contains(":")) {
                String[] parts = trimmed.split(":", 2);
                String key = parts[0].trim();
                String value = parts.length > 1 ? parts[1].trim() : "";

                currentList = null;
                switch (key) {
                    case "allowed_hosts" -> currentList = "hosts";
                    case "allowed_path_prefixes" -> currentList = "paths";
                    case "denied_path_substrings" -> currentList = "denied_paths";
                    case "denied_commands" -> currentList = "commands";
                    case "max_depth" -> maxDepth = parseInt(value, 10);
                    case "max_requests" -> maxRequests = parseInt(value, 1000);
                    case "read_only" -> readOnly = Boolean.parseBoolean(value);
                }
            }
        }

        // 保存最后一个 zone
        if (currentZone != null) {
            result.put(currentZone, new ZonePolicy(currentZone, allowedHosts, allowedPathPrefixes,
                    deniedPathSubstrings, deniedCommands, maxDepth, maxRequests, readOnly));
        }

        return result;
    }

    private static int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
