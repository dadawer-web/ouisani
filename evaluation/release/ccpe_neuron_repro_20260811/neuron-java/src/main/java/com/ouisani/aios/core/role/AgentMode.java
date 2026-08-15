package com.ouisani.aios.core.role;

/**
 * Agent 运行模式 — 对标 OpenScience {@code agent.ts} 的 {@code mode} 字段。
 * <p>
 * 标注 Agent 的调度身份，区分主 agent 与子 agent。当前为**存储元数据**（不强制调度门控），
 * 用于 RoleBlueprint 描述角色的预期用途。
 *
 * <h3>取值</h3>
 * <ul>
 *   <li>{@link #PRIMARY} — 主 agent，可被用户直接拉起</li>
 *   <li>{@link #SUBAGENT} — 子 agent，仅可被父 agent 委托拉起（如 reviewer）</li>
 *   <li>{@link #ALL} — 既能主拉起也能作子 agent</li>
 *   <li>{@link #SYSTEM_HIDDEN} — 系统内置且对用户隐藏（如 compaction/title）</li>
 * </ul>
 */
public enum AgentMode {
    PRIMARY,
    SUBAGENT,
    ALL,
    SYSTEM_HIDDEN;

    /**
     * 从字符串解析模式，未知/空 → {@link #PRIMARY}。
     */
    public static AgentMode fromString(String s) {
        if (s == null || s.isBlank()) return PRIMARY;
        String t = s.trim();
        for (var m : values()) {
            if (m.name().equalsIgnoreCase(t)) return m;
        }
        return PRIMARY;
    }
}
