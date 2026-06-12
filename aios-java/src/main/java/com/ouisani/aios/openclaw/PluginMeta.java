package com.ouisani.aios.openclaw;

/**
 * 插件元数据 — 对标 OpenClaw 的 openclaw.plugin.json 清单文件。
 *
 * @param id          插件唯一标识（如 "telegram", "openai", "browser"）
 * @param name        人类可读名称
 * @param description 插件描述
 * @param version     版本号
 */
public record PluginMeta(
        String id,
        String name,
        String description,
        String version
) {
    public PluginMeta {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Plugin id required");
        if (name == null || name.isBlank()) name = id;
        if (description == null) description = "";
        if (version == null) version = "0.0.1";
    }
}
