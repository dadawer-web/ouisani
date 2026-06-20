package com.ouisani.aios.core.permission;

/**
 * 权限模式 — 对标 Claude Code 的 PermissionMode。
 * <p>
 * 控制工具调用时的权限检查行为：
 * - DEFAULT: 每次工具调用都需用户确认
 * - PLAN: 只允许只读工具，禁止修改操作
 * - AUTO: 自动决策（基于分类器），减少交互
 * - ACCEPT_EDITS: 自动接受文件编辑，其他仍需确认
 * - BYPASS: 绕过所有权限检查（危险！）
 * - DONT_ASK: 自动拒绝所有需要确认的请求
 * <p>
 * OS 类比：相当于 Linux 的 Capability 集合 + Seccomp 模式。
 */
public enum PermissionMode {

    /** 默认模式 — 每次工具调用都需用户确认 */
    DEFAULT("default", "Default", "?", false),

    /** 计划模式 — 只允许只读工具 */
    PLAN("plan", "Plan", "P", false),

    /** 自动模式 — 分类器自动决策 */
    AUTO("auto", "Auto", "A", false),

    /** 自动接受编辑 — 文件编辑自动通过 */
    ACCEPT_EDITS("acceptEdits", "Accept Edits", "E", false),

    /** 绕过所有权限 — 危险！仅限可信环境 */
    BYPASS("bypassPermissions", "Bypass", "!", true),

    /** 不询问 — 自动拒绝所有需要确认的请求 */
    DONT_ASK("dontAsk", "Don't Ask", "X", false);

    private final String key;
    private final String title;
    private final String symbol;
    private final boolean dangerous;

    PermissionMode(String key, String title, String symbol, boolean dangerous) {
        this.key = key;
        this.title = title;
        this.symbol = symbol;
        this.dangerous = dangerous;
    }

    public String key() { return key; }
    public String title() { return title; }
    public String symbol() { return symbol; }
    public boolean isDangerous() { return dangerous; }

    /**
     * 从字符串解析权限模式，默认返回 DEFAULT。
     */
    public static PermissionMode fromString(String s) {
        if (s == null || s.isBlank()) return DEFAULT;
        for (var mode : values()) {
            if (mode.key.equalsIgnoreCase(s.trim()) || mode.name().equalsIgnoreCase(s.trim())) {
                return mode;
            }
        }
        return DEFAULT;
    }

    /**
     * 是否允许写操作。
     */
    public boolean allowsWrite() {
        return this == BYPASS || this == ACCEPT_EDITS || this == AUTO;
    }

    /**
     * 是否跳过权限提示。
     */
    public boolean skipsPrompts() {
        return this == BYPASS || this == DONT_ASK || this == AUTO;
    }
}
