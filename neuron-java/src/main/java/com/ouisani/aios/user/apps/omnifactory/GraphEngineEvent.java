package com.ouisani.aios.user.apps.omnifactory;

import java.time.Instant;
import java.util.Map;

/**
 * DAG 引擎事件体系 — Dify 风格的 GraphEngineEvent。
 * <p>
 * 事件是引擎与外部系统（前端大屏、持久化层、可观测性层）通信的唯一通道。
 * 所有事件不可变（immutable），确保并发安全。
 * <p>
 * 事件层次：
 * <pre>
 *   GraphEngineEvent (抽象基类)
 *   ├── GraphRunStartedEvent       工作流开始
 *   ├── GraphRunSucceededEvent     工作流成功
 *   ├── GraphRunFailedEvent        工作流失败
 *   ├── GraphRunAbortedEvent       工作流中止
 *   ├── GraphRunPausedEvent        工作流暂停
 *   │
 *   └── GraphNodeEvent (抽象基类 — 节点相关事件)
 *       ├── NodeRunStartedEvent    节点开始执行
 *       ├── NodeRunSucceededEvent  节点执行成功
 *       ├── NodeRunFailedEvent     节点执行失败
 *       ├── NodeRunSkippedEvent    节点被跳过（上游失败级联）
 *       ├── NodeRunRetryEvent      节点重试
 *       │
 *       ├── NodeRunIterationStartedEvent   迭代开始
 *       ├── NodeRunIterationNextEvent      迭代下一步
 *       ├── NodeRunIterationSucceededEvent 迭代成功
 *       └── NodeRunIterationFailedEvent    迭代失败
 * </pre>
 *
 * @see GraphEngineLayer
 * @see WorkflowEngine
 */
