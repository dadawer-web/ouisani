package com.ouisani.aios.user.apps.omnifactory;

import java.util.Map;

/**
 * 条件节点 — 不唤醒任何 Agent，直接在内存中秒级执行表达式评估。
 * <p>
 * 借鉴 n8n 的 IF 节点和 Switch 节点设计，将"确定性逻辑"还给 CPU，
 * 把"模糊推理"留给大模型。
 * <p>
 * <h3>工作流程</h3>
 * <pre>
 *   上游节点 (如 WebFetchTool) 返回 JSON
 *     → ConditionNode 在内存中评估表达式
 *       → true  → 走 A 分支 (publishTopic = "branch_a")
 *       → false → 走 B 分支 (publishTopic = "branch_b")
 * </pre>
 * <p>
 * <h3>与现有 ConditionEvaluator 的关系</h3>
 * <p>
 * WorkflowNode 已有 condition 字段，由 ConditionEvaluator.evaluate() 求值。
 * ConditionNode 是一个语义化的节点类型封装，明确表达"此节点不做 LLM 推理，
 * 仅做条件判断"的意图，并支持多分支路由（Switch 语义）。
 * <p>
 * OS 类比：相当于 Linux 内核的 if-else 路由表 — 纯 CPU 逻辑，不涉及 I/O。
 *
 * @see ConditionEvaluator
 * @see WorkflowNode
 */
public class ConditionNode {

    /** 条件分支定义 */
    public record Branch(
            String label,
            String condition,
            String publishTopic
    ) {}

    private final String nodeId;
    private final Branch defaultBranch;
    private final java.util.List<Branch> branches;

    /**
     * 创建 IF 条件节点 — 二分支 (true/false)。
     *
     * @param nodeId        节点 ID
     * @param condition     条件表达式 (如 "{{search_result.count}} > 0")
     * @param trueTopic     条件为 true 时发布的 topic
     * @param falseTopic    条件为 false 时发布的 topic
     */
    public ConditionNode(String nodeId, String condition, String trueTopic, String falseTopic) {
        this.nodeId = nodeId;
        this.branches = java.util.List.of(
                new Branch("true", condition, trueTopic),
                new Branch("false", null, falseTopic)
        );
        this.defaultBranch = new Branch("default", null, falseTopic);
    }

    /**
     * 创建 SWITCH 条件节点 — 多分支。
     *
     * @param nodeId     节点 ID
     * @param branches   分支列表（按顺序匹配，第一个匹配的分支胜出）
     * @param defaultTopic 默认分支 topic（所有分支都不匹配时）
     */
    public ConditionNode(String nodeId, java.util.List<Branch> branches, String defaultTopic) {
        this.nodeId = nodeId;
        this.branches = java.util.List.copyOf(branches);
        this.defaultBranch = new Branch("default", null, defaultTopic);
    }

    /**
     * 评估条件，返回匹配的分支。
     * <p>
     * 此方法不唤醒任何 Agent，纯 CPU 内存操作，微秒级完成。
     *
     * @param nodeMap 工作流节点映射
     * @param context 工作流上下文
     * @return 匹配的分支（含 publishTopic），无匹配则返回 default 分支
     */
    public Branch evaluate(Map<String, WorkflowNode> nodeMap, WorkflowContext context) {
        for (Branch branch : branches) {
            if (branch.condition() == null) {
                // null condition = 默认匹配（用于 IF 的 false 分支）
                continue;
            }

            try {
                boolean result = ConditionEvaluator.evaluate(
                        branch.condition(), nodeMap, context
                );
                if (result) {
                    return branch;
                }
            } catch (Exception e) {
                // 表达式评估失败，继续尝试下一个分支
            }
        }

        // 检查是否有 null condition 的分支（IF 的 false 分支）
        for (Branch branch : branches) {
            if (branch.condition() == null) {
                return branch;
            }
        }

        return defaultBranch;
    }

    /**
     * 获取节点 ID。
     */
    public String nodeId() {
        return nodeId;
    }

    /**
     * 获取所有分支。
     */
    public java.util.List<Branch> branches() {
        return branches;
    }

    /**
     * 获取默认分支。
     */
    public Branch defaultBranch() {
        return defaultBranch;
    }

    /**
     * 构建 WorkflowNode — 将 ConditionNode 转换为 DAG 引擎可识别的节点。
     * <p>
     * executor 设为 "condition"，WorkflowEngine 可据此路由到纯 CPU 评估逻辑，
     * 不创建 Agent 进程。
     */
    public WorkflowNode toWorkflowNode() {
        WorkflowNode node = new WorkflowNode(nodeId, "condition", null, Map.of(), null, null, "condition");
        return node;
    }
}
