package com.ouisani.aios.user.apps.omnifactory;

/**
 * 工作流边 — 连接源节点的输出端口到目标节点的输入端口。
 * 借鉴 Langflow 的 Edge 路由，支持端口级别的精确数据路由。
 */
public class WorkflowEdge {
    private final String sourceNodeId;
    private final String sourcePortName;
    private final String targetNodeId;
    private final String targetPortName;

    public WorkflowEdge(String sourceNodeId, String sourcePortName,
                        String targetNodeId, String targetPortName) {
        this.sourceNodeId = sourceNodeId;
        this.sourcePortName = sourcePortName;
        this.targetNodeId = targetNodeId;
        this.targetPortName = targetPortName;
    }

    public String sourceNodeId() { return sourceNodeId; }
    public String sourcePortName() { return sourcePortName; }
    public String targetNodeId() { return targetNodeId; }
    public String targetPortName() { return targetPortName; }

    @Override
    public String toString() {
        return sourceNodeId + "." + sourcePortName + " → " + targetNodeId + "." + targetPortName;
    }
}
