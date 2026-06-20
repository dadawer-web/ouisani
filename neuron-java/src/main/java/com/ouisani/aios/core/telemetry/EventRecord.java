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
 */
public record EventRecord(
        long timestamp,
        String component,
        String eventType,
        String payload
) {
    @Override
    public String toString() {
        return "[%s] [%s] %d | %s".formatted(component, eventType, timestamp, payload);
    }
}
