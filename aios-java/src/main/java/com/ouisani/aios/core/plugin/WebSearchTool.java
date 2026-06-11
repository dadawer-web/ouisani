package com.ouisani.aios.core.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 网络搜索工具 — 多后端搜索，为智能体提供真实的互联网搜索能力。
 * <p>
 * 搜索后端优先级：
 * 1. Serper API (google.serper.dev) — 国内可达，需要 API Key
 * 2. Jina Search (s.jina.ai) — 海外端点，国内可能被墙
 * <p>
 * OS 类比：相当于内核的 DNS 解析器 — 将符号名（搜索意图）解析为
 * 具体的网络资源（API 文档），供用户空间程序使用。
 */
public class WebSearchTool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    private static final int MAX_CONTEXT_LENGTH = 4000;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // 从环境变量读取 Serper API Key
    private static String serperApiKey = System.getenv().getOrDefault("SERPER_API_KEY", "");

    /**
     * 配置 Serper API Key（由 AiosShell 启动时从 .env 注入）。
     */
    public static void configureSerperApiKey(String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            serperApiKey = apiKey;
            log.info("[WebSearchTool] Serper API Key configured");
        }
    }

    /**
     * 为智能体执行网络搜索，返回截断的搜索结果。
     * <p>
     * 优先使用 Serper API（国内可达），失败则回退到 Jina。
     *
     * @param query 搜索关键词
     * @return 搜索结果文本（最多 4000 字符），失败时返回空字符串
     */
    public static String searchForAgent(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }

        // 优先尝试 Serper（国内可达）
        if (serperApiKey != null && !serperApiKey.isBlank()) {
            try {
                String result = searchViaSerper(query);
                if (result != null && !result.isBlank()) {
                    return result;
                }
            } catch (Exception e) {
                log.warn("[WebSearchTool] Serper search failed, falling back to Jina: {}", e.getMessage());
                System.out.println("[WebSearchTool] Serper failed, trying Jina fallback: " + e.getMessage());
            }
        }

        // 回退到 Jina（国内可能被墙）
        try {
            return searchViaJina(query);
        } catch (Exception e) {
            log.warn("[WebSearchTool] All search backends failed for query: '{}'", query);
            System.out.println("[WebSearchTool] All search backends failed: " + e.getMessage());
            throw new RuntimeException("Web search unavailable: " + e.getMessage(), e);
        }
    }

    /**
     * 通过 Serper API 搜索（Google 搜索代理，国内可达）。
     */
    private static String searchViaSerper(String query) {
        String url = "https://google.serper.dev/search";
        String body = "{\"q\":\"" + escapeJson(query) + "\",\"gl\":\"cn\",\"hl\":\"zh-cn\",\"num\":5}";

        log.info("[WebSearchTool] Searching Serper for: '{}'", query);
        System.out.println("[WebSearchTool] Searching Serper for: " + query);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-API-KEY", serperApiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() == 200) {
                String responseBody = response.body();
                // 将 Serper JSON 结果转换为简洁的文本格式
                String text = parseSerperResponse(responseBody);
                String truncated = text.length() > MAX_CONTEXT_LENGTH
                        ? text.substring(0, MAX_CONTEXT_LENGTH) + "\n... [truncated]"
                        : text;
                log.info("[WebSearchTool] Serper search completed: {} chars", truncated.length());
                System.out.printf("[WebSearchTool] Serper search completed: %d chars%n", truncated.length());
                return truncated;
            } else {
                throw new RuntimeException("Serper returned HTTP " + response.statusCode());
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException("Serper network error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Serper search interrupted", e);
        }
    }

    /**
     * 解析 Serper JSON 响应为简洁文本。
     */
    private static String parseSerperResponse(String json) {
        StringBuilder sb = new StringBuilder();
        // 简单的 JSON 解析：提取 organic results 的 title + snippet
        int idx = 0;
        int count = 0;
        while (idx < json.length() && count < 5) {
            int titleStart = json.indexOf("\"title\":", idx);
            if (titleStart < 0) break;

            String title = extractJsonValue(json, titleStart + 8);
            int snippetStart = json.indexOf("\"snippet\":", titleStart);
            String snippet = snippetStart > titleStart ? extractJsonValue(json, snippetStart + 10) : "";

            int linkStart = json.indexOf("\"link\":", titleStart);
            String link = linkStart > titleStart ? extractJsonValue(json, linkStart + 7) : "";

            if (title != null && !title.isEmpty()) {
                sb.append("[").append(count + 1).append("] ").append(title).append("\n");
                if (!link.isEmpty()) sb.append("    URL: ").append(link).append("\n");
                if (!snippet.isEmpty()) sb.append("    ").append(snippet).append("\n");
                sb.append("\n");
                count++;
            }
            idx = titleStart + 10;
        }
        return sb.toString();
    }

    /**
     * 从 JSON 字符串中提取引号内的值。
     */
    private static String extractJsonValue(String json, int startIdx) {
        if (startIdx >= json.length()) return "";
        // 跳过空白
        int i = startIdx;
        while (i < json.length() && json.charAt(i) != '"') i++;
        if (i >= json.length()) return "";
        i++; // skip opening quote
        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\') {
                i++;
                if (i < json.length()) sb.append(json.charAt(i));
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
            i++;
        }
        return sb.toString();
    }

    /**
     * 通过 Jina Search API 搜索（海外端点，国内可能被墙）。
     */
    private static String searchViaJina(String query) {
        String encodedQuery = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
        String url = "https://s.jina.ai/" + encodedQuery;

        log.info("[WebSearchTool] Searching Jina for: '{}'", query);
        System.out.println("[WebSearchTool] Searching Jina for: " + query);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "text/plain")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() == 200) {
                String responseBody = response.body();
                String truncated = responseBody.length() > MAX_CONTEXT_LENGTH
                        ? responseBody.substring(0, MAX_CONTEXT_LENGTH) + "\n... [truncated]"
                        : responseBody;
                log.info("[WebSearchTool] Jina search completed: {} chars", truncated.length());
                return truncated;
            } else {
                throw new RuntimeException("Jina returned HTTP " + response.statusCode());
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException("Jina network error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Jina search interrupted", e);
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t");
    }
}
