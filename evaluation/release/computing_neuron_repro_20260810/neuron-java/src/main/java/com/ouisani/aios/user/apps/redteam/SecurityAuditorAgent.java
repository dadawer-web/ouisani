package com.ouisani.aios.user.apps.redteam;

import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.security.redteam.SecurityModule;
import com.ouisani.aios.core.security.redteam.VulnerabilityReport;
import com.ouisani.aios.core.team.MailMessage;
import com.ouisani.aios.core.team.TeamRegistry;
import com.ouisani.aios.user.sdk.AbstractAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 安全审计官 — 第三大母体 (Security_Auditor / Red_Team_Agent)。
 * <p>
 * 在 AIOS 的三母体架构中，Security_Auditor 扮演"免疫与试炼"角色：
 * <pre>
 *   Claude Code 母体  → 生长 (代码生成)
 *   OpenClaw 母体     → 物理交互 (RPA)
 *   Security_Auditor  → 免疫与试炼 (红队渗透测试)  ← 本类
 * </pre>
 * <p>
 * <h3>核心职责</h3>
 * <ul>
 *   <li>对其他母体开发的代码进行全自动红队渗透测试</li>
 *   <li>使用 HackingTool 的 185+ 工具（Nmap/SQLmap/Nuclei/Nikto...）</li>
 *   <li>发现漏洞后通过 AgentMailbox 向开发 Agent 发送战报</li>
 *   <li>形成"创造→攻击→自愈"的闭环对抗生成网络</li>
 * </ul>
 * <p>
 * <h3>DAG 工作流角色</h3>
 * <pre>
 *   节点A (创造)     → System_Architect + Python_Coder 开发应用
 *   节点B (攻击/质检) → Security_Auditor 红队渗透测试      ← 本类
 *   节点C (自愈闭环) → Security_Auditor 发送漏洞战报
 *   节点D (修复)     → Python_Coder 根据战报修复代码
 * </pre>
 * <p>
 * OS 类比：相当于 Linux 内核的 SELinux Audit — 独立于业务逻辑，
 * 持续审计系统安全性，发现威胁即报警。
 *
 * @see SecurityModule
 * @see VulnerabilityReport
 * @see SecurityScanApprovalHook
 */
public class SecurityAuditorAgent extends AbstractAgent {

    private static final Logger log = LoggerFactory.getLogger(SecurityAuditorAgent.class);

    /** 安全审计官的专属 PERSONA */
    private static final String PERSONA = """
            你是 Security_Auditor，AIOS 内核的第三大母体——安全审计官。
            
            你的职责是对其他 Agent 开发的代码进行红队渗透测试，发现安全漏洞。
            
            你拥有 HackingTool 的 185+ 渗透测试工具：
            - 信息收集: nmap, masscan, rustscan, theharvester, amass, subfinder, httpx
            - Web 攻击: nuclei, nikto, gobuster, ffuf, feroxbuster, wafw00f, katana
            - SQL 注入: sqlmap, nosqlmap
            - XSS 攻击: dalfox, xssstrike
            - 云安全: trivy, prowler
            - 活动目录: netexec, bloodhound
            - 取证: binwalk, volatility
            
            工作原则：
            1. 只扫描内网目标 (10.x/172.16-31.x/192.168.x)，绝不扫描公网
            2. 每次扫描都需要人类审批 (God Hand 机制)
            3. 发现漏洞后，生成结构化战报，通过 AgentMailbox 发送给开发 Agent
            4. 你的目标是提升代码安全性，不是破坏系统
            """;

    /** 扫描结果存储路径 */
    private static final String REPORT_VFS_PATH = "/vfs/security/reports/";

    /** 目标应用信息（由 DAG 引擎注入） */
    private String targetAppUrl;
    private String targetAppPort;
    private String developerAgentId;

    /**
     * 构造安全审计官。
     *
     * @param agentId Agent 唯一标识 (如 "security_auditor_01")
     */
    public SecurityAuditorAgent(String agentId) {
        super(agentId, ProcessPriority.HIGH, 80000);
    }

