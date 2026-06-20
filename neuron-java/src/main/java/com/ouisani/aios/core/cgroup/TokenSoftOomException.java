package com.ouisani.aios.core.cgroup;

/**
 * Token 软 OOM 异常 — Agent 的 Token 消耗超过软限制时抛出。
 * <p>
 * 软限制是硬限制的一个比例（默认 80%），超过软限制不会立即 Kill Agent，
 * 而是触发 TokenZram 语义压缩，尝试回收 Token 空间。
 * 类比 Linux 的 memory.soft_limit_in_bytes：超过软限制时触发内存回收，
 * 而非直接 OOM Kill。
 *
 * @see CgroupNode#consumeTokens(long, String)
 * @see CgroupManager#preCheckAndReserve(String, long)
 */
public class TokenSoftOomException extends RuntimeException {

    private final String cgroupNode;
    private final long quota;
    private final long softLimit;
    private final long consumed;
    private final long requested;

    public TokenSoftOomException(String cgroupNode, long quota, long softLimit, long consumed, long requested) {
        super(String.format(
                "[CGROUP SOFT OOM] Node '%s' exceeded soft limit: consumed=%d + requested=%d > softLimit=%d (quota=%d). "
                        + "Consider compressing memory via TokenZram.",
                cgroupNode, consumed, requested, softLimit, quota));
        this.cgroupNode = cgroupNode;
        this.quota = quota;
        this.softLimit = softLimit;
        this.consumed = consumed;
        this.requested = requested;
    }

    public String cgroupNode() {
        return cgroupNode;
    }

    public long quota() {
        return quota;
    }

    public long softLimit() {
        return softLimit;
    }

    public long consumed() {
        return consumed;
    }

    public long requested() {
        return requested;
    }
}
