package com.ouisani.aios.core.cgroup;

/**
 * Token 硬 OOM 异常 — Agent 的 Token 消耗超过硬限制时抛出。
 * <p>
 * 类比 Linux 的 OOM (Out of Memory)：当进程的内存使用超过 cgroup 的
 * memory.limit_in_bytes 时，内核触发 OOM Killer。
 * AIOS 中，当 Agent 的 Token 消耗超过 CgroupNode 的 tokenQuota 时，
 * 抛出此异常，CgroupManager 随后执行 OOM Kill。
 *
 * @see CgroupNode#consumeTokens(long, String)
 * @see CgroupManager#oomKill(String)
 */
public class TokenOomException extends RuntimeException {

    private final String cgroupNode;
    private final long quota;
    private final long consumed;
    private final long requested;

    public TokenOomException(String cgroupNode, long quota, long consumed, long requested) {
        super(String.format("[CGROUP OOM] Node '%s' exceeded token quota: consumed=%d + requested=%d > quota=%d",
                cgroupNode, consumed, requested, quota));
        this.cgroupNode = cgroupNode;
        this.quota = quota;
        this.consumed = consumed;
        this.requested = requested;
    }

    public String cgroupNode() {
        return cgroupNode;
    }

    public long quota() {
        return quota;
    }

    public long consumed() {
        return consumed;
    }

    public long requested() {
        return requested;
    }
}
