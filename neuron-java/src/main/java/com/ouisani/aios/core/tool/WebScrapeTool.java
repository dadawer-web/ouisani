package com.ouisani.aios.core.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 多引擎瀑布流网页抓取工具 — 借鉴 Firecrawl 的引擎瀑布流设计。
 * <p>
 * 按优先级依次启动多个抓取引擎，第一个成功的结果胜出：
 * <ol>
 *   <li>引擎0：Jina Reader（r.jina.ai，LLM 优化 Markdown，MRT=10s）— 借鉴 Apix/tools/web_search/providers/jina.py</li>
 *   <li>引擎1：Playwright/Puppeteer（JS 渲染，高质量，MRT=15s）</li>
 *   <li>引擎2：HTTP + HTML 解析（轻量快速，MRT=8s）</li>
 *   <li>引擎3：BashTool + curl（兜底，MRT=10s）</li>
 * </ol>
 * <p>
 * OS 类比：Linux 内核的 I/O 调度器 — 先尝试最快的路径，
 * 失败则降级到更慢但更可靠的路径。
 */
public class WebScrapeTool implements Tool<WebScrapeTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(WebScrapeTool.class);
    private static final int MAX_CONTENT_LENGTH = 100000;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * 抓取引擎 — 借鉴 Firecrawl 的引擎抽象。
     */
    public interface ScrapeEngine {
        /** 引擎名称 */
        String engineName();
        /** 最大合理时间（毫秒） */
        long mrtMs();
        /** 执行抓取 */
        ScrapeResult scrape(String url, ToolContext context);
    }

    /**
     * 抓取结果。
     */
    public record ScrapeResult(
            boolean success,
            String content,
            String engineUsed,
            int httpStatusCode,
            String errorMessage
    ) {}

    public record Input(
            String url,
            String formats,
            boolean onlyMainContent
    ) implements ToolInput {
        public Input {
            if (url == null || url.isBlank()) throw new IllegalArgumentException("url required");
            if (formats == null || formats.isBlank()) formats = "markdown";
            onlyMainContent = true; // 默认只提取主体内容
        }

        public Input(String url) { this(url, "markdown", true); }

        @Override public String toJson() {
            return "{\"url\":\"" + url.replace("\"", "\\\"")
                    + "\",\"formats\":\"" + formats
                    + "\",\"onlyMainContent\":" + onlyMainContent + "}";
        }
    }

    // ── 四个引擎实现 ──

    /**
     * 引擎0：Jina Reader（r.jina.ai）— 借鉴 Apix/tools/web_search/providers/jina.py。
     * <p>
     * 直接调用 {@code https://r.jina.ai/{url}} 获取为 LLM 优化的纯净 Markdown，
     * 无需自己写正则剥离 HTML 标签。返回内容信噪比极高，避免上下文爆炸。
     * <p>
     * 当传统 HtmlToMarkdownTool 爬几万字无用 DOM 撑爆内存时，
     * Jina Reader 返回的纯净 Markdown 能让 Agent 检索速度和准确率飙升。
     */
    private static class JinaReaderEngine implements ScrapeEngine {
        @Override public String engineName() { return "jina-reader"; }
        @Override public long mrtMs() { return 10000; }

        @Override
        public ScrapeResult scrape(String url, ToolContext context) {
            try {
                // 拼接 r.jina.ai 前缀
                String jinaUrl = "https://r.jina.ai/" + url;
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(jinaUrl))
                        .header("Accept", "text/plain")          // 返回纯 Markdown
                        .header("X-Return-Format", "markdown")   // 强制 Markdown 格式
                        .header("X-With-Links-Summary", "true")  // 链接汇总
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();

                HttpResponse<String> response = HTTP_CLIENT.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    String body = response.body();
                    // Jina 返回的内容已经是 LLM 优化的 Markdown，无需再解析 HTML
                    if (body != null && body.length() > 50) {
                        return new ScrapeResult(true, body, engineName(),
                                response.statusCode(), null);
                    }
                    return new ScrapeResult(false, null, engineName(),
                            response.statusCode(), "Empty or too short body");
                }
                return new ScrapeResult(false, null, engineName(),
                        response.statusCode(), "HTTP " + response.statusCode());
            } catch (Exception e) {
                return new ScrapeResult(false, null, engineName(), -1, e.getMessage());
            }
        }
    }

    /** 引擎1：Playwright（JS 渲染，高质量） */
    private static class PlaywrightEngine implements ScrapeEngine {
        @Override public String engineName() { return "playwright"; }
        @Override public long mrtMs() { return 15000; }

        @Override
        public ScrapeResult scrape(String url, ToolContext context) {
            // 尝试用 python3 + playwright 抓取
            String script = """
                import sys
                try:
                    from playwright.sync_api import sync_playwright
                    with sync_playwright() as p:
                        browser = p.chromium.launch(headless=True)
                        page = browser.new_page()
                        page.goto('%s', timeout=12000, wait_until='domcontentloaded')
                        page.wait_for_timeout(2000)
                        content = page.content()
                        browser.close()
                        print(content)
                except Exception as e:
                    print(f'PLAYWRIGHT_ERROR: {e}', file=sys.stderr)
                    sys.exit(1)
                """.formatted(url.replace("'", "\\'"));

            BashTool bashTool = new BashTool();
            BashTool.Input input = new BashTool.Input(
                    "python3 -u -c \"" + script.replace("\"", "\\\"") + "\"", 15);
            ToolOutput result = bashTool.call(input, new ToolContext(context.agentId(), context.sdk(), context.workingDir()));

            if (result.success() && !result.toText().contains("PLAYWRIGHT_ERROR")) {
                return new ScrapeResult(true, result.toText(), engineName(), 200, null);
            }
            return new ScrapeResult(false, null, engineName(), -1, result.toText());
        }
    }

    /** 引擎2：HTTP + HTML 解析（轻量快速） */
    private static class HttpEngine implements ScrapeEngine {
        @Override public String engineName() { return "http"; }
        @Override public long mrtMs() { return 8000; }

        @Override
        public ScrapeResult scrape(String url, ToolContext context) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml,*/*")
                        .header("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8")
                        .timeout(Duration.ofSeconds(8))
                        .GET()
                        .build();

                HttpResponse<String> response = HTTP_CLIENT.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return new ScrapeResult(true, response.body(), engineName(), response.statusCode(), null);
                }
                return new ScrapeResult(false, null, engineName(), response.statusCode(),
                        "HTTP " + response.statusCode());
            } catch (Exception e) {
                return new ScrapeResult(false, null, engineName(), -1, e.getMessage());
            }
        }
    }

    /** 引擎3：curl 兜底 */
    private static class CurlEngine implements ScrapeEngine {
        @Override public String engineName() { return "curl"; }
        @Override public long mrtMs() { return 10000; }

        @Override
        public ScrapeResult scrape(String url, ToolContext context) {
            BashTool bashTool = new BashTool();
            String command = String.format(
                    "curl -sL --max-time 8 -H 'User-Agent: Mozilla/5.0' -H 'Accept: text/html,*/*' '%s'",
                    url.replace("'", "'\\''"));
            BashTool.Input input = new BashTool.Input(command, 10);
            ToolOutput result = bashTool.call(input, new ToolContext(context.agentId(), context.sdk(), context.workingDir()));

            if (result.success() && result.toText().length() > 100) {
                return new ScrapeResult(true, result.toText(), engineName(), 200, null);
            }
            return new ScrapeResult(false, null, engineName(), -1, result.toText());
        }
    }

    // ── 引擎列表 ── 瀑布流顺序：Jina Reader 优先（LLM 优化 Markdown）
    private final List<ScrapeEngine> engines = List.of(
            new JinaReaderEngine(),
            new PlaywrightEngine(),
            new HttpEngine(),
            new CurlEngine()
    );

    @Override public String name() { return "web_scrape"; }

    @Override public String description() {
        return "Scrapes a web page using multi-engine waterfall (JinaReader→Playwright→HTTP→curl). Jina Reader returns LLM-optimized clean Markdown first; falls back to Playwright/HTTP/curl. Better than web_fetch for JS-heavy sites.";
    }

    @Override public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\",\"description\":\"The URL to scrape\"},\"formats\":{\"type\":\"string\",\"description\":\"Output format: markdown, html, or both (default: markdown)\"}},\"required\":[\"url\"]}";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        log.info("[WebScrape] 开始瀑布流抓取: url={}", input.url());

        // ── 瀑布流执行 — 借鉴 Firecrawl 的引擎瀑布流 ──
        AtomicReference<ScrapeResult> bestResult = new AtomicReference<>();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        try {
            for (ScrapeEngine engine : engines) {
                // 如果已有成功结果，不再启动新引擎
                if (bestResult.get() != null && bestResult.get().success()) break;

                log.debug("[WebScrape] 启动引擎: {} (MRT={}ms)", engine.engineName(), engine.mrtMs());

                CompletableFuture<ScrapeResult> future = CompletableFuture.supplyAsync(
                        () -> engine.scrape(input.url(), context), executor);

                try {
                    ScrapeResult result = future.get(engine.mrtMs(), TimeUnit.MILLISECONDS);
                    if (result.success()) {
                        bestResult.set(result);
                        log.info("[WebScrape] 引擎 {} 成功: url={}", engine.engineName(), input.url());
                        break;
                    }
                } catch (TimeoutException e) {
                    future.cancel(true);
                    log.debug("[WebScrape] 引擎 {} 超时 (MRT={}ms)", engine.engineName(), engine.mrtMs());
                } catch (Exception e) {
                    log.debug("[WebScrape] 引擎 {} 异常: {}", engine.engineName(), e.getMessage());
                }
            }
        } finally {
            executor.shutdownNow();
        }

        ScrapeResult result = bestResult.get();
        if (result == null || !result.success()) {
            return ToolOutput.fail("All scrape engines failed for: " + input.url());
        }

        // ── 后处理 ──
        String content = result.content();
        String engineUsed = result.engineUsed();

        // 截断
        if (content.length() > MAX_CONTENT_LENGTH) {
            content = content.substring(0, MAX_CONTENT_LENGTH)
                    + "\n... [truncated at " + MAX_CONTENT_LENGTH + " chars]";
        }

        // 如果请求 markdown 格式，使用 HtmlToMarkdownTool 转换
        if (input.formats().contains("markdown") && looksLikeHtml(content)) {
            HtmlToMarkdownTool mdTool = new HtmlToMarkdownTool();
            HtmlToMarkdownTool.Input mdInput = new HtmlToMarkdownTool.Input(content, input.onlyMainContent());
            ToolOutput mdResult = mdTool.call(mdInput, context);
            if (mdResult.success()) {
                content = mdResult.toText();
            }
        }

        // 添加元信息
        String output = "[Scraped via " + engineUsed + " engine]\n\n" + content;
        return ToolOutput.ok(output);
    }

    /** 简单判断内容是否为 HTML */
    private boolean looksLikeHtml(String content) {
        String lower = content.toLowerCase().trim();
        return lower.startsWith("<!doctype") || lower.startsWith("<html")
                || (lower.contains("<body") && lower.contains("</body>"))
                || (lower.contains("<div") && lower.contains("</div>"));
    }

    @Override public boolean readOnly() { return true; }

    @Override public String prompt() {
        return "Use web_scrape for fetching web pages with JS rendering support. Uses multi-engine waterfall: JinaReader→Playwright→HTTP→curl. Jina Reader returns LLM-optimized clean Markdown first to avoid context bloat. Prefer over web_fetch for modern websites.";
    }
}