    /**
     * 构造安全审计官并注入目标信息。
     *
     * @param agentId            Agent ID
     * @param targetAppUrl       目标应用 URL (如 "172.18.0.5")
     * @param targetAppPort      目标应用端口 (如 "8080")
     * @param developerAgentId  开发 Agent ID (用于发送战报)
     */
    public SecurityAuditorAgent(
            String agentId,
            String targetAppUrl,
            String targetAppPort,
            String developerAgentId
    ) {
        this(agentId);
        this.targetAppUrl = targetAppUrl;
        this.targetAppPort = targetAppPort;
        this.developerAgentId = developerAgentId;
    }

    @Override
    protected void onStart() {
        log.info("[Security_Auditor] 第三母体启动 — 安全审计官已就位");
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  🛡️  Security_Auditor (红队智能体) 已启动                       ║");
        System.out.println("  ║  角色: 免疫与试炼 — 对其他母体的代码进行红队渗透测试          ║");
        System.out.println("  ║  工具: HackingTool 185+ 渗透测试模块                          ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");

        if (targetAppUrl != null) {
            runSecurityAudit(targetAppUrl, targetAppPort);
        }
    }

    /**
     * 执行完整的安全审计流程。
     * <p>
     * 按以下顺序执行多阶段渗透测试：
     * <ol>
     *   <li>端口扫描 (Nmap) — 发现开放端口</li>
     *   <li>Web 漏洞扫描 (Nuclei) — 检测已知漏洞</li>
     *   <li>SQL 注入检测 (SQLmap) — 测试数据库注入</li>
     *   <li>XSS 检测 (DalFox) — 测试跨站脚本</li>
     *   <li>目录枚举 (Gobuster) — 发现隐藏路径</li>
     * </ol>
     *
     * @param targetIp   目标 IP
     * @param targetPort 目标端口
     */
    public void runSecurityAudit(String targetIp, String targetPort) {
        log.info("[Security_Auditor] 开始对 {}:{} 执行红队渗透测试", targetIp, targetPort);
        System.out.printf("  🔍 [Security_Auditor] 目标: %s:%s — 启动红队渗透测试%n", targetIp, targetPort);

        String fullTarget = targetPort != null && !targetPort.isBlank()
                ? targetIp + ":" + targetPort
                : targetIp;

        List<VulnerabilityReport> reports = new ArrayList<>();

        // ── 阶段 1: 端口扫描 ──
        VulnerabilityReport portScan = executeScan(
                SecurityModule.NMAP, targetIp,
                "-sV -sC --top-ports 1000 -T4"
        );
        if (portScan != null) reports.add(portScan);

        // ── 阶段 2: Web 漏洞扫描 ──
        VulnerabilityReport webVuln = executeScan(
                SecurityModule.NUCLEI, "http://" + fullTarget,
                "-severity medium,high,critical"
        );
        if (webVuln != null) reports.add(webVuln);

        // ── 阶段 3: SQL 注入检测 ──
        VulnerabilityReport sqli = executeScan(
                SecurityModule.SQLMAP, "http://" + fullTarget,
                "--forms --crawl=2 --level=1 --risk=1"
        );
        if (sqli != null) reports.add(sqli);

        // ── 阶段 4: XSS 检测 ──
        VulnerabilityReport xss = executeScan(
                SecurityModule.DALFOX, "http://" + fullTarget,
                "--blind"
        );
        if (xss != null) reports.add(xss);

        // ── 阶段 5: 目录枚举 ──
        VulnerabilityReport dirEnum = executeScan(
                SecurityModule.GOBUSTER, "http://" + fullTarget,
                "-w /usr/share/wordlists/dirb/common.txt -t 20"
        );
        if (dirEnum != null) reports.add(dirEnum);

        // ── 汇总战报 ──
        sendVulnerabilityReports(reports);

        // ── 广播审计完成事件 ──
        EventBus.instance().broadcast("sys.security.audit_complete",
                String.format("{\"agentId\":\"%s\",\"target\":\"%s\",\"vulnCount\":%d}",
                        agentId, fullTarget, reports.size()));

        log.info("[Security_Auditor] 红队渗透测试完成 — 发现 {} 个漏洞", reports.size());
    }

