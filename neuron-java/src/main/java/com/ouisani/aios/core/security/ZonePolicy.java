package com.ouisani.aios.core.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 安全约束声明式定义 — 借鉴 Google Knowledge Catalog 的 Web 爬虫安全约束模式。
 * <p>
 * 将安全规则从硬编码改为配置驱动。支持：
 * <ul>
 *   <li>域名白名单 (allowed_hosts)</li>
 *   <li>路径前缀白名单 (allowed_path_prefixes)</li>
 *   <li>路径子串黑名单 (denied_path_substrings)</li>
 *   <li>深度限制 (max_depth)</li>
 *   <li>资源预算 (max_pages / max_requests)</li>
 *   <li>命令黑名单 (denied_commands)</li>
 * </ul>
 * <p>
 * 配置文件格式（YAML 风格，由 ZonePolicyLoader 解析）：
 * <pre>
 * zone: WORK
 * allowed_path_prefixes:
 *   - /workspace/
 *   - /tmp/
 * denied_path_substrings:
 *   - ../
 *   - /etc/passwd
 * denied_commands:
 *   - rm -rf /
 *   - chmod 777
 * max_depth: 5
 * max_requests: 100
 * </pre>
 *
 * @see ZonePolicyLoader
 * @see ContainmentZoneManager
 */
public class ZonePolicy {

    private static final Logger log = LoggerFactory.getLogger(ZonePolicy.class);

    private final String zoneName;
    private final Set<String> allowedHosts;
    private final Set<String> allowedPathPrefixes;
    private final Set<String> deniedPathSubstrings;
    private final Set<String> deniedCommands;
    private final int maxDepth;
    private final int maxRequests;
    private final boolean readOnly;

    /** 全局策略注册表：zoneName → ZonePolicy */
    private static final ConcurrentHashMap<String, ZonePolicy> policies = new ConcurrentHashMap<>();

    /** 默认策略（当某个 zone 没有显式配置时使用） */
    private static volatile ZonePolicy defaultPolicy = new ZonePolicy(
            "DEFAULT",
            Set.of(), // 不限制域名
            Set.of(), // 不限制路径前缀
            Set.of("../", "/etc/shadow", "/etc/passwd"), // 默认禁止路径遍历
            Set.of("rm -rf /", "mkfs", "dd if=/dev/zero of=/dev/sda", ":(){ :|:& };:"),
            10,   // 默认深度 10
            1000, // 默认请求 1000
            false
    );

    public ZonePolicy(String zoneName, Set<String> allowedHosts, Set<String> allowedPathPrefixes,
                      Set<String> deniedPathSubstrings, Set<String> deniedCommands,
                      int maxDepth, int maxRequests, boolean readOnly) {
        this.zoneName = zoneName;
        this.allowedHosts = allowedHosts != null ? Set.copyOf(allowedHosts) : Set.of();
        this.allowedPathPrefixes = allowedPathPrefixes != null ? Set.copyOf(allowedPathPrefixes) : Set.of();
        this.deniedPathSubstrings = deniedPathSubstrings != null ? Set.copyOf(deniedPathSubstrings) : Set.of();
        this.deniedCommands = deniedCommands != null ? Set.copyOf(deniedCommands) : Set.of();
        this.maxDepth = maxDepth;
        this.maxRequests = maxRequests;
        this.readOnly = readOnly;
    }

    /**
     * 注册一个 zone 的安全策略。
     */
    public static void register(String zoneName, ZonePolicy policy) {
        policies.put(zoneName, policy);
        log.info("[ZonePolicy] 策略已注册: zone={}, allowedPaths={}, deniedPaths={}, deniedCmds={}, maxDepth={}, maxReq={}, readOnly={}",
                zoneName, policy.allowedPathPrefixes.size(), policy.deniedPathSubstrings.size(),
                policy.deniedCommands.size(), policy.maxDepth, policy.maxRequests, policy.readOnly);
    }

    /**
     * 获取指定 zone 的策略。如果没有显式配置，返回默认策略。
     */
    public static ZonePolicy get(String zoneName) {
        return policies.getOrDefault(zoneName, defaultPolicy);
    }

