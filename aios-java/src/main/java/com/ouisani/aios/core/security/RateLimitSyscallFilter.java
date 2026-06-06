package com.ouisani.aios.core.security;

import com.ouisani.aios.core.syscall.SyscallRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rate-limiting syscall filter — token-bucket algorithm.
 * <p>
 * Limits each non-privileged Agent to at most {@code maxPermitsPerSecond}
 * syscalls per second. REALTIME and HIGH priority agents are exempt
 * (kernel agents should never be throttled).
 *
 * <h3>Token Bucket Algorithm:</h3>
 * <ul>
 *   <li>Each agent gets its own bucket of tokens.</li>
 *   <li>Tokens refill at a steady rate (maxPermitsPerSecond / refillIntervalMs per tick).</li>
 *   <li>Each syscall consumes one token.</li>
 *   <li>If no tokens remain, the syscall is rejected with a SecurityException.</li>
 * </ul>
 */
public class RateLimitSyscallFilter implements SyscallFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitSyscallFilter.class);

    /** Default: 50 syscalls per second per agent. */
    private static final int DEFAULT_MAX_PERMITS = 50;
    /** Refill interval in milliseconds. */
    private static final long REFILL_INTERVAL_MS = 100;
    /** Maximum burst size (tokens cannot exceed this). */
    private static final int MAX_BURST = 60;

    private final int maxPermitsPerSecond;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimitSyscallFilter() {
        this(DEFAULT_MAX_PERMITS);
    }

    public RateLimitSyscallFilter(int maxPermitsPerSecond) {
        this.maxPermitsPerSecond = maxPermitsPerSecond;
        log.info("[Seccomp/RateLimit] Initialized: maxPermitsPerSecond={}, exempt=REALTIME/HIGH",
                maxPermitsPerSecond);
    }

    @Override
    public void preFilter(String agentId, SyscallRequest request) throws SecurityException {
        // Kernel and system agents are exempt from rate limiting
        if (isPrivilegedAgent(agentId)) {
            return;
        }

        TokenBucket bucket = buckets.computeIfAbsent(agentId,
                id -> new TokenBucket(MAX_BURST, maxPermitsPerSecond));

        if (!bucket.tryConsume()) {
            String msg = "Syscall rate limit exceeded for agent '" + agentId
                    + "' (max=" + maxPermitsPerSecond + "/s, action=" + request.fullAction() + ")";
            log.warn("[Seccomp/RateLimit] {}", msg);
            throw new SecurityException(msg);
        }
    }

    /**
     * Kernel agents (sys_*) and root CLI are exempt from rate limiting.
     */
    private boolean isPrivilegedAgent(String agentId) {
        return agentId != null
                && (agentId.startsWith("sys_") || "root_cli".equals(agentId) || "kernel".equals(agentId));
    }

    /**
     * Simple token bucket implementation.
     */
    static class TokenBucket {
        private final int maxTokens;
        private final int refillPerTick; // tokens added per REFILL_INTERVAL_MS
        private final AtomicLong tokens;
        private volatile long lastRefillTime;

        TokenBucket(int maxTokens, int permitsPerSecond) {
            this.maxTokens = maxTokens;
            this.refillPerTick = Math.max(1, permitsPerSecond * (int) REFILL_INTERVAL_MS / 1000);
            this.tokens = new AtomicLong(maxTokens);
            this.lastRefillTime = System.currentTimeMillis();
        }

        synchronized boolean tryConsume() {
            refill();
            while (true) {
                long current = tokens.get();
                if (current <= 0) {
                    return false;
                }
                if (tokens.compareAndSet(current, current - 1)) {
                    return true;
                }
            }
        }

        private void refill() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefillTime;
            if (elapsed >= REFILL_INTERVAL_MS) {
                long ticks = elapsed / REFILL_INTERVAL_MS;
                long toAdd = ticks * refillPerTick;
                long current = tokens.get();
                long newTokens = Math.min(maxTokens, current + toAdd);
                tokens.set(newTokens);
                lastRefillTime = now;
            }
        }
    }
}
