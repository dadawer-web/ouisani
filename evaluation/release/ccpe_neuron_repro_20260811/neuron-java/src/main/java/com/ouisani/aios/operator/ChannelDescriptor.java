package com.ouisani.aios.operator;

/**
 * 渠道描述符 — 描述一个消息渠道的能力。
 * <p>
 * 对标 OpenClaw 的 ChannelPlugin 元数据。
 *
 * @param id            渠道唯一标识（如 "telegram", "discord", "webchat"）
 * @param name          人类可读名称
 * @param ownerPluginId 所属插件 ID
 * @param supportsText  是否支持文本消息
 * @param supportsImage 是否支持图片
 * @param supportsFile  是否支持文件
 * @param supportsVoice 是否支持语音
 */
public record ChannelDescriptor(
        String id,
        String name,
        String ownerPluginId,
        boolean supportsText,
        boolean supportsImage,
        boolean supportsFile,
        boolean supportsVoice
) {
    public ChannelDescriptor {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Channel id required");
        if (name == null || name.isBlank()) name = id;
        if (ownerPluginId == null) ownerPluginId = "";
    }
}
