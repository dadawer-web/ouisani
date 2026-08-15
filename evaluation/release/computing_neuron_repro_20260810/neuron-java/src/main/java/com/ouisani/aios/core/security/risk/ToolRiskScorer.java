package com.ouisani.aios.core.security.risk;

import com.ouisani.aios.core.syscall.SyscallRequest;
import com.ouisani.aios.core.syscall.schema.RawPayload;
import com.ouisani.aios.core.syscall.schema.StoragePayload;
import com.ouisani.aios.core.syscall.schema.ToolPayload;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tool call risk scorer - four-dimensional weighted scoring engine.
 * <p>
 * Inspired by ECC (Everything Claude Code) ToolCallEvent::compute_risk(),
 * replaces traditional blacklist with weighted scoring for each tool call.
 *
 * <h3>Four scoring dimensions</h3>
 * <ul>
 *   <li>Base tool risk: Bash=0.20, Write=0.15, Edit=0.10, other=0.05 (range 0.05-0.20)</li>
 *   <li>File sensitivity: secrets=0.25, configs=0.15, other=0.0 (range 0-0.25)</li>
 *   <li>Blast radius: catastrophic=0.35, high=0.25, none=0.0 (range 0-0.35)</li>
 *   <li>Irreversibility: destructive=0.45, high=0.40, medium=0.20, none=0.0 (range 0-0.45)</li>
 * </ul>
 *
 * <h3>OS analogy: Linux Kernel Risk Assessment</h3>
 * Similar to Linux kernel syscall risk assessment, but more fine-grained.
 * BpfManager 5 builtin rules are qualitative (HIGH/CRITICAL block),
 * this scorer is quantitative (weighted score to 4-level action).
 * Both complement each other: rule engine handles known threats,
 * score engine handles unknown combination risks.
 *
 * @see ToolRiskScore
 * @see RiskAction
 */
public final class ToolRiskScorer {

    /** 基础工具风险分值 */
    private static final double RISK_BASH = 0.20;
    private static final double RISK_WRITE = 0.15;
    private static final double RISK_EDIT = 0.10;
    private static final double RISK_DEFAULT = 0.05;

    /** 文件敏感性分值 */
    private static final double SENSITIVITY_CRITICAL = 0.25;  // .env, secret, token, .pem
    private static final double SENSITIVITY_HIGH = 0.15;      // Cargo.toml, Dockerfile, .github/workflows
    private static final double SENSITIVITY_NONE = 0.0;

    /** 爆炸半径分值 */
    private static final double BLAST_CATASTROPHIC = 0.35;    // git push --force, rm -rf /
    private static final double BLAST_HIGH = 0.25;            // **, --recursive, find
    private static final double BLAST_NONE = 0.0;

    /** 不可逆性分值 */
    private static final double IRREV_DESTRUCTIVE = 0.45;     // rm -rf, git reset --hard, drop database
    private static final double IRREV_HIGH = 0.40;            // rm -f, git push -f
    private static final double IRREV_MEDIUM = 0.20;          // git commit, docker rm
    private static final double IRREV_NONE = 0.0;

    /** 高敏感文件路径模式 */
    private static final Set<String> CRITICAL_FILES = Set.of(
            ".env", ".env.local", ".env.production", ".env.development",
            "secret", "secrets", "credentials", "credential",
            ".pem", ".key", ".pfx", ".p12", ".keystore", ".jks",
            "id_rsa", "id_ed25519", "id_ecdsa",
            ".aws/credentials", ".npmrc", ".pypirc",
            "settings.json", "config.json"
    );

    /** 高风险配置文件模式 */
    private static final Set<String> HIGH_RISK_CONFIGS = Set.of(
            "Cargo.toml", "Dockerfile", "docker-compose.yml",
            ".github/workflows", "Makefile", "CMakeLists.txt",
            "pom.xml", "build.gradle", "package.json",
            "tsconfig.json", "webpack.config", "vite.config"
    );

    /** 爆炸半径模式 */
    private static final Set<String> CATASTROPHIC_PATTERNS = Set.of(
            "git push --force", "git push -f",
            "rm -rf /", "rm -rf ~", "rm -rf /*",
            "dd if=/dev/zero", "mkfs", "fdisk",
            "shutdown", "reboot", "halt",
            "drop database", "drop table", "truncate table"
    );

    private static final Set<String> HIGH_BLAST_PATTERNS = Set.of(
            "**", "--recursive", "-r", "-rf",
            "find /", "find ~",
            "chmod -R", "chown -R"
    );

    /** 不可逆操作模式 */
    private static final Set<String> IRREVERSIBLE_PATTERNS = Set.of(
            "rm -rf", "git reset --hard", "git clean -f",
            "drop database", "drop table", "truncate",
            "git push --force", "git push -f",
            "rm -f", "rm -fr",
            "docker rm", "docker rmi", "docker system prune",
            "git commit", "git merge"
    );

    private ToolRiskScorer() {}

    /**
     * 对 syscall 请求进行四维风险评分。
     *
     * @param request syscall 请求
     * @return 风险评分结果
     */
    public static ToolRiskScore score(SyscallRequest request) {
        String action = request.fullAction();
        String toolName = extractToolName(request);
        String path = extractPath(request);
        String command = extractCommand(request);

        double baseRisk = computeBaseToolRisk(action, toolName);
        double fileSensitivity = computeFileSensitivity(path);
        double blastRadius = computeBlastRadius(command, toolName);
        double irreversibility = computeIrreversibility(command, toolName);

        return new ToolRiskScore(baseRisk, fileSensitivity, blastRadius, irreversibility);
    }

