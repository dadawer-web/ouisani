package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.selection.SelectionPolicy;

import java.util.List;

/**
 * 工作流总清单 — 用户编排的完整工作流定义。
 * <p>
 * OS 类比：相当于 docker-compose.yml — 定义了一组需要协同运行的"容器"（节点实例），
 * 它们之间的依赖关系通过 EventBus 的 Pub-Sub topic 隐式表达，
 * 或通过 {@link WorkflowEdge} 显式声明端口级连线。
 * <p>
 * WorkflowManifest 是 WorkflowEngine 的输入：引擎遍历所有节点，
 * 从蓝图注册表中取出代码模板，注入用户参数和 topic 配置，
 * 然后为每个节点拉起一个隔离的 Agent 进程。
 * <p>
 * <b>强类型 I/O 契约</b>：edges 字段携带端口级连线信息，
 * 供 {@link GraphValidator} 在部署前验证类型兼容性。
 *
 * @param workflowName     工作流名称（如 "crypto_price_tracker"）
 * @param nodes            工作流中的节点实例列表
 * @param enabledSkills    按需装载的技能模块
 * @param enabledRoles     按需装载的角色列表
 * @param agentType        Agent 类型（"omni" / "moe" 等）
 * @param edges            端口级连线列表（可为空，向后兼容旧拓扑）
 * @param selectionPolicy  角色选择策略（listwise top-K 裁剪）；null = 未声明，{@link SelectionPolicy#NONE_POLICY} = 显式无策略
 */
public record WorkflowManifest(
        String workflowName,
        List<WorkflowNode> nodes,
        List<String> enabledSkills,
        List<String> enabledRoles,
        String agentType,
        List<WorkflowEdge> edges,
        SelectionPolicy selectionPolicy
) {
    /** 兼容旧调用：无 skills/roles/agentType/edges/selectionPolicy */
    public WorkflowManifest(String workflowName, List<WorkflowNode> nodes) {
        this(workflowName, nodes, List.of(), List.of(), "omni", List.of(), null);
    }

    public WorkflowManifest(String workflowName, List<WorkflowNode> nodes,
                            List<String> enabledSkills, List<String> enabledRoles) {
        this(workflowName, nodes, enabledSkills, enabledRoles, "omni", List.of(), null);
    }

    /** 兼容旧调用：无 edges/selectionPolicy（edges 默认空列表） */
    public WorkflowManifest(String workflowName, List<WorkflowNode> nodes,
                            List<String> enabledSkills, List<String> enabledRoles, String agentType) {
        this(workflowName, nodes, enabledSkills, enabledRoles, agentType, List.of(), null);
    }

    /**
     * 兼容旧调用：无 selectionPolicy（selectionPolicy = null 表示未声明）。
     * <p>
     * 现有 5 个调用点（GatewayJsonParser、InitDaemon、TopologyJsonParser、OmniMotherAgent、
     * OperatorAgent）全走此构造器，加 selectionPolicy 字段零改动。
     */
    public WorkflowManifest(String workflowName, List<WorkflowNode> nodes,
                            List<String> enabledSkills, List<String> enabledRoles,
                            String agentType, List<WorkflowEdge> edges) {
        this(workflowName, nodes, enabledSkills, enabledRoles, agentType, edges, null);
    }

    /** 规范化：null 字段转为空列表，避免 NPE。selectionPolicy 保持 null（=未声明） */
    public WorkflowManifest {
        if (nodes == null) nodes = List.of();
        if (enabledSkills == null) enabledSkills = List.of();
        if (enabledRoles == null) enabledRoles = List.of();
        if (agentType == null) agentType = "omni";
        if (edges == null) edges = List.of();
        // selectionPolicy 不规范化：null = 未声明，区别于 NONE_POLICY 哨兵
    }
}
