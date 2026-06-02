package com.ouisani.aios.core.cgroup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

public class CgroupNode {

    private static final Logger log = LoggerFactory.getLogger(CgroupNode.class);

    private final String name;
    private final AtomicLong tokenQuota;
    private final AtomicLong tokenConsumed;
    private final CgroupNode parent;

    public CgroupNode(String name, long tokenQuota) {
        this(name, tokenQuota, null);
    }

    public CgroupNode(String name, long tokenQuota, CgroupNode parent) {
        this.name = name;
        this.tokenQuota = new AtomicLong(tokenQuota);
        this.tokenConsumed = new AtomicLong(0);
        this.parent = parent;
        log.info("[CgroupNode] Created: name={}, quota={}, parent={}", name, tokenQuota,
                parent != null ? parent.name : "none");
    }

    public boolean consumeTokens(long amount) {
        if (amount <= 0) return true;

        long newConsumed = tokenConsumed.addAndGet(amount);
        if (newConsumed > tokenQuota.get()) {
            tokenConsumed.addAndGet(-amount);
            log.warn("[CgroupNode] OOM at node '{}': consumed={} > quota={}", name, newConsumed, tokenQuota.get());
            throw new TokenOomException(name, tokenQuota.get(), newConsumed - amount, amount);
        }

        if (parent != null) {
            try {
                parent.consumeTokens(amount);
            } catch (TokenOomException e) {
                tokenConsumed.addAndGet(-amount);
                log.warn("[CgroupNode] OOM propagated from parent '{}': rolling back node '{}'",
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
