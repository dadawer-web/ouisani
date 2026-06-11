package com.ouisani.aios.core.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 认证管理器 — AIOS 的 PAM 认证子系统。
 * <p>
 * 类比 Linux 的 PAM (Pluggable Authentication Modules)：
 * AuthManager 负责验证外部请求的身份凭证，决定是否允许访问内核系统调用。
 * 当前实现使用共享密钥模拟 JWT 验证，生产环境应集成真实的 OAuth2/JWT 提供者。
 * <p>
 * Token 格式：
 * <ul>
 *   <li>Header: {@code Authorization: Bearer AIOS-SUPER-SECRET-KEY}</li>
 *   <li>WebSocket query: {@code ?token=AIOS-SUPER-SECRET-KEY}</li>
 * </ul>
 */
public class AuthManager {

    private static final Logger log = LoggerFactory.getLogger(AuthManager.class);

    private static final String GATEWAY_SECRET = "AIOS-SUPER-SECRET-KEY";

    private static final class Holder {
        static final AuthManager INSTANCE = new AuthManager();
    }

    public static AuthManager instance() {
        return Holder.INSTANCE;
    }

    private AuthManager() {
        log.info("[API Gateway] AuthManager initialized. Gateway secret configured.");
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

        boolean valid = GATEWAY_SECRET.equals(cleaned);
        if (!valid) {
            log.warn("[API Gateway] Token verification failed: invalid token");
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