    /**
     * 执行单个安全扫描模块。
     *
     * @param module 扫描模块
     * @param target 目标
     * @param args   额外参数
     * @return 漏洞报告，扫描失败返回 null
     */
    private VulnerabilityReport executeScan(SecurityModule module, String target, String args) {
        log.info("[Security_Auditor] 执行 {} 扫描 — 目标: {}", module.moduleName(), target);
        System.out.printf("  ├─ 🔍 扫描模块: %s — 目标: %s%n", module.moduleName(), target);

        try {
            // 通过 LLM 驱动 SecurityScanTool
            String scanPrompt = String.format(
                    "使用 security_scan 工具执行 %s 扫描。\n目标: %s\n参数: %s\n\n"
                            + "请执行扫描并返回完整结果。",
                    module.moduleName(), target, args
            );

            String scanResult = sdk.think(agentId, scanPrompt, PERSONA);

            // 解析扫描结果为漏洞报告
            VulnerabilityReport report = parseScanResult(module, target, scanResult);
            if (report != null) {
                // 将报告写入 VFS
                String reportPath = REPORT_VFS_PATH + module.moduleName() + "_"
                        + System.currentTimeMillis() + ".md";
                sdk.writeFile(agentId, reportPath, report.toMarkdown());
                log.info("[Security_Auditor] {} 扫描完成 — 严重等级: {}",
                        module.moduleName(), report.severity());
            }

            return report;

        } catch (Exception e) {
            log.error("[Security_Auditor] {} 扫描失败: {}", module.moduleName(), e.getMessage());
            return null;
        }
    }

    /**
     * 解析扫描输出为结构化漏洞报告。
     */
    private VulnerabilityReport parseScanResult(
            SecurityModule module, String target, String scanOutput
    ) {
        if (scanOutput == null || scanOutput.isBlank()) {
            return null;
        }

        // 基于输出内容判断严重等级
        VulnerabilityReport.Severity severity = VulnerabilityReport.Severity.INFO;
        String vulnType = "扫描完成";
        String description = "未发现明显漏洞";
        List<String> affectedEndpoints = new ArrayList<>();

        String lowerOutput = scanOutput.toLowerCase();

        if (lowerOutput.contains("critical") || lowerOutput.contains("rce")
                || lowerOutput.contains("remote code execution")) {
            severity = VulnerabilityReport.Severity.CRITICAL;
            vulnType = "远程代码执行 (RCE)";
            description = "检测到可能的远程代码执行漏洞";
        } else if (lowerOutput.contains("sql injection") || lowerOutput.contains("sqlmap")
                && lowerOutput.contains("injectable")) {
            severity = VulnerabilityReport.Severity.HIGH;
            vulnType = "SQL 注入";
            description = "检测到 SQL 注入漏洞";
        } else if (lowerOutput.contains("xss") || lowerOutput.contains("cross-site scripting")) {
            severity = VulnerabilityReport.Severity.HIGH;
            vulnType = "跨站脚本 (XSS)";
            description = "检测到 XSS 漏洞";
        } else if (lowerOutput.contains("high") || lowerOutput.contains("vulnerability")) {
            severity = VulnerabilityReport.Severity.HIGH;
            vulnType = "高危漏洞";
            description = "检测到高危漏洞";
        } else if (lowerOutput.contains("medium")) {
            severity = VulnerabilityReport.Severity.MEDIUM;
            vulnType = "中危漏洞";
            description = "检测到中危漏洞";
        } else if (lowerOutput.contains("open") && lowerOutput.contains("port")) {
            severity = VulnerabilityReport.Severity.LOW;
            vulnType = "端口暴露";
            description = "检测到开放端口";
        }

        // 提取受影响端点
        String[] lines = scanOutput.split("\n");
        for (String line : lines) {
            if (line.contains("http://") || line.contains("https://")) {
                String trimmed = line.trim();
                if (trimmed.length() < 200) {
                    affectedEndpoints.add(trimmed);
                }
            }
        }

        return new VulnerabilityReport(
                UUID.randomUUID().toString(),
                module.moduleName(),
                target,
                severity,
                vulnType,
                description,
                scanOutput.length() > 2000 ? scanOutput.substring(0, 2000) : scanOutput,
                scanOutput.length() > 5000 ? scanOutput.substring(0, 5000) : scanOutput,
                System.currentTimeMillis(),
                affectedEndpoints
        );
    }

