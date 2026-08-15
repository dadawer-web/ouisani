package com.ouisani.aios.core.security;

import com.ouisani.aios.core.audit.UnifiedAuditLog;
import com.ouisani.aios.core.ipc.CallerContext;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * VFS 限流器 — per-agent + per-tenant 令牌桶，防止跨维度挤兑。
 * <p>
 * <b>设计动机</b>：场景4 红队测试暴露的盲点 — VfsManager.readText/writeText/mount 零配额，
 * 仅靠 rwLock 保证并发正确性、FileAccessRecorder 只记录不节流。Agent 可无限发起 VFS 操作，
 * 消耗锁竞争 + 物理磁盘 I/O，多租户下拖慢其他租户。本限流器在资源访问层直接节流。
 * <p>
 * <b>OS 类比</b>：Linux cgroup v2 的 io.max（IO 限速）+ memory.max（内存配额）。
 * 与 {@link RateLimitSyscallFilter} 同构，但作用在 VFS API 直调路径（agent 经 tool 触达）。
 * <p>
 * <b>差异化限速</b>：
 * <ul>
 *   <li>write / mount：重限流（20/s，burst 25）— 写操作消耗锁 + 磁盘 I/O，挤兑影响大</li>
 *   <li>read：轻限流（200/s，burst 240）— 读操作走读锁，影响小</li>
 * </ul>
 * <p>
 * <b>豁免规则</b>（与 {@code RateLimitSyscallFilter.isPrivilegedAgent} 对齐）：
 * {@link CallerContext#current()} 为 null 豁免（内核守护进程）+
 * {@code sys_*} / {@code root_cli} / {@code kernel} 前缀特权 agent 豁免。
 * <p>
 * <b>双维度令牌桶</b>：per-agent（防单 agent 滥用）+ per-tenant（防单租户挤兑）。任一耗尽即拒绝。
 * tenantId 为 null（legacy）时 per-tenant 维度 skip。
 * <p>
 * <b>拒绝行为</b>：抛 {@link SecurityException}（与 {@link RateLimitSyscallFilter} 一致，
 * agent 需感知写失败并决定重试/放弃）+ 双写审计（{@link UnifiedAuditLog#append} LAYER_RATELIMIT
 * 跨层 traceId 串联 + {@link SemanticEtw#logAuditEvent} 补齐三层 ETW 的 rate-limit 腿）。
 * <p>
 * <b>开关</b>：{@link #setEnabled(false)} 供红队测试模拟 Baseline（无限流）配置。生产默认开启。
 *
 * @see CallerContext
 * @see RateLimitSyscallFilter
 */
public final class VfsRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(VfsRateLimiter.class);

    private static final class Holder {
        static final VfsRateLimiter INSTANCE = new VfsRateLimiter();
    }

    public static VfsRateLimiter instance() {
        return Holder.INSTANCE;
    }

    /** write/mount 重限流：每秒 20 次，突发 25。 */
    static final int WRITE_PERMITS_PER_SECOND = 20;
    static final int WRITE_BURST = 25;
    /** read 轻限流：每秒 200 次，突发 240。 */
    static final int READ_PERMITS_PER_SECOND = 200;
    static final int READ_BURST = 240;
    private static final long REFILL_INTERVAL_MS = 100;

    /** 全局开关 — 测试可关闭模拟 Baseline。生产默认开启。 */
    private volatile boolean enabled = true;

    private final ConcurrentHashMap<String, TokenBucket> agentWriteBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TokenBucket> tenantWriteBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TokenBucket> agentReadBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TokenBucket> tenantReadBuckets = new ConcurrentHashMap<>();

    private VfsRateLimiter() {
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        log.info("[VfsRateLimiter] enabled={}", enabled);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 检查 write/mount 配额；超限抛 SecurityException（已双写审计）。 */
    public void checkWrite(String path) {
        check("write", path, WRITE_PERMITS_PER_SECOND, WRITE_BURST,
                agentWriteBuckets, tenantWriteBuckets);
    }

    /** 检查 read 配额；超限抛 SecurityException（已双写审计）。 */
    public void checkRead(String path) {
        check("read", path, READ_PERMITS_PER_SECOND, READ_BURST,
                agentReadBuckets, tenantReadBuckets);
    }

    private void check(String opType, String path, int permitsPerSecond, int burst,
                       ConcurrentHashMap<String, TokenBucket> agentMap,
                       ConcurrentHashMap<String, TokenBucket> tenantMap) {
        if (!enabled) return; // Baseline 模式：无限流
        CallerContext ctx = CallerContext.current();
        if (ctx == null) return; // 内核守护进程豁免
        String agentId = ctx.agentId();
        if (isPrivileged(agentId)) return;

        boolean agentOk = bucketFor(agentMap, agentId, burst, permitsPerSecond).tryConsume();
        String tenantId = ctx.tenantId();
        boolean tenantOk = (tenantId == null || tenantId.isBlank())
                ? true : bucketFor(tenantMap, tenantId, burst, permitsPerSecond).tryConsume();

        if (!agentOk || !tenantOk) {
            String deniedBy = !agentOk ? "agent:" + agentId : "tenant:" + tenantId;
            String reason = "VFS " + opType + " rate limit exceeded (max=" + permitsPerSecond + "/s)";
            log.warn("[VfsRateLimiter] {} 被限流: path={}, caller={}, deniedBy={}",
                    opType, path, agentId, deniedBy);
            recordDenial(opType, path, agentId, tenantId, deniedBy, reason);
            throw new SecurityException(reason + " for agent '" + agentId + "' (path=" + path + ")");
        }
    }

    private boolean isPrivileged(String agentId) {
        return agentId != null
                && (agentId.startsWith("sys_") || "root_cli".equals(agentId) || "kernel".equals(agentId));
    }

    private TokenBucket bucketFor(ConcurrentHashMap<String, TokenBucket> map, String key,
                                  int burst, int permitsPerSecond) {
        return map.computeIfAbsent(key, k -> new TokenBucket(burst, permitsPerSecond));
    }

    private void recordDenial(String opType, String path, String agentId, String tenantId,
                              String deniedBy, String reason) {
        String target = "vfs_" + opType + ":" + path + " " + deniedBy;
        // 双写审计：UnifiedAuditLog（跨层 traceId 串联）+ SemanticEtw（三层 ETW 补齐 rate-limit 腿）
        UnifiedAuditLog.append(UnifiedAuditLog.LAYER_RATELIMIT, "RATE_LIMITED",
                agentId, target, reason);
        try {
            SemanticEtw.getInstance().logAuditEvent(agentId,
                    tenantId != null ? tenantId : "null",
                    "vfs_" + opType, "MEDIUM", "vfs_rate_limit",
                    path, reason);
        } catch (Throwable t) {
            log.debug("[VfsRateLimiter] ETW 记录失败: {}", t.getMessage());
        }
    }

    /** 测试用：重置所有令牌桶，避免跨用例污染。 */
    public void resetForTest() {
        agentWriteBuckets.clear();
        tenantWriteBuckets.clear();
        agentReadBuckets.clear();
        tenantReadBuckets.clear();
    }

    /** 令牌桶（与 RateLimitSyscallFilter.TokenBucket 同构）。 */
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
