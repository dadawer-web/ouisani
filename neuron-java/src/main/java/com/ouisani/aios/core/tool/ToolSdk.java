package com.ouisani.aios.core.tool;

import java.util.function.Consumer;

/**
 * 工具层 LLM/IO 访问契约 — 内核工具子系统对用户态 SDK 的最小依赖面。
 * <p>
 * 依赖倒置原则（DIP）：{@code core/tool/} 定义此接口，
 * {@code user/sdk/AiosSdk} 实现它。这样 {@code core/tool/} 不再
 * 依赖 {@code user.sdk} 包，架构方向保持 "core ← user"。
 * <p>
 * OS 类比：相当于内核导出的系统调用原型 — 用户态 libc 实现这些原型，
 * 内核代码只依赖原型不依赖具体实现。
 */
public interface ToolSdk {
    /**
     * 向 LLM 提问，返回响应文本。
     *
     * @param agentId 调用方 Agent ID
     * @param prompt  提示词
     * @return LLM 响应文本
     */
    String think(String agentId, String prompt);

    /**
     * 流式推理 — LLM 响应逐 token 回调。
     *
     * @param agentId  Agent ID
     * @param prompt   提示词
     * @param onDelta  每个 token 片段的回调
     * @return 完整的文本回复
     */
    String thinkStream(String agentId, String prompt, Consumer<String> onDelta);

    /**
     * 向 VFS 路径写入数据。
     *
     * @param agentId 调用方 Agent ID
     * @param path    VFS 路径
     * @param data    要写入的数据
     */
    void writeFile(String agentId, String path, String data);
}