    /**
     * 维度1: 基础工具风险。
     * <p>
     * Bash 类工具风险最高 (0.20)，因为可执行任意命令；
     * Write/MultiEdit 次之 (0.15)，因为可覆盖文件；
     * Edit 较低 (0.10)，因为通常是局部修改；
     * 其他工具最低 (0.05)。
     */
    private static double computeBaseToolRisk(String action, String toolName) {
        String combined = (action + " " + toolName).toLowerCase();

        if (combined.contains("bash") || combined.contains("shell") || combined.contains("exec")) {
            return RISK_BASH;
        }
        if (combined.contains("write") || combined.contains("multiedit") || combined.contains("create")) {
            return RISK_WRITE;
        }
        if (combined.contains("edit") || combined.contains("modify") || combined.contains("update")) {
            return RISK_EDIT;
        }
        return RISK_DEFAULT;
    }

    /**
     * 维度2: 文件敏感性。
     * <p>
     * 检查操作目标路径是否包含敏感文件（密钥、凭证、配置）。
     */
    private static double computeFileSensitivity(String path) {
        if (path == null || path.isBlank()) return SENSITIVITY_NONE;

        String lowerPath = path.toLowerCase();

        // 检查关键敏感文件
        for (String critical : CRITICAL_FILES) {
            if (lowerPath.contains(critical.toLowerCase())) {
                return SENSITIVITY_CRITICAL;
            }
        }

        // 检查高风险配置文件
        for (String config : HIGH_RISK_CONFIGS) {
            if (lowerPath.contains(config.toLowerCase())) {
                return SENSITIVITY_HIGH;
            }
        }

        // 路径中包含敏感关键词
        if (lowerPath.contains("secret") || lowerPath.contains("credential")
                || lowerPath.contains("password") || lowerPath.contains("token")
                || lowerPath.contains("api_key") || lowerPath.contains("apikey")
                || lowerPath.contains("private_key")) {
            return SENSITIVITY_CRITICAL;
        }

        return SENSITIVITY_NONE;
    }

    /**
     * 维度3: 爆炸半径。
     * <p>
     * 检查命令是否具有大范围影响（递归、强制、全盘操作）。
     */
    private static double computeBlastRadius(String command, String toolName) {
        String combined = ((command != null ? command : "") + " " + toolName).toLowerCase();

        // 检查灾难性模式
        for (String pattern : CATASTROPHIC_PATTERNS) {
            if (combined.contains(pattern.toLowerCase())) {
                return BLAST_CATASTROPHIC;
            }
        }

        // 检查高爆炸半径模式
        for (String pattern : HIGH_BLAST_PATTERNS) {
            if (combined.contains(pattern.toLowerCase())) {
                return BLAST_HIGH;
            }
        }

        return BLAST_NONE;
    }

    /**
     * 维度4: 不可逆性。
     * <p>
     * 检查操作是否不可逆（删除、强制推送、数据库销毁）。
     */
    private static double computeIrreversibility(String command, String toolName) {
        String combined = ((command != null ? command : "") + " " + toolName).toLowerCase();

        // 检查不可逆模式
        for (String pattern : IRREVERSIBLE_PATTERNS) {
            if (combined.contains(pattern.toLowerCase())) {
                // 进一步区分灾难性不可逆和一般不可逆
                if (pattern.equals("rm -rf") || pattern.equals("git reset --hard")
                        || pattern.equals("drop database") || pattern.equals("drop table")
                        || pattern.equals("truncate") || pattern.equals("git push --force")
                        || pattern.equals("git push -f")) {
                    return IRREV_DESTRUCTIVE;
                }
                if (pattern.equals("rm -f") || pattern.equals("rm -fr")) {
                    return IRREV_HIGH;
                }
                return IRREV_MEDIUM;
            }
        }

        return IRREV_NONE;
    }

    // ── Payload 提取工具方法 ──

    private static String extractToolName(SyscallRequest request) {
        if (request.payload() instanceof ToolPayload toolPayload) {
            return toolPayload.toolName();
        }
        if (request.payload() instanceof RawPayload raw) {
            String name = raw.paramString("toolName");
            if (name == null) name = raw.paramString("tool_name");
            return name != null ? name : "";
        }
        return "";
    }

    private static String extractPath(SyscallRequest request) {
        if (request.payload() instanceof StoragePayload storage) {
            return storage.path();
        }
        if (request.payload() instanceof ToolPayload tool) {
            String path = tool.argString("path");
            if (path == null) path = tool.argString("file");
            if (path == null) path = tool.argString("file_path");
            return path;
        }
        if (request.payload() instanceof RawPayload raw) {
            String path = raw.paramString("path");
            if (path == null) path = raw.paramString("file");
            if (path == null) path = raw.paramString("file_path");
            return path;
        }
        return null;
    }

    private static String extractCommand(SyscallRequest request) {
        if (request.payload() instanceof ToolPayload tool) {
            String cmd = tool.argString("command");
            if (cmd == null) cmd = tool.argString("cmd");
            return cmd;
        }
        if (request.payload() instanceof RawPayload raw) {
            String cmd = raw.paramString("command");
            if (cmd == null) cmd = raw.paramString("cmd");
            return cmd;
        }
        return null;
    }
}
