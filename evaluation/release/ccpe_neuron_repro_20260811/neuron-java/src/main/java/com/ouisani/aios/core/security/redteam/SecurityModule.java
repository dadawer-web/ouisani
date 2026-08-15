package com.ouisani.aios.core.security.redteam;

import java.util.Map;
import java.util.Set;

/**
 * 安全扫描模块枚举 — HackingTool 185+ 工具的非交互式命令映射。
 * <p>
 * HackingTool 原本是交互式菜单工具（输入 1 选 Nmap，输入 2 选 SQLmap），
 * 大模型无法直接操作。此枚举将每个工具映射为非交互式的命令行模板，
 * 供 SecurityScanTool 通过 docker exec 调用。
 * <p>
 * OS 类比：相当于 Linux 的 /proc/sys/kernel/ 模块表 — 每个模块有明确的
 * 加载参数和执行模板。
 *
 * @see SecurityScanTool
 */
public enum SecurityModule {

    // ════════════════════════════════════════════════════════════════
    //  信息收集 (Information Gathering)
    // ════════════════════════════════════════════════════════════════

    NMAP(
            "nmap",
            "网络端口扫描与服务识别",
            "nmap {target} {args}",
            Set.of("target_ip"),
            "information_gathering"
    ),

    MASSCAN(
            "masscan",
            "大规模高速端口扫描",
            "masscan {target} {args}",
            Set.of("target_ip"),
            "information_gathering"
    ),

    RUSTSCAN(
            "rustscan",
            "Rust 实现的高速端口扫描器",
            "rustscan -a {target} {args}",
            Set.of("target_ip"),
            "information_gathering"
    ),

    THEHARVESTER(
            "theharvester",
            "邮箱/子域名/主机信息收集",
            "theHarvester -d {target} {args}",
            Set.of("target_domain"),
            "information_gathering"
    ),

    AMASS(
            "amass",
            "OSINT 子域名枚举",
            "amass enum -d {target} {args}",
            Set.of("target_domain"),
            "information_gathering"
    ),

    SUBFINDER(
            "subfinder",
            "被动子域名发现",
            "subfinder -d {target} {args}",
            Set.of("target_domain"),
            "information_gathering"
    ),

    HTTPX(
            "httpx",
            "HTTP 探测与指纹识别",
            "httpx -u {target} {args}",
            Set.of("target_url"),
            "information_gathering"
    ),

    // ════════════════════════════════════════════════════════════════
    //  Web 攻击 (Web Attack)
    // ════════════════════════════════════════════════════════════════

    NUCLEI(
            "nuclei",
            "基于模板的漏洞扫描器",
            "nuclei -u {target} {args}",
            Set.of("target_url"),
            "web_attack"
    ),

    NIKTO(
            "nikto",
            "Web 服务器漏洞扫描",
            "nikto -h {target} {args}",
            Set.of("target_url"),
            "web_attack"
    ),

    GOBUSTER(
            "gobuster",
            "目录/文件暴力枚举",
            "gobuster dir -u {target} {args}",
            Set.of("target_url"),
            "web_attack"
    ),

    FFUF(
            "ffuf",
            "快速 Web Fuzzer",
            "ffuf -u {target} {args}",
            Set.of("target_url"),
            "web_attack"
    ),

    FEROXBUG(
            "feroxbuster",
            "递归目录暴力枚举",
            "feroxbuster -u {target} {args}",
            Set.of("target_url"),
            "web_attack"
    ),

    WAFW00F(
            "wafw00f",
            "WAF 检测与识别",
            "wafw00f {target} {args}",
            Set.of("target_url"),
            "web_attack"
    ),

    KATANA(
            "katana",
            "下一代爬虫框架",
            "katana -u {target} {args}",
            Set.of("target_url"),
            "web_attack"
    ),

    // ════════════════════════════════════════════════════════════════
    //  SQL 注入 (SQL Injection)
    // ════════════════════════════════════════════════════════════════

    SQLMAP(
            "sqlmap",
            "自动化 SQL 注入检测与利用",
            "sqlmap -u {target} --batch {args}",
            Set.of("target_url"),
            "sql_injection"
    ),

    NOSQLMAP(
            "nosqlmap",
            "NoSQL 注入扫描",
            "nosqlmap --victim {target} {args}",
            Set.of("target_host"),
            "sql_injection"
    ),

    // ════════════════════════════════════════════════════════════════
    //  XSS 攻击 (XSS Attack)
    // ════════════════════════════════════════════════════════════════

    DALFOX(
            "dalfox",
            "XSS 漏洞扫描器",
            "dalfox url {target} {args}",
            Set.of("target_url"),
            "xss_attack"
    ),

    XSSTRIKE(
            "xssstrike",
            "XSS 检测与利用套件",
            "python3 xsstrike.py -u {target} {args}",
            Set.of("target_url"),
            "xss_attack"
    ),

    // ════════════════════════════════════════════════════════════════
    //  云安全 (Cloud Security)
    // ════════════════════════════════════════════════════════════════

