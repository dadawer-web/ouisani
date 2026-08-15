package com.ouisani.aios.core.trace.builtin;

import com.ouisani.aios.core.trace.TraceSpan;
import com.ouisani.aios.core.trace.TracingExporter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * JSON 文件导出器 — 将完成的 Span 导出为 JSON 文件到 {@code /tmp/aios_traces/} 目录。
 * <p>
 * 每个 Trace（按 traceId 分组）生成一个独立的 JSON 文件，文件名为 {@code {traceId}.json}。
 * 同一 Trace 的多个 Span 追加写入（NDJSON 风格 — 每行一个 Span JSON）。
 * <p>
 * 适用于离线分析和外部工具（Jaeger UI、FlameGraph）导入。
 * <p>
 * OS 类比：相当于 ftrace 的 trace_pipe 落盘 — 内核追踪数据持久化到 ring buffer 文件。
 */
public class JsonFileTracingExporter implements TracingExporter {

    private static final Logger log = LoggerFactory.getLogger(JsonFileTracingExporter.class);

    /** 默认输出目录。 */
    public static final String DEFAULT_DIR = "/tmp/aios_traces";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path outputDir;

    /** 默认输出到 {@link #DEFAULT_DIR}。 */
    public JsonFileTracingExporter() {
        this(DEFAULT_DIR);
    }

    public JsonFileTracingExporter(String outputDir) {
        this.outputDir = Path.of(outputDir);
        try {
            Files.createDirectories(this.outputDir);
            log.info("[JsonFileTracingExporter] 输出目录已就绪: {}", this.outputDir);
        } catch (IOException e) {
            log.warn("[JsonFileTracingExporter] 创建输出目录失败: {} — 导出将被禁用", e.getMessage());
        }
    }

    @Override
    public void export(List<TraceSpan> spans) {
        if (spans == null || spans.isEmpty()) {
            return;
        }
        if (!Files.isDirectory(outputDir)) {
            log.debug("[JsonFileTracingExporter] 输出目录不存在，跳过导出");
            return;
        }

        // 按 traceId 分组写入（NDJSON — 每行一个 Span）
        for (TraceSpan span : spans) {
            String traceId = span.traceId() != null ? span.traceId() : "unknown";
            String safeName = sanitize(traceId);
            Path file = outputDir.resolve(safeName + ".json");
            try {
                String line = span.toJson();
                // INDENT_OUTPUT 会产生多行 JSON，这里用紧凑单行写入便于追加
                String compact = JSON.readTree(line).toString();
                Files.writeString(file, compact + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.debug("[JsonFileTracingExporter] 写入 Span 失败: spanId={}, error={}",
                        span.spanId(), e.getMessage());
            }
        }
    }

    /** 净化文件名 — 移除路径分隔符等危险字符。 */
    private static String sanitize(String name) {
        if (name == null || name.isBlank()) return "unknown";
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
