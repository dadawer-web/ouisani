package com.ouisani.aios.openclaw;

/**
 * Provider 描述符 — 描述一个 LLM/嵌入/语音 Provider。
 * <p>
 * 对标 OpenClaw 的 Provider 注册。
 *
 * @param id            Provider 唯一标识（如 "openai", "anthropic", "ollama"）
 * @param name          人类可读名称
 * @param ownerPluginId 所属插件 ID
 * @param type          Provider 类型（"llm", "embedding", "speech", "image"）
 * @param models        支持的模型列表
 */
public record ProviderDescriptor(
        String id,
        String name,
        String ownerPluginId,
        String type,
        String[] models
) {
    public ProviderDescriptor {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Provider id required");
        if (name == null || name.isBlank()) name = id;
        if (ownerPluginId == null) ownerPluginId = "";
        if (type == null) type = "llm";
        if (models == null) models = new String[0];
    }
}
