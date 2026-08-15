package com.ouisani.aios.core.state;

import com.ouisani.aios.core.snapshot.BoulderSection;
import com.ouisani.aios.core.snapshot.SnapshotCapturer;
import com.ouisani.aios.core.snapshot.SnapshotSection;
import com.ouisani.aios.user.apps.omnifactory.WorkflowNode;

/**
 * DAG 节点级快照捕获器 — 包装 {@link BoulderStateManager} 为 {@link BoulderSection}。
 * <p>
 * <b>为何放在 core/state 而非 core/snapshot</b>:{@link BoulderCheckpoint#status}
 * 类型为 {@link WorkflowNode.Status}(user 态),status 字符串 ↔ enum 转换需要
 * import {@code user.apps.omnifactory.WorkflowNode}。{@code core/snapshot} 是契约包
 * (CONTRACT_RULES 禁止 import user),而 {@code core/state} 不是契约包
 * (BoulderCheckpoint 本身已 import WorkflowNode),故此处可安全持有 user 类型,
 * 把 user 依赖封在 core/state 边界内,不污染 core/snapshot 契约层。
 * <p>
 * capture 调 {@link BoulderStateManager#loadCheckpoint} 读既有检查点,平铺为
 * BoulderSection(status 转 name);无活跃检查点时返回 null(被 manager 跳过)。
 * restore 反向重建 BoulderCheckpoint 调 saveCheckpoint。
 */
public class BoulderSectionCapturer implements SnapshotCapturer {

    private final String workflowId;
    private final String nodeId;

    public BoulderSectionCapturer(String workflowId, String nodeId) {
        this.workflowId = workflowId;
        this.nodeId = nodeId;
    }

    @Override
    public String sectionType() {
        return "Boulder";
    }

    @Override
    public SnapshotSection capture() {
        return BoulderStateManager.loadCheckpoint(workflowId, nodeId)
                .map(BoulderSectionCapturer::toSection)
                .orElse(null);
    }

    @Override
    public void restore(SnapshotSection section) {
        if (!(section instanceof BoulderSection bs)) return;
        BoulderStateManager.saveCheckpoint(toCheckpoint(bs));
    }

    /** BoulderCheckpoint → BoulderSection 平铺(status enum → name 字符串)。 */
    static BoulderSection toSection(BoulderCheckpoint cp) {
        return new BoulderSection(
                cp.getWorkflowId(),
                cp.getNodeId(),
                cp.getStatus() != null ? cp.getStatus().name() : null,
                cp.getOutputSnapshot(),
                cp.getCarryoverSnapshot(),
                cp.getErrorMessage(),
                cp.getDurationMs(),
                cp.getRetryCount(),
                cp.getEnvironmentSnapshotId()
        );
    }

    /** BoulderSection → BoulderCheckpoint 反向重建(status name → enum)。 */
    static BoulderCheckpoint toCheckpoint(BoulderSection bs) {
        BoulderCheckpoint cp = new BoulderCheckpoint();
        cp.setWorkflowId(bs.workflowId());
        cp.setNodeId(bs.nodeId());
        if (bs.status() != null) {
            cp.setStatus(WorkflowNode.Status.valueOf(bs.status()));
        }
        cp.setOutputSnapshot(bs.outputSnapshot());
        cp.setCarryoverSnapshot(bs.carryoverSnapshot());
        cp.setEnvironmentSnapshotId(bs.environmentSnapshotId());
        cp.setErrorMessage(bs.errorMessage());
        cp.setDurationMs(bs.durationMs());
        cp.setRetryCount(bs.retryCount());
        return cp;
    }
}
