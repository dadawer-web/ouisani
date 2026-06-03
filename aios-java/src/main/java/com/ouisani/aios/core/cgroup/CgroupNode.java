package com.ouisani.aios.core.cgroup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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

    public boolean consumeTokens(long amount, String agentId) {
        if (amount <= 0) return true;

        long currentConsumed = tokenConsumed.get();
        long newConsumed = currentConsumed + amount;

        // Hard limit: fatal OOM
        if (newConsumed > tokenQuota.get()) {
            log.warn("[CgroupNode] HARD OOM at node '{}': {}+{} > quota={}",
                    name, currentConsumed, amount, tokenQuota.get());
            throw new TokenOomException(name, tokenQuota.get(), currentConsumed, amount);
        }

        // Soft limit: warning — trigger compression if not yet done for this agent
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

        // CAS commit
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
        return true;
    }

    public long estimateAndConsume(String text) {
        long tokens = Math.max(1, text.length() / 4);
        consumeTokens(tokens);
        return tokens;
    }

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
