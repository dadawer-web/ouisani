package com.ouisani.aios.core.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 确定性 JSON 提取工具 — 借鉴 Firecrawl 的 performDeterministicJson。
 * <p>
 * 创新设计：用 LLM 生成提取代码（Python），在沙箱中执行，
 * 缓存代码供后续复用——相同 URL/Schema 直接执行缓存代码，跳过 LLM 推理。
 * <p>
 * 与 AIOS 的"动态工具锻造"机制融合：
 * LLM 生成提取代码 → BashTool 执行 → 注册为 DynamicForgedTool → 后续直接调用
 * <p>
 * OS 类比：JIT 编译器 — 首次解释执行，热点代码编译为机器码缓存复用。
 */
public class DeterministicExtractTool implements Tool<DeterministicExtractTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(DeterministicExtractTool.class);

    /** 提取代码缓存：cacheKey → Python 代码 — 借鉴 Firecrawl 的代码缓存 */
    private static final ConcurrentHashMap<String, String> extractionCodeCache = new ConcurrentHashMap<>();

    public record Input(
            String url,
            String schema,
            String prompt
    ) implements ToolInput {
        public Input {
            if (url == null || url.isBlank()) throw new IllegalArgumentException("url required");
            if (schema == null || schema.isBlank()) {
                schema = "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}}}";
            }
            if (prompt == null) prompt = "Extract key information from this page";
        }

        @Override public String toJson() {
            return "{\"url\":\"" + url.replace("\"", "\\\"")
                    + "\",\"schema\":\"" + schema.replace("\"", "\\\"").substring(0, Math.min(schema.length(), 200))
                    + "...\"}";
        }
    }

    @Override public String name() { return "deterministic_extract"; }

    @Override public String description() {
        return "Extracts structured data using LLM-generated code (cached for reuse). Faster than structured_extract for repeated extractions. Provide URL and JSON Schema.";
    }

    @Override public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\",\"description\":\"URL to extract from\"},\"schema\":{\"type\":\"string\",\"description\":\"JSON Schema for extraction\"},\"prompt\":{\"type\":\"string\",\"description\":\"What to extract\"}},\"required\":[\"url\"]}";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        log.info("[DeterministicExtract] 开始提取: url={}", input.url());

        try {
            String cacheKey = buildCacheKey(input.url(), input.schema());

            // 1. 检查代码缓存
            String cachedCode = extractionCodeCache.get(cacheKey);
            if (cachedCode != null) {
                log.info("[DeterministicExtract] 命中代码缓存: key={}", cacheKey);
                // 直接执行缓存代码
                String result = executeExtractionCode(cachedCode, input.url(), context);
                if (result != null) {
                    return ToolOutput.ok(result);
                }
                // 缓存代码执行失败，清除缓存，重新生成
                extractionCodeCache.remove(cacheKey);
                log.warn("[DeterministicExtract] 缓存代码执行失败，重新生成");
            }

            // 2. 抓取网页内容（HTML 格式，供代码生成参考）
            WebScrapeTool scraper = new WebScrapeTool();
            WebScrapeTool.Input scrapeInput = new WebScrapeTool.Input(input.url(), "html", true);
            ToolOutput scrapeResult = scraper.call(scrapeInput, context);

            if (!scrapeResult.success()) {
                return ToolOutput.fail("Failed to scrape URL: " + scrapeResult.toText());
            }

            String htmlContent = scrapeResult.toText();
            if (htmlContent.length() > 30000) {
                htmlContent = htmlContent.substring(0, 30000);
            }

            // 3. 用 LLM 生成提取代码 — 借鉴 Firecrawl 的确定性提取
            ToolSdk sdk = context.sdk();
            String codeGenPrompt = buildCodeGenPrompt(input.prompt(), input.schema(), htmlContent);
            String llmResponse = sdk.think(context.agentId(), codeGenPrompt);

            // 4. 提取生成的代码
            String extractionCode = extractPythonCode(llmResponse);
            if (extractionCode == null || extractionCode.isBlank()) {
                // 降级到 StructuredExtractTool
                log.warn("[DeterministicExtract] LLM 未生成有效代码，降级到 structured_extract");
                StructuredExtractTool fallback = new StructuredExtractTool();
                return fallback.call(new StructuredExtractTool.Input(input.url(), input.schema(), input.prompt()), context);
            }

            // 5. 缓存代码
            extractionCodeCache.put(cacheKey, extractionCode);
            log.info("[DeterministicExtract] 提取代码已缓存: key={}, codeLen={}", cacheKey, extractionCode.length());

            // 6. 执行提取代码
            String result = executeExtractionCode(extractionCode, input.url(), context);
            if (result != null) {
                // 7. 注册为动态锻造工具（融合 ToolForgeService）
                tryRegisterAsForgedTool(input, extractionCode, context);
                return ToolOutput.ok(result);
            }

            // 执行失败，降级
            StructuredExtractTool fallback = new StructuredExtractTool();
            return fallback.call(new StructuredExtractTool.Input(input.url(), input.schema(), input.prompt()), context);

        } catch (Exception e) {
            log.error("[DeterministicExtract] 提取异常: url={}", input.url(), e);
            return ToolOutput.fail("Deterministic extraction error: " + e.getMessage());
        }
    }

    // ── 内部方法 ──

    /**
     * 构建代码生成提示词 — 借鉴 Firecrawl 的确定性提取代码生成。
     */
    private String buildCodeGenPrompt(String userPrompt, String schema, String sampleHtml) {
        return """
            You are a Python code generator. Generate a Python script that extracts structured data from HTML.

            Requirements:
            1. Define a function: def extract(html: str) -> dict
            2. Use only Python standard library (re, json, html.parser)
            3. Parse the HTML and extract data matching this JSON Schema:
            %s

            User request: %s

            Sample HTML structure (for reference):
            ---
            %s
            ---

            Output ONLY the Python code in a ```python``` block. The script must:
            - Define extract(html) function
            - Return a dict matching the schema
            - Handle missing data gracefully (use None)
            - Not fabricate data
            """.formatted(schema, userPrompt, sampleHtml.substring(0, Math.min(sampleHtml.length(), 5000)));
    }

    /**
     * 从 LLM 响应中提取 Python 代码。
     */
    private String extractPythonCode(String llmResponse) {
        if (llmResponse == null) return null;

        // 提取 ```python ... ``` 代码块
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "```python\\s*\\n([\\s\\S]*?)```").matcher(llmResponse);
        if (m.find()) return m.group(1).trim();

        // 回退：找 def extract 行
        m = java.util.regex.Pattern.compile("(def extract[\\s\\S]+?)(?=\\n\\S|$)").matcher(llmResponse);
        if (m.find()) return m.group(1).trim();

        return null;
    }

    /**
     * 执行提取代码。
     */
    private String executeExtractionCode(String code, String url, ToolContext context) {
        try {
            // 构建执行脚本：先抓取 HTML，再执行提取代码
            String script = code + "\n\n" + """
                import sys, json
                try:
                    # 读取 HTML from stdin
                    html = sys.stdin.read()
                    result = extract(html)
                    print(json.dumps(result, ensure_ascii=False, indent=2))
                except Exception as e:
                    print(json.dumps({"error": str(e)}, ensure_ascii=False))
                """;

            // 先抓取 HTML
            WebScrapeTool scraper = new WebScrapeTool();
            WebScrapeTool.Input scrapeInput = new WebScrapeTool.Input(url, "html", true);
            ToolOutput scrapeResult = scraper.call(scrapeInput, context);
            if (!scrapeResult.success()) return null;

            String htmlContent = scrapeResult.toText();

            // 通过 BashTool 执行提取代码
            // 将 HTML 写入临时文件，再通过管道传给 Python
            String tmpFile = "/tmp/aios_extract_" + Math.abs(url.hashCode()) + ".html";
            BashTool bashTool = new BashTool();

            // 先写 HTML 到临时文件
            String writeCmd = "cat > " + tmpFile + " << 'HTMLEOF'\n"
                    + htmlContent.substring(0, Math.min(htmlContent.length(), 50000))
                    + "\nHTMLEOF";
            BashTool.Input writeInput = new BashTool.Input(writeCmd, 10);
            bashTool.call(writeInput, new ToolContext(context.agentId(), context.sdk(), context.workingDir()));

            // 执行 Python 提取代码
            String pythonScript = script.replace("\"", "\\\"").replace("$", "\\$");
            String command = "cat " + tmpFile + " | python3 -u -c \"" + pythonScript + "\" 2>&1";
            BashTool.Input bashInput = new BashTool.Input(command, 15);
            ToolOutput result = bashTool.call(bashInput, new ToolContext(context.agentId(), context.sdk(), context.workingDir()));

            // 清理临时文件
            bashTool.call(new BashTool.Input("rm -f " + tmpFile, 5),
                    new ToolContext(context.agentId(), context.sdk(), context.workingDir()));

            if (result.success() && result.toText().contains("{")) {
                return result.toText();
            }
            return null;
        } catch (Exception e) {
            log.debug("[DeterministicExtract] 代码执行失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 注册为动态锻造工具 — 融合 ToolForgeService。
     */
    private void tryRegisterAsForgedTool(Input input, String code, ToolContext context) {
        try {
            String toolName = "forged_extract_" + Math.abs(input.url().hashCode() % 10000);
            DynamicForgedTool tool = new DynamicForgedTool(
                    toolName,
                    "Deterministic extraction for " + input.url(),
                    code,
                    "extract",
                    input.schema(),
                    context.agentId(),
                    context.sdk(),
                    context.workingDir()
            );
            ToolRegistry.instance().register(tool);
            ToolForgeService.getInstance().registerForgedTool(tool, context.agentId());
            log.info("[DeterministicExtract] 已注册为锻造工具: name={}", toolName);
        } catch (Exception e) {
            log.debug("[DeterministicExtract] 注册锻造工具失败（不影响结果）: {}", e.getMessage());
        }
    }

    /**
     * 构建缓存键。
     */
    private String buildCacheKey(String url, String schema) {
        return url.hashCode() + "_" + schema.hashCode();
    }

    /**
     * 获取缓存统计。
     */
    public static int cacheSize() { return extractionCodeCache.size(); }

    /**
     * 清除缓存。
     */
    public static void clearCache() { extractionCodeCache.clear(); }

    @Override public boolean readOnly() { return true; }

    @Override public String prompt() {
        return "Use deterministic_extract for fast repeated extractions. Generates Python code, caches it for reuse. Falls back to structured_extract if code generation fails.";
    }
}
