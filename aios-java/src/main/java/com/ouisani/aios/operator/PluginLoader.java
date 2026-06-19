package com.ouisani.aios.operator;

import com.ouisani.aios.core.tool.Tool;
import com.ouisani.aios.core.tool.ToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * 插件加载器 — 对标 OpenClaw 的 Plugin Loader。
 * <p>
 * 从文件系统扫描插件目录，发现并加载插件。
 * 每个插件目录需包含一个 {@code openclaw.plugin.json} 清单文件
 * 和一个实现 {@link OperatorPlugin} 接口的 Java 类。
 * <p>
 * 热插拔机制：
 * <ul>
 *   <li>扫描时发现新插件 → 自动注册</li>
 *   <li>插件目录被删除 → 自动卸载</li>
 *   <li>清单文件更新 → 重新加载</li>
 * </ul>
 * <p>
 * OS 类比：相当于 Linux 的 modprobe — 扫描 /lib/modules/ 加载内核模块。
 */
public class PluginLoader {

    private static final Logger log = LoggerFactory.getLogger(PluginLoader.class);

    private final PluginRegistry registry;
    private final Path pluginsDir;

    /** 已加载插件的修改时间戳，用于检测变更 */
    private final Map<String, Long> loadedTimestamps = new LinkedHashMap<>();

    public PluginLoader(PluginRegistry registry, Path pluginsDir) {
        this.registry = registry;
        this.pluginsDir = pluginsDir;
    }

