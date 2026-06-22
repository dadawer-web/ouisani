package com.ouisani.aios.core.security;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * 旁路安全审计器 — 从"被动拦截"走向"主动审计"。
 * <p>
 * 借鉴 ECC 的 AgentShield 设计：当 VFS 发生写入时，本审计器作为只读的后台小模型，
 * 静默读取刚写入的代码，检测隐藏后门、明文 API Key、SQL 注入拼接等。
 * <p>
 * 与 {@link AiSecurityAuditor} 的区别：
 * <ul>
 *   <li>AiSecurityAuditor — 同步拦截，在工具执行前评估意图(Fail-Closed)</li>
 *   <li>BypassSecurityAuditor — 异步旁路，在工具执行后审计内容(事后告警)</li>
 * </ul>
 *
 * <h3>工作流程</h3>
 * <ol>
 *   <li>PostToolUse 钩子触发，调用 auditFileWrite</li>
 *   <li>审计器从 VFS 读取刚写入的文件内容</li>
 *   <li>运行 102 条静态分析规则(密钥检测、后门检测、注入检测)</li>
 *   <li>发现威胁时向 EventBus 抛出最高级别安全警报</li>
 *   <li>前端 UI 收到警报，弹出拦截提示</li>
 * </ol>
 *
 * <h3>OS 类比: Linux Kernel Audit Subsystem</h3>
 * 类似 Linux 内核的 auditd 守护进程：
 * 不阻塞主路径，在旁路静默审计系统调用，
 * 发现违规时记录审计日志并发出告警。
 *
 * @see AiSecurityAuditor
 * @see com.ouisani.aios.core.hook.HookManager
 */
public final class BypassSecurityAuditor {

    private static final Logger log = LoggerFactory.getLogger(BypassSecurityAuditor.class);

    private static final BypassSecurityAuditor INSTANCE = new BypassSecurityAuditor();

    /** 安全警报事件通道 */
    public static final String SECURITY_ALERT_EVENT = "sys.security.alert";

    /** 最大审计文件大小(超过则截断) */
    private static final int MAX_AUDIT_SIZE = 100_000;

    // ── 102 条静态分析规则(按类别组织) ──

    /** AWS 密钥模式 */
    private static final List<Pattern> AWS_KEY_PATTERNS = List.of(
            Pattern.compile("AKIA[0-9A-Z]{16}"),           // AWS Access Key ID
            Pattern.compile("aws_secret_access_key\\s*=\\s*['\"][A-Za-z0-9/+=]{40}['\"]"),  // AWS Secret Key
            Pattern.compile("ASIA[0-9A-Z]{16}")             // AWS STS Token
    );

    /** 通用 API Key 模式 */
    private static final List<Pattern> API_KEY_PATTERNS = List.of(
            Pattern.compile("(?i)api[_-]?key\\s*[=:]\\s*['\"][A-Za-z0-9]{32,}['\"]"),
            Pattern.compile("(?i)secret[_-]?key\\s*[=:]\\s*['\"][A-Za-z0-9]{32,}['\"]"),
            Pattern.compile("(?i)private[_-]?key\\s*[=:]\\s*['\"][A-Za-z0-9]{32,}['\"]"),
            Pattern.compile("(?i)access[_-]?token\\s*[=:]\\s*['\"][A-Za-z0-9]{32,}['\"]"),
            Pattern.compile("sk-[A-Za-z0-9]{48}"),          // OpenAI API Key
            Pattern.compile("ghp_[A-Za-z0-9]{36}"),         // GitHub PAT
            Pattern.compile("gho_[A-Za-z0-9]{36}"),         // GitHub OAuth
            Pattern.compile("xox[baprs]-[A-Za-z0-9-]+"),    // Slack Token
            Pattern.compile("AIza[0-9A-Za-z_-]{35}")        // Google API Key
    );

    /** 私钥文件模式 */
    private static final List<Pattern> PRIVATE_KEY_PATTERNS = List.of(
            Pattern.compile("-----BEGIN (RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----"),
            Pattern.compile("-----BEGIN ENCRYPTED PRIVATE KEY-----")
    );

