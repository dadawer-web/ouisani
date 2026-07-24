package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.hibernation.AgentSnapshot;
import com.ouisani.aios.core.hibernation.TaskQueueSnapshotProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Omnifactory 任务队列快照提供者 — 把活跃工作流节点转为 {@link AgentSnapshot.TaskState}。
 * <p>
 * 通过 {@link WorkflowEngine#snapshotActiveNodeMaps()} 枚举所有活跃工作流的节点,
 * 将 PENDING / RUNNING / SUSPENDED 状态(未终结)的节点映射为 TaskState
 * (taskId=instanceId, status=name, payload=role, priority=0)。
 * <p>
 * 取数失败时降级返回空列表,不阻断 hibernate。
 */
public class OmnifactoryTaskQueueProvider implements TaskQueueSnapshotProvider {

    @Override
    public List<AgentSnapshot.TaskState> capture() {
        List<AgentSnapshot.TaskState> tasks = new ArrayList<>();
        try {
            Map<String, Map<String, WorkflowNode>> activeNodeMaps =
                    WorkflowEngine.getInstance().snapshotActiveNodeMaps();
            for (Map<String, WorkflowNode> nodeMap : activeNodeMaps.values()) {
                for (WorkflowNode node : nodeMap.values()) {
                    WorkflowNode.Status st = node.getStatus();
                    if (st == WorkflowNode.Status.PENDING
                            || st == WorkflowNode.Status.RUNNING
                            || st == WorkflowNode.Status.SUSPENDED) {
                        tasks.add(new AgentSnapshot.TaskState(
                                node.instanceId(),
                                st.name(),
                                node.role() != null ? node.role() : "",
                                0
                        ));
                    }
                }
            }
        } catch (Exception e) {
            // 静默降级:不阻断 hibernate
        }
        return tasks;
    }
}
