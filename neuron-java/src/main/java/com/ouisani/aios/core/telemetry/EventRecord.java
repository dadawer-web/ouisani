package com.ouisani.aios.core.telemetry;

/**
 * ETW 事件记录 — 单条遥测事件的不可变快照。
 * <p>
 * 零分配写入路径（在环形缓冲区中复用槽位）。
 * <p>
 * OS 类比: perf event record / ftrace 的一行输出。
 *
 * @param timestamp 时间戳
 * @param component 组件名（如 "LLM"、"VFS"、"SCHED"）
 * @param eventType 事件类型（如 "THINK"、"WRITE"、"SPAWN"）
 * @param payload   事件载荷
 * @param traceId   端到端追踪标识（由 {@link com.ouisani.aios.core.ipc.TraceContext} 注入；
 *                  null 表示无 turn 上下文）。用于把 cgroup/sandbox/permission 三层决策
 *                  串成统一审计链。
 */
public record EventRecord(
        long timestamp,
        String component,
        String eventType,
        String payload,
        String traceId
) {
    /**
     * 向后兼容构造 — 旧 4 参数调用方默认 traceId=null。
     * {@link SemanticEtw#logEvent} 已改为自动从 TraceContext 取 traceId 后走 5 参构造，
     * 此构造仅供其它直接 new EventRecord 的旧代码使用（零回归）。
     */
    public EventRecord(long timestamp, String component, String eventType, String payload) {
        this(timestamp, component, eventType, payload, null);
    }

    @Override
    public String toString() {
        return "[%s] [%s] %d | %s".formatted(component, eventType, timestamp, payload);
    }
}