public sealed class GraphEngineEvent permits
        GraphEngineEvent.GraphRunStartedEvent,
        GraphEngineEvent.GraphRunSucceededEvent,
        GraphEngineEvent.GraphRunFailedEvent,
        GraphEngineEvent.GraphRunAbortedEvent,
        GraphEngineEvent.GraphRunPausedEvent,
        GraphEngineEvent.GraphNodeEvent {

    private final String workflowId;
    private final long timestamp;

    protected GraphEngineEvent(String workflowId) {
        this.workflowId = workflowId;
        this.timestamp = Instant.now().toEpochMilli();
    }

    public String workflowId() { return workflowId; }
    public long timestamp() { return timestamp; }

    // ════════════════════════════════════════════════════════════════
    //  图级别事件 (Graph-level Events)
    // ════════════════════════════════════════════════════════════════

    /** 工作流开始执行 */
    public static final class GraphRunStartedEvent extends GraphEngineEvent {
        private final int totalNodes;

        public GraphRunStartedEvent(String workflowId, int totalNodes) {
            super(workflowId);
            this.totalNodes = totalNodes;
        }

        public int totalNodes() { return totalNodes; }
    }

    /** 工作流执行成功 */
    public static final class GraphRunSucceededEvent extends GraphEngineEvent {
        private final Map<String, Map<String, Object>> outputs;

        public GraphRunSucceededEvent(String workflowId, Map<String, Map<String, Object>> outputs) {
            super(workflowId);
            this.outputs = outputs;
        }

        public Map<String, Map<String, Object>> outputs() { return outputs; }
    }

    /** 工作流执行失败 */
    public static final class GraphRunFailedEvent extends GraphEngineEvent {
        private final String error;
        private final int failedCount;

        public GraphRunFailedEvent(String workflowId, String error, int failedCount) {
            super(workflowId);
            this.error = error;
            this.failedCount = failedCount;
        }

        public String error() { return error; }
        public int failedCount() { return failedCount; }
    }

    /** 工作流被中止 */
    public static final class GraphRunAbortedEvent extends GraphEngineEvent {
        private final String reason;

        public GraphRunAbortedEvent(String workflowId, String reason) {
            super(workflowId);
            this.reason = reason;
        }

        public String reason() { return reason; }
    }

    /** 工作流暂停 */
    public static final class GraphRunPausedEvent extends GraphEngineEvent {
        private final String reason;

        public GraphRunPausedEvent(String workflowId, String reason) {
            super(workflowId);
            this.reason = reason;
        }

        public String reason() { return reason; }
    }

    // ════════════════════════════════════════════════════════════════
    //  节点级别事件 (Node-level Events)
    // ════════════════════════════════════════════════════════════════

    /**
     * 节点事件抽象基类。
     * 所有节点级事件共享 nodeId、executor、nodeType 等公共字段。
     */
    public static sealed class GraphNodeEvent extends GraphEngineEvent permits
            GraphNodeEvent.NodeRunStartedEvent,
            GraphNodeEvent.NodeRunSucceededEvent,
            GraphNodeEvent.NodeRunFailedEvent,
            GraphNodeEvent.NodeRunSkippedEvent,
            GraphNodeEvent.NodeRunRetryEvent,
            GraphNodeEvent.NodeRunIterationStartedEvent,
            GraphNodeEvent.NodeRunIterationNextEvent,
            GraphNodeEvent.NodeRunIterationSucceededEvent,
            GraphNodeEvent.NodeRunIterationFailedEvent {

        private final String nodeId;
        private final String executor;
        private final String nodeType;   // "task" | "iteration" | "loop"
        private final String parentIterationId; // 如果在迭代内，记录迭代节点 ID

        protected GraphNodeEvent(String workflowId, String nodeId, String executor,
                                 String nodeType, String parentIterationId) {
            super(workflowId);
            this.nodeId = nodeId;
            this.executor = executor;
            this.nodeType = nodeType;
            this.parentIterationId = parentIterationId;
        }

        public String nodeId() { return nodeId; }
        public String executor() { return executor; }
        public String nodeType() { return nodeType; }
        public String parentIterationId() { return parentIterationId; }

        // ── 普通节点事件 ──

        /** 节点开始执行 */
        public static final class NodeRunStartedEvent extends GraphNodeEvent {
            private final String role;

            public NodeRunStartedEvent(String workflowId, String nodeId, String executor,
                                       String role, String parentIterationId) {
                super(workflowId, nodeId, executor, "task", parentIterationId);
                this.role = role;
            }

            public String role() { return role; }
        }

        /** 节点执行成功 */
        public static final class NodeRunSucceededEvent extends GraphNodeEvent {
            private final Map<String, Object> outputs;
            private final long durationMs;

            public NodeRunSucceededEvent(String workflowId, String nodeId, String executor,
                                         Map<String, Object> outputs, long durationMs,
                                         String parentIterationId) {
                super(workflowId, nodeId, executor, "task", parentIterationId);
                this.outputs = outputs;
                this.durationMs = durationMs;
            }

            public Map<String, Object> outputs() { return outputs; }
            public long durationMs() { return durationMs; }
        }

        /** 节点执行失败 */
        public static final class NodeRunFailedEvent extends GraphNodeEvent {
            private final String error;
            private final long durationMs;

            public NodeRunFailedEvent(String workflowId, String nodeId, String executor,
                                      String error, long durationMs, String parentIterationId) {
                super(workflowId, nodeId, executor, "task", parentIterationId);
                this.error = error;
                this.durationMs = durationMs;
            }

            public String error() { return error; }
            public long durationMs() { return durationMs; }
        }

        /** 节点被跳过（上游失败级联） */
        public static final class NodeRunSkippedEvent extends GraphNodeEvent {
            private final String reason;

            public NodeRunSkippedEvent(String workflowId, String nodeId, String executor,
                                       String reason, String parentIterationId) {
                super(workflowId, nodeId, executor, "task", parentIterationId);
                this.reason = reason;
            }

            public String reason() { return reason; }
        }

        /** 节点重试 */
        public static final class NodeRunRetryEvent extends GraphNodeEvent {
            private final int retryIndex;
            private final String error;

            public NodeRunRetryEvent(String workflowId, String nodeId, String executor,
                                     int retryIndex, String error, String parentIterationId) {
                super(workflowId, nodeId, executor, "task", parentIterationId);
                this.retryIndex = retryIndex;
                this.error = error;
            }

            public int retryIndex() { return retryIndex; }
            public String error() { return error; }
        }

        // ── 迭代容器事件 ──

        /** 迭代开始 */
        public static final class NodeRunIterationStartedEvent extends GraphNodeEvent {
            private final int totalIterations;

            public NodeRunIterationStartedEvent(String workflowId, String nodeId,
                                                int totalIterations) {
                super(workflowId, nodeId, "omni", "iteration", null);
                this.totalIterations = totalIterations;
            }

            public int totalIterations() { return totalIterations; }
        }

        /** 迭代下一步 */
        public static final class NodeRunIterationNextEvent extends GraphNodeEvent {
            private final int currentIndex;
            private final Map<String, Object> previousOutput;

            public NodeRunIterationNextEvent(String workflowId, String nodeId,
                                             int currentIndex, Map<String, Object> previousOutput) {
                super(workflowId, nodeId, "omni", "iteration", null);
                this.currentIndex = currentIndex;
                this.previousOutput = previousOutput;
            }

            public int currentIndex() { return currentIndex; }
            public Map<String, Object> previousOutput() { return previousOutput; }
        }

        /** 迭代成功 */
        public static final class NodeRunIterationSucceededEvent extends GraphNodeEvent {
            private final Map<String, Object> aggregatedOutputs;

            public NodeRunIterationSucceededEvent(String workflowId, String nodeId,
                                                  Map<String, Object> aggregatedOutputs) {
                super(workflowId, nodeId, "omni", "iteration", null);
                this.aggregatedOutputs = aggregatedOutputs;
            }

            public Map<String, Object> aggregatedOutputs() { return aggregatedOutputs; }
        }

        /** 迭代失败 */
        public static final class NodeRunIterationFailedEvent extends GraphNodeEvent {
            private final String error;
            private final int failedAtIndex;

            public NodeRunIterationFailedEvent(String workflowId, String nodeId,
                                               String error, int failedAtIndex) {
                super(workflowId, nodeId, "omni", "iteration", null);
                this.error = error;
                this.failedAtIndex = failedAtIndex;
            }

            public String error() { return error; }
            public int failedAtIndex() { return failedAtIndex; }
        }
    }
}
