package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.vfs.VectorNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自动上下文注入器 — 透明地为 LLM 提示词注入相关背景知识。
 * <p>
 * 类比 OS 的透明大页（THP）或预取机制：内核在后台自动完成
 * 内存页的合并/预取，应用程序完全无感知。
 * <p>
 * ContextInjector 做同样的事：当 Agent 发起 {@code llm.think} 系统调用时，
 * ContextInjector 拦截提示词，从向量记忆 ({@code /dev/vec_mem}) 中
 * 检索 Top-3 相似条目，将匹配结果格式化后前置注入到原始提示词中，
 * 确保 Agent 始终能访问到最相关的长期记忆，无需显式调用检索。
 *
 * <h3>OS 类比</h3>
 * <table>
 *   <tr><th>OS 概念</th><th>AIOS ContextInjector</th><th>说明</th></tr>
 *   <tr><td>透明预取</td><td>augmentPrompt()</td><td>自动注入背景知识</td></tr>
 *   <tr><td>Page Cache</td><td>/dev/vec_mem</td><td>向量记忆缓存</td></tr>
 *   <tr><td>预读窗口</td><td>TOP_K = 3</td><td>检索数量</td></tr>
 * </table>
 */
public final class ContextInjector {

    private static final Logger log = LoggerFactory.getLogger(ContextInjector.class);

    private static final String VEC_MEM_PATH = "/dev/vec_mem";
    private static final int TOP_K = 3;
    private static final double SIMILARITY_THRESHOLD = 0.5;

    // Pattern to extract text and similarity from VectorNode.search() JSON output
    private static final Pattern RESULT_PATTERN = Pattern.compile(
            "\"similarity\":([0-9.]+),\"text\":\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static final class Holder {
        static final ContextInjector INSTANCE = new ContextInjector();
    }

    public static ContextInjector getInstance() {
        return Holder.INSTANCE;
    }

    private ContextInjector() {}

    /**
     * 为提示词注入相关背景知识 — 从向量记忆中检索并前置注入。
     * <p>
     * 如果找到高相似度条目，格式化为：
     * <pre>
     * [System Augmented Memory:
     *   1. (0.87) 知识文本
     *   2. (0.72) 另一条相关知识
     * ]
     * </pre>
     * 前置到原始提示词之前。
     *
     * @param originalPrompt Agent 的原始提示词
     * @return 增强后的提示词（无相关记忆时返回原始提示词）
     */
    public String augmentPrompt(String originalPrompt) {
        if (originalPrompt == null || originalPrompt.isBlank()) {
            return originalPrompt;
        }

        VectorNode vecMem = resolveVectorMemory();
        if (vecMem == null) {
            log.debug("[Context Injector] /dev/vec_mem 不可用，跳过增强");
            return originalPrompt;
        }

        if (vecMem.recordCount() == 0) {
            log.debug("[Context Injector] Vector Memory 为空，跳过增强");
            return originalPrompt;
        }

        try {
            String searchResult = vecMem.search(originalPrompt, TOP_K);
            String augmentedMemory = extractRelevantMemory(searchResult);

            if (augmentedMemory == null || augmentedMemory.isEmpty()) {
                log.debug("[Context Injector] 未找到高相似度结果，Prompt 未变更");
                return originalPrompt;
            }

            String augmented = "[System Augmented Memory:\n" + augmentedMemory + "]\n\n" + originalPrompt;

            log.info("[Context Injector] 已透明增强 Prompt（Vector Memory）！");
            System.out.printf("  🧠 [Context Injector] 已透明增强 Prompt（Vector Memory）！%n");

            return augmented;
        } catch (Exception e) {
            log.warn("[Context Injector] 增强失败，使用原始 Prompt: {}", e.getMessage());
            return originalPrompt;
        }
    }

    private VectorNode resolveVectorMemory() {
        var nodeOpt = VfsManager.instance().resolve(VEC_MEM_PATH);
        if (nodeOpt.isEmpty()) {
            return null;
        }
        if (nodeOpt.get() instanceof VectorNode vecNode) {
            return vecNode;
        }
        return null;
    }

    /**
     * 解析向量搜索的 JSON 结果，提取超过相似度阈值的条目。
     */
    private String extractRelevantMemory(String searchResult) {
        if (searchResult == null || searchResult.equals("[]")) {
            return null;
        }

        Matcher matcher = RESULT_PATTERN.matcher(searchResult);
        StringBuilder sb = new StringBuilder();
        int rank = 0;

        while (matcher.find()) {
            double similarity = Double.parseDouble(matcher.group(1));
            String text = unescapeJson(matcher.group(2));

            if (similarity >= SIMILARITY_THRESHOLD) {
                rank++;
                sb.append("  ").append(rank).append(". (")
                  .append(String.format("%.2f", similarity)).append(") ")
                  .append(text).append("\n");
            }
        }

        return sb.length() > 0 ? sb.toString() : null;
    }

    private String unescapeJson(String s) {
        return s.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }
}
