package com.ouisani.aios.core.cgroup;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cgroup 节点 — AIOS 的层级式 Token 配额控制单元。
 * <p>
 * CgroupNode 构成一棵配额树：每个节点拥有独立的 Token 配额（硬限制）和软限制，
 * 子节点的消费会向上传播到父节点。当 Token 消耗超过硬限制时抛出
 * {@link TokenOomException}，超过软限制时抛出 {@link TokenSoftOomException}。
 *
 * <h3>OS 类比: Linux Cgroup memory controller</h3>
 * <ul>
 *   <li>memory.limit_in_bytes → tokenQuota（Token 硬限制）</li>
 *   <li>memory.soft_limit_in_bytes → softLimit（Token 软限制，默认为配额的 80%）</li>
 *   <li>cgroup 层级 → 父子节点关系（子节点消费向上传播）</li>
 * </ul>
 *
 * <h3>Token 消费与回滚</h3>
 * 当子节点消费 Token 时，先在本地记账，再向父节点传播。
 * 若父节点 OOM，则回滚本地记账（类似分布式事务的两阶段提交）。
 *
 * @see CgroupManager
 * @see TokenOomException
 * @see TokenSoftOomException
 */
public class CgroupNode {

    private static final Logger log = LoggerFactory.getLogger(CgroupNode.class);

    private final String name;
    private final AtomicLong tokenQuota;
    private final AtomicLong tokenConsumed;
    private final CgroupNode parent;
    private final double softLimitRatio;
    private final Set<String> compressionTriggered;

    public CgroupNode(String name, long tokenQuota) {
        this(name, tokenQuota, null, 0.8);
    }

    public CgroupNode(String name, long tokenQuota, CgroupNode parent) {
        this(name, tokenQuota, parent, 0.8);
    }

    public CgroupNode(String name, long tokenQuota, CgroupNode parent, double softLimitRatio) {
        this.name = name;
        this.tokenQuota = new AtomicLong(tokenQuota);
        this.tokenConsumed = new AtomicLong(0);
        this.parent = parent;
        this.softLimitRatio = softLimitRatio;
        this.compressionTriggered = ConcurrentHashMap.newKeySet();
        log.info("[CgroupNode] Created: name={}, quota={}, softLimit={} ({}%), parent={}",
                name, tokenQuota, softLimit(), (long)(softLimitRatio * 100),
                parent != null ? parent.name : "none");
    }

    public boolean consumeTokens(long amount) {
        return consumeTokens(amount, null);
    }