    /**
     * 扫描并加载所有插件。
     * <p>
     * 遍历 pluginsDir 下的每个子目录，查找 openclaw.plugin.json 清单文件，
     * 解析元数据，实例化插件类，调用其 register 方法。
     *
     * @return 新加载的插件数量
     */
    public int scanAndLoad() {
        if (!Files.isDirectory(pluginsDir)) {
            log.warn("[PluginLoader] Plugins directory does not exist: {}", pluginsDir);
            return 0;
        }

        int loaded = 0;
        try (var stream = Files.list(pluginsDir)) {
            List<Path> dirs = stream.filter(Files::isDirectory).sorted().toList();

            for (Path dir : dirs) {
                String pluginId = dir.getFileName().toString();

                // 跳过隐藏目录
                if (pluginId.startsWith(".")) continue;

                try {
                    if (loadPlugin(dir, pluginId)) {
                        loaded++;
                    }
                } catch (Exception e) {
                    log.error("[PluginLoader] Failed to load plugin '{}': {}", pluginId, e.getMessage(), e);
                    System.err.printf("[PluginLoader] Failed to load plugin '%s': %s%n", pluginId, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("[PluginLoader] Failed to scan plugins directory: {}", e.getMessage());
        }

        log.info("[PluginLoader] Scan complete. {} new plugins loaded. Total: {}", loaded, registry.allPlugins().size());
        System.out.printf("[PluginLoader] Scan complete. %d new plugins loaded. Total: %d%n",
                loaded, registry.allPlugins().size());
        return loaded;
    }

    /**
     * 热重载 — 检测变更并重新加载。
     * <p>
     * 对标 OpenClaw 的 registerReload 机制：
     * - 新增插件目录 → 加载
     * - 清单文件修改 → 重新加载
     * - 插件目录删除 → 卸载
     *
     * @return 变更的插件数量
     */
    public int hotReload() {
        int changes = 0;

        // 1. 卸载已删除的插件
        Iterator<String> it = loadedTimestamps.keySet().iterator();
        while (it.hasNext()) {
            String pluginId = it.next();
            Path dir = pluginsDir.resolve(pluginId);
            if (!Files.isDirectory(dir)) {
                registry.unregisterPlugin(pluginId);
                it.remove();
                changes++;
                log.info("[PluginLoader] Plugin '{}' 插件目录已移除，已注销", pluginId);
            }
        }

        // 2. 扫描新增或修改的插件
        changes += scanAndLoad();

        return changes;
    }

    /**
     * 加载单个插件。
     *
     * @return true 如果插件被成功加载
     */
    private boolean loadPlugin(Path dir, String pluginId) throws IOException {
        Path manifestPath = dir.resolve("openclaw.plugin.json");

        if (!Files.exists(manifestPath)) {
            log.debug("[PluginLoader] No openclaw.plugin.json in {}, skipping", dir);
            return false;
        }

        // 检查是否已加载且未修改
        long lastModified = Files.getLastModifiedTime(manifestPath).toMillis();
        Long prevTimestamp = loadedTimestamps.get(pluginId);
        if (prevTimestamp != null && prevTimestamp == lastModified) {
            return false; // 未修改，跳过
        }

        // 解析清单
        PluginMeta meta = parseManifest(manifestPath, pluginId);

        // 如果已存在，先卸载旧版本
        if (registry.hasPlugin(pluginId)) {
            registry.unregisterPlugin(pluginId);
            log.info("[PluginLoader] Plugin '{}' updated, reloading", pluginId);
        }

        // 注册插件
        PluginRegistrationApi api = registry.registerPlugin(pluginId, meta);

        // 尝试加载插件类
        loadPluginClass(dir, pluginId, api);

        loadedTimestamps.put(pluginId, lastModified);
        log.info("[PluginLoader] Plugin '{}' v{} 插件已加载", pluginId, meta.version());
        System.out.printf("[PluginLoader] Plugin '%s' v%s 插件已加载%n", pluginId, meta.version());
        return true;
    }

    /**
     * 解析 openclaw.plugin.json 清单文件。
     * <p>
     * 简化实现：使用正则提取关键字段，无需引入 JSON 库。
     */
    private PluginMeta parseManifest(Path manifestPath, String fallbackId) throws IOException {
        String content = Files.readString(manifestPath);

        String id = extractJsonString(content, "id");
        if (id == null || id.isBlank()) id = fallbackId;

        String name = extractJsonString(content, "name");
        if (name == null || name.isBlank()) name = id;

        String description = extractJsonString(content, "description");
        if (description == null) description = "";

        String version = extractJsonString(content, "version");
        if (version == null) version = "0.0.1";

        return new PluginMeta(id, name, description, version);
    }

    /**
     * 加载插件类并调用其 register 方法。
     * <p>
     * 查找策略：
     * 1. 清单中的 "mainClass" 字段
     * 2. 目录下的 .jar 文件中的 Main 类
     * 3. 如果都没有，仅注册元数据（工具由外部注册）
     */
    private void loadPluginClass(Path dir, String pluginId, PluginRegistrationApi api) {
        // 读取 mainClass 配置
        try {
            Path manifestPath = dir.resolve("openclaw.plugin.json");
            String content = Files.readString(manifestPath);
            String mainClass = extractJsonString(content, "mainClass");

            if (mainClass != null && !mainClass.isBlank()) {
                // 使用反射加载插件类
                Class<?> clazz = Class.forName(mainClass);
                Object instance = clazz.getDeclaredConstructor().newInstance();

                if (instance instanceof OperatorPlugin plugin) {
                    plugin.register(api);
                    log.info("[PluginLoader] Plugin '{}' 插件类已加载: {}", pluginId, mainClass);
                } else {
                    log.warn("[PluginLoader] Plugin '{}' class {} does not implement OperatorPlugin", pluginId, mainClass);
                }
            }
            // 如果没有 mainClass，仅注册元数据，工具由外部代码通过 api 注册
        } catch (ClassNotFoundException e) {
            log.warn("[PluginLoader] Plugin '{}' 插件 mainClass 未找到，仅注册元数据", pluginId);
        } catch (Exception e) {
            log.warn("[PluginLoader] Plugin '{}' 插件类加载失败: {}，仅注册元数据", pluginId, e.getMessage());
        }
    }

    /** 从 JSON 中提取字符串值 */
    private static String extractJsonString(String json, String key) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
        java.util.regex.Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
