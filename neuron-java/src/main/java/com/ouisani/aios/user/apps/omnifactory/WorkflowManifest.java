package com.ouisani.aios.user.apps.omnifactory;

import java.util.List;

/**
 * 工作流总清单 — 用户编排的完整工作流定义。
 * <p>
 * OS 类比：相当于 docker-compose.yml — 定义了一组需要协同运行的"容器"（节点实例），
 * 它们之间的依赖关系通过 EventBus 的 Pub-Sub topic 隐式表达。
 * <p>
 * WorkflowManifest 是 WorkflowEngine 的输入：引擎遍历所有节点，
 * 从蓝图注册表中取出代码模板，注入用户参数和 topic 配置，
 * 然后为每个节点拉起一个隔离的 Agent 进程。
 *
 * @param workflowName 工作流名称（如 "crypto_price_tracker"）
 * @param nodes        工作流中的节点实例列表
 */
public record WorkflowManifest(
        String workflowName,
        List<WorkflowNode> nodes,
        List<String> enabledSkills,
        List<String> enabledRoles,
        String agentType
) {
    /** 兼容旧调用：无 skills/roles/agentType 时使用默认值 */
    public WorkflowManifest(String workflowName, List<WorkflowNode> nodes) {
        this(workflowName, nodes, List.of(), List.of(), "omni");
    }

    public WorkflowManifest(String workflowName, List<WorkflowNode> nodes,
                            List<String> enabledSkills, List<String> enabledRoles) {
        this(workflowName, nodes, enabledSkills, enabledRoles, "omni");
    }
}
