package com.ouisani.aios.core.pipeline;

/**
 * LLM 问答转换器 — 借鉴 Firecrawl 的 performQuery。
 * 根据用户问题从内容中提取答案。
 */
public class QueryTransformer implements ContentTransformer {

    @Override
    public String transform(String content, TransformContext context) {
        if (content == null || content.isBlank()) return content;

        String query = context.getOption("query", null);
        if (query == null || query.isBlank()) return content; // 无问题则跳过

        try {
            String prompt = "Based on the following content, answer this question: " + query + "\n\nContent:\n" + content;
            String answer = context.sdk().think(context.agentId(), prompt);
            if (answer != null && !answer.isBlank()) {
                return "## Answer to: " + query + "\n" + answer + "\n\n## Source Content\n" + content;
            }
        } catch (Exception e) {
            // LLM 调用失败，返回原始内容
        }
        return content;
    }

    @Override public String name() { return "query"; }
}
