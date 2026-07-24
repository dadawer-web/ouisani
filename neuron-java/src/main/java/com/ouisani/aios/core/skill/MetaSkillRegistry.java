package com.ouisani.aios.core.skill;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MetaSkill 注册表 — 全局管理已注册的 meta-skill 定义。
 * <p>
 * 与 {@link SkillLoader}（自动发现 SKILL.md）不同，MetaSkillRegistry 当前仅支持
 * 编程式注册。未来可扩展为从 {@code META_SKILL.md} frontmatter 自动加载。
 * <p>
 * OS 类比：相当于 Linux 的 {@code /etc/init.d/} 目录 —
 * 存放可被 init 调起的运行级脚本（MetaSkill）。
 * <p>
 * 线程安全：使用 {@link ConcurrentHashMap}，注册/查询可并发。
 */
public final class MetaSkillRegistry {

    private static final class Holder {
        static final MetaSkillRegistry INSTANCE = new MetaSkillRegistry();
    }

    public static MetaSkillRegistry instance() {
        return Holder.INSTANCE;
    }

    private final Map<String, MetaSkill> registry = new ConcurrentHashMap<>();

    private MetaSkillRegistry() {
        // 注册内置 meta-skill
        register(MetaSkills.ai4sAgent());
    }

    /**
     * 注册一个 meta-skill。同名覆盖。
     *
     * @param meta meta-skill 定义
     */
    public void register(MetaSkill meta) {
        if (meta == null) return;
        registry.put(meta.name(), meta);
    }

    /**
     * 按名称查找 meta-skill。
     *
     * @param name meta-skill 名
     * @return meta-skill 定义（可能为空）
     */
    public java.util.Optional<MetaSkill> get(String name) {
        if (name == null || name.isBlank()) return java.util.Optional.empty();
        return java.util.Optional.ofNullable(registry.get(name));
    }

    /**
     * 列出所有已注册的 meta-skill。
     */
    public Map<String, MetaSkill> all() {
        return Map.copyOf(registry);
    }

    /**
     * 注销一个 meta-skill。
     *
     * @param name meta-skill 名
     * @return 被移除的 meta-skill（可能为空）
     */
    public java.util.Optional<MetaSkill> unregister(String name) {
        if (name == null) return java.util.Optional.empty();
        return java.util.Optional.ofNullable(registry.remove(name));
    }

    /** 已注册数量 */
    public int size() {
        return registry.size();
    }
}