    /**
     * 根据路径获取适用的策略。
     */
    public static ZonePolicy getForPath(String path) {
        ContainmentZone zone = ContainmentZone.forPath(path);
        if (zone != null) {
            return get(zone.name());
        }
        return defaultPolicy;
    }

    /**
     * 检查路径是否被允许访问。
     *
     * @param path     要检查的路径
     * @param isWrite  是否是写操作
     * @return true 如果路径被允许
     */
    public boolean checkPath(String path, boolean isWrite) {
        if (path == null || path.isEmpty()) return false;

        // 只读 zone 拒绝写操作
        if (isWrite && readOnly) {
            log.warn("[ZonePolicy] 写操作被拒绝（只读 zone）: zone={}, path={}", zoneName, path);
            return false;
        }

        // 检查路径子串黑名单
        String normalizedPath = path.toLowerCase();
        for (String denied : deniedPathSubstrings) {
            if (normalizedPath.contains(denied.toLowerCase())) {
                log.warn("[ZonePolicy] 路径命中黑名单: zone={}, path={}, pattern={}", zoneName, path, denied);
                return false;
            }
        }

        // 检查路径前缀白名单（如果配置了）
        if (!allowedPathPrefixes.isEmpty()) {
            boolean allowed = false;
            for (String prefix : allowedPathPrefixes) {
                if (path.startsWith(prefix)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                log.warn("[ZonePolicy] 路径不在白名单中: zone={}, path={}", zoneName, path);
                return false;
            }
        }

        return true;
    }

    /**
     * 检查命令是否被允许执行。
     *
     * @param command 要检查的命令字符串
     * @return true 如果命令被允许
     */
    public boolean checkCommand(String command) {
        if (command == null || command.isEmpty()) return false;

        String normalizedCmd = command.toLowerCase().trim();

        for (String denied : deniedCommands) {
            if (normalizedCmd.contains(denied.toLowerCase())) {
                log.warn("[ZonePolicy] 命令命中黑名单: zone={}, cmd={}, pattern={}", zoneName, command, denied);
                return false;
            }
        }

        return true;
    }

    /**
     * 检查域名是否被允许访问。
     *
     * @param host 要检查的域名
     * @return true 如果域名被允许（白名单为空时允许所有）
     */
    public boolean checkHost(String host) {
        if (host == null || host.isEmpty()) return false;

        // 白名单为空时允许所有域名
        if (allowedHosts.isEmpty()) return true;

        for (String allowed : allowedHosts) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) {
                return true;
            }
        }

        log.warn("[ZonePolicy] 域名不在白名单中: zone={}, host={}", zoneName, host);
        return false;
    }

    /**
     * 检查深度是否在允许范围内。
     *
     * @param depth 当前深度
     * @return true 如果深度在允许范围内
     */
    public boolean checkDepth(int depth) {
        if (depth > maxDepth) {
            log.warn("[ZonePolicy] 深度超限: zone={}, depth={}, max={}", zoneName, depth, maxDepth);
            return false;
        }
        return true;
    }

    // ── Getters ──

    public String getZoneName() { return zoneName; }
    public Set<String> getAllowedHosts() { return allowedHosts; }
    public Set<String> getAllowedPathPrefixes() { return allowedPathPrefixes; }
    public Set<String> getDeniedPathSubstrings() { return deniedPathSubstrings; }
    public Set<String> getDeniedCommands() { return deniedCommands; }
    public int getMaxDepth() { return maxDepth; }
    public int getMaxRequests() { return maxRequests; }
    public boolean isReadOnly() { return readOnly; }

    /**
     * 设置默认策略。
     */
    public static void setDefaultPolicy(ZonePolicy policy) {
        defaultPolicy = policy;
        log.info("[ZonePolicy] 默认策略已更新");
    }

    /**
     * 获取所有已注册的策略。
     */
    public static Map<String, ZonePolicy> getAllPolicies() {
        return new LinkedHashMap<>(policies);
    }
}
