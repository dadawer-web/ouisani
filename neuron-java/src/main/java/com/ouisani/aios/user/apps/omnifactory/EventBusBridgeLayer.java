package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.network.EventBus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * EventBus 桥接层 — 将 DAG 引擎事件桥接到 AIOS EventBus，推送到前端大屏。
 * <p>
 * 这是 Dify PersistenceLayer + ObservabilityLayer 的 AIOS 简化版。
 * 每个引擎事件都会被转换为 EventBus 消息，发送到 {@code sys.dag.events} 频道，
 * 前端 WebSocket 订阅该频道即可实时展示 DAG 执行进度。
 *
 * @see GraphEngineLayer
 * @see GraphEngineEvent
 */
public class EventBusBridgeLayer extends GraphEngineLayer {

    private static final Logger log = LoggerFactory.getLogger(EventBusBridgeLayer.class);
    private static final String DAG_EVENT_CHANNEL = "sys.dag.events";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String name() {
        return "EventBusBridge";
    }

    @Override
    public void onGraphStart(WorkflowContext context) {
        broadcast("DAG_START", context.getWorkflowId(), Map.of(
                "workflowId", context.getWorkflowId()
        ));
    }

    @Override
    public void onEvent(GraphEngineEvent event) {
        switch (event) {
            case GraphEngineEvent.GraphRunStartedEvent e ->
                    broadcast("WORKFLOW_STARTED", e.workflowId(), Map.of(
                            "totalNodes", e.totalNodes()
                    ));
            case GraphEngineEvent.GraphRunSucceededEvent e ->
                    broadcast("WORKFLOW_SUCCEEDED", e.workflowId(), Map.of(
                            "nodeCount", e.outputs().size()
                    ));
            case GraphEngineEvent.GraphRunFailedEvent e ->
                    broadcast("WORKFLOW_FAILED", e.workflowId(), Map.of(
                            "error", e.error(),
                            "failedCount", e.failedCount()
                    ));
            case GraphEngineEvent.GraphRunAbortedEvent e ->
                    broadcast("WORKFLOW_ABORTED", e.workflowId(), Map.of(
                            "reason", e.reason()
                    ));
            case GraphEngineEvent.GraphNodeEvent.NodeRunStartedEvent e ->
                    broadcast("NODE_STARTED", e.workflowId(), Map.of(
                            "nodeId", e.nodeId(),
                            "executor", e.executor(),
                            "role", e.role()
                    ));
            case GraphEngineEvent.GraphNodeEvent.NodeRunSucceededEvent e ->
                    broadcast("NODE_SUCCEEDED", e.workflowId(), Map.of(
                            "nodeId", e.nodeId(),
                            "executor", e.executor(),
                            "durationMs", e.durationMs()
                    ));
            case GraphEngineEvent.GraphNodeEvent.NodeRunFailedEvent e ->
                    broadcast("NODE_FAILED", e.workflowId(), Map.of(
                            "nodeId", e.nodeId(),
                            "error", e.error(),
                            "durationMs", e.durationMs()
                    ));
            case GraphEngineEvent.GraphNodeEvent.NodeRunSkippedEvent e ->
                    broadcast("NODE_SKIPPED", e.workflowId(), Map.of(
                            "nodeId", e.nodeId(),
                            "reason", e.reason()
                    ));
            case GraphEngineEvent.GraphNodeEvent.NodeRunIterationStartedEvent e ->
                    broadcast("ITERATION_STARTED", e.workflowId(), Map.of(
                            "nodeId", e.nodeId(),
                            "totalIterations", e.totalIterations()
                    ));
            case GraphEngineEvent.GraphNodeEvent.NodeRunIterationNextEvent e ->
                    broadcast("ITERATION_NEXT", e.workflowId(), Map.of(
                            "nodeId", e.nodeId(),
                            "currentIndex", e.currentIndex()
                    ));
            case GraphEngineEvent.GraphNodeEvent.NodeRunIterationSucceededEvent e ->
                    broadcast("ITERATION_SUCCEEDED", e.workflowId(), Map.of(
                            "nodeId", e.nodeId()
                    ));
            case GraphEngineEvent.GraphNodeEvent.NodeRunIterationFailedEvent e ->
                    broadcast("ITERATION_FAILED", e.workflowId(), Map.of(
                            "nodeId", e.nodeId(),
                            "error", e.error(),
                            "failedAtIndex", e.failedAtIndex()
                    ));
            default -> {} // 忽略其他事件
        }
    }

    @Override
    public void onNodeRunEnd(WorkflowNode node, Exception error) {
        if (error != null) {
            log.warn("[EventBusBridge] Node '{}' ended with error: {}", node.instanceId(), error.getMessage());
        }
    }

    @Override
    public void onGraphEnd(Exception error) {
        if (error != null) {
            log.error("[EventBusBridge] Graph ended with error: {}", error.getMessage());
        }
    }

    /**
     * 将事件广播到 EventBus。
     */
    private void broadcast(String eventType, String workflowId, Map<String, Object> payload) {
        try {
            Map<String, Object> message = new HashMap<>(payload);
            message.put("type", "DAG_EVENT");
            message.put("eventType", eventType);
            message.put("workflowId", workflowId);
            message.put("timestamp", System.currentTimeMillis());
            // EventBus.broadcast 接受 String, String
            EventBus.instance().broadcast(DAG_EVENT_CHANNEL, JSON.writeValueAsString(message));
        } catch (Exception e) {
            log.debug("[EventBusBridge] 广播失败: {}", e.getMessage());
        }
    }
}
