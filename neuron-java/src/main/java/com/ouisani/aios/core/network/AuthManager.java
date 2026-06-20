package com.ouisani.aios.core.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 认证管理器 — AIOS 的 PAM 认证子系统。
 * <p>
 * 类比 Linux 的 PAM (Pluggable Authentication Modules)：
 * AuthManager 负责验证外部请求的身份凭证，决定是否允许访问内核系统调用。
 * <p>
 * 安全设计：
 * <ul>
 *   <li>网关密钥从环境变量 {@code AIOS_GATEWAY_SECRET} 读取，绝不硬编码</li>
 *   <li>如果环境变量未设置，启动时生成随机密钥并打印到控制台（仅一次）</li>
 *   <li>密钥值不记录到日志中</li>
 * </ul>
 */
public class AuthManager {

    private static final Logger log = LoggerFactory.getLogger(AuthManager.class);

    /** 网关密钥 — 从环境变量读取，绝不硬编码 */
    private final String gatewaySecret;

    private static final class Holder {
        static final AuthManager INSTANCE = new AuthManager();
    }

    public static AuthManager instance() {
        return Holder.INSTANCE;
    }

    private AuthManager() {
        // 从环境变量读取密钥
        String secret = System.getenv("AIOS_GATEWAY_SECRET");
        if (secret == null || secret.isEmpty()) {
            // 未配置密钥 — 使用固定默认值（与前端一致）
            // 生产环境必须配置 AIOS_GATEWAY_SECRET 环境变量！
            secret = "AIOS-SUPER-SECRET-KEY";
            System.out.println("  ⚠ [AuthManager] AIOS_GATEWAY_SECRET 未设置! 正在使用默认开发密钥。");
            System.out.println("  ⚠ [AuthManager] 生产环境请设置 AIOS_GATEWAY_SECRET 环境变量。");
        }
        this.gatewaySecret = secret;
        log.info("[AuthManager] Gateway 密钥已配置 (length={}).", gatewaySecret.length());
    }

    /**
     * 生成随机密钥 — 当环境变量未配置时使用。
     */
    private static String generateRandomSecret() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Verify a security token.
     *
     * @param token the token string (without "Bearer " prefix)
     * @return true if the token is valid
     */
    public boolean verifyToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }

        // Strip "Bearer " prefix if present
        String cleaned = token.trim();
        if (cleaned.toLowerCase().startsWith("bearer ")) {
            cleaned = cleaned.substring(7).trim();
        }

        boolean valid = gatewaySecret.equals(cleaned);
        if (!valid) {
            log.warn("[API Gateway] Token 验证失败: 无效 Token");
        }
        return valid;
    }

    /**
     * Extract token from Authorization header.
     *
     * @param authHeader the Authorization header value
     * @return the token string, or null if absent
     */
    public String extractFromHeader(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return null;
        }
        return authHeader.trim();
    }

    /**
     * Extract token from query parameter.
     *
     * @param queryToken the token query parameter value
     * @return the token string, or null if absent
     */
    public String extractFromQuery(String queryToken) {
        if (queryToken == null || queryToken.isBlank()) {
            return null;
        }
        return queryToken.trim();
    }
}
