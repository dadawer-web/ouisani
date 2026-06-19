package com.ouisani.aios.core.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 可组合内容处理管道 — 借鉴 Firecrawl 的 Transformer Stack。
 * <p>
 * 按顺序执行一系列 ContentTransformer，每个转换器处理一次内容。
 * 可以像 Langflow 的组件一样按需组装。
 * <p>
 * OS 类比：Linux 的管道 — cmd1 | cmd2 | cmd3
 * <p>
 * 内置转换器（按 Firecrawl 的 transformer stack 顺序）：
 * <ol>
 *   <li>HtmlCleanTransformer — HTML 清洗（移除 script/style/nav/footer）</li>
 *   <li>HtmlToMarkdownTransformer — HTML 转 Markdown</li>
 *   <li>PiiRedactTransformer — PII 脱敏</li>
 *   <li>SummaryTransformer — LLM 摘要</li>
 *   <li>QueryTransformer — LLM 问答</li>
 * </ol>
 */
public class ContentPipeline {

    private static final Logger log = LoggerFactory.getLogger(ContentPipeline.class);

    private final List<ContentTransformer> transformers = new CopyOnWriteArrayList<>();

    public ContentPipeline() {}

    /**
     * 添加转换器到管道末尾。
     */
    public ContentPipeline addTransformer(ContentTransformer transformer) {
        transformers.add(transformer);
        return this;
    }

    /**
     * 在指定位置插入转换器。
     */
    public ContentPipeline addTransformer(int index, ContentTransformer transformer) {
        transformers.add(index, transformer);
        return this;
    }

    /**
     * 执行管道 — 按顺序执行所有转换器。
     *
     * @param content 输入内容
     * @param context 转换上下文
     * @return 处理后的内容
     */
    public PipelineResult execute(String content, TransformContext context) {
        long startTime = System.currentTimeMillis();
        String current = content;
        List<StepResult> steps = new ArrayList<>();

        for (ContentTransformer transformer : transformers) {
            long stepStart = System.currentTimeMillis();
            try {
                String previous = current;
                current = transformer.transform(current, context);
                long elapsed = System.currentTimeMillis() - stepStart;

                steps.add(new StepResult(
                        transformer.name(), true, elapsed,
                        previous.length(), current != null ? current.length() : 0,
                        null
                ));

                log.debug("[ContentPipeline] 步骤 '{}' 完成: {}ms, {}→{} chars",
                        transformer.name(), elapsed, previous.length(),
                        current != null ? current.length() : 0);

            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - stepStart;
                steps.add(new StepResult(
                        transformer.name(), false, elapsed,
                        current != null ? current.length() : 0, 0,
                        e.getMessage()
                ));

                if (transformer.required()) {
                    log.error("[ContentPipeline] 必需步骤 '{}' 失败，中断管道: {}",
                            transformer.name(), e.getMessage());
                    break;
                } else {
                    log.warn("[ContentPipeline] 可选步骤 '{}' 失败，跳过: {}",
                            transformer.name(), e.getMessage());
                }
            }
        }

        long totalElapsed = System.currentTimeMillis() - startTime;
        return new PipelineResult(current, steps, totalElapsed);
    }

    /**
     * 获取转换器列表。
     */
    public List<ContentTransformer> getTransformers() {
        return Collections.unmodifiableList(transformers);
    }

    /**
     * 清除所有转换器。
     */
    public void clear() {
        transformers.clear();
    }

    // ── 结果记录 ──

    public record StepResult(
            String transformerName,
            boolean success,
            long elapsedMs,
            int inputLength,
            int outputLength,
            String errorMessage
    ) {}

    public record PipelineResult(
            String output,
            List<StepResult> steps,
            long totalElapsedMs
    ) {
        public boolean allSuccess() {
            return steps.stream().allMatch(StepResult::success);
        }
        public int successCount() {
            return (int) steps.stream().filter(StepResult::success).count();
        }
    }

    // ── 工厂方法 ──

    /**
     * 创建默认的网页内容处理管道 — 借鉴 Firecrawl 的 transformer stack。
     */
    public static ContentPipeline createWebScrapePipeline() {
        return new ContentPipeline()
                .addTransformer(new HtmlCleanTransformer())
                .addTransformer(new HtmlToMarkdownTransformer())
                .addTransformer(new PiiRedactTransformer());
    }

    /**
     * 创建带 LLM 增强的管道。
     */
    public static ContentPipeline createEnhancedPipeline() {
        return new ContentPipeline()
                .addTransformer(new HtmlCleanTransformer())
                .addTransformer(new HtmlToMarkdownTransformer())
                .addTransformer(new PiiRedactTransformer())
                .addTransformer(new SummaryTransformer())
                .addTransformer(new QueryTransformer());
    }
}