    /**
     * 消费 Token — 核心方法。
     * <p>
     * 检查流程：
     * <ol>
     *   <li>REALTIME 进程直接放行，绕过所有 cgroup 限制</li>
     *   <li>超过硬限制 → 抛出 TokenOomException</li>
     *   <li>超过软限制 → 抛出 TokenSoftOomException（首次触发，后续放行）</li>
     *   <li>向父节点传播消费；若父节点 OOM，回滚本地消费</li>
     * </ol>
     *
     * @param amount  要消费的 Token 数量
     * @param agentId Agent 标识（用于软限制触发追踪）
     * @return true 如果消费成功
     */
    public boolean consumeTokens(long amount, String agentId) {
        if (amount <= 0) return true;

        // 内核特权：REALTIME 进程绕过所有 cgroup 限制
        AgentTask currentTask = TaskScheduler.CURRENT_TASK.get();
        if (currentTask != null && currentTask.processPriority() == ProcessPriority.REALTIME) {
            log.info("[Kernel Privilege] REALTIME process '{}' requested LLM. Cgroup limits bypassed!",
                    agentId != null ? agentId : "pid=" + currentTask.pid());
            return true;
        }

        long currentConsumed = tokenConsumed.get();
        long newConsumed = currentConsumed + amount;

        // 硬限制：致命 OOM
        if (newConsumed > tokenQuota.get()) {
            log.warn("[CgroupNode] HARD OOM at node '{}': {}+{} > quota={}",
                    name, currentConsumed, amount, tokenQuota.get());
            throw new TokenOomException(name, tokenQuota.get(), currentConsumed, amount);
        }

        // 软限制：警告 — 若该 Agent 尚未触发过压缩，则触发
        long soft = softLimit();
        if (newConsumed > soft) {
            if (agentId != null && !compressionTriggered.contains(agentId)) {
                compressionTriggered.add(agentId);
                log.warn("[CgroupNode] SOFT OOM at node '{}': {}+{} > softLimit={} (quota={}). "
                        + "Agent '{}' should compress memory via TokenZram.",
                        name, currentConsumed, amount, soft, tokenQuota.get(), agentId);
                throw new TokenSoftOomException(name, tokenQuota.get(), soft, currentConsumed, amount);
            }
            log.warn("[CgroupNode] Soft limit exceeded at '{}' (agent '{}' already compressed, allowing)",
                    name, agentId);
        }

        // 提交消费到本地计数器
        tokenConsumed.addAndGet(amount);

        if (parent != null) {
            try {
                parent.consumeTokens(amount, agentId);
            } catch (TokenOomException e) {
                tokenConsumed.addAndGet(-amount);
                log.warn("[CgroupNode] OOM propagated from parent '{}': rolling back node '{}'",
                        parent.name, name);
                throw e;
            } catch (TokenSoftOomException e) {
                tokenConsumed.addAndGet(-amount);
                if (agentId != null) compressionTriggered.remove(agentId);
                log.warn("[CgroupNode] Soft OOM propagated from parent '{}': rolling back node '{}'",
                        parent.name, name);
                throw e;
            }
        }

        log.debug("[CgroupNode] Consumed {} tokens at '{}': {}/{}", amount, name, newConsumed, tokenQuota.get());

        SemanticEtw.getInstance().logEvent("CGROUP", "CONSUME",
                "cgroup=" + name + " amount=" + amount
                + " total=" + newConsumed + "/" + tokenQuota.get()
                + " agent=" + (agentId != null ? agentId : "?"));

        return true;
    }

    /** 估算文本的 Token 数量并消费（约 4 字符 = 1 Token） */
    public long estimateAndConsume(String text) {
        long tokens = Math.max(1, text.length() / 4);
        consumeTokens(tokens);
        return tokens;
    }

    /**
     * 退还 Token — 将已消费的 Token 归还到配额中。
     * 同时向父节点传播退还。
     *
     * @param amount 要退还的 Token 数量
     * @return 实际退还的数量
     */
    public long refundTokens(long amount) {
        if (amount <= 0) return 0;
        long oldConsumed = tokenConsumed.get();
        long newConsumed = Math.max(0, oldConsumed - amount);
        tokenConsumed.set(newConsumed);
        long refunded = oldConsumed - newConsumed;

        if (parent != null) {
            parent.refundTokens(refunded);
        }

        log.info("[CgroupNode] Refunded {} tokens at '{}': {}/{}", refunded, name, newConsumed, tokenQuota.get());
        return refunded;
    }

    public String name() {
        return name;
    }

    public long tokenQuota() {
        return tokenQuota.get();
    }

    public void setTokenQuota(long quota) {
        tokenQuota.set(quota);
    }

    public long tokenConsumed() {
        return tokenConsumed.get();
    }

    public long tokenRemaining() {
        return tokenQuota.get() - tokenConsumed.get();
    }

    public long softLimit() {
        return (long) (tokenQuota.get() * softLimitRatio);
    }

    public double softLimitRatio() {
        return softLimitRatio;
    }

    public void markCompressed(String agentId) {
        if (agentId != null) {
            compressionTriggered.add(agentId);
        }
    }

    public boolean hasTriggeredCompression(String agentId) {
        return agentId != null && compressionTriggered.contains(agentId);
    }

    public CgroupNode parent() {
        return parent;
    }

    public CgroupUsage usage() {
        return new CgroupUsage(name, tokenQuota.get(), tokenConsumed.get(),
                tokenQuota.get() - tokenConsumed.get(), parent != null ? parent.name : null);
    }

    @Override
    public String toString() {
        return "CgroupNode{name='%s', quota=%d, consumed=%d, remaining=%d, parent=%s}"
                .formatted(name, tokenQuota.get(), tokenConsumed.get(), tokenRemaining(),
                        parent != null ? parent.name : "none");
    }

    public record CgroupUsage(String name, long quota, long consumed, long remaining, String parentName) {
    }
}
