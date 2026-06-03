package com.ouisani.aios.core.cgroup;

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
