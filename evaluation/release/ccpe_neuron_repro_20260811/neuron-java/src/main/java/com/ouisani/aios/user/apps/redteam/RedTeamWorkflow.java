package com.ouisani.aios.user.apps.redteam;

import com.ouisani.aios.core.network.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 红队质检工作流 — DAG 引擎层面的"创造→攻击→自愈"闭环。
 * <p>
 * 在 WorkflowEngine 中编排一个四节点 DAG 工作流，实现对抗生成网络
 * 思想的工程化：
 * <pre>
 *   节点A (创造)     → System_Architect + Python_Coder 开发应用
 *   节点B (攻击/质检) → Security_Auditor 红队渗透测试
 *   节点C (自愈闭环) → Security_Auditor 发送漏洞战报给 Python_Coder
 *   节点D (修复)     → Python_Coder 根据战报修复代码
 * </pre>
 * <p>
 * <h3>DAG 拓扑</h3>
 * <pre>
 *   [A: 创造] ──→ [B: 攻击] ──→ [C: 战报] ──→ [D: 修复]
 *                                        ↗
 *   [A: 创造] ────────────────────────────→
 * </pre>
 * <p>
 * OS 类比：相当于 Linux 的 CI/CD Pipeline — 代码提交后自动触发
 * 安全扫描，发现问题自动回退修复。
 *
 * @see SecurityAuditorAgent
 */
public class RedTeamWorkflow {

    private static final Logger log = LoggerFactory.getLogger(RedTeamWorkflow.class);

    /** 工作流节点定义 */
    public record WorkflowNodeDef(
            String nodeId,
            String role,
            String executor,
            String description,
            List<String> upstreamDependencies
    ) {}

    /** 工作流定义 */
    public record WorkflowDef(
            String workflowName,
            String targetAppUrl,
            String targetAppPort,
            String developerAgentId,
            String auditorAgentId,
            List<WorkflowNodeDef> nodes
    ) {}

    /**
     * 构建红队质检工作流定义。
     * <p>
     * 此定义可提交给 WorkflowEngine 执行，实现完整的
     * "创造→攻击→自愈"闭环。
     *
     * @param targetAppUrl      目标应用 URL (如 "172.18.0.5")
     * @param targetAppPort      目标应用端口 (如 "8080")
     * @param developerAgentId  开发 Agent ID
     * @param auditorAgentId     安全审计 Agent ID
     * @return 工作流定义
     */
    public static WorkflowDef buildRedTeamPipeline(
            String targetAppUrl,
            String targetAppPort,
            String developerAgentId,
            String auditorAgentId
    ) {
        List<WorkflowNodeDef> nodes = new ArrayList<>();

        // 节点 A: 创造 — 开发 Agent 部署应用
        nodes.add(new WorkflowNodeDef(
                "node_a_create",
                "developer",
                "omni",
                "开发 Agent 部署目标应用到 Docker 沙箱",
                List.of()
        ));

        // 节点 B: 攻击/质检 — Security_Auditor 红队渗透测试
        nodes.add(new WorkflowNodeDef(
                "node_b_attack",
                "security_auditor",
                "security_auditor",
                "Security_Auditor 对目标应用执行红队渗透测试 (Nmap/Nuclei/SQLmap/DalFox/Gobuster)",
                List.of("node_a_create")
        ));

        // 节点 C: 自愈闭环 — 发送漏洞战报
        nodes.add(new WorkflowNodeDef(
                "node_c_report",
                "security_auditor",
                "security_auditor",
                "Security_Auditor 将漏洞战报通过 AgentMailbox 发送给开发 Agent",
                List.of("node_b_attack")
        ));

        // 节点 D: 修复 — 开发 Agent 根据战报修复代码
        nodes.add(new WorkflowNodeDef(
                "node_d_fix",
                "developer",
                "omni",
                "开发 Agent 根据漏洞战报修复代码，重新提交安全审计",
                List.of("node_c_report")
        ));

        return new WorkflowDef(
                "red_team_quality_gate",
                targetAppUrl,
                targetAppPort,
                developerAgentId,
                auditorAgentId,
                nodes
        );
    }

    /**
     * 启动红队质检工作流。
     * <p>
     * 创建 Security_Auditor Agent，注入目标信息，启动事件循环。
     * <p>
     * 注意：此方法不直接执行 DAG（DAG 由 WorkflowEngine 编排），
     * 而是准备好 Security_Auditor Agent 并触发安全审计流程。
     *
     * @param targetAppUrl      目标应用 URL
     * @param targetAppPort      目标应用端口
     * @param developerAgentId  开发 Agent ID (接收战报)
     */
    public static void launchRedTeamAudit(
            String targetAppUrl,
            String targetAppPort,
            String developerAgentId
    ) {
        log.info("[RedTeamWorkflow] 启动红队质检工作流 — 目标: {}:{}", targetAppUrl, targetAppPort);
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  🔴 红队质检工作流启动                                          ║");
        System.out.printf("  ║  目标应用: %s:%s%n", targetAppUrl, targetAppPort);
        System.out.printf("  ║  开发 Agent: %s%n", developerAgentId);
        System.out.println("  ║  流程: 创造 → 攻击 → 战报 → 修复                                ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");

        // 创建 Security_Auditor Agent
        String auditorId = "security_auditor_" + System.currentTimeMillis();
        SecurityAuditorAgent auditor = new SecurityAuditorAgent(
                auditorId,
                targetAppUrl,
                targetAppPort,
                developerAgentId
        );

        // 注册到 TeamRegistry
        com.ouisani.aios.core.team.TeamRegistry.getInstance().register(auditor);

        // 广播工作流启动事件
        EventBus.instance().broadcast("sys.workflow.red_team_started",
                String.format("{\"workflow\":\"red_team_quality_gate\","
                                + "\"target\":\"%s:%s\","
                                + "\"auditor\":\"%s\","
                                + "\"developer\":\"%s\"}",
                        targetAppUrl, targetAppPort, auditorId, developerAgentId));

        // 启动安全审计 (在虚拟线程中执行)
        Thread.startVirtualThread(() -> {
            try {
                auditor.runSecurityAudit(targetAppUrl, targetAppPort);
            } catch (Exception e) {
                log.error("[RedTeamWorkflow] 红队审计失败: {}", e.getMessage(), e);
            }
        });

        log.info("[RedTeamWorkflow] Security_Auditor 已在虚拟线程中启动");
    }
}
