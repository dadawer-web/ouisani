package com.ouisani.aios.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * 语义配置注册表 — AIOS 的 /etc 配置注册表。
 * <p>
 * 类比 Linux 的 /etc 配置目录与 Windows 注册表的融合：
 * 使用 {@link ConcurrentSkipListMap} 维护一个有序的全局配置树，
 * 既可通过 Java API 访问，也可通过 VFS 的 {@code /proc/registry} 路径访问。
 * <p>
 * 默认键值在系统启动时由 AIOS 内核配置初始化。
 */
public final class SemanticRegistry {

    private static final Logger log = LoggerFactory.getLogger(SemanticRegistry.class);

    private static final class Holder {
        static final SemanticRegistry INSTANCE = new SemanticRegistry();
    }

    public static SemanticRegistry instance() {
        return Holder.INSTANCE;
    }

    private final ConcurrentSkipListMap<String, String> tree = new ConcurrentSkipListMap<>();

    private SemanticRegistry() {
        // Default kernel configuration
        setValue("HKEY_LOCAL_AIOS/System/DefaultLlm", "smart_model");
        setValue("HKEY_LOCAL_AIOS/System/KernelVersion", "0.1.0-java");
        setValue("HKEY_LOCAL_AIOS/System/VirtualThreads", "enabled");
        setValue("HKEY_LOCAL_AIOS/System/SwapEnabled", "true");
        setValue("HKEY_LOCAL_AIOS/System/SignalSupport", "SIGTERM,SIGINT,SIGUSR1");
        setValue("HKEY_LOCAL_AIOS/System/ShmDefaultSegment", "blackboard");
        setValue("HKEY_LOCAL_AIOS/LLM/FastModel", "fast_model");
        setValue("HKEY_LOCAL_AIOS/LLM/SmartModel", "smart_model");
        setValue("HKEY_LOCAL_AIOS/LLM/SmartThreshold", "500");
        setValue("HKEY_LOCAL_AIOS/Security/BpfEnabled", "true");
        setValue("HKEY_LOCAL_AIOS/Security/HandleBasedAccess", "true");
        setValue("HKEY_LOCAL_AIOS/Cgroup/RootQuota", "1000000");
        setValue("HKEY_LOCAL_AIOS/Cgroup/AgentDefaultQuota", "50000");
        setValue("HKEY_LOCAL_AIOS/Cgroup/SwapThresholdRatio", "0.95");

        log.info("[SemanticRegistry] 已初始化，默认条目数: {}", tree.size());
    }

    public void setValue(String key, String value) {
        tree.put(key, value);
        log.debug("[SemanticRegistry] 设置: {} = {}", key, value);
    }

    public String getValue(String key) {
        return tree.get(key);
    }

    public String getValue(String key, String defaultValue) {
        return tree.getOrDefault(key, defaultValue);
    }

    public boolean removeKey(String key) {
        String removed = tree.remove(key);
        if (removed != null) {
            log.debug("[SemanticRegistry] 已移除: {}", key);
            return true;
        }
        return false;
    }

    public boolean containsKey(String key) {
        return tree.containsKey(key);
    }

    /**
     * Get all keys with a given prefix (e.g. "HKEY_LOCAL_AIOS/System/").
     */
    public NavigableMap<String, String> getSubTree(String prefix) {
        return tree.subMap(prefix, true, prefix + Character.MAX_VALUE, true);
    }

    /**
     * Dump the entire registry as a formatted string.
     */
    public String dumpAll() {
        StringBuilder sb = new StringBuilder();
        tree.forEach((k, v) -> sb.append(k).append(" = ").append(v).append('\n'));
        return sb.toString();
    }

    /**
     * Dump a subtree as a formatted string.
     */
    public String dumpSubTree(String prefix) {
        StringBuilder sb = new StringBuilder();
        getSubTree(prefix).forEach((k, v) -> sb.append(k).append(" = ").append(v).append('\n'));
        return sb.toString();
    }

    public int size() {
        return tree.size();
    }

    public Map<String, String> asMap() {
        return Collections.unmodifiableMap(tree);
    }
}
