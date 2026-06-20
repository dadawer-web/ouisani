package com.ouisani.aios.core.llm;

import java.util.List;

/**
 * Noop LLM Provider — 借鉴 Langflow 的 Noop 服务降级设计。
 * <p>
 * 当所有 LLM 后端不可用（API Key 缺失、服务不可达）时，
 * LlmRouter 降级到此 Provider，返回预定义的降级响应，
 * 而不是直接崩溃抛出异常。
 * <p>
 * 类比操作系统：当 GPU 驱动不可用时，回退到 VGA 兼容模式。
 */
public class NoopLlmProvider implements LlmProvider {

    private static final String DEGRADED_RESPONSE =
        "[AIOS Degraded Mode] LLM service is currently unavailable. " +
        "This is a fallback response from NoopLlmProvider. " +
        "Please check your API key configuration and network connectivity.";

    @Override
    public String name() {
        return "noop-llm";
    }

    @Override
    public ComputeCore computeCore() {
        return ComputeCore.E_CORE;
    }

    @Override
    public String think(String prompt, String systemPrompt) {
        // 降级模式：返回提示信息而非崩溃
        return DEGRADED_RESPONSE;
    }

    @Override
    public String thinkWithHistory(List<ChatMessage> messages, String systemPrompt) {
        return DEGRADED_RESPONSE;
    }

    @Override
    public float[] embed(String text) {
        // 降级模式：使用 mockEmbed 生成伪向量
        return mockEmbed(text);
    }

    @Override
    public boolean isAvailable() {
        return true; // Noop 始终"可用"（降级模式）
    }
}
