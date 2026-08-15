package com.ouisani.aios.core.overnight;

import com.ouisani.aios.core.permission.PermissionBehavior;
import com.ouisani.aios.core.permission.PermissionMode;
import com.ouisani.aios.core.permission.PermissionProfile;
import com.ouisani.aios.core.permission.PermissionRule;

import java.util.List;

/**
 * Overnight 权限画像工厂 — 把散落在 prompt / TaskCard 里的硬约束收编为结构化规则。
 * <p>
 * 借鉴 AgentScope 2.0 的 DONT_ASK 模式：所有 ASK 自动转 DENY（无人值守），
 * 保留 suggestedRules 供晨报呈现给用户"加什么规则能放行"。
 * <p>
 * <b>设计原则</b>（对齐项目记忆里的 overnight 硬约束）：
 * <ul>
 *   <li>绝对禁止项：rm -rf、远程推送、curl/wget、写敏感文件（.env/.aws/.ssh/.git）、spawn 子 agent</li>
 *   <li>allow 白名单：verified/low-risk 工具（只读分析 + 限定 Bash 子命令）</li>
 *   <li>未在 allow 白名单内的工具 → 默认 DENY（DONT_ASK 兜底）</li>
 *   <li>read-only 工具自动 ALLOW（由 PermissionChecker.checkReadOnlyFastPath 保证）</li>
 * </ul>
 * <p>
 * 通过 {@link com.ouisani.aios.core.tool.QueryEngine#QueryEngine(
 * com.ouisani.aios.core.tool.ToolSdk, String, String, List, PermissionProfile)}
 * 注入到子 agent 的 QueryEngine。
 */
public final class OvernightPermissionProfile {

    private OvernightPermissionProfile() {}

    /**
     * 构造 overnight DONT_ASK 权限画像。
     * <p>
     * 返回的画像是不可变的（{@link PermissionProfile#denyRules()} 等都 List.copyOf 过），
     * 多个并发子 agent 可共享同一实例。
     */
    public static PermissionProfile build() {
        return new PermissionProfile(
                PermissionMode.DONT_ASK,
                // ── deny：绝对禁止项（项目记忆里的 overnight 硬约束） ──
                List.of(
                        // 数据删除 / 破坏性命令
                        rule(PermissionBehavior.DENY, "Bash", "rm:*"),
                        rule(PermissionBehavior.DENY, "Bash", "rmdir:*"),
                        rule(PermissionBehavior.DENY, "Bash", "mv:*"),       // 防覆盖
                        rule(PermissionBehavior.DENY, "Bash", "chmod:*"),
                        rule(PermissionBehavior.DENY, "Bash", "chown:*"),
                        rule(PermissionBehavior.DENY, "Bash", ":(){ :|:& };:"),  // fork bomb
                        // 远程推送 / 网络（远程写）
                        rule(PermissionBehavior.DENY, "Bash", "git push:*"),
                        rule(PermissionBehavior.DENY, "Bash", "git force:*"),
                        rule(PermissionBehavior.DENY, "Bash", "curl:*"),
                        rule(PermissionBehavior.DENY, "Bash", "wget:*"),
                        rule(PermissionBehavior.DENY, "Bash", "scp:*"),
                        rule(PermissionBehavior.DENY, "Bash", "rsync:*"),
                        // 包管理 / 系统配置
                        rule(PermissionBehavior.DENY, "Bash", "apt:*"),
                        rule(PermissionBehavior.DENY, "Bash", "apt-get:*"),
                        rule(PermissionBehavior.DENY, "Bash", "pip install:*"),
                        rule(PermissionBehavior.DENY, "Bash", "npm install:*"),
                        rule(PermissionBehavior.DENY, "Bash", "systemctl:*"),
                        rule(PermissionBehavior.DENY, "Bash", "service:*"),
                        // 敏感文件写入
                        rule(PermissionBehavior.DENY, "FileEdit", ".env"),
                        rule(PermissionBehavior.DENY, "FileEdit", ".aws/*"),
                        rule(PermissionBehavior.DENY, "FileEdit", ".ssh/*"),
                        rule(PermissionBehavior.DENY, "FileEdit", ".git/*"),
                        rule(PermissionBehavior.DENY, "FileEdit", ".gnupg/*"),
                        // 防 spawn 子 agent 失控
                        rule(PermissionBehavior.DENY, "Agent", null)
                ),
                // ── ask：空（DONT_ASK 模式下 ask 自动转 DENY，等价 deny，无需重复配置） ──
                List.of(),
                // ── allow：verified/low-risk 工具白名单 ──
                List.of(
                        // 只读分析工具（理论上由 read-only fast path 自动放行，显式 allow 作为双保险）
                        rule(PermissionBehavior.ALLOW, "file_read", null),
                        rule(PermissionBehavior.ALLOW, "grep", null),
                        rule(PermissionBehavior.ALLOW, "glob", null),
                        rule(PermissionBehavior.ALLOW, "web_search", null),
                        rule(PermissionBehavior.ALLOW, "web_fetch", null),
                        // 只读 Bash 子命令（限定范围）
                        rule(PermissionBehavior.ALLOW, "Bash", "ls:*"),
                        rule(PermissionBehavior.ALLOW, "Bash", "cat:*"),
                        rule(PermissionBehavior.ALLOW, "Bash", "head:*"),
                        rule(PermissionBehavior.ALLOW, "Bash", "tail:*"),
                        rule(PermissionBehavior.ALLOW, "Bash", "wc:*"),
                        rule(PermissionBehavior.ALLOW, "Bash", "git status"),
                        rule(PermissionBehavior.ALLOW, "Bash", "git diff:*"),
                        rule(PermissionBehavior.ALLOW, "Bash", "git log:*"),
                        rule(PermissionBehavior.ALLOW, "Bash", "mvn test:*"),
                        rule(PermissionBehavior.ALLOW, "Bash", "rg:*"),
                        rule(PermissionBehavior.ALLOW, "Bash", "find:*")
                )
        );
    }

    /** 简化规则构造 — source 固定为 POLICY_SETTINGS。 */
    private static PermissionRule rule(PermissionBehavior behavior, String toolName, String ruleContent) {
        return new PermissionRule(
                PermissionRule.RuleSource.POLICY_SETTINGS,
                behavior,
                toolName,
                ruleContent);
    }
}
