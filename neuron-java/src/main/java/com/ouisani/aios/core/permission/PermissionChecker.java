package com.ouisani.aios.core.permission;

import com.ouisani.aios.core.tool.Tool;
import com.ouisani.aios.core.tool.ToolContext;
import com.ouisani.aios.core.tool.ToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 权限检查器 — 对标 Claude Code 的 hasPermissionsToUseTool 流水线。
 * <p>
 * 执行顺序（对标 Claude Code 的 1a→1g, 2a→2b, 3）：
 * 1. deny 规则匹配 → 直接拒绝
 * 2. ask 规则匹配 → 需要用户确认
 * 3. 工具自身 checkPermissions() → 工具特定逻辑
 * 4. 安全路径检查（.git/, .claude/ 等）
 * 5. bypass/plan 模式 → 直接允许
 * 6. allow 规则匹配 → 允许
 * 7. 默认 → 询问
 * <p>
 * OS 类比：相当于 Linux 的 SELinux DAC + MAC 检查流水线。
 */
public class PermissionChecker {

    private static final Logger log = LoggerFactory.getLogger(PermissionChecker.class);

    private PermissionMode mode = PermissionMode.DEFAULT;
    private final List<PermissionRule> denyRules = new ArrayList<>();
    private final List<PermissionRule> askRules = new ArrayList<>();
    private final List<PermissionRule> allowRules = new ArrayList<>();
    private int consecutiveDenials = 0;
    private int totalDenials = 0;

    private static final int MAX_CONSECUTIVE_DENIALS = 3;
    private static final int MAX_TOTAL_DENIALS = 20;

    /** 安全敏感路径 */
    private static final Set<String> PROTECTED_PATHS = Set.of(
            ".git", ".claude", ".ssh", ".gnupg", ".env", ".aws"
    );

    /** Auto 模式安全白名单工具 */
    private static final Set<String> SAFE_AUTO_TOOLS = Set.of(
            "file_read", "grep", "glob", "web_fetch", "web_search"
    );

    /**
     * 工具行为分级 — 镜像 jcode {@code safety.rs:177-184} 的 {@code classify} 主入口。
     * <p>
     * 复用 {@link #SAFE_AUTO_TOOLS} 白名单做精确等值匹配（大小写不敏感）：
     * <ul>
     *   <li>命中白名单 → {@link ActionTier#AutoAllowed}（免询问自动放行）</li>
     *   <li>未命中或 null → {@link ActionTier#RequiresPermission}（须询问或异步裁决）</li>
     * </ul>
     * 这是 {@link PrivilegeSyscallFilter#askPermission} 的前置分级步骤，
     * 与既有 7 步流水线 {@link #checkPermission} 旁路共存，互不影响。
     */
    public static ActionTier classify(String toolName) {
        if (toolName == null || toolName.isBlank()) return ActionTier.RequiresPermission;
        return SAFE_AUTO_TOOLS.contains(toolName.toLowerCase())
                ? ActionTier.AutoAllowed : ActionTier.RequiresPermission;
    }

