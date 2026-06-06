package com.ouisani.aios.core.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * API Gateway Authentication Manager — simulated JWT verification.
 * <p>
 * In production, this would integrate with a real OAuth2 / JWT provider.
 * For now, it validates against a shared secret to demonstrate the
 * gateway auth pattern.
 *
 * <h3>Token format:</h3>
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
