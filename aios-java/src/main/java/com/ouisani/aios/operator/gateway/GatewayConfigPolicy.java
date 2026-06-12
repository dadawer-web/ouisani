package com.ouisani.aios.operator.gateway;

import java.util.*;

/**
 * Gateway 配置变更安全策略 — 对标 OpenClaw 的 ALLOWED_GATEWAY_CONFIG_PATHS。
 * <p>
 * 防止 Agent 通过 config.apply/config.patch 修改危险配置项。
 * 采用白名单机制：只有明确允许的配置路径才能被修改。
 * <p>
 * OS 类比：相当于 Linux 的 capabilities — 进程只能使用明确授权的能力，
 * 而不是拥有 root 的全部权限。
 */
public class GatewayConfigPolicy {

    /** 允许修改的配置路径白名单（支持通配符） */
    private static final List<String> ALLOWED_PATHS = List.of(
            "agents.defaults.thinkingDefault",
            "agents.defaults.reasoningDefault",
            "agents.defaults.fastModeDefault",
            "agents.list.*.id",
            "agents.list.*.model",
            "agents.list.*.thinkingDefault",
            "agents.list.*.reasoningDefault",
            "agents.list.*.fastModeDefault",
            "channels.*.requireMention",
            "messages.visibleReplies",
            "messages.groupChat.visibleReplies",
            "messages.groupChat.unmentionedInbound"
    );

    /** 危险配置标志 — 启用这些标志时需要额外确认 */
    private static final Set<String> DANGEROUS_FLAGS = Set.of(
            "security.allowUnauthenticatedAccess",
            "security.disableSandbox",
            "security.allowNetworkAccess",
            "experimental.enabled"
    );

    /**
     * 验证配置变更是否被允许。
     *
     * @param changedPaths 变更的配置路径集合
     * @return 验证结果
     */
    public static ValidationResult validateChanges(Set<String> changedPaths) {
        List<String> disallowed = new ArrayList<>();

        for (String path : changedPaths) {
            if (!isPathAllowed(path)) {
                disallowed.add(path);
            }
        }

        // 检查危险标志
        List<String> dangerousEnabled = new ArrayList<>();
        for (String path : changedPaths) {
            if (DANGEROUS_FLAGS.contains(path)) {
                dangerousEnabled.add(path);
            }
        }

        if (!disallowed.isEmpty()) {
            return ValidationResult.denied("Configuration paths not allowed: " + disallowed, disallowed);
        }

        if (!dangerousEnabled.isEmpty()) {
            return ValidationResult.denied("Dangerous configuration flags cannot be enabled: " + dangerousEnabled, dangerousEnabled);
        }

        return ValidationResult.allowed();
    }

    /** 检查路径是否匹配白名单 */
    private static boolean isPathAllowed(String path) {
        for (String pattern : ALLOWED_PATHS) {
            if (matchPath(path, pattern)) return true;
        }
        return false;
    }

    /** 路径匹配 — 支持 * 通配符 */
    private static boolean matchPath(String path, String pattern) {
        if (pattern.equals(path)) return true;

        String[] pathParts = path.split("\\.");
        String[] patternParts = pattern.split("\\.");

        if (pathParts.length != patternParts.length) return false;

        for (int i = 0; i < pathParts.length; i++) {
            if (patternParts[i].equals("*")) continue;
            if (!patternParts[i].equals(pathParts[i])) return false;
        }
        return true;
    }

    /** 验证结果 */
    public record ValidationResult(boolean allowed, String reason, List<String> disallowedPaths) {
        static ValidationResult allowed() {
            return new ValidationResult(true, null, List.of());
        }
        static ValidationResult denied(String reason, List<String> disallowed) {
            return new ValidationResult(false, reason, disallowed);
        }
    }
}
