package com.ouisani.aios.user.apps.omnifactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活跃工作流注册表 — 从 WorkflowEngine 抽取的状态容器。
 * <p>
 * 管理 workflowId → nodeMap / context 的映射，供引擎核心和自愈器共享。
 * <p>
 * OS 类比：Linux 内核的 tasklist — 跟踪所有活跃"进程"的可寻址状态。
 */
class WorkflowRegistry {

    private final ConcurrentHashMap<String, Map<String, WorkflowNode>> activeNodeMaps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WorkflowContext> activeContexts = new ConcurrentHashMap<>();

    void registerActiveWorkflow(String workflowId, Map<String, WorkflowNode> nodeMap, WorkflowContext context) {
        activeNodeMaps.put(workflowId, nodeMap);
        activeContexts.put(workflowId, context);
    }

    void unregisterActiveWorkflow(String workflowId) {
        activeNodeMaps.remove(workflowId);
        activeContexts.remove(workflowId);
    }

    WorkflowContext getActiveWorkflowContext(String workflowId) {
        return activeContexts.get(workflowId);
    }

    WorkflowNode findNodeInActiveWorkflows(String nodeId) {
        for (Map<String, WorkflowNode> nodeMap : activeNodeMaps.values()) {
            WorkflowNode node = nodeMap.get(nodeId);
            if (node != null) return node;
        }
        return null;
    }

    Map<String, WorkflowNode> findNodeMapForWorkflow(String workflowId) {
        return activeNodeMaps.get(workflowId);
    }
}
