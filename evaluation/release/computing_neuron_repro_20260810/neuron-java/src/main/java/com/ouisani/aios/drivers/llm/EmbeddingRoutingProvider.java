package com.ouisani.aios.drivers.llm;

import com.ouisani.aios.core.llm.ComputeCore;
import com.ouisani.aios.core.llm.LlmProvider;

import java.util.List;
import java.util.function.Consumer;

/**
 * Embedding 路由复合 Provider — 把 embed 与 think 路由到不同后端。
 * <p>
 * 解决：VfsManager 的 embed 路径切到本地 ONNX（{@link LocalOnnxEmbedder}），
 * 而 chat 路径仍走 OpenAI（{@link OpenAiAdapter}），无需分裂 VfsManager/VectorNode/SemanticNode。
 * <ul>
 *   <li>{@code embed(text)} → embedProvider（本地 ONNX，零外部 API）</li>
 *   <li>{@code think*()} → chatProvider（保留远程 chat 能力）</li>
 *   <li>{@code isAvailable()} → chatProvider 可用性决定（chat 是主路径）</li>
 * </ul>
 * 当本地 ONNX 不可用时，bootstrap 把 embedProvider 设为同一 chatProvider，
 * 退化为单 Provider 行为（兼容原 EMBEDDING_API_KEY 路径）。
 */
public class EmbeddingRoutingProvider implements LlmProvider {

    private final LlmProvider chatProvider;
    private final LlmProvider embedProvider;

    public EmbeddingRoutingProvider(LlmProvider chatProvider, LlmProvider embedProvider) {
        this.chatProvider = chatProvider;
        this.embedProvider = embedProvider;
    }

    @Override
    public String name() {
        return "embedding_routing";
    }

    @Override
    public ComputeCore computeCore() {
        return chatProvider.computeCore();
    }

    @Override
    public float[] embed(String text) {
        return embedProvider.embed(text);
    }

    @Override
    public String think(String prompt, String systemPrompt) {
        return chatProvider.think(prompt, systemPrompt);
    }

    @Override
    public String think(String prompt) {
        return chatProvider.think(prompt);
    }

    @Override
    public String thinkWithHistory(List<ChatMessage> messages, String systemPrompt) {
        return chatProvider.thinkWithHistory(messages, systemPrompt);
    }

    @Override
    public String thinkStream(String prompt, String systemPrompt, Consumer<String> onDelta) {
        return chatProvider.thinkStream(prompt, systemPrompt, onDelta);
    }

    @Override
    public String thinkStream(String prompt, Consumer<String> onDelta) {
        return chatProvider.thinkStream(prompt, onDelta);
    }

    @Override
    public boolean isAvailable() {
        return chatProvider.isAvailable();
    }

    /** 是否已将 embed 路由到独立 Provider（非 chatProvider 自身）。 */
    public boolean isEmbedRouted() {
        return embedProvider != chatProvider;
    }

    @Override
    public String toString() {
        return "EmbeddingRoutingProvider{chat=" + chatProvider.name()
                + ", embed=" + embedProvider.name()
                + (isEmbedRouted() ? " (routed)" : " (unified)") + "}";
    }
}
