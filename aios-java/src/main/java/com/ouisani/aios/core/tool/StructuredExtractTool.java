package com.ouisani.aios.core.tool;

import com.ouisani.aios.user.sdk.AiosSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.*;

/**
 * LLM 结构化提取工具 — 借鉴 Firecrawl 的 performLLMExtract。
 * <p>
 * 用户提供 URL + JSON Schema，工具自动抓取网页并用 LLM 按 Schema 提取结构化数据。
 * <p>
 * 流程：
 * 1. 用 WebScrapeTool 抓取网页内容（markdown 格式，减少 token 消耗）
 * 2. 裁剪内容到 token 限制
 * 3. 构建 LLM 提示词（含 Schema + 内容）
 * 4. 调用 LLM 生成结构化 JSON
 * 5. 修复和验证 JSON
 * <p>
 * OS 类比：Linux 的 /proc 文件系统 — 将非结构化的内核数据
 * 转换为结构化的可查询格式。
 */
public class StructuredExtractTool implements Tool<StructuredExtractTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(StructuredExtractTool.class);
    private static final int MAX_CONTENT_TOKENS = 8000; // 约 32000 字符

    public record Input(
            String url,
            String schema,
            String prompt
    ) implements ToolInput {
        public Input {
            if (url == null || url.isBlank()) throw new IllegalArgumentException("url required");
            if (schema == null || schema.isBlank()) {
                schema = "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"},\"summary\":{\"type\":\"string\"}}}";
            }
            if (prompt == null) prompt = "Extract the key information from this page according to the schema.";
        }

        @Override public String toJson() {
            return "{\"url\":\"" + url.replace("\"", "\\\"")
                    + "\",\"schema\":\"" + schema.replace("\"", "\\\"").substring(0, Math.min(schema.length(), 200))
                    + "...\"}";
        }
    }

    @Override public String name() { return "structured_extract"; }

    @Override public String description() {
        return "Extracts structured data from a web page using LLM. Provide URL and JSON Schema. Returns structured JSON matching the schema.";
    }

    @Override public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\",\"description\":\"URL to extract from\"},\"schema\":{\"type\":\"string\",\"description\":\"JSON Schema defining the structure to extract\"},\"prompt\":{\"type\":\"string\",\"description\":\"What to extract (default: extract key information)\"}},\"required\":[\"url\"]}";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        log.info("[StructuredExtract] 开始提取: url={}", input.url());

        try {
            // 1. 抓取网页（markdown 格式，减少 token 消耗）
            WebScrapeTool scraper = new WebScrapeTool();
            WebScrapeTool.Input scrapeInput = new WebScrapeTool.Input(input.url(), "markdown", true);
            ToolOutput scrapeResult = scraper.call(scrapeInput, context);

            if (!scrapeResult.success()) {
                return ToolOutput.fail("Failed to scrape URL: " + scrapeResult.toText());
            }

            String content = scrapeResult.toText();

            // 2. 裁剪内容到 token 限制（借鉴 Firecrawl 的 trimToTokenLimit）
            content = trimToTokenLimit(content, MAX_CONTENT_TOKENS);

            // 3. 规范化 Schema（借鉴 Firecrawl 的 normalizeSchema）
            String normalizedSchema = normalizeSchema(input.schema());

            // 4. 构建 LLM 提示词
            String extractPrompt = buildExtractPrompt(input.prompt(), normalizedSchema, content);

            // 5. 调用 LLM
            AiosSdk sdk = context.sdk();
            String llmResponse = sdk.think(context.agentId(), extractPrompt);

            // 6. 修复和验证 JSON
            String json = extractJson(llmResponse);
            if (json == null) {
                // 降级重试：简化提示词
                String retryPrompt = buildSimpleExtractPrompt(normalizedSchema, content);
                llmResponse = sdk.think(context.agentId(), retryPrompt);
                json = extractJson(llmResponse);
            }

            if (json != null) {
                log.info("[StructuredExtract] 提取成功: url={}", input.url());
                return ToolOutput.ok(json);
            } else {
                return ToolOutput.fail("LLM failed to produce valid JSON. Raw response: "
                        + llmResponse.substring(0, Math.min(llmResponse.length(), 500)));
            }

        } catch (Exception e) {
            log.error("[StructuredExtract] 提取异常: url={}", input.url(), e);
            return ToolOutput.fail("Extraction error: " + e.getMessage());
        }
    }

    // ── 内部方法 ──

    /**
     * 构建 LLM 提取提示词 — 借鉴 Firecrawl 的 generateCompletions。
     */
    private String buildExtractPrompt(String userPrompt, String schema, String content) {
        return """
            You are a data extraction assistant. Extract structured data from the following web page content.

            Instructions:
            1. Extract data according to the JSON Schema below
            2. Return ONLY valid JSON, no markdown, no explanation
            3. If a field cannot be found, use null
            4. Do not fabricate data that is not in the content

            User Request: %s

            JSON Schema:
            ```json
            %s
            ```

            Web Page Content:
            ---
            %s
            ---

            Return the extracted data as a JSON object matching the schema above:
            """.formatted(userPrompt, schema, content);
    }

    /**
     * 简化提示词（降级重试用）。
     */
    private String buildSimpleExtractPrompt(String schema, String content) {
        return """
            Extract data from this content as JSON matching this schema.
            Return ONLY valid JSON, nothing else.

            Schema: %s

            Content: %s

            JSON:
            """.formatted(schema, content.substring(0, Math.min(content.length(), 3000)));
    }

    /**
     * 裁剪内容到 token 限制 — 借鉴 Firecrawl 的 trimToTokenLimit。
     * 粗略估算：4 字符 ≈ 1 token。
     */
    private String trimToTokenLimit(String content, int maxTokens) {
        int maxChars = maxTokens * 4;
        if (content.length() <= maxChars) return content;
        // 保留前 2/3 和后 1/3
        int headEnd = maxChars * 2 / 3;
        int tailStart = content.length() - maxChars / 3;
        return content.substring(0, headEnd)
                + "\n\n... [content truncated] ...\n\n"
                + content.substring(tailStart);
    }

    /**
     * 规范化 Schema — 借鉴 Firecrawl 的 normalizeSchema。
     * 所有属性标记为 required，设置 additionalProperties: false。
     */
    private String normalizeSchema(String schema) {
        try {
            // 简单的字符串操作规范化
            // 移除 existing required 字段
            String normalized = schema.replaceAll("\"required\"\\s*:\\s*\\[[^\\]]*]", "");
            // 在 properties 后添加 required
            String[] props = schema.split("\"properties\"");
            if (props.length >= 2) {
                // 提取属性名
                java.util.List<String> required = new java.util.ArrayList<>();
                Matcher m = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\\{").matcher(props[1]);
                while (m.find()) required.add(m.group(1));
                if (!required.isEmpty()) {
                    normalized = schema.replaceAll("\\}\\s*$",
                            ",\"required\":[\"" + String.join("\",\"", required) + "\"],\"additionalProperties\":false}");
                }
            }
            return normalized;
        } catch (Exception e) {
            return schema; // 规范化失败，返回原始 schema
        }
    }

    /**
     * 从 LLM 响应中提取 JSON — 借鉴 Firecrawl 的 JSON 修复。
     */
    private String extractJson(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) return null;

        String text = llmResponse.trim();

        // 尝试1：直接解析
        if (text.startsWith("{") && text.endsWith("}")) {
            if (isValidJson(text)) return text;
        }

        // 尝试2：提取 ```json ... ``` 代码块
        Matcher jsonBlock = Pattern.compile("```(?:json)?\\s*\\n?(\\{[\\s\\S]*?\\})\\s*```").matcher(text);
        if (jsonBlock.find()) {
            String json = jsonBlock.group(1);
            if (isValidJson(json)) return json;
        }

        // 尝试3：找第一个 { 到最后一个 }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            String json = text.substring(start, end + 1);
            if (isValidJson(json)) return json;
        }

        return null;
    }

    private boolean isValidJson(String text) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.readTree(text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override public boolean readOnly() { return true; }

    @Override public String prompt() {
        return "Use structured_extract to extract structured data from web pages. Provide URL and optional JSON Schema. Returns clean JSON data.";
    }
}