    TRIVY(
            "trivy",
            "容器/文件系统漏洞扫描",
            "trivy {target} {args}",
            Set.of("target_path"),
            "cloud_security"
    ),

    PROWLER(
            "prowler",
            "AWS 安全审计工具",
            "prowler {args}",
            Set.of(),
            "cloud_security"
    ),

    // ════════════════════════════════════════════════════════════════
    //  漏洞利用框架 (Exploit Frameworks)
    // ════════════════════════════════════════════════════════════════

    METASPLOIT(
            "metasploit",
            "漏洞利用框架 (非交互模式)",
            "msfconsole -q -x 'use {module}; set RHOSTS {target}; set {args}; run; exit'",
            Set.of("target_ip"),
            "exploit_framework"
    ),

    // ════════════════════════════════════════════════════════════════
    //  密码破解 (Wordlist / Hash Crack)
    // ════════════════════════════════════════════════════════════════

    HASHCAT(
            "hashcat",
            "GPU 加速哈希破解",
            "hashcat {args}",
            Set.of(),
            "wordlist_generator"
    ),

    JOHN(
            "john",
            "John the Ripper 密码破解",
            "john {target} {args}",
            Set.of("target_file"),
            "wordlist_generator"
    ),

    // ════════════════════════════════════════════════════════════════
    //  移动安全 (Mobile Security)
    // ════════════════════════════════════════════════════════════════

    MOBSF(
            "mobsf",
            "移动应用安全分析",
            "mobsfscan {target} {args}",
            Set.of("target_path"),
            "mobile_security"
    ),

    // ════════════════════════════════════════════════════════════════
    //  活动目录 (Active Directory)
    // ════════════════════════════════════════════════════════════════

    NETEXEC(
            "netexec",
            "网络执行框架 (原 CrackMapExec)",
            "nxc {target} {args}",
            Set.of("target_ip"),
            "active_directory"
    ),

    BLOODHOUND(
            "bloodhound",
            "AD 分析与可视化",
            "bloodhound-python -d {domain} -u {user} -p {password} -c All {args}",
            Set.of("domain", "user", "password"),
            "active_directory"
    ),

    // ════════════════════════════════════════════════════════════════
    //  取证 (Forensics)
    // ════════════════════════════════════════════════════════════════

    BINWALK(
            "binwalk",
            "固件分析与提取",
            "binwalk {target} {args}",
            Set.of("target_file"),
            "forensics"
    ),

    VOLATILITY(
            "volatility",
            "内存取证分析",
            "python3 vol.py -f {target} {args}",
            Set.of("target_file"),
            "forensics"
    );

    // ════════════════════════════════════════════════════════════════
    //  字段定义
    // ════════════════════════════════════════════════════════════════

    private final String moduleName;
    private final String description;
    private final String commandTemplate;
    private final Set<String> requiredParams;
    private final String category;

    SecurityModule(
            String moduleName,
            String description,
            String commandTemplate,
            Set<String> requiredParams,
            String category
    ) {
        this.moduleName = moduleName;
        this.description = description;
        this.commandTemplate = commandTemplate;
        this.requiredParams = requiredParams;
        this.category = category;
    }

    public String moduleName() { return moduleName; }
    public String description() { return description; }
    public String commandTemplate() { return commandTemplate; }
    public Set<String> requiredParams() { return requiredParams; }
    public String category() { return category; }

    /**
     * 根据模块名查找枚举值（大小写不敏感）。
     *
     * @param name 模块名
     * @return 枚举值，不存在则返回 null
     */
    public static SecurityModule fromName(String name) {
        if (name == null || name.isBlank()) return null;
        String lower = name.toLowerCase().trim();
        for (SecurityModule m : values()) {
            if (m.moduleName.equalsIgnoreCase(lower)) return m;
        }
        return null;
    }

    /**
     * 渲染命令模板 — 将 {target}、{args} 等占位符替换为实际值。
     *
     * @param params 参数映射
     * @return 渲染后的命令行字符串
     */
    public String renderCommand(Map<String, String> params) {
        String cmd = commandTemplate;
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                String placeholder = "{" + entry.getKey() + "}";
                String value = entry.getValue() != null ? entry.getValue() : "";
                cmd = cmd.replace(placeholder, escapeShellArg(value));
            }
        }
        // 清理未替换的占位符
        cmd = cmd.replaceAll("\\{[^}]+}", "");
        return cmd.trim();
    }

    /**
     * 列出所有可用模块名。
     */
    public static String listAvailable() {
        StringBuilder sb = new StringBuilder();
        for (SecurityModule m : values()) {
            sb.append(m.moduleName).append(" (").append(m.category).append("): ")
              .append(m.description).append("\n");
        }
        return sb.toString();
    }

    /**
     * 基础的 shell 参数转义 — 防止命令注入。
     * 仅允许字母数字、点、斜杠、冒号、连字符、下划线。
     */
    private static String escapeShellArg(String arg) {
        if (arg == null || arg.isEmpty()) return "";
        // 只保留安全字符，其余替换为下划线
        return arg.replaceAll("[^a-zA-Z0-9.:/_-]", "_");
    }
}
