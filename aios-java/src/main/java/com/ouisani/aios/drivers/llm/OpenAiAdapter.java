package com.ouisani.aios.drivers.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.ipc.SignalInterceptor;
import com.ouisani.aios.core.llm.ComputeCore;
import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.llm.LlmProvider.ChatMessage;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * OpenAI API 适配器 — AIOS 用户空间设备驱动实现。
 * <p>
 * 已从内核空间 (core.llm) 迁移至驱动空间 (drivers.llm)。
 * 内核只定义 {@link LlmProvider} 抽象接口，具体厂商实现作为驱动动态加载。
 * <p>
 * 类比操作系统中的具体设备驱动：正如 ext4 驱动实现了 VFS 接口来操作磁盘，
 * OpenAiAdapter 实现了 {@link LlmProvider} 接口来访问 OpenAI 的 API。
 * 上层（如 {@link com.ouisani.aios.core.llm.LlmRouter}）只依赖 LlmProvider 接口，
 * 不感知底层是 OpenAI 还是其他 LLM 服务。
 *
 * @see LlmProvider
 * @see com.ouisani.aios.core.llm.LlmRouter
 */
public class OpenAiAdapter implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiAdapter.class);

    /** Chat Completion API 的基础 URL */
    private final String baseUrl;
    /** API 密钥 */
    private final String apiKey;
    /** 模型名称（如 gpt-4o, gpt-4o-mini） */
    private final String model;
    /** 请求超时时间（秒） */
    private final int timeoutSeconds;
    private final Gson gson;

    /** Embedding API 的独立密钥（可与 Chat API 使用不同密钥） */
    private final String embeddingApiKey;
    /** Embedding API 的基础 URL */
    private final String embeddingBaseUrl;
    /** Embedding 模型名称 */
    private final String embeddingModel;

    /**
     * 完整构造器。
     *
     * @param apiKey         Chat API 密钥
     * @param baseUrl        Chat API 基础 URL
     * @param model          模型名称
     * @param timeoutSeconds 请求超时时间（秒）
     */
    public OpenAiAdapter(String apiKey, String baseUrl, String model, int timeoutSeconds) {
        this.apiKey = apiKey;
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.gson = new Gson();

        this.embeddingApiKey = System.getenv().getOrDefault("EMBEDDING_API_KEY", "");
        this.embeddingBaseUrl = normalizeBaseUrl(
                System.getenv().getOrDefault("EMBEDDING_BASE_URL", ""));
        this.embeddingModel = System.getenv().getOrDefault("EMBEDDING_MODEL", "text-embedding-3-small");

        log.info("OpenAiAdapter initialized: baseUrl={}, model={}, timeout={}s",
                this.baseUrl, this.model, this.timeoutSeconds);
        log.info("Embedding config: baseUrl={}, model={}, hasKey={}",
                this.embeddingBaseUrl, this.embeddingModel,
                this.embeddingApiKey != null && !this.embeddingApiKey.isBlank());
    }

    /** 简化构造器，默认超时 300 秒（大模型生成长代码耗时极长，120s 不够） */
    public OpenAiAdapter(String apiKey, String baseUrl, String model) {
        this(apiKey, baseUrl, model, 300);
        log.info("[OpenAiAdapter] LLM Request timeout extended to 300s to prevent generation truncation.");
    }

    /** 最简构造器，默认使用 OpenAI 官方地址和 gpt-4o-mini 模型 */
    public OpenAiAdapter(String apiKey) {
        this(apiKey, "https://api.openai.com", "gpt-4o-mini");
    }

    /**
     * 为每次请求创建新的 HttpClient。
     * 避免在虚拟线程环境下共享 HttpClient 导致的 "selector manager closed" 问题。
     */
    private HttpClient createHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(300))
                .build();
    }

    /**
     * 调用 Embedding API 将文本转换为向量。
     * 如果 Embedding API 不可用或调用失败，降级为本地模拟向量。
     */
    @Override
    public float[] embed(String text) {
        if (embeddingApiKey == null || embeddingApiKey.isBlank()) {
            System.out.println("  [LLM Adapter] Using Mock Embedding for text: "
                    + text.substring(0, Math.min(50, text.length())) + "...");
            return mockEmbedLocal(text);
        }

        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", embeddingModel);
            body.addProperty("input", text);

            String bodyStr = gson.toJson(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(embeddingBaseUrl + "/v1/embeddings"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + embeddingApiKey)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                    .build();

            HttpResponse<String> response = createHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Embedding API returned HTTP {}: {}", response.statusCode(),
                        response.body().length() > 200 ? response.body().substring(0, 200) : response.body());
                System.out.println("  [LLM Adapter] Embedding API failed (HTTP " + response.statusCode()
                        + "), falling back to Mock Embedding");
                return mockEmbedLocal(text);
            }

            return parseEmbeddingResponse(response.body());
        } catch (Exception e) {
            log.error("Embedding request failed: {}", e.getMessage());
            System.out.println("  [LLM Adapter] Embedding request failed (" + e.getMessage()
                    + "), falling back to Mock Embedding");
            return mockEmbedLocal(text);
        }
    }

    /** 解析 Embedding API 的 JSON 响应，提取向量数组 */
    private float[] parseEmbeddingResponse(String responseBody) {
        JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonArray embeddingArray = json.getAsJsonArray("data")
                .get(0).getAsJsonObject()
                .getAsJsonArray("embedding");

        float[] result = new float[embeddingArray.size()];
        for (int i = 0; i < embeddingArray.size(); i++) {
            result[i] = embeddingArray.get(i).getAsFloat();
        }

        log.debug("Embedding received: {} dimensions", result.length);
        return result;
    }

    /** 本地模拟 Embedding 生成 — 使用确定性伪随机算法 */
    private float[] mockEmbedLocal(String text) {
        int dimensions = 1536;
        float[] vector = new float[dimensions];
        int hash = text.hashCode();
        long seed = hash != 0 ? Math.abs(hash) : 42;
        for (int i = 0; i < dimensions; i++) {
            seed = (seed * 6364136223846793005L + 1442695040888963407L);
            vector[i] = ((float) ((seed >>> 33) & 0x7FFFFFFF) / 0x7FFFFFFF - 0.5f) * 0.1f;
        }
        return vector;
    }

    @Override
    public String name() {
        return "OpenAI-" + model;
    }

    @Override
    public ComputeCore computeCore() {
        return ComputeCore.E_CORE; // 默认为 E_CORE，由 LlmRouter 决定路由
    }

    /**
     * 向 LLM 发送推理请求（含系统提示词）。
     * 在发送前会检查是否有挂起的信号（SIGUSR1），如有则注入中断前缀。
     */
    @Override
    public String think(String prompt, String systemPrompt) {
        List<ChatMessage> messages = List.of(ChatMessage.user(prompt));
        return thinkWithHistory(messages, systemPrompt);
    }

    /**
     * 基于多轮对话历史向 LLM 发送推理请求。
     * <p>
     * 流程：信号拦截 → 构建请求体 → HTTP 调用 → 解析响应 → 遥测记录
     */
    @Override
    public String thinkWithHistory(List<ChatMessage> messages, String systemPrompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("[LLM FATAL] No API key configured — cannot perform inference");
        }

        // 信号拦截：在发出真实 LLM 请求前检查挂起的信号
        AgentTask currentTask = TaskScheduler.CURRENT_TASK.get();
        if (currentTask != null) {
            try {
                String prefix = SignalInterceptor.checkAndDrain(currentTask);
                if (prefix != null) {
                    // 收到 SIGUSR1：将中断前缀注入到第一条用户消息中
                    List<ChatMessage> modified = new java.util.ArrayList<>(messages);
                    for (int i = 0; i < modified.size(); i++) {
                        ChatMessage msg = modified.get(i);
                        if ("user".equals(msg.role()) && !msg.isMultimodal()) {
                            modified.set(i, new ChatMessage("user", prefix + msg.contentAsString()));
                            break;
                        }
                    }
                    messages = modified;
                    log.info("[SignalInterceptor] SIGUSR1 prefix injected into prompt for Agent#{}", currentTask.pid());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[SignalInterceptor] Agent#{} interrupted by signal before LLM call", currentTask.pid());
                throw new RuntimeException("LLM request interrupted by signal", e);
            }
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", false);

        JsonArray msgArray = new JsonArray();

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            JsonObject sysMsg = new JsonObject();
            sysMsg.addProperty("role", "system");
            sysMsg.addProperty("content", systemPrompt);
            msgArray.add(sysMsg);
        }

        for (ChatMessage msg : messages) {
            JsonObject m = new JsonObject();
            m.addProperty("role", msg.role());

            if (msg.isMultimodal() && msg.content() instanceof JsonArray contentArray) {
                // 多模态消息：content 为 JsonArray（text + image_url 块）
                m.add("content", contentArray);
            } else {
                // 纯文本消息：content 为 String
                m.addProperty("content", msg.contentAsString());
            }
            msgArray.add(m);
        }

        body.add("messages", msgArray);

        String bodyStr = gson.toJson(body);

        log.debug("Sending request to {}/v1/chat/completions (model={}, messages={})",
                baseUrl, model, msgArray.size());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                .build();

        try {
            long startNanos = System.nanoTime();
            HttpResponse<String> response = createHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

            SemanticEtw.getInstance().logEvent("LLM", "CALL",
                    "model=" + model + " status=" + response.statusCode()
                    + " latencyMs=" + elapsedMs + " msgCount=" + msgArray.size());

            if (response.statusCode() != 200) {
                String errorBody = response.body().length() > 300
                        ? response.body().substring(0, 300) + "..."
                        : response.body();
                log.error("API returned non-200: status={}, body={}", response.statusCode(), errorBody);
                throw new RuntimeException("LLM request failed: HTTP " + response.statusCode() + ": " + errorBody);
            }

            return parseResponse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Request interrupted");
            throw new RuntimeException("LLM request interrupted", e);
        } catch (RuntimeException e) {
            throw e; // 重新抛出我们自己的 RuntimeException
        } catch (Exception e) {
            log.error("Request failed: {}", e.getMessage(), e);
            throw new RuntimeException("LLM request failed: " + e.getMessage(), e);
        }
    }

    /**
     * 检查 API 是否可用（密钥已配置且服务可达）。
     * 通过 GET /models 端点验证连通性。
     */
    @Override
    public boolean isAvailable() {
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/models"))
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = createHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 || response.statusCode() == 401;
        } catch (Exception e) {
            log.debug("Availability check failed: {}", e.getMessage());
            return false;
        }
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String model() {
        return model;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 解析 Chat Completion API 的 JSON 响应，提取助手回复内容 */
    private String parseResponse(String responseBody) {
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
            String content = json.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();

            log.debug("Response received ({} chars)", content.length());
            return content;
        } catch (Exception e) {
            log.error("JSON parse error: {} | body={}", e.getMessage(),
                    responseBody.length() > 200 ? responseBody.substring(0, 200) + "..." : responseBody);
            throw new RuntimeException("LLM response parse error: " + e.getMessage(), e);
        }
    }

    /** 规范化基础 URL：去除尾部斜杠，空值回退到 OpenAI 官方地址 */
    private static String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return "https://api.openai.com";
        }
        String normalized = url.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
