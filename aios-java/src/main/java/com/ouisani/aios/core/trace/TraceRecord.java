package com.ouisani.aios.core.trace;

public record TraceRecord(
        String agentId,
        String eventType,
        String requestPayload,
        String responsePayload,
        long timestamp
) {
    public TraceRecord(String agentId, String eventType, String requestPayload, String responsePayload) {
        this(agentId, eventType, requestPayload, responsePayload, System.currentTimeMillis());
    }
}
