package com.ouisani.aios.core.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

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

    /**
     * 聊天消息记录，支持纯文本和多模态内容。
     * <p>
     * content 可以是纯文本字符串，也可以是 OpenAI 多模态格式的 JsonArray
     * （包含 text 和 image_url 类型的内容块）。
     */
    record ChatMessage(String role, Object content) {

        /** 创建用户消息（纯文本） */
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

        /**
         * 创建包含图片的用户消息（OpenAI 多模态格式）。
         *
         * @param text      文本描述
         * @param imageBase64 图片的 Base64 编码（JPEG/PNG）
         * @param imageUrl   图片 URL（与 imageBase64 二选一）
         * @return 多模态用户消息
         */
        public static ChatMessage userWithImage(String text, String imageBase64, String imageUrl) {
            JsonArray contentArray = new JsonArray();

            JsonObject textBlock = new JsonObject();
            textBlock.addProperty("type", "text");
            textBlock.addProperty("text", text);
            contentArray.add(textBlock);

            JsonObject imageBlock = new JsonObject();
            imageBlock.addProperty("type", "image_url");
            JsonObject imageUrlObj = new JsonObject();
            if (imageBase64 != null && !imageBase64.isEmpty()) {
                imageUrlObj.addProperty("url", "data:image/jpeg;base64," + imageBase64);
            } else if (imageUrl != null && !imageUrl.isEmpty()) {
                imageUrlObj.addProperty("url", imageUrl);
            }
            imageBlock.add("image_url", imageUrlObj);
            contentArray.add(imageBlock);

            return new ChatMessage("user", contentArray);
        }

        /** 获取纯文本内容（兼容旧代码） */
        public String contentAsString() {
            if (content instanceof String s) return s;
            if (content instanceof JsonArray arr) {
                // 提取第一个 text 块
                for (var elem : arr) {
                    if (elem.isJsonObject()) {
                        JsonObject obj = elem.getAsJsonObject();
                        if ("text".equals(obj.get("type").getAsString())) {
                            return obj.get("text").getAsString();
                        }
                    }
                }
            }
            return content != null ? content.toString() : "";
        }

        /** 是否为多模态消息（包含图片） */
        public boolean isMultimodal() {
            return content instanceof JsonArray;
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
