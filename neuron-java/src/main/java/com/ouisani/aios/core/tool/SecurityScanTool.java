package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.security.redteam.SecurityModule;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 安全扫描工具 — 将 HackingTool 的 185+ 渗透测试工具封装为非交互式系统调用。
 * <p>
 * HackingTool 原本是给人用的交互式菜单（输入 1 选 Nmap，输入 2 选 SQLmap），
 * 大模型无法直接操作这种需要频繁 stdin 交互的终端。此工具将交互式菜单
 * 转换为非交互式的命令行执行，通过 docker exec 在隔离容器中运行。
 * <p>
 * <h3>工作流程</h3>
 * <pre>
 *   LLM 输出 JSON Payload
 *     → SecurityScanTool.call()
 *       → SecurityModule.renderCommand() (非交互式命令渲染)
 *         → docker exec hackingtool_container <command>
 *           → 扫描结果返回 VFS
 * </pre>
 * <p>
 * <h3>安全特性</h3>
 * <ul>
 *   <li>强制非交互模式 — 所有命令附加 --batch / -batch 等参数</li>
 *   <li>目标 IP 白名单 — 仅允许私有网段 (10.x / 172.16-31.x / 192.168.x)</li>
 *   <li>容器隔离 — 在 hackingtool Docker 容器内执行，物理隔离宿主机</li>
 *   <li>超时控制 — 默认 300 秒，防止长时间扫描</li>
 *   <li>输出截断 — 默认 50000 字符</li>
 * </ul>
 * <p>
 * OS 类比：相当于 Linux 的 security_load_policy — 加载安全策略并执行扫描。
 *
 * @see SecurityModule
 * @see SecurityScanApprovalHook
 */
public class SecurityScanTool implements Tool<SecurityScanTool.Input> {

    private static final int DEFAULT_TIMEOUT_SECONDS = 300;
    private static final int MAX_OUTPUT_LENGTH = 50000;
    private static final String CONTAINER_NAME = "aios_hackingtool";

    /**
     * 工具输入 — 大模型输出的安全扫描意图。
     */
    public record Input(
            String module,
            String target,
            String args,
            int timeoutSeconds
    ) implements ToolInput {
        public Input {
            if (timeoutSeconds <= 0) timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        }

        public Input(String module, String target, String args) {
            this(module, target, args, DEFAULT_TIMEOUT_SECONDS);
        }

        @Override public String toJson() {
            return "{\"module\":\"" + escape(module) + "\","
                    + "\"target\":\"" + escape(target) + "\","
                    + "\"args\":\"" + escape(args) + "\","
                    + "\"timeout\":" + timeoutSeconds + "}";
        }

        private static String escape(String s) {
            return s == null ? "" : s.replace("\"", "\\\"").replace("\n", "\\n");
        }
    }

    @Override public String name() { return "security_scan"; }

    @Override public String description() {
        return "Executes a security scan using HackingTool modules (nmap, sqlmap, nuclei, etc.) "
                + "against a target in an isolated Docker container. "
                + "REQUIRES human approval before execution. "
                + "Only private network targets are allowed (10.x/172.16-31.x/192.168.x).";
    }

    @Override public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"module\":{\"type\":\"string\",\"description\":\"Security module name (nmap, sqlmap, nuclei, nikto, gobuster, ffuf, dalfox, trivy, etc.)\"},"
                + "\"target\":{\"type\":\"string\",\"description\":\"Target IP, URL, or domain (must be private network)\"},"
                + "\"args\":{\"type\":\"string\",\"description\":\"Additional command-line arguments for the module\"},"
                + "\"timeoutSeconds\":{\"type\":\"integer\",\"description\":\"Timeout in seconds (default 300)\"}"
                + "},\"required\":[\"module\",\"target\"]}";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        // ── 1. 参数校验 ──
        if (input.module() == null || input.module().isBlank()) {
            return ToolOutput.fail("Module name is required. Available modules: " + SecurityModule.listAvailable());
        }

        SecurityModule module = SecurityModule.fromName(input.module());
        if (module == null) {
            return ToolOutput.fail("Unknown security module: '" + input.module()
                    + "'. Available modules:\n" + SecurityModule.listAvailable());
        }

        if (input.target() == null || input.target().isBlank()) {
            return ToolOutput.fail("Target is required for module: " + module.moduleName());
        }

        // ── 2. 目标安全验证 — 仅允许私有网段 ──
        String targetError = validateTarget(input.target());
        if (targetError != null) {
            return ToolOutput.fail("TARGET REJECTED — " + targetError
                    + "\nSecurityScanTool only allows private network targets (10.x/172.16-31.x/192.168.x)."
                    + "\nScanning public IPs is strictly prohibited.");
        }

