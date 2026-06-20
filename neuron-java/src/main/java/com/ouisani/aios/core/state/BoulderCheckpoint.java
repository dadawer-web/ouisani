package com.ouisani.aios.core.state;

import com.ouisani.aios.user.apps.omnifactory.WorkflowNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 巨石状态快照 (Boulder Checkpoint)。
 * <p>
 * 记录 DAG 中每一个节点的执行截面，保证系统重启后可精确恢复现场。
 * 对标 oh-my-openagent 的状态机设计：每个节点执行完毕后落盘快照，
 * 重启时通过检查点跳过已完成的节点，实现断点续传和异常自愈。
 * <p>
 * 持久化路径：~/.aios/boulders/{workflowId}_{nodeId}.json
 *
 * @see BoulderStateManager
 * @see com.ouisani.aios.user.apps.omnifactory.WorkflowEngine
 */
public class BoulderCheckpoint {

    /** 工作流 ID */
    private String workflowId;

    /** 节点 ID */
    private String nodeId;

    /** 节点执行状态 */
    private WorkflowNode.Status status;

    /** 当前节点执行后的内存输出快照 */
    private Map<String, Object> outputSnapshot = new ConcurrentHashMap<>();

    /** 重试次数，用于防御性降级 */
    private int retryCount = 0;

    /** 快照时间戳 */
    private long timestamp;

    /** 错误信息（失败时记录） */
    private String errorMessage;

    /** 节点执行耗时（毫秒） */
    private long durationMs;

    public BoulderCheckpoint() {
        this.timestamp = System.currentTimeMillis();
    }

    // ════════════════════════════════════════════════════════════════
    //  Getters & Setters
    // ════════════════════════════════════════════════════════════════

    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String workflowId) { this.workflowId = workflowId; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public WorkflowNode.Status getStatus() { return status; }
    public void setStatus(WorkflowNode.Status status) { this.status = status; }

    public Map<String, Object> getOutputSnapshot() { return outputSnapshot; }
    public void setOutputSnapshot(Map<String, Object> outputSnapshot) {
        this.outputSnapshot = outputSnapshot != null ? new ConcurrentHashMap<>(outputSnapshot) : new ConcurrentHashMap<>();
    }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    /** 递增重试计数 */
    public void incrementRetry() { this.retryCount++; }

    /** 是否已成功完成 */
    public boolean isCompleted() { return status == WorkflowNode.Status.SUCCESS; }

    /** 是否已失败且超过最大重试次数 */
    public boolean isExhausted(int maxRetries) { return retryCount >= maxRetries; }

    @Override
    public String toString() {
        return "BoulderCheckpoint{" + workflowId + "/" + nodeId
                + " status=" + status
                + " retries=" + retryCount
                + " outputs=" + (outputSnapshot != null ? outputSnapshot.size() : 0)
                + " ts=" + timestamp + "}";
    }
}
