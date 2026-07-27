package com.ouisani.aios.core.skill;

/**
 * 技能供应商标识枚举 — Cap 模型新增字段，标识"谁发布"该能力。
 * <p>
 * 与既有 {@link SkillLoader.SkillSource} 是<b>不同维度</b>：
 * <ul>
 *   <li>{@code SkillSource} — <b>加载来源</b>（从哪个目录读到的：PROJECT/BUNDLED/USER/LEARNED...），
 *       描述文件系统位置</li>
 *   <li>{@code ProviderId} — <b>发布者身份</b>（谁写的：AIOS_CORE/COMMUNITY/VENDOR/USER），
 *       描述信任级别与命名空间归属</li>
 * </ul>
 * 同一份 SKILL.md 可由 USER 目录加载（source=USER）但 providerId=COMMUNITY
 * （从社区 fork 过来本地化的）—— 两者正交。
 *
 * <h3>OS 类比</h3>
 * 相当于 Linux 包管理器的 origin 字段：
 * <ul>
 *   <li>{@link #AIOS_CORE} — main 仓库官方包（信任级别最高）</li>
 *   <li>{@link #COMMUNITY} — universe 仓库社区包</li>
 *   <li>{@link #VENDOR} — 第三方供应商商业包（需 vendor 命名空间）</li>
 *   <li>{@link #USER} — 用户本地自建包（~/.aios/skills/ 手写）</li>
 * </ul>
 * 该枚举配合 {@code author} 命名空间字段使用：providerId=VENDOR 时 author 应为
 * vendor 命名空间（如 {@code "vendor.acme.tools"}），便于后续做来源校验与
 * 沙箱策略差异化（如 VENDOR 包禁用 file_write）。
 *
 * <h3>默认值</h3>
 * frontmatter 缺失 {@code provider-id} 字段时降级为 {@link #AIOS_CORE}，
 * 保证存量 SKILL.md 零回归（视为官方内置）。
 *
 * @see SkillCap
 * @see SkillLoader.SkillSource
 */
public enum ProviderId {
    /** 官方核心仓库发布（{aisos/skills/、classpath resources/skills/}）— 信任级别最高 */
    AIOS_CORE,

    /** 社区发布（开源仓库 fork / PR 合入）— 信任级别中 */
    COMMUNITY,

    /** 第三方供应商商业发布 — 需 vendor 命名空间校验 */
    VENDOR,

    /** 用户本地自建 — 信任级别最低，沙箱策略最严 */
    USER;

    /**
     * 从 frontmatter 字符串解析 — 大小写不敏感，未知值降级为 {@link #AIOS_CORE}
     * （best-effort，不抛异常以保证加载链不中断）。
     *
     * @param raw frontmatter 原值（可空）
     * @return 匹配的枚举；null/空/未知 → {@link #AIOS_CORE}
     */
    public static ProviderId fromString(String raw) {
        if (raw == null || raw.isBlank()) return AIOS_CORE;
        try {
            return ProviderId.valueOf(raw.trim().toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException ignored) {
            return AIOS_CORE;
        }
    }
}