        // ── 3. 渲染非交互式命令 ──
        Map<String, String> params = new HashMap<>();
        params.put("target", input.target());
        params.put("args", input.args() != null ? input.args() : "");
        params.put("target_ip", input.target());
        params.put("target_url", input.target());
        params.put("target_domain", input.target());
        params.put("target_host", input.target());
        params.put("target_path", input.target());
        params.put("target_file", input.target());

        String command = module.renderCommand(params);

        // ── 4. 在 Docker 容器中执行 ──
        String dockerExecCommand = String.format(
                "docker exec %s bash -c '%s'",
                CONTAINER_NAME,
                command.replace("'", "'\\''")
        );

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "exec", CONTAINER_NAME,
                    "bash", "-c", command
            );
            pb.redirectErrorStream(true);
            pb.environment().put("DEBIAN_FRONTEND", "noninteractive");

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(input.timeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolOutput.fail("Security scan timed out after " + input.timeoutSeconds()
                        + "s. Module: " + module.moduleName() + ", Target: " + input.target());
            }

            int exitCode = process.exitValue();
            String result = output.toString();
            if (result.length() > MAX_OUTPUT_LENGTH) {
                result = result.substring(0, MAX_OUTPUT_LENGTH)
                        + "\n... [truncated at " + MAX_OUTPUT_LENGTH + " chars]";
            }

            // ── 5. 将扫描报告写入 VFS ──
            if (context.sdk() != null && context.agentId() != null) {
                try {
                    String reportPath = "/vfs/security/reports/"
                            + module.moduleName() + "_"
                            + System.currentTimeMillis() + ".txt";
                    context.sdk().writeFile(context.agentId(), reportPath, result);
                } catch (Exception ignored) {
                    // VFS 写入失败不影响扫描结果返回
                }
            }

            if (exitCode == 0) {
                return ToolOutput.ok("Security scan completed.\nModule: " + module.moduleName()
                        + "\nTarget: " + input.target()
                        + "\n\n--- Scan Output ---\n" + result);
            } else {
                return ToolOutput.ok("Security scan completed (exit code " + exitCode
                        + ", some findings may require attention).\nModule: " + module.moduleName()
                        + "\nTarget: " + input.target()
                        + "\n\n--- Scan Output ---\n" + result);
            }

        } catch (Exception e) {
            return ToolOutput.fail("Security scan execution failed: " + e.getMessage()
                    + "\nEnsure the hackingtool container is running: docker start " + CONTAINER_NAME);
        }
    }

    @Override public boolean readOnly() { return false; }

    @Override public String prompt() {
        return "SecurityScanTool executes penetration testing modules in an isolated Docker container. "
                + "CRITICAL: This tool requires human approval before execution. "
                + "Only private network targets (10.x/172.16-31.x/192.168.x) are allowed. "
                + "Available modules: " + SecurityModule.listAvailable();
    }

    /**
     * 验证目标是否为私有网段 — 防止误扫公网。
     * <p>
     * 允许的目标：
     * <ul>
     *   <li>10.0.0.0/8</li>
     *   <li>172.16.0.0/12 (172.16.x - 172.31.x)</li>
     *   <li>192.168.0.0/16</li>
     *   <li>127.0.0.0/8 (localhost)</li>
     *   <li>localhost</li>
     * </ul>
     *
     * @return null 表示通过，非 null 表示拒绝原因
     */
    private String validateTarget(String target) {
        if (target == null || target.isBlank()) {
            return "Target is empty";
        }

        String cleaned = target.trim()
                .replaceFirst("^https?://", "")
                .replaceFirst("^ftps?://", "")
                .split("/")[0]
                .split(":")[0];

        if ("localhost".equalsIgnoreCase(cleaned)) {
            return null;
        }

        // 纯 IP 地址验证
        if (cleaned.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) {
            String[] parts = cleaned.split("\\.");
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);

            // 10.0.0.0/8
            if (first == 10) return null;
            // 172.16.0.0/12
            if (first == 172 && second >= 16 && second <= 31) return null;
            // 192.168.0.0/16
            if (first == 192 && second == 168) return null;
            // 127.0.0.0/8
            if (first == 127) return null;

            return "Public IP detected: " + cleaned + " — scanning public IPs is strictly prohibited";
        }

        // 域名 — 检查是否为内网域名
        if (cleaned.endsWith(".local") || cleaned.endsWith(".internal")
                || cleaned.endsWith(".lan") || cleaned.endsWith(".docker")
                || cleaned.contains(".docker.")) {
            return null;
        }

        // Docker 容器名格式 (如 aios_hackingtool)
        if (cleaned.equals(CONTAINER_NAME) || cleaned.startsWith("aios_")) {
            return null;
        }

        // 其他域名 — 默认拒绝，需人工确认
        return "Domain '" + cleaned + "' is not in private network range. "
                + "Only private IPs (10.x/172.16-31.x/192.168.x), localhost, or .local/.internal/.lan domains are allowed.";
    }
}
