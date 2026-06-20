package com.ouisani.aios.core.trace;

/**
 * 追踪记录 — 一次方法调用的完整快照，用于 strace 录制/回放。
 * <p>
 * OS 类比: strace 输出的一行记录，包含进程 PID、系统调用名、参数和返回值。
 *
 * @param agentId         Agent 进程标识（类比 PID）
 * @param eventType       事件类型（类比 syscall 名称，如 "LlmProvider.think"）
 * @param requestPayload  请求参数序列化（类比 syscall 入参）
 * @param responsePayload 响应结果序列化（类比 syscall 返回值）
 * @param timestamp       时间戳
 */
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