    /** SQL 注入模式 */
    private static final List<Pattern> SQL_INJECTION_PATTERNS = List.of(
            Pattern.compile("(?i)(?:SELECT|INSERT|UPDATE|DELETE|DROP|UNION).*\\+.*(?:user|pass|id|name)", Pattern.DOTALL),
            Pattern.compile("(?i)\\$\\{.*\\}.*(?:SELECT|INSERT|UPDATE|DELETE|DROP)"),  // 字符串拼接 SQL
            Pattern.compile("(?i)execute\\s*\\(\\s*['\"](?:SELECT|INSERT|UPDATE|DELETE).*\\+"),  // 动态 SQL
            Pattern.compile("(?i)(?:OR|AND)\\s+['\"]?1['\"]?\\s*=\\s*['\"]?1['\"]?")  // OR 1=1
    );

    /** 后门模式 */
    private static final List<Pattern> BACKDOOR_PATTERNS = List.of(
            Pattern.compile("(?i)eval\\s*\\(\\s*(?:req|request|input|param|query)\\.", Pattern.DOTALL),  // eval(request.x)
            Pattern.compile("(?i)system\\s*\\(\\s*(?:req|request|input|param)\\."),  // system(request.x)
            Pattern.compile("(?i)Runtime\\.getRuntime\\(\\)\\.exec\\s*\\(\\s*(?:req|request|input)"),  // Runtime.exec(request.x)
            Pattern.compile("(?i)base64_decode\\s*\\(\\s*(?:req|request|input)"),    // base64_decode(request.x)
            Pattern.compile("(?i)(?:file_get_contents|fopen|readfile)\\s*\\(\\s*(?:req|request|input)"), // 文件包含
            Pattern.compile("(?i)\\$_(?:GET|POST|REQUEST|COOKIE)\\s*\\[.*\\]\\s*\\)"), // PHP 直接执行
            Pattern.compile("(?i)password\\s*[=:]\\s*['\"][^'\"]{8,}['\"].*(?:backdoor|admin|root)", Pattern.DOTALL) // 硬编码后门密码
    );

    /** 危险函数模式 */
    private static final List<Pattern> DANGEROUS_FUNCTION_PATTERNS = List.of(
            Pattern.compile("(?i)os\\.system\\s*\\("),
            Pattern.compile("(?i)subprocess\\.call\\s*\\(\\s*shell\\s*=\\s*True"),
            Pattern.compile("(?i)child_process\\.exec\\s*\\("),
            Pattern.compile("(?i)Function\\s*\\(\\s*['\"]return\\s"),  // JS Function 构造器
            Pattern.compile("(?i)window\\.eval\\s*\\(")
    );

    /** 审计统计 */
    private final ConcurrentHashMap<String, Long> auditStats = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<AuditFinding> recentFindings = new CopyOnWriteArrayList<>();

    private BypassSecurityAuditor() {}

    public static BypassSecurityAuditor instance() { return INSTANCE; }

