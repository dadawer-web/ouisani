package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.pipeline.ContentPipeline;
import com.ouisani.aios.core.pipeline.TransformContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 内容管道工具 — 借鉴 Firecrawl 的 Transformer Stack。
 * <p>
 * 将 ContentPipeline 暴露为 Tool，供 Agent 通过 QueryEngine 调用。
 * 支持选择预设管道或自定义转换器列表。
 */
public class ContentPipelineTool implements Tool<ContentPipelineTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(ContentPipelineTool.class);

    public record Input(
            String content,
            String pipeline,
            String query,
            boolean onlyMainContent
    ) implements ToolInput {
        public Input {
            if (content == null || content.isBlank()) throw new IllegalArgumentException("content required");
            if (pipeline == null || pipeline.isBlank()) pipeline = "web_scrape";
            onlyMainContent = true; // 默认
        }

        @Override public String toJson() {
            return "{\"content\":\"...\",\"pipeline\":\"" + pipeline + "\"}";
        }
    }

    @Override public String name() { return "content_pipeline"; }

    @Override public String description() {
        return "Processes content through a pipeline of transformers (HTML clean→Markdown→PII redact→optional LLM summary/query). Use for web content processing.";
    }

    @Override public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{\"content\":{\"type\":\"string\",\"description\":\"Content to process (HTML or text)\"},\"pipeline\":{\"type\":\"string\",\"description\":\"Pipeline preset: web_scrape (default) or enhanced (with LLM summary)\"},\"query\":{\"type\":\"string\",\"description\":\"Optional question to answer from the content\"}},\"required\":[\"content\"]}";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        log.info("[ContentPipeline] 执行管道: pipeline={}", input.pipeline());

        // 选择管道
        ContentPipeline pipeline;
        if ("enhanced".equals(input.pipeline())) {
            pipeline = ContentPipeline.createEnhancedPipeline();
        } else {
            pipeline = ContentPipeline.createWebScrapePipeline();
        }

        // 构建上下文
        TransformContext ctx = new TransformContext(context.agentId(), context.sdk(), context.workingDir());
        ctx.setOption("onlyMainContent", String.valueOf(input.onlyMainContent()));
        if (input.query() != null) {
            ctx.setOption("query", input.query());
        }

        // 执行管道
        ContentPipeline.PipelineResult result = pipeline.execute(input.content(), ctx);

        // 构建输出
        StringBuilder output = new StringBuilder();
        output.append(result.output());

        if (!result.allSuccess()) {
            output.append("\n\n--- Pipeline Stats ---\n");
            for (ContentPipeline.StepResult step : result.steps()) {
                if (!step.success()) {
                    output.append("- ").append(step.transformerName()).append(": FAILED (").append(step.errorMessage()).append(")\n");
                }
            }
        }

        output.append("\n[Pipeline: ").append(result.successCount()).append("/").append(result.steps().size())
                .append(" steps, ").append(result.totalElapsedMs()).append("ms]");

        return ToolOutput.ok(output.toString());
    }

    @Override public boolean readOnly() { return true; }

    @Override public String prompt() {
        return "Use content_pipeline to process web content through a chain of transformers. Presets: web_scrape (clean+markdown+pii) or enhanced (+summary+query).";
    }
}
