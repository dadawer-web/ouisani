package com.ouisani.aios.core.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 配置管理器 — 对标 Claude Code 的 mcp/config.ts。
 * <p>
 * 合并所有作用域的 MCP 配置，优先级：
 * enterprise > plugin > user > project > local > claude.ai
 * <p>
 * OS 类比：相当于 Linux 的 modprobe 配置 — /etc/modprobe.d/*.conf 合并加载。
 */
public class McpConfigManager {

    private static final Logger log = LoggerFactory.getLogger(McpConfigManager.class);
    private static final McpConfigManager INSTANCE = new McpConfigManager();

    /** 配置作用域 */
    public enum ConfigScope {
        ENTERPRISE, PLUGIN, USER, PROJECT, LOCAL
    }

    /** MCP 服务器配置 */
    public record McpServerConfig(
            String name,
            String type,       // stdio, sse, http, ws
            String url,        // for sse/http/ws
            List<String> command, // for stdio
            Map<String, String> env,
            Map<String, String> headers,
            ConfigScope scope
    ) {}

    private final Map<String, McpServerConfig> servers = new ConcurrentHashMap<>();

    private McpConfigManager() {}

    public static McpConfigManager instance() { return INSTANCE; }

    /**
     * 添加 MCP 服务器配置。
     */
    public void addServer(McpServerConfig config) {
        McpServerConfig existing = servers.get(config.name());
        if (existing != null) {
            // 高优先级覆盖低优先级
            if (config.scope().ordinal() <= existing.scope().ordinal()) {
                servers.put(config.name(), config);
                log.info("[McpConfig] Updated server: {} (scope: {} → {})", config.name(), existing.scope(), config.scope());
            }
        } else {
            servers.put(config.name(), config);
            log.info("[McpConfig] Added server: {} (type: {}, scope: {})", config.name(), config.type(), config.scope());
        }
    }

    /**
     * 移除 MCP 服务器配置。
     */
    public void removeServer(String name) {
        McpServerConfig removed = servers.remove(name);
        if (removed != null) {
            log.info("[McpConfig] Removed server: {}", name);
        }
    }

    /**
     * 获取所有服务器配置。
     */
    public Collection<McpServerConfig> getAllServers() {
        return Collections.unmodifiableCollection(servers.values());
    }

    /**
     * 按名称查找服务器。
     */
    public Optional<McpServerConfig> getServer(String name) {
        return Optional.ofNullable(servers.get(name));
    }

    /**
     * 策略过滤 — 企业策略检查。
     */
    public void filterByPolicy(Set<String> allowedServers, Set<String> deniedServers) {
        Iterator<Map.Entry<String, McpServerConfig>> it = servers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, McpServerConfig> entry = it.next();
            String name = entry.getKey();
            if (deniedServers.contains(name)) {
                it.remove();
                log.info("[McpConfig] Removed by policy (denied): {}", name);
            } else if (!allowedServers.isEmpty() && !allowedServers.contains(name)) {
                it.remove();
                log.info("[McpConfig] Removed by policy (not in allowlist): {}", name);
            }
        }
    }
}