    /**
     * 检查工具调用权限。
     *
     * @return PermissionDecision 包含行为和原因
     */
    public <I extends ToolInput> PermissionDecision checkPermission(Tool<I> tool, I input, ToolContext context) {
        String toolName = tool.name();

        // ── 1a. deny 规则检查 ──
        for (PermissionRule rule : denyRules) {
            if (matchesRule(rule, toolName, input)) {
                log.debug("[Permission] Denied by rule: {} → {}", rule.toRuleString(), toolName);
                return PermissionDecision.deny("Denied by rule: " + rule.toRuleString(), "rule");
            }
        }

        // ── 1b. ask 规则检查 ──
        for (PermissionRule rule : askRules) {
            if (matchesRule(rule, toolName, input)) {
                if (mode == PermissionMode.DONT_ASK) {
                    return PermissionDecision.deny("Auto-denied (dontAsk mode)", "mode");
                }
                return PermissionDecision.ask("Tool '" + toolName + "' requires confirmation", "rule");
            }
        }

        // ── 1c. 工具自身权限检查 ──
        String toolCheck = tool.checkPermission(input, context);
        if (toolCheck != null) {
            return PermissionDecision.deny(toolCheck, "tool_check");
        }

        // ── 1d. 只读工具在 plan 模式下自动允许 ──
        if (mode == PermissionMode.PLAN && tool.readOnly()) {
            return PermissionDecision.allow("Read-only tool in plan mode", "mode");
        }

        // ── 1e. plan 模式下禁止写操作 ──
        if (mode == PermissionMode.PLAN && !tool.readOnly()) {
            return PermissionDecision.deny("Write operations not allowed in plan mode", "mode");
        }

        // ── 1f. 安全路径检查 ──
        if (!tool.readOnly()) {
            String pathError = checkProtectedPaths(input);
            if (pathError != null) {
                return PermissionDecision.deny(pathError, "safety_check");
            }
        }

        // ── 2a. bypass 模式 → 直接允许 ──
        if (mode == PermissionMode.BYPASS) {
            return PermissionDecision.allow("Bypass permissions mode", "mode");
        }

        // ── 2b. accept_edits + 写工具 → 自动允许 ──
        if (mode == PermissionMode.ACCEPT_EDITS && !tool.readOnly()) {
            return PermissionDecision.allow("Auto-accepted (acceptEdits mode)", "mode");
        }

        // ── 2c. allow 规则匹配 ──
        for (PermissionRule rule : allowRules) {
            if (matchesRule(rule, toolName, input)) {
                return PermissionDecision.allow("Allowed by rule: " + rule.toRuleString(), "rule");
            }
        }

        // ── 2d. auto 模式 + 安全白名单工具 → 自动允许 ──
        if (mode == PermissionMode.AUTO && SAFE_AUTO_TOOLS.contains(toolName)) {
            return PermissionDecision.allow("Auto-allowed (safe tool)", "mode");
        }

        // ── 3. 默认 → 询问 ──
        if (mode == PermissionMode.DONT_ASK) {
            return PermissionDecision.deny("Auto-denied (dontAsk mode)", "mode");
        }
        if (mode == PermissionMode.AUTO) {
            // Auto 模式下非白名单工具需要分类器决策，这里简化为允许
            return PermissionDecision.allow("Auto-allowed (auto mode)", "mode");
        }

        return PermissionDecision.ask("Tool '" + toolName + "' requires confirmation", "default");
    }

    /**
     * 规则匹配 — 检查规则是否匹配当前工具调用。
     */
    private boolean matchesRule(PermissionRule rule, String toolName, ToolInput input) {
        if (!rule.toolName().equalsIgnoreCase(toolName)) return false;
        if (rule.ruleContent() == null || rule.ruleContent().isEmpty()) return true;

        // Shell 规则匹配（Bash 工具）
        if ("bash".equalsIgnoreCase(toolName) && input instanceof BashToolLike bashInput) {
            return matchShellRule(rule.ruleContent(), bashInput.getCommand());
        }

        // 通用：规则内容作为子串匹配
        String inputStr = input.toJson().toLowerCase();
        return inputStr.contains(rule.ruleContent().toLowerCase());
    }

    /**
     * Shell 命令规则匹配 — 支持精确、前缀、通配符三种模式。
     */
    private boolean matchShellRule(String ruleContent, String command) {
        if (command == null) return false;
        String cmd = command.trim();

        // 通配符模式：git* → 匹配 git, git commit, git push 等
        if (ruleContent.endsWith("*")) {
            String prefix = ruleContent.substring(0, ruleContent.length() - 1);
            return cmd.startsWith(prefix);
        }

        // 前缀模式：npm: → 匹配 npm install, npm run 等
        if (ruleContent.endsWith(":") || ruleContent.endsWith(":*")) {
            String prefix = ruleContent.replace(":*", "").replace(":", "");
            return cmd.startsWith(prefix + " ");
        }

        // 精确匹配
        return cmd.equals(ruleContent) || cmd.startsWith(ruleContent + " ");
    }

    /**
     * 检查受保护路径。
     */
    private String checkProtectedPaths(ToolInput input) {
        String json = input.toJson().toLowerCase();
        for (String prot : PROTECTED_PATHS) {
            if (json.contains("/" + prot + "/") || json.contains("/" + prot + "\"")) {
                return "Access to " + prot + " directory is restricted for safety";
            }
        }
        return null;
    }

    // ── 状态管理 ──

    public void setMode(PermissionMode mode) { this.mode = mode; }
    public PermissionMode getMode() { return mode; }

    public void addRule(PermissionRule rule) {
        switch (rule.behavior()) {
            case DENY -> denyRules.add(rule);
            case ASK -> askRules.add(rule);
            case ALLOW -> allowRules.add(rule);
        }
    }

    public void clearRules() {
        denyRules.clear();
        askRules.clear();
        allowRules.clear();
    }

    public void recordDenial() {
        consecutiveDenials++;
        totalDenials++;
    }

    public void resetDenialStreak() { consecutiveDenials = 0; }

    public boolean isDenialLimitReached() {
        return consecutiveDenials >= MAX_CONSECUTIVE_DENIALS || totalDenials >= MAX_TOTAL_DENIALS;
    }

    /**
     * Bash 类工具输入的接口 — 用于 Shell 规则匹配。
     */
    public interface BashToolLike {
        String getCommand();
    }
}
