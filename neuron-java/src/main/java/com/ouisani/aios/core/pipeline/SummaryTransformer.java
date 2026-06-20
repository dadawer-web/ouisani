package com.ouisani.aios.core.pipeline;

/**
 * LLM 摘要转换器 — 借鉴 Firecrawl 的 performSummary。
 * 使用 LLM 生成内容摘要。
 */
public class SummaryTransformer implements ContentTransformer {

    @Override
    public String transform(String content, TransformContext context) {
        if (content == null || content.isBlank()) return content;
        if (content.length() < 200) return content; // 短内容不需要摘要

        try {
            String prompt = "Summarize the following content concisely in 3-5 sentences. Keep key facts and data points.\n\n" + content;
            String summary = context.sdk().think(context.agentId(), prompt);
            if (summary != null && !summary.isBlank()) {
                return "## Summary\n" + summary + "\n\n## Original Content\n" + content;
            }
        } catch (Exception e) {
            // LLM 调用失败，返回原始内容
        }
        return content;
    }

    @Override public String name() { return "summary"; }
}
