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
 * 网络搜索工具 — 接入 Jina Search API，为智能体提供真实的互联网搜索能力。
 * <p>
 * 使用 Jina 的 s.jina.ai 端点，返回纯 Markdown 格式的搜索结果，
 * 供 LLM 作为 RAG 上下文参考，避免代码生成时捏造不存在的 API。
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

    /**
     * 为智能体执行网络搜索，返回截断的 Markdown 格式搜索结果。
     *
     * @param query 搜索关键词
     * @return 搜索结果文本（最多 4000 字符），失败时返回空字符串
     */
    public static String searchForAgent(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }

        String encodedQuery = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
        String url = "https://s.jina.ai/" + encodedQuery;

        log.info("[WebSearchTool] Searching Jina for: '{}'", query);
        System.out.println("[WebSearchTool] Searching Jina for: " + query);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "text/plain")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() == 200) {
                String body = response.body();
                String truncated = body.length() > MAX_CONTEXT_LENGTH
                        ? body.substring(0, MAX_CONTEXT_LENGTH) + "\n... [truncated]"
                        : body;

                log.info("[WebSearchTool] Search completed: {} chars (truncated from {})", truncated.length(), body.length());
                System.out.printf("[WebSearchTool] Search completed: %d chars (from %d)%n", truncated.length(), body.length());
                return truncated;
            } else {
                log.warn("[WebSearchTool] Jina returned HTTP {}: {}", response.statusCode(), response.body().substring(0, Math.min(200, response.body().length())));
                System.out.printf("[WebSearchTool] Jina returned HTTP %d%n", response.statusCode());
                return "";
            }
        } catch (java.io.IOException e) {
            log.warn("[WebSearchTool] Network error during search: {}", e.getMessage());
            System.out.println("[WebSearchTool] Network error: " + e.getMessage());
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[WebSearchTool] Search interrupted: {}", e.getMessage());
            System.out.println("[WebSearchTool] Search interrupted: " + e.getMessage());
            return "";
        }
    }
}