    /**
     * 将漏洞战报发送给开发 Agent。
     * <p>
     * 通过 AgentMailbox 投递 Markdown 格式的战报，
     * 触发"创造→攻击→自愈"闭环。
     */
    private void sendVulnerabilityReports(List<VulnerabilityReport> reports) {
        if (reports.isEmpty()) {
            log.info("[Security_Auditor] 未发现漏洞 — 无需发送战报");
            System.out.println("  ✅ [Security_Auditor] 未发现漏洞 — 代码安全审计通过");
            return;
        }

        // 汇总报告
        StringBuilder summary = new StringBuilder();
        summary.append("# 红队渗透测试战报汇总\n\n");
        summary.append("扫描目标: ").append(targetAppUrl).append(":").append(targetAppPort).append("\n");
        summary.append("发现漏洞数: ").append(reports.size()).append("\n\n");

        int critical = 0, high = 0, medium = 0, low = 0;
        for (VulnerabilityReport report : reports) {
            summary.append("## ").append(report.scanModule()).append("\n");
            summary.append(report.toMarkdown()).append("\n");

            switch (report.severity()) {
                case CRITICAL -> critical++;
                case HIGH -> high++;
                case MEDIUM -> medium++;
                case LOW -> low++;
                default -> {}
            }
        }

        summary.append("---\n## 漏洞统计\n");
        summary.append("| 严重等级 | 数量 |\n|----------|------|\n");
        summary.append("| 严重 | ").append(critical).append(" |\n");
        summary.append("| 高危 | ").append(high).append(" |\n");
        summary.append("| 中危 | ").append(medium).append(" |\n");
        summary.append("| 低危 | ").append(low).append(" |\n");

        // 通过 AgentMailbox 发送战报给开发 Agent
        if (developerAgentId != null && !developerAgentId.isBlank()) {
            MailMessage warReport = new MailMessage(
                    agentId,
                    developerAgentId,
                    MailMessage.MessageType.TASK_ASSIGN,
                    summary.toString(),
                    MailMessage.Priority.HIGH
            );
            TeamRegistry.getInstance().dispatch(warReport);
            log.info("[Security_Auditor] 战报已发送给 {}", developerAgentId);
            System.out.printf("  📨 [Security_Auditor] 战报已发送给 %s — %d 个漏洞%n",
                    developerAgentId, reports.size());
        }

        // 广播安全警报
        EventBus.instance().broadcast("sys.security.vulnerability_found",
                String.format("{\"agentId\":\"%s\",\"target\":\"%s\",\"critical\":%d,\"high\":%d,\"medium\":%d,\"low\":%d}",
                        agentId, targetAppUrl, critical, high, medium, low));
    }

    @Override
    protected void onMessage(String msg) {
        // 处理来自其他 Agent 的消息
        log.info("[Security_Auditor] 收到消息: {}", msg.substring(0, Math.min(msg.length(), 100)));

        // 如果收到扫描请求
        if (msg.contains("scan") || msg.contains("audit")) {
            // 解析目标信息并启动扫描
            String target = extractValue(msg, "target");
            String port = extractValue(msg, "port");
            if (target != null) {
                runSecurityAudit(target, port);
            }
        }
    }

    /**
     * 设置目标应用信息 — 供 DAG 引擎注入。
     */
    public void setTarget(String url, String port, String developerAgentId) {
        this.targetAppUrl = url;
        this.targetAppPort = port;
        this.developerAgentId = developerAgentId;
    }

    private static String extractValue(String text, String key) {
        String needle = "\"" + key + "\":\"";
        int start = text.indexOf(needle);
        if (start < 0) return null;
        start += needle.length();
        int end = text.indexOf("\"", start);
        return end > start ? text.substring(start, end) : null;
    }
}
