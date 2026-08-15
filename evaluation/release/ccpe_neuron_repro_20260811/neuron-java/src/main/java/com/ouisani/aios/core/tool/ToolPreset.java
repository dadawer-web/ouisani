package com.ouisani.aios.core.tool;

import java.util.Set;

/**
 * 工具授权预设 — 借鉴 OMA (open-multi-agent) 的 toolPreset 设计。
 * <p>
 * 三档预设,对应不同信任级别的 Agent:
 * <ul>
 *   <li>{@link #READONLY} — 只读工具 (file_read, grep, glob),适合观察型 Agent</li>
 *   <li>{@link #READWRITE} — 读写+执行 (READONLY + file_write, file_edit, bash),适合工作型 Agent</li>
 *   <li>{@link #FULL} — 全部工具 (READWRITE + agent, web_scrape, security_scan, ...),信任型 Agent</li>
 * </ul>
 * <p>
 * <b>Default-Deny 原则</b>:Agent 不声明工具预设或显式工具列表时,grantedTools 为空集,
 * 即零工具访问权。这是 OMA 的核心安全不变量 —— 注册不等于授权。
 * <p>
 * OS 类比:相当于 Linux 的 capability 集合 (CAP_DAC_READ_SEARCH / CAP_DAC_OVERRIDE / CAP_SYS_ADMIN)。
 */
public enum ToolPreset {

    /** 只读预设 — 只能读文件和搜索,不能修改任何东西 */
    READONLY(Set.of(
            "file_read",
            "grep",
            "glob"
    )),

    /** 读写预设 — 可以读写文件和执行命令,但不能委托/上网/安全扫描 */
    READWRITE(Set.of(
            "file_read",
            "file_write",
            "file_edit",
            "bash",
            "grep",
            "glob"
    )),

    /** 完全预设 — 所有已注册工具,信任型 Agent 使用 */
    FULL(Set.of(
            "file_read",
            "file_write",
            "file_edit",
            "bash",
            "grep",
            "glob",
            "agent",
            "web_scrape",
            "content_pipeline",
            "deterministic_extract",
            "structured_extract",
            "handoff",
            "send_message",
            "security_scan",
            "lsp_tool",
            "frontend_tool",
            "ccr_retrieve",
            "config"
    ));

    private final Set<String> tools;

    ToolPreset(Set<String> tools) {
        this.tools = Set.copyOf(tools);
    }

    /**
     * 获取此预设包含的工具名集合 (不可变)。
     */
    public Set<String> tools() {
        return tools;
    }

    /**
     * 检查此预设是否包含指定工具。
     */
    public boolean contains(String toolName) {
        return tools.contains(toolName);
    }
}
