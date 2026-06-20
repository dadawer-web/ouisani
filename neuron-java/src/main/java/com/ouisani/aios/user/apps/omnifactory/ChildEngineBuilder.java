package com.ouisani.aios.user.apps.omnifactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 子引擎构建器 — Dify 风格的 ChildEngineBuilder。
 * <p>
 * 为迭代（Iteration）和循环节点创建子引擎，支持 DAG 内的嵌套执行。
 * 子引擎拥有独立的运行时状态，但共享父引擎的 {@link WorkflowContext}（内存总线）。
 * <p>
 * 使用场景：
 * <ul>
 *   <li><b>迭代节点</b>：对列表中的每个元素执行相同的子工作流（如：对 10 个 URL 逐一爬取）</li>
 *   <li><b>循环节点</b>：重复执行子工作流直到满足退出条件（如：翻页爬取直到无更多数据）</li>
 * </ul>
 * <p>
 * 设计参考 Dify 的 {@code _WorkflowChildEngineBuilder}：
 * <ul>
 *   <li>子引擎共享父引擎的 VariablePool（内存总线）</li>
 *   <li>子引擎只继承安全相关的 Layer（如 ExecutionLimitsLayer），不继承持久化层</li>
 *   <li>子引擎通过事件向父引擎汇报执行进度</li>
 * </ul>
 *
 * @see WorkflowEngine
 * @see WorkflowContext
 * @see GraphEngineEvent
 */
public class ChildEngineBuilder {

    private static final Logger log = LoggerFactory.getLogger(ChildEngineBuilder.class);

    private final String parentWorkflowId;
    private final WorkflowContext parentContext;
    private final List<GraphEngineLayer> parentLayers;

    public ChildEngineBuilder(String parentWorkflowId, WorkflowContext parentContext,
                              List<GraphEngineLayer> parentLayers) {
        this.parentWorkflowId = parentWorkflowId;
        this.parentContext = parentContext;
        this.parentLayers = parentLayers;
    }

    /**
     * 构建一个用于迭代执行的子引擎。
     * <p>
     * 子引擎会为迭代列表中的每个元素创建一个独立的执行轮次，
     * 每轮执行完毕后将输出写入内存总线，供下一轮或下游节点读取。
     *
     * @param iterationNodeId  迭代节点 ID
     * @param iterationItems   迭代元素列表
     * @param iterationNodes   每轮迭代要执行的子工作流节点模板
     * @param enabledSkills    启用的技能列表
     * @param enabledRoles     启用的角色列表
     * @return 迭代执行结果
     */
    public IterationResult buildIterationEngine(
            String iterationNodeId,
            List<Object> iterationItems,
            List<WorkflowNode> iterationNodes,
            List<String> enabledSkills,
            List<String> enabledRoles) {

        log.info("[ChildEngine] Building iteration engine for node '{}': {} items",
                iterationNodeId, iterationItems.size());

        List<Map<String, Object>> iterationOutputs = new ArrayList<>();
        Map<String, Object> aggregatedOutputs = new HashMap<>();
        int failedAtIndex = -1;

        for (int i = 0; i < iterationItems.size(); i++) {
            Object item = iterationItems.get(i);

            // 发出迭代下一步事件
            Map<String, Object> prevOutput = i > 0 ? iterationOutputs.get(i - 1) : Map.of();
            emitEvent(new GraphEngineEvent.GraphNodeEvent.NodeRunIterationNextEvent(
                    parentWorkflowId, iterationNodeId, i, prevOutput));

            log.info("[ChildEngine] Iteration {}/{}: processing item {}", i + 1, iterationItems.size(), item);

            try {
                // 为当前迭代轮次创建节点副本（替换 userParams 中的迭代变量）
                List<WorkflowNode> roundNodes = createIterationRoundNodes(iterationNodes, i, item);

                // 创建子引擎的 WorkflowContext（共享父引擎的内存总线）
                // 子引擎的输出通过 parentContext 传递给下游
                String childWorkflowId = parentWorkflowId + "_iter_" + iterationNodeId + "_" + i;

                // 执行子 DAG
                WorkflowEngine.getInstance().executeDagWithContext(
                        roundNodes, childWorkflowId, parentContext);

                // 收集本轮输出
                Map<String, Object> roundOutput = new HashMap<>();
                for (WorkflowNode node : roundNodes) {
                    if (node.getStatus() == WorkflowNode.Status.SUCCESS) {
                        roundOutput.putAll(node.getOutputData());
                    }
                }
                iterationOutputs.add(roundOutput);

                // 将本轮输出写入父上下文，供下游节点读取
                parentContext.commitNodeOutput(iterationNodeId + "_round_" + i, roundOutput);

            } catch (Exception e) {
                failedAtIndex = i;
                log.error("[ChildEngine] 迭代 {}/{} 失败: {}", i + 1, iterationItems.size(), e.getMessage());

                // 发出迭代失败事件
                emitEvent(new GraphEngineEvent.GraphNodeEvent.NodeRunIterationFailedEvent(
                        parentWorkflowId, iterationNodeId, e.getMessage(), failedAtIndex));
                break;
            }
        }

        // 聚合所有轮次的输出
        aggregatedOutputs.put("iteration_count", iterationItems.size());
        aggregatedOutputs.put("iteration_outputs", iterationOutputs);
        aggregatedOutputs.put("failed_at_index", failedAtIndex);

        if (failedAtIndex == -1) {
            // 全部成功
            emitEvent(new GraphEngineEvent.GraphNodeEvent.NodeRunIterationSucceededEvent(
                    parentWorkflowId, iterationNodeId, aggregatedOutputs));
        }

        return new IterationResult(failedAtIndex == -1, iterationOutputs, aggregatedOutputs, failedAtIndex);
    }

