package com.ouisani.aios.drivers.memory;

import com.ouisani.aios.core.memory.providers.MemoryProvider;
import com.ouisani.aios.core.syscall.SyscallException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Mem0 云端记忆驱动 — 生产级 HTTP 客户端，直接对接 Mem0 向量数据库 API。
 * <p>
 * 已从内核空间 (core.memory.providers) 迁移至驱动空间 (drivers.memory)。
 * 内核只定义 {@link MemoryProvider} 抽象接口，具体厂商实现作为驱动动态加载。
 * <p>
 * 这不是 Mock。每个方法都发出真实的网络请求到
 * {@code https://api.mem0.ai/v1/memories/}，使用 Java 11+ 原生
 * {@link HttpClient}。API 密钥从环境变量 {@code MEM0_API_KEY} 读取。
 *
 * @see MemoryProvider
 */
public class Mem0Provider implements MemoryProvider {

    private static final Logger log = LoggerFactory.getLogger(Mem0Provider.class);

    // ── Mem0 API 端点 ──
    private static final String API_URL = "https://api.mem0.ai/v1/memories/";
    private static final String SEARCH_URL = "https://api.mem0.ai/v1/memories/search/";

    // ── ANSI 终端颜色码 ──
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_RESET = "\u001B[0m";

    private final HttpClient httpClient;
    private final String apiKey;

    public Mem0Provider() {
        this.apiKey = resolveApiKey();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_2)
                .build();

        if (apiKey != null && !apiKey.isEmpty()) {
            log.info("[Mem0 Driver] HTTP 客户端已初始化。API 密钥已加载 (len={})。", apiKey.length());
        } else {
            log.warn("[Mem0 Driver] No API key found. All requests will fail with SyscallException.");
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  STORE — POST /v1/memories/
    // ════════════════════════════════════════════════════════════════

    @Override
    public boolean store(String agentId, String memoryContent) {
        ensureApiKey();

        String jsonPayload = buildStorePayload(agentId, memoryContent);

        log.info("[Mem0 Driver] Issuing POST {} — agent='{}', contentLen={}",
                API_URL, agentId, memoryContent != null ? memoryContent.length() : 0);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .timeout(Duration.ofSeconds(30))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 201) {
                System.out.println(ANSI_GREEN
                        + "[Mem0 Driver] Successfully stored neural memory for agent: " + agentId
                        + ANSI_RESET);
                log.info("[Mem0 Driver] Store success: agent='{}', httpStatus={}", agentId, response.statusCode());
                return true;
            } else {
                log.error("[Mem0 Driver] Store failed: agent='{}', httpStatus={}, body={}",
                        agentId, response.statusCode(), response.body());
                System.out.println(ANSI_RED
                        + "[Mem0 Driver] Store failed for agent: " + agentId
                        + " (HTTP " + response.statusCode() + ")" + ANSI_RESET);
                return false;
            }
        } catch (Exception e) {
            log.error("[Mem0 Driver] Store request exception: agent='{}', error={}", agentId, e.getMessage(), e);
            throw new SyscallException("memory.store", "Mem0 External API call failed: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  RETRIEVE — POST /v1/memories/search/
    // ════════════════════════════════════════════════════════════════

    @Override
    public String retrieve(String agentId, String query) {
        ensureApiKey();

        String jsonPayload = buildSearchPayload(agentId, query);

        log.info("[Mem0 Driver] Issuing POST {} — agent='{}', query='{}'",
                SEARCH_URL, agentId, query);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SEARCH_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .timeout(Duration.ofSeconds(30))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String result = extractSearchResults(response.body());

                System.out.println(ANSI_GREEN
                        + "[Mem0 Driver] Executed real vector search in external cloud for agent: " + agentId
                        + ANSI_RESET);
                log.info("[Mem0 Driver] Retrieve success: agent='{}', resultLen={}", agentId, result.length());
                return result;
            } else {
                log.error("[Mem0 Driver] Retrieve failed: agent='{}', httpStatus={}, body={}",
                        agentId, response.statusCode(), response.body());
                throw new SyscallException("memory.retrieve",
                        "Mem0 External API call failed (HTTP " + response.statusCode() + ")");
            }
        } catch (SyscallException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Mem0 Driver] Retrieve request exception: agent='{}', error={}", agentId, e.getMessage(), e);
            throw new SyscallException("memory.retrieve", "Mem0 External API call failed: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  CLEAR — DELETE /v1/memories/
    // ════════════════════════════════════════════════════════════════

    @Override
    public void clear(String agentId) {
        ensureApiKey();

        String jsonPayload = buildClearPayload(agentId);

        log.info("[Mem0 Driver] Issuing DELETE {} — agent='{}'", API_URL, agentId);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(jsonPayload))
                .timeout(Duration.ofSeconds(30))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 204) {
                log.info("[Mem0 Driver] Clear success: agent='{}', httpStatus={}", agentId, response.statusCode());
            } else {
                log.error("[Mem0 Driver] Clear failed: agent='{}', httpStatus={}, body={}",
                        agentId, response.statusCode(), response.body());
                throw new SyscallException("memory.delete",
                        "Mem0 External API call failed (HTTP " + response.statusCode() + ")");
            }
        } catch (SyscallException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Mem0 Driver] Clear request exception: agent='{}', error={}", agentId, e.getMessage(), e);
            throw new SyscallException("memory.delete", "Mem0 External API call failed: " + e.getMessage());
        }
    }

    @Override
    public String providerName() {
        return "Mem0";
    }

    // ════════════════════════════════════════════════════════════════
    //  JSON Payload Builders
    // ════════════════════════════════════════════════════════════════

    private String buildStorePayload(String agentId, String memoryContent) {
        return "{\"messages\":[{\"role\":\"user\",\"content\":"
                + escapeJsonString(memoryContent)
                + "}],\"user_id\":" + escapeJsonString(agentId) + "}";
    }

    private String buildSearchPayload(String agentId, String query) {
        return "{\"query\":" + escapeJsonString(query)
                + ",\"user_id\":" + escapeJsonString(agentId) + "}";
    }

    private String buildClearPayload(String agentId) {
        return "{\"user_id\":" + escapeJsonString(agentId) + "}";
    }

    // ════════════════════════════════════════════════════════════════
    //  JSON Response Parser
    // ════════════════════════════════════════════════════════════════

    private String extractSearchResults(String responseBody) {
        if (responseBody == null || responseBody.isEmpty() || responseBody.equals("[]")) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        int idx = 0;

        while (idx < responseBody.length()) {
            int memoryKeyStart = responseBody.indexOf("\"memory\"", idx);
            if (memoryKeyStart < 0) break;

            int colonPos = responseBody.indexOf(':', memoryKeyStart + 8);
            if (colonPos < 0) break;

            int valueStart = responseBody.indexOf('"', colonPos + 1);
            if (valueStart < 0) break;

            int valueEnd = findClosingQuote(responseBody, valueStart + 1);
            if (valueEnd < 0) break;

            String memoryText = unescapeJsonString(responseBody.substring(valueStart + 1, valueEnd));
            if (!memoryText.isEmpty()) {
                if (!result.isEmpty()) {
                    result.append("\n");
                }
                result.append(memoryText);
            }

            idx = valueEnd + 1;
        }

        return result.toString();
    }

    private int findClosingQuote(String json, int fromIndex) {
        int i = fromIndex;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == '"') {
                return i;
            } else {
                i++;
            }
        }
        return -1;
    }

    // ════════════════════════════════════════════════════════════════
    //  Utilities
    // ════════════════════════════════════════════════════════════════

    private void ensureApiKey() {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new SyscallException("memory", "Mem0 External API call failed: MEM0_API_KEY not configured");
        }
    }

    private static String resolveApiKey() {
        String key = System.getenv("MEM0_API_KEY");
        if (key != null && !key.isEmpty()) {
            return key;
        }
        key = System.getProperty("MEM0_API_KEY");
        if (key != null && !key.isEmpty()) {
            return key;
        }
        return null;
    }

    private static String escapeJsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default   -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String unescapeJsonString(String s) {
        if (s == null) return "";
        return s.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\b", "\b")
                .replace("\\f", "\f");
    }
}
