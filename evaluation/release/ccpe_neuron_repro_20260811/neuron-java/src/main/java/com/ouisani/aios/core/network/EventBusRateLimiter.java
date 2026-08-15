package com.ouisani.aios.core.network;

import com.ouisani.aios.core.audit.UnifiedAuditLog;
import com.ouisani.aios.core.ipc.CallerContext;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EventBus 限流器 — per-agent + per-tenant 令牌桶，防止跨维度挤兑。
 * <p>
 * <b>设计动机</b>：场景4 红队测试暴露的盲点 — EventBus.broadcast 零限流，任意 agent 可无限广播，
 * 触发 SSE 推送 + 虚拟线程 fan-out，多租户下单恶意租户可在被 campaign 层拦截前拖慢其他租户。
 * 本限流器在资源访问层（而非 campaign 层）直接节流，消除 pressure_buildup 残留。
 * <p>
 * <b>OS 类比</b>：Linux tc（Traffic Control）的 Token Bucket Filter — 限制网络带宽。
 * 与 {@link com.ouisani.aios.core.security.RateLimitSyscallFilter} 同构，但作用在 EventBus 通道
 * 而非 syscall 层。
 * <p>
 * <b>豁免规则</b>（与 {@code RateLimitSyscallFilter.isPrivilegedAgent} 对齐）：
 * <ul>
 *   <li>{@code sys.*} 系统通道豁免（内核遥测/告警/恢复通道不应被节流）</li>
 *   <li>{@link CallerContext#current()} 为 null 豁免（内核守护进程未 set CallerContext）</li>
 *   <li>{@code sys_*} / {@code root_cli} / {@code kernel} 前缀的特权 agent 豁免</li>
 * </ul>
 * <p>
 * <b>双维度令牌桶</b>：per-agent（防单 agent 滥用）+ per-tenant（防单租户挤兑）。任一耗尽即拒绝。
 * tenantId 为 null（legacy）时 per-tenant 维度 skip，per-agent 仍生效。
 * <p>
 * <b>拒绝行为</b>：丢弃（不抛异常 — broadcast 是 fire-and-forget，抛异常会阻塞调用方）+ 双写审计
 * （{@link UnifiedAuditLog#append} LAYER_RATELIMIT 跨层 traceId 串联 +
 * {@link SemanticEtw#logAuditEvent} 补齐三层 ETW 的 rate-limit 腿）。
 * <p>
 * <b>开关</b>：{@link #setEnabled(false)} 供红队测试模拟 Baseline（无限流）配置。生产默认开启。
 *
 * @see CallerContext
 * @see com.ouisani.aios.core.security.RateLimitSyscallFilter
 */
public final class EventBusRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(EventBusRateLimiter.class);

    private static final class Holder {
        static final EventBusRateLimiter INSTANCE = new EventBusRateLimiter();
    }

    public static EventBusRateLimiter instance() {
        return Holder.INSTANCE;
    }

    /** user 通道默认限速：每秒 50 次广播，突发上限 60（与 RateLimitSyscallFilter 一致）。 */
    static final int DEFAULT_PERMITS_PER_SECOND = 50;
    static final int DEFAULT_BURST = 60;
    private static final long REFILL_INTERVAL_MS = 100;

    /** 全局开关 — 测试可关闭模拟 Baseline。生产默认开启。 */
    private volatile boolean enabled = true;

    private final ConcurrentHashMap<String, TokenBucket> agentBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TokenBucket> tenantBuckets = new ConcurrentHashMap<>();

    private EventBusRateLimiter() {
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        log.info("[EventBusRateLimiter] enabled={}", enabled);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 尝试消费一次广播配额。
     * <p>
     * 豁免路径（sys.* 通道 / 内核守护进程 / 特权 agent）直接返回 true，不消耗令牌。
     *
     * @param eventType 事件通道名
     * @return true 放行；false 拒绝（已双写审计）
     */
    public boolean tryConsume(String eventType) {
        if (!enabled) return true; // Baseline 模式：无限流
        if (eventType != null && eventType.startsWith("sys.")) return true; // 系统通道豁免
        CallerContext ctx = CallerContext.current();
        if (ctx == null) return true; // 内核守护进程豁免
        String agentId = ctx.agentId();
        if (isPrivileged(agentId)) return true;

        boolean agentOk = bucketFor(agentBuckets, agentId).tryConsume();
        String tenantId = ctx.tenantId();
        boolean tenantOk = (tenantId == null || tenantId.isBlank())
                ? true : bucketFor(tenantBuckets, tenantId).tryConsume();

        if (!agentOk || !tenantOk) {
            String deniedBy = !agentOk ? "agent:" + agentId : "tenant:" + tenantId;
            log.warn("[EventBusRateLimiter] 广播被限流: channel={}, caller={}, deniedBy={}",
                    eventType, agentId, deniedBy);
            recordDenial(eventType, agentId, tenantId, deniedBy);
            return false;
        }
        return true;
    }

    private boolean isPrivileged(String agentId) {
        return agentId != null
                && (agentId.startsWith("sys_") || "root_cli".equals(agentId) || "kernel".equals(agentId));
    }

    private TokenBucket bucketFor(ConcurrentHashMap<String, TokenBucket> map, String key) {
        return map.computeIfAbsent(key, k -> new TokenBucket(DEFAULT_BURST, DEFAULT_PERMITS_PER_SECOND));
    }

    private void recordDenial(String eventType, String agentId, String tenantId, String deniedBy) {
        String target = "channel=" + eventType + " " + deniedBy;
        String reason = "EventBus broadcast rate limit exceeded (max=" + DEFAULT_PERMITS_PER_SECOND + "/s)";
        // 双写审计：UnifiedAuditLog（跨层 traceId 串联）+ SemanticEtw（三层 ETW 补齐 rate-limit 腿）
        UnifiedAuditLog.append(UnifiedAuditLog.LAYER_RATELIMIT, "RATE_LIMITED",
                agentId, target, reason);
        try {
            SemanticEtw.getInstance().logAuditEvent(agentId,
                    tenantId != null ? tenantId : "null",
                    "eventbus_broadcast", "MEDIUM", "eventbus_rate_limit",
                    eventType, reason);
        } catch (Throwable t) {
            log.debug("[EventBusRateLimiter] ETW 记录失败: {}", t.getMessage());
        }
    }

    /** 测试用：重置所有令牌桶，避免跨用例污染。 */
    public void resetForTest() {
        agentBuckets.clear();
        tenantBuckets.clear();
    }

    /** 令牌桶（与 RateLimitSyscallFilter.TokenBucket 同构，不强行抽公共以避免过度耦合）。 */
    static final class TokenBucket {
        private final int maxTokens;
        private final int refillPerTick;
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
            long current = tokens.get();
            if (current <= 0) return false;
            tokens.set(current - 1);
            return true;
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
