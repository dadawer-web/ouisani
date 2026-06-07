package com.ouisani.aios.core.security;

import com.ouisani.aios.core.syscall.SyscallRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 限速系统调用过滤器 — 令牌桶算法。
 * <p>
 * 限制每个非特权 Agent 每秒最多发起 {@code maxPermitsPerSecond} 次系统调用。
 * REALTIME 和 HIGH 优先级 Agent 豁免限速（内核 Agent 不应被节流）。
 *
 * <h3>OS 类比: Linux Seccomp 速率限制 + tc 令牌桶</h3>
 * Linux 的流量控制 (tc) 使用令牌桶过滤器 (Token Bucket Filter) 限制网络带宽，
 * Seccomp 可以限制系统调用频率。RateLimitSyscallFilter 将两者融合：
 * 用令牌桶算法限制 Agent 的系统调用频率，防止资源滥用。
 *
 * <h3>令牌桶算法：</h3>
 * <ul>
 *   <li>每个 Agent 拥有独立的令牌桶</li>
 *   <li>令牌以恒定速率补充（maxPermitsPerSecond / refillIntervalMs 每次补充）</li>
 *   <li>每次系统调用消耗一个令牌</li>
 *   <li>令牌耗尽时，系统调用被拒绝并抛出 SecurityException</li>
 * </ul>
 *
 * @see SyscallFilter
 */
public class RateLimitSyscallFilter implements SyscallFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitSyscallFilter.class);

    /** 默认值：每个 Agent 每秒 50 次系统调用 */
    private static final int DEFAULT_MAX_PERMITS = 50;
    /** 令牌补充间隔（毫秒） */
    private static final long REFILL_INTERVAL_MS = 100;
    /** 最大突发量（令牌数不超过此值） */
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
        // 内核和系统 Agent 豁免限速
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
     * 内核 Agent（sys_*）和 root_cli 豁免限速。
     */
    private boolean isPrivilegedAgent(String agentId) {
        return agentId != null
                && (agentId.startsWith("sys_") || "root_cli".equals(agentId) || "kernel".equals(agentId));
    }

    /** 简单令牌桶实现 */
    static class TokenBucket {
        private final int maxTokens;
        private final int refillPerTick; // 每次 REFILL_INTERVAL_MS 补充的令牌数
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