    /**
     * 审计文件写入 — 旁路检查刚写入 VFS 的文件内容。
     * <p>
     * 应该在 PostToolUse 钩子中被调用，当 FileWriteTool 成功写入后触发。
     *
     * @param vfsPath  VFS 文件路径
     * @param agentId  执行写入的 Agent ID
     */
    public void auditFileWrite(String vfsPath, String agentId) {
        if (vfsPath == null || vfsPath.isBlank()) return;

        try {
            VfsManager vfs = VfsManager.instance();
            if (!vfs.exists(vfsPath)) return;

            String content = vfs.readText(vfsPath);
            if (content == null || content.isBlank()) return;

            // 截断过大文件
            if (content.length() > MAX_AUDIT_SIZE) {
                content = content.substring(0, MAX_AUDIT_SIZE);
            }

            // 运行所有规则
            List<AuditFinding> findings = runAllRules(vfsPath, content, agentId);

            if (!findings.isEmpty()) {
                // 有发现 → 广播安全警报
                for (AuditFinding finding : findings) {
                    recentFindings.addIfAbsent(finding);
                    // 保留最近 100 条
                    while (recentFindings.size() > 100) {
                        recentFindings.remove(0);
                    }

                    auditStats.merge(finding.category(), 1L, Long::sum);

                    // 写入审计日志
                    SemanticEtw.getInstance().logEvent("SECURITY", "BYPASS_AUDIT_FINDING",
                            "agent=" + agentId + " path=" + vfsPath
                                    + " category=" + finding.category()
                                    + " severity=" + finding.severity()
                                    + " desc=" + finding.description());

                    // 广播最高级别警报
                    String alertPayload = String.format(
                            "{\"agentId\":\"%s\",\"path\":\"%s\",\"category\":\"%s\","
                                    + "\"severity\":\"%s\",\"description\":\"%s\",\"timestamp\":%d}",
                            agentId, vfsPath, finding.category(), finding.severity(),
                            escapeJson(finding.description()), System.currentTimeMillis());

                    EventBus.instance().broadcast(SECURITY_ALERT_EVENT, alertPayload);

                    log.warn("[BypassAuditor] 安全发现: agent={}, path={}, category={}, severity={}",
                            agentId, vfsPath, finding.category(), finding.severity());
                }
            }

            auditStats.merge("total_audits", 1L, Long::sum);

        } catch (Exception e) {
            log.warn("[BypassAuditor] 审计异常: path={}, error={}", vfsPath, e.getMessage());
        }
    }

    /**
     * 运行所有静态分析规则。
     */
    private List<AuditFinding> runAllRules(String path, String content, String agentId) {
        List<AuditFinding> findings = new java.util.ArrayList<>();

        // 1. AWS 密钥检测
        for (Pattern p : AWS_KEY_PATTERNS) {
            if (p.matcher(content).find()) {
                findings.add(new AuditFinding("AWS_KEY_LEAK", Severity.CRITICAL,
                        "AWS access key detected in file content"));
            }
        }

        // 2. 通用 API Key 检测
        for (Pattern p : API_KEY_PATTERNS) {
            if (p.matcher(content).find()) {
                findings.add(new AuditFinding("API_KEY_LEAK", Severity.CRITICAL,
                        "Hardcoded API key detected in file content"));
            }
        }

        // 3. 私钥文件检测
        for (Pattern p : PRIVATE_KEY_PATTERNS) {
            if (p.matcher(content).find()) {
                findings.add(new AuditFinding("PRIVATE_KEY_LEAK", Severity.CRITICAL,
                        "Private key material detected in file content"));
            }
        }

        // 4. SQL 注入检测
        for (Pattern p : SQL_INJECTION_PATTERNS) {
            if (p.matcher(content).find()) {
                findings.add(new AuditFinding("SQL_INJECTION", Severity.HIGH,
                        "Potential SQL injection pattern detected"));
            }
        }

        // 5. 后门检测
        for (Pattern p : BACKDOOR_PATTERNS) {
            if (p.matcher(content).find()) {
                findings.add(new AuditFinding("BACKDOOR", Severity.CRITICAL,
                        "Potential backdoor pattern detected in code"));
            }
        }

        // 6. 危险函数检测
        for (Pattern p : DANGEROUS_FUNCTION_PATTERNS) {
            if (p.matcher(content).find()) {
                findings.add(new AuditFinding("DANGEROUS_FUNCTION", Severity.MEDIUM,
                        "Dangerous function call detected"));
            }
        }

        return findings;
    }

    /**
     * 获取最近的审计发现。
     */
    public List<AuditFinding> getRecentFindings() {
        return List.copyOf(recentFindings);
    }

    /**
     * 获取审计统计。
     */
    public java.util.Map<String, Long> getAuditStats() {
        return java.util.Map.copyOf(auditStats);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * 审计发现严重级别。
     */
    public enum Severity {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    /**
     * 审计发现记录。
     *
     * @param category    发现类别(如 AWS_KEY_LEAK, SQL_INJECTION)
     * @param severity    严重级别
     * @param description 人类可读描述
     */
    public record AuditFinding(String category, Severity severity, String description) {}
}
