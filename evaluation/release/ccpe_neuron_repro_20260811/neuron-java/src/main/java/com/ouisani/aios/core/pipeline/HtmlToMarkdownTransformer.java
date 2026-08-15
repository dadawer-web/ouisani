package com.ouisani.aios.core.pipeline;

import com.ouisani.aios.core.tool.HtmlToMarkdownTool;
import com.ouisani.aios.core.tool.ToolContext;

/**
 * HTML→Markdown 转换器 — 借鉴 Firecrawl 的 deriveMarkdownFromHTML。
 * 使用 HtmlToMarkdownTool 进行转换。
 */
public class HtmlToMarkdownTransformer implements ContentTransformer {

    @Override
    public String transform(String content, TransformContext context) {
        if (content == null || content.isBlank()) return content;

        // 检查是否为 HTML
        String lower = content.toLowerCase().trim();
        boolean isHtml = lower.startsWith("<!doctype") || lower.startsWith("<html")
                || (lower.contains("<body") && lower.contains("</body>"))
                || (lower.contains("<div") && lower.contains("</div>"));

        if (!isHtml) return content; // 非 HTML 内容不转换

        boolean onlyMain = !"false".equals(context.getOption("onlyMainContent", "true"));

        HtmlToMarkdownTool tool = new HtmlToMarkdownTool();
        HtmlToMarkdownTool.Input input = new HtmlToMarkdownTool.Input(content, onlyMain);
        ToolContext toolContext = new ToolContext(context.agentId(), context.sdk(), context.workingDir());

        var result = tool.call(input, toolContext);
        if (result.success()) {
            return result.toText();
        }
        // 转换失败，返回原始内容
        return content;
    }

    @Override public String name() { return "html_to_markdown"; }
}
