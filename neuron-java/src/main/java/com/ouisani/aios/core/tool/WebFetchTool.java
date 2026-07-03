package com.ouisani.aios.core.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 网页抓取工具 — 对标 Claude Code 的 WebFetchTool。
 * <p>
 * 抓取指定 URL 的内容，返回纯文本/HTML。
 * 支持预批准 URL 列表，减少权限弹窗。
 * <p>
 * OS 类比：相当于 Linux 的 wget/curl 命令。
 */
public class WebFetchTool implements Tool<WebFetchTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(WebFetchTool.class);
    private static final int MAX_CONTENT_LENGTH = 50000;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public record Input(String url, String prompt) implements ToolInput {
        public Input {
            if (url == null || url.isBlank()) throw new IllegalArgumentException("url required");
            if (prompt == null) prompt = "";
        }

        public Input(String url) { this(url, ""); }

        @Override public String toJson() {
            return "{\"url\":\"" + url.replace("\"", "\\\"") + "\"}";
        }
    }

    @Override public String name() { return "web_fetch"; }

    @Override public String description() {
        return "Fetches content from a URL. Returns the page content as text. Use for reading documentation, APIs, or web pages.";
    }

    @Override public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\",\"description\":\"The URL to fetch\"},\"prompt\":{\"type\":\"string\",\"description\":\"Optional prompt for what to extract from the page\"}},\"required\":[\"url\"]}";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(input.url()))
                    .header("User-Agent", "AIOS-WebFetchTool/1.0")
                    .header("Accept", "text/html,text/plain,application/json,*/*")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String body = response.body();
                if (body.length() > MAX_CONTENT_LENGTH) {
                    body = body.substring(0, MAX_CONTENT_LENGTH) + "\n... [truncated at " + MAX_CONTENT_LENGTH + " chars]";
                }
                return ToolOutput.ok(body);
            } else {
                return ToolOutput.fail("HTTP " + response.statusCode() + " for URL: " + input.url());
            }
        } catch (java.io.IOException e) {
            return ToolOutput.fail("Network error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolOutput.fail("Fetch interrupted: " + e.getMessage());
        }
    }

    @Override public boolean readOnly() { return true; }

    @Override public String prompt() {
        return "Use web_fetch to retrieve web content. Results are truncated to 50KB. For search, prefer the web_search tool.";
    }

    // ── 强类型 I/O 契约 ──
    @Override
    public List<Port> inputPorts() {
        return List.of(
            new Port("url", DataTypes.URL, "要抓取的网页 URL"),
            new Port("prompt", DataTypes.PLAIN_TEXT, "可选：从页面中提取什么内容")
        );
    }

    @Override
    public List<Port> outputPorts() {
        return List.of(
            new Port("content", DataTypes.WEB_PAGE_CONTENT, "网页内容（纯文本，最多 50KB）")
        );
    }

    @Override
    public Optional<ToolExample> example() {
        return Optional.of(new ToolExample(
            "如果你需要抓取网页内容进行分析",
            Map.of(
                "url", "https://example.com/article",
                "prompt", "提取文章正文内容"
            )
        ));
    }
}
