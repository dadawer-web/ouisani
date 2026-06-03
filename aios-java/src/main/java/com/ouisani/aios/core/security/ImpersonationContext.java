package com.ouisani.aios.core.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Impersonation context for AIOS security token management.
 * <p>
 * Provides a static {@link ThreadLocal} to temporarily elevate a thread's
 * security token, and a {@link #runAs(SecurityToken, Runnable)} method
 * that guarantees cleanup in a finally block to prevent privilege leaks.
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * SecurityToken adminToken = SecurityToken.kernelToken("sys_admin");
 * ImpersonationContext.runAs(adminToken, () -> {
 *     // This code runs with adminToken's privileges
 *     ObjectManager.instance().openHandle("worker_1", "/var/secret_keys");
 * });
 * // After runAs returns, the impersonation token is automatically removed
 * }</pre>
 *
 * <h3>Alternative try-with-resources style:</h3>
 * <pre>{@code
 * try (var ignored = ImpersonationContext.impersonate(adminToken)) {
 *     // elevated privileges here
 * } // auto-reverted
 * }</pre>
 */
public final class ImpersonationContext {

    private static final Logger log = LoggerFactory.getLogger(ImpersonationContext.class);

    /**
     * The current impersonation token for this thread.
     * When set, security checks use this token instead of the task's primary token.
     */
    public static final ThreadLocal<SecurityToken> CURRENT_TOKEN = new ThreadLocal<>();

    private ImpersonationContext() {}

    /**
     * Execute an action with an impersonated security token.
     * The token is set before execution and <strong>always</strong> removed
     * in the finally block, preventing privilege leaks even if the action throws.
     *
     * @param token  the security token to impersonate
     * @param action the action to execute under the impersonated token
     */
    public static void runAs(SecurityToken token, Runnable action) {
        SecurityToken previous = CURRENT_TOKEN.get();
        try {
            CURRENT_TOKEN.set(token);
            log.info("[Security] Agent '{}' is impersonating '{}' for critical operation!",
                    previous != null ? previous.ownerId() : "unknown",
                    token.ownerId());
            action.run();
        } finally {
            if (previous != null) {
                CURRENT_TOKEN.set(previous);
            } else {
                CURRENT_TOKEN.remove();
            }
            log.debug("[Security] Impersonation reverted for thread '{}'",
                    Thread.currentThread().getName());
        }
    }

    /**
     * Impersonate a security token, returning an {@link AutoCloseable}
     * for try-with-resources usage.
     *
     * @param token the token to impersonate
     * @return an AutoCloseable that removes the impersonation token on close
     */
    public static AutoCloseable impersonate(SecurityToken token) {
        CURRENT_TOKEN.set(token);
        log.info("[Security] Agent is impersonating '{}' for critical operation!",
                token.ownerId());
        return () -> {
            CURRENT_TOKEN.remove();
            log.debug("[Security] Impersonation ended for thread '{}'",
                    Thread.currentThread().getName());
        };
    }

    /**
     * Get the current impersonation token (may be null).
     */
    public static SecurityToken current() {
        return CURRENT_TOKEN.get();
    }

    /**
     * Clear any impersonation token on the current thread.
     */
    public static void clear() {
        CURRENT_TOKEN.remove();
    }
}
