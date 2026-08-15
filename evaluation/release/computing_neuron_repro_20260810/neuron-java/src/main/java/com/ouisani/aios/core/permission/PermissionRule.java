package com.ouisani.aios.core.permission;

/**
 * 权限规则 — 对标 Claude Code 的 PermissionRule。
 * <p>
 * 定义一条权限规则：某个工具的某个操作模式。
 * 例如：Bash(npm:*) → ALLOW 表示允许所有 npm 开头的命令。
 * <p>
 * OS 类比：相当于 Linux 的 SELinux 规则条目。
 *
 * @param source    规则来源（user/project/policy/session）
 * @param behavior  行为（allow/deny/ask）
 * @param toolName  工具名称（如 Bash, FileEdit, Agent）
 * @param ruleContent 规则内容（如 "npm:*", "git*", 具体命令等）
 */
public record PermissionRule(
        RuleSource source,
        PermissionBehavior behavior,
        String toolName,
        String ruleContent
) {
    /** 规则来源 */
    public enum RuleSource {
        USER_SETTINGS, PROJECT_SETTINGS, LOCAL_SETTINGS,
        POLICY_SETTINGS, CLI_ARG, SESSION, HOOK
    }

    /**
     * 从字符串解析规则，格式：ToolName(content) 或 ToolName
     * 例如：Bash(npm:*) → {toolName="Bash", ruleContent="npm:*"}
     *       FileRead → {toolName="FileRead", ruleContent=null}
     */
    public static PermissionRule parse(String source, PermissionBehavior behavior, String ruleStr) {
        int parenIdx = findFirstUnescaped(ruleStr, '(');
        if (parenIdx < 0) {
            return new PermissionRule(RuleSource.valueOf(source), behavior, ruleStr.trim(), null);
        }
        String toolName = ruleStr.substring(0, parenIdx).trim();
        int closeIdx = findLastUnescaped(ruleStr, ')');
        String content = closeIdx > parenIdx
                ? ruleStr.substring(parenIdx + 1, closeIdx).trim()
                : ruleStr.substring(parenIdx + 1).trim();
        return new PermissionRule(RuleSource.valueOf(source), behavior, toolName, content);
    }

    /**
     * 序列化为字符串。
     */
    public String toRuleString() {
        if (ruleContent == null || ruleContent.isEmpty()) return toolName;
        return toolName + "(" + ruleContent.replace(")", "\\)") + ")";
    }

    /** 查找第一个未转义的字符 */
    private static int findFirstUnescaped(String s, char c) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c && countPrecedingBackslashes(s, i) % 2 == 0) return i;
        }
        return -1;
    }

    /** 查找最后一个未转义的字符 */
    private static int findLastUnescaped(String s, char c) {
        for (int i = s.length() - 1; i >= 0; i--) {
            if (s.charAt(i) == c && countPrecedingBackslashes(s, i) % 2 == 0) return i;
        }
        return -1;
    }

    private static int countPrecedingBackslashes(String s, int idx) {
        int count = 0;
        for (int i = idx - 1; i >= 0 && s.charAt(i) == '\\'; i--) count++;
        return count;
    }
}
