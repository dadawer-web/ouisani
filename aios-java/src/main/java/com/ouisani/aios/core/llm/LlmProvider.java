package com.ouisani.aios.core.llm;

import java.util.List;

/**
 * LLM 提供者接口 — AIOS 的"硬件驱动抽象层"。
 * <p>
 * 类比操作系统中的设备驱动接口：OS 不直接操作硬件，而是通过统一的驱动接口
 * 访问不同厂商的设备。同样，AIOS 通过 LlmProvider 接口屏蔽不同 LLM 后端
 * （OpenAI、Claude、本地模型等）的差异，上层只需调用 {@link #think} 和
 * {@link #embed}，无需关心底层 API 细节。
 *
 * <h3>OS 类比</h3>
 * <table>
 *   <tr><th>OS 概念</th><th>AIOS 概念</th><th>说明</th></tr>
 *   <tr><td>设备驱动接口</td><td>LlmProvider</td><td>统一硬件/模型访问</td></tr>
 *   <tr><td>read()/write()</td><td>think()/embed()</td><td>标准 I/O 操作</td></tr>
 *   <tr><td>设备能力查询</td><td>computeCore()</td><td>查询设备性能层级</td></tr>
 * </table>
 *
 * @see LlmRouter
 * @see ComputeCore
 */
public interface LlmProvider {

    /** 返回此 Provider 的名称标识 */
    String name();

    /**
     * 返回此 Provider 对应的算力核心层级。
     * <p>
     * P_CORE Provider（如 GPT-4o）返回 {@link ComputeCore#P_CORE}，
     * E_CORE Provider（如 GPT-4o-mini）返回 {@link ComputeCore#E_CORE}。
     * 默认为 P_CORE（向后兼容）。
     */
    default ComputeCore computeCore() {
        return ComputeCore.P_CORE;
    }

    /**
     * 向 LLM 发送推理请求（含系统提示词）。
     *
     * @param prompt       用户提示词
     * @param systemPrompt 系统提示词（定义角色和行为约束）
     * @return LLM 生成的文本回复
     */
    String think(String prompt, String systemPrompt);

    /** 向 LLM 发送推理请求（无系统提示词） */
    default String think(String prompt) {
        return think(prompt, "");
    }

    /** 聊天消息记录，包含角色和内容 */
    record ChatMessage(String role, String content) {
        /** 创建用户消息 */
        public static ChatMessage user(String content) {
            return new ChatMessage("user", content);
        }

        /** 创建系统消息 */
        public static ChatMessage system(String content) {
            return new ChatMessage("system", content);
        }

        /** 创建助手消息 */
        public static ChatMessage assistant(String content) {
            return new ChatMessage("assistant", content);
        }
    }

    /**
     * 基于多轮对话历史向 LLM 发送推理请求。
     *
     * @param messages     对话消息列表
     * @param systemPrompt 系统提示词
     * @return LLM 生成的文本回复
     */
    String thinkWithHistory(List<ChatMessage> messages, String systemPrompt);

    /**
     * 将文本转换为嵌入向量。
     *
     * @param text 输入文本
     * @return 嵌入向量（浮点数组）
     */
    float[] embed(String text);

    /**
     * 模拟嵌入向量生成 — 当真实 Embedding API 不可用时的降级方案。
     * <p>
     * 使用确定性伪随机算法（基于文本哈希）生成固定维度的向量，
     * 保证相同输入始终产生相同输出，但向量不具备语义信息。
     */
    default float[] mockEmbed(String text) {
        int dimensions = 1536;
        float[] vector = new float[dimensions];
        int hash = text != null ? text.hashCode() : 0;
        // 使用线性同余伪随机数生成器（LCG），保证确定性
        long seed = hash != 0 ? Math.abs(hash) : 42;
        for (int i = 0; i < dimensions; i++) {
            seed = (seed * 6364136223846793005L + 1442695040888963407L);
            vector[i] = ((float) ((seed >>> 33) & 0x7FFFFFFF) / 0x7FFFFFFF - 0.5f) * 0.1f;
        }
        return vector;
    }

    /** 检查此 Provider 是否可用（API Key 已配置且服务可达） */
    boolean isAvailable();
}