    /**
     * 为迭代轮次创建节点副本，注入当前迭代的索引和元素值。
     */
    private List<WorkflowNode> createIterationRoundNodes(
            List<WorkflowNode> templateNodes, int index, Object item) {

        List<WorkflowNode> roundNodes = new ArrayList<>();
        for (WorkflowNode template : templateNodes) {
            // 创建新节点，替换 instanceId 和 userParams
            Map<String, String> newParams = new HashMap<>(template.userParams());
            newParams.put("iteration_index", String.valueOf(index));
            newParams.put("iteration_item", item != null ? item.toString() : "");

            // 如果 userParams 中有 {{iteration.item}} 引用，WorkflowContext.resolveValue 会处理
            WorkflowNode roundNode = new WorkflowNode(
                    template.instanceId() + "_round_" + index,
                    template.role(),
                    template.blueprintId(),
                    newParams,
                    template.subscribeTopic(),
                    template.publishTopic(),
                    template.executor()
            );

            // 复制上游依赖
            for (String dep : template.getUpstreamDependencies()) {
                roundNode.addDependency(dep + "_round_" + index);
            }

            roundNodes.add(roundNode);
        }
        return roundNodes;
    }

    /**
     * 向父引擎的 Layer 发送事件。
     */
    private void emitEvent(GraphEngineEvent event) {
        for (GraphEngineLayer layer : parentLayers) {
            try {
                layer.onEvent(event);
            } catch (Exception e) {
                log.warn("[ChildEngine] Layer '{}' 处理事件失败: {}",
                        layer.name(), e.getMessage());
            }
        }
    }

    /**
     * 迭代执行结果。
     */
    public static class IterationResult {
        private final boolean success;
        private final List<Map<String, Object>> iterationOutputs;
        private final Map<String, Object> aggregatedOutputs;
        private final int failedAtIndex;

        public IterationResult(boolean success, List<Map<String, Object>> iterationOutputs,
                               Map<String, Object> aggregatedOutputs, int failedAtIndex) {
            this.success = success;
            this.iterationOutputs = iterationOutputs;
            this.aggregatedOutputs = aggregatedOutputs;
            this.failedAtIndex = failedAtIndex;
        }

        public boolean isSuccess() { return success; }
        public List<Map<String, Object>> iterationOutputs() { return iterationOutputs; }
        public Map<String, Object> aggregatedOutputs() { return aggregatedOutputs; }
        public int failedAtIndex() { return failedAtIndex; }
    }
}
