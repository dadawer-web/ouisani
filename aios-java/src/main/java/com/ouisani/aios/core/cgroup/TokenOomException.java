package com.ouisani.aios.core.cgroup;

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
