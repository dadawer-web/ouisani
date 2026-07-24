package com.ouisani.aios.core.snapshot;

import java.util.Map;

/**
 * Boulder 检查点分片 — 平铺 {@code BoulderCheckpoint} 关键字段。
 * <p>
 * <b>为何平铺而非内嵌:</b>{@code BoulderCheckpoint} 位于 core/state,但其
 * {@code status} 字段类型 {@code WorkflowNode.Status} 来自 user.apps.omnifactory,
 * 因此 BoulderCheckpoint 自身已依赖 user 态。core/snapshot 若内嵌它会传染 user 依赖,
 * 违反边界。此处平铺为 {@code String status},由 {@code BoulderSectionCapturer}
 * 负责 BoulderCheckpoint ↔ BoulderSection 的双向转换。
 *
 * @param workflowId      工作流 ID
 * @param nodeId          节点 ID
 * @param status          节点状态名称(SUCCESS/SUSPENDED/FAILED/...)
 * @param outputSnapshot  节点输出快照(变量键 → 值)
 * @param carryoverSnapshot 携带状态快照(可选,Phase 6 起填充)
 * @param errorMessage    错误信息(失败时)
 * @param durationMs      节点执行耗时
 * @param retryCount      重试次数
 * @param environmentSnapshotId 关联的 EnvironmentSnapshot ID(可选,Phase 6 起填充)
 */
public record BoulderSection(
        String workflowId,
        String nodeId,
        String status,
        Map<String, Object> outputSnapshot,
        Map<String, Object> carryoverSnapshot,
        String errorMessage,
        long durationMs,
        int retryCount,
        String environmentSnapshotId
) implements SnapshotSection {

    @Override
    public String sectionType() {
        return "Boulder";
    }
}
