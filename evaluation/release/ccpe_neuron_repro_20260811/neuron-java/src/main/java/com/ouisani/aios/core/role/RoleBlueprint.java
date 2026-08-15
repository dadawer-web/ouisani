package com.ouisani.aios.core.role;

import com.ouisani.aios.core.permission.PermissionProfile;

/**
 * 角色蓝图 — aios_roles 的结构化运行时定义，对标 OpenScience {@code agent.ts} 的 {@code Agent.Info}。
 * <p>
 * 与既有 {@code AgentBlueprint}（Omnifactory 代码载荷模板，Dockerfile 类比，4 字段，7+ 处引用）
 * 是<b>不同概念</b> —— 命名冲突通过新建本类解决，零回归。
 * <p>
 * 三维分层（借鉴 OpenScience mode/permission/model）：
 * <ul>
 *   <li>{@code mode} — 调度身份（PRIMARY/SUBAGENT/ALL/SYSTEM_HIDDEN）。<b>存储元数据，不接线调度门控</b></li>
 *   <li>{@code permissionProfile} — 权限画像。<b>唯一接线的维度</b>，经
 *       {@link com.ouisani.aios.core.tool.QueryEngine#QueryEngine(
 *       com.ouisani.aios.core.tool.ToolSdk, String, String,
 *       java.util.List, PermissionProfile)} 注入 PermissionChecker</li>
 *   <li>{@code model}/{@code temperature}/{@code steps} — 模型/温度/步数预算。<b>存储但不接线</b>
 *       （LLM 调用路径未读取），留作 follow-up</li>
 * </ul>
 * <p>
 * 特别地，reviewer 子 agent 的 {@code *:deny + 只读工具白名单} 通过 {@link PermissionProfile}
 * 在权限层强制 blindness —— 这与 {@link com.ouisani.aios.core.security.BpfManager} 的
 * SecurityToken 思路一致（能力由结构化令牌保证，而非 prompt 文字）。
 *
 * @param name              角色名（key）
 * @param description       角色描述
 * @param prompt            角色 prompt 素材（null 表示无，沿用 AiosAppManager 的原文拼接）
 * @param mode              调度身份；null → PRIMARY
 * @param hidden            是否对用户隐藏
 * @param model             模型覆盖；null 表示不覆盖
 * @param temperature       温度覆盖；{@link #DEFAULT_TEMPERATURE} 表示不覆盖
 * @param steps             步数预算；{@link #DEFAULT_STEPS} 表示不覆盖
 * @param permissionProfile 权限画像；null → empty（no-op）
 */
public record RoleBlueprint(
        String name,
        String description,
        String prompt,
        AgentMode mode,
        boolean hidden,
        String model,
        double temperature,
        int steps,
        PermissionProfile permissionProfile
) {

    /** 温度默认哨兵 —— 表示"不覆盖" */
    public static final double DEFAULT_TEMPERATURE = -1.0;
    /** 步数默认哨兵 —— 表示"不覆盖" */
    public static final int DEFAULT_STEPS = -1;

    public RoleBlueprint {
        if (mode == null) mode = AgentMode.PRIMARY;
        if (permissionProfile == null) permissionProfile = PermissionProfile.empty();
    }

    /**
     * 便捷构造 —— 无运行时块（向后兼容，等价于无 runtime: 的旧 yaml）。
     */
    public RoleBlueprint(String name, String description, String prompt) {
        this(name, description, prompt, AgentMode.PRIMARY, false, null,
                DEFAULT_TEMPERATURE, DEFAULT_STEPS, PermissionProfile.empty());
    }
}
