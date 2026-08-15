package com.ouisani.aios.core.task;

import java.time.Instant;
import java.util.Map;

/**
 * 死信队列条目 — 记录一个彻底失败（超过最大重试/自愈阈值）的任务。
 * <p>
 * 借鉴 n8n 的 Dead Letter Queue 设计和 RabbitMQ 的 DLX 机制：
 * 当消息/任务在主队列中重试超过最大阈值依然失败时，
 * 系统将其从主队列踢出，放入死信队列，防止阻塞其他任务。
 * <p>
 * OS 类比：相当于 Linux 内核的 OOM Killer 受害者列表 —
 * 被选中的进程被标记为"不可救药"，从运行队列中移除，
 * 但保留现场供事后分析。
 *
 * @see DeadLetterQueue
 */
public record DlqEntry(
        /** 条目 ID（唯一标识） */
        String entryId,
        /** 失败的节点 ID */
        String nodeId,
        /** 工作流 ID */
        String workflowId,
        /** 节点角色 */
        String role,
        /** 执行器类型 */
        String executor,
        /** 最终错误消息 */
        String errorMessage,
        /** 重试/自愈次数 */
        int retryCount,
        /** Core Dump 文件路径（供事后分析） */
        String dumpPath,
        /** 进入 DLQ 的时间戳 */
        long timestamp,
        /** 条目状态 */
        DlqStatus status,
        /** 附加元数据 */
        Map<String, Object> metadata
) {

    /** 死信条目状态 */
    public enum DlqStatus {
        /** 刚进入 DLQ，等待处理 */
        PENDING,
        /** 人类已查看，决定手动重试 */
        RETRY_REQUESTED,
        /** 人类已查看，决定放弃 */
        DISMISSED,
        /** 重试后成功，已移出 DLQ */
        RESOLVED
    }

    /**
     * 创建一个新的 PENDING 状态的 DLQ 条目。
     *
     * @param nodeId      失败的节点 ID
     * @param workflowId  工作流 ID
     * @param role        节点角色
     * @param executor    执行器类型
     * @param errorMessage 最终错误消息
     * @param retryCount  重试次数
     * @param dumpPath    Core Dump 路径
     * @return 新的 DlqEntry 实例
     */
    public static DlqEntry create(
            String nodeId, String workflowId, String role, String executor,
            String errorMessage, int retryCount, String dumpPath
    ) {
        String entryId = "dlq-" + nodeId + "-" + System.currentTimeMillis();
        return new DlqEntry(
                entryId, nodeId, workflowId, role, executor,
                errorMessage, retryCount, dumpPath,
                System.currentTimeMillis(),
                DlqStatus.PENDING,
                new java.util.concurrent.ConcurrentHashMap<>()
        );
    }

    /**
     * 添加元数据。
     */
    public DlqEntry withMetadata(String key, Object value) {
        Map<String, Object> newMeta = new java.util.HashMap<>(metadata);
        newMeta.put(key, value);
        return new DlqEntry(
                entryId, nodeId, workflowId, role, executor,
                errorMessage, retryCount, dumpPath,
                timestamp, status, newMeta
        );
    }

    /**
     * 更改状态。
     */
    public DlqEntry withStatus(DlqStatus newStatus) {
        return new DlqEntry(
                entryId, nodeId, workflowId, role, executor,
                errorMessage, retryCount, dumpPath,
                timestamp, newStatus, metadata
        );
    }

    /**
     * 转换为 JSON 字符串 — 供前端 UI 展示和 EventBus 广播。
     */
    public String toJson() {
        return String.format(
                "{\"entryId\":\"%s\",\"nodeId\":\"%s\",\"workflowId\":\"%s\","
                        + "\"role\":\"%s\",\"executor\":\"%s\","
                        + "\"errorMessage\":\"%s\",\"retryCount\":%d,"
                        + "\"dumpPath\":\"%s\",\"timestamp\":%d,"
                        + "\"status\":\"%s\",\"isoTime\":\"%s\"}",
                escape(entryId),
                escape(nodeId),
                escape(workflowId),
                escape(role != null ? role : ""),
                escape(executor != null ? executor : ""),
                escape(errorMessage != null ? errorMessage : "unknown"),
                retryCount,
                escape(dumpPath != null ? dumpPath : ""),
                timestamp,
                status.name(),
                Instant.ofEpochMilli(timestamp).toString()
        );
    }

    /**
     * 获取摘要信息 — 供日志输出。
     */
    public String summary() {
        return String.format("DlqEntry{node=%s, workflow=%s, retries=%d, error=%s}",
                nodeId, workflowId, retryCount,
                errorMessage != null && errorMessage.length() > 80
                        ? errorMessage.substring(0, 80) + "..." : errorMessage);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
