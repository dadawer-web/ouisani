package com.ouisani.aios.drivers.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.ipc.SignalInterceptor;
import com.ouisani.aios.core.llm.ComputeCore;
import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.llm.LlmProvider.ChatMessage;
import com.ouisani.aios.core.llm.StreamCancellationHook;
import com.ouisani.aios.core.llm.auth.AuthProfile;
import com.ouisani.aios.core.llm.auth.AuthProfileManager;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

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
     * 共享 HttpClient 连接池 — 按 baseUrl 隔离。
     * Java 21 的 HttpClient 内部维护连接池（Keep-Alive + HTTP/2 多路复用），
     * 虚拟线程环境下共享同一个实例是安全的（send() 方法是线程安全的）。
     * 之前的 "selector manager closed" 问题是因为 HttpClient 被 GC 回收导致的，
     * 用强引用持有即可解决。
     */
    private static final ConcurrentHashMap<String, HttpClient> CLIENT_POOL = new ConcurrentHashMap<>();

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

        log.info("OpenAiAdapter 已初始化: baseUrl={}, model={}, timeout={}s",
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
     * 获取或创建共享 HttpClient — 连接池复用。
     * 按 baseUrl 隔离，同一 API 端点共享同一个 HttpClient 实例。
     * HttpClient 内部自动管理 Keep-Alive 连接池和 HTTP/2 多路复用，
     * 并发虚拟线程共享同一个实例是线程安全的。
     */
    /**
     * 专用虚拟线程执行器 — 让 HttpClient 底层的 I/O 也拥抱虚拟线程。
     * <p>
     * 【动刀3】默认 HttpClient 的内部 Selector 和回调使用平台线程，
     * 并发请求受限于平台线程数。注入虚拟线程执行器后，
     * 每个 HTTP 请求的 I/O 等待不再占用 OS 线程。
     */
    private static final java.util.concurrent.ExecutorService HTTP_VTHREAD_EXECUTOR =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    private HttpClient getOrCreateClient(String url) {
        return CLIENT_POOL.computeIfAbsent(url, k -> {
            log.info("[OpenAiAdapter] Created shared HttpClient for: {} (virtual-thread executor)", k);
            return HttpClient.newBuilder()
                    .executor(HTTP_VTHREAD_EXECUTOR)  // 【动刀3】注入虚拟线程执行器
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
        });
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

            HttpResponse<String> response = getOrCreateClient(embeddingBaseUrl).send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Embedding API 返回 HTTP {}: {}", response.statusCode(),
                        response.body().length() > 200 ? response.body().substring(0, 200) : response.body());
                System.out.println("  [LLM Adapter] Embedding API failed (HTTP " + response.statusCode()
                        + "), falling back to Mock Embedding");
                return mockEmbedLocal(text);
            }

            return parseEmbeddingResponse(response.body());
        } catch (Exception e) {
            log.error("Embedding 请求失败: {}", e.getMessage());
            System.out.println("  [LLM Adapter] Embedding 请求失败 (" + e.getMessage()
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

    /** 同步推理 + ephemeral 系统上下文块（send-time 追加到最后一条 user message）。 */
    @Override
    public String think(String prompt, String systemPrompt, String ephemeralContext) {
        List<ChatMessage> messages = List.of(ChatMessage.user(prompt));
        return thinkWithHistory(messages, systemPrompt, ephemeralContext);
    }

    // ════════════════════════════════════════════════════════════════
    //  真流式推理 — SSE 逐 token 推送
    // ════════════════════════════════════════════════════════════════

    /**
     * 流式推理（含系统提示词）— 真正的 SSE 逐 token 推送。
     * <p>
     * 重写 {@link LlmProvider#thinkStream} 的默认降级实现，使用
     * {@code stream: true} + SSE 解析，逐 token 回调 onDelta。
     * 用户立刻能看到输出，也能更早发现 LLM 跑偏。
     * <p>
     * 与 {@link #thinkWithHistory} 共享信号拦截、Profile 轮换、超时重试逻辑，
     * 但使用流式 HTTP 响应 + SSE 行解析。
     */
    @Override
    public String thinkStream(String prompt, String systemPrompt, Consumer<String> onDelta) {
        List<ChatMessage> messages = List.of(ChatMessage.user(prompt));
        return thinkWithHistoryStream(messages, systemPrompt, onDelta);
    }

    /** 流式推理 + ephemeral 系统上下文块（send-time 追加到最后一条 user message）。 */
    @Override
    public String thinkStream(String prompt, String systemPrompt, String ephemeralContext, Consumer<String> onDelta) {
        List<ChatMessage> messages = List.of(ChatMessage.user(prompt));
        return thinkWithHistoryStream(messages, systemPrompt, ephemeralContext, onDelta);
    }

    /**
     * 流式多轮对话推理 — 与 {@link #thinkWithHistory} 对应的流式版本。
     * <p>
     * 共享信号拦截、请求体构建、Profile 轮换逻辑，但使用
     * {@code stream: true} 和 {@link #executeStreamingRequest}。
     */
    @Override
    public String thinkWithHistoryStream(List<ChatMessage> messages, String systemPrompt,
                                          Consumer<String> onDelta) {
        return thinkWithHistoryStreamInternal(messages, systemPrompt, "", onDelta);
    }

    /**
     * 流式多轮对话推理（含 ephemeral 系统上下文块）— 与 {@link #thinkWithHistory(List, String, String)} 对应的流式版本。
     * <p>
     * ephemeralContext 由 {@link #buildRequestBody} 追加为 {@code <system-context>} 块到最后一条
     * user message（send-time only，永不持久化）。借鉴 OpenWorker engine.py:966-985。
     */
    @Override
    public String thinkWithHistoryStream(List<ChatMessage> messages, String systemPrompt,
                                          String ephemeralContext, Consumer<String> onDelta) {
        return thinkWithHistoryStreamInternal(messages, systemPrompt, ephemeralContext, onDelta);
    }

    /**
     * thinkWithHistoryStream 的实际实现 — 3 参 / 4 参公共入口。
     * 共享信号拦截、请求体构建、Profile 轮换逻辑，但使用
     * {@code stream: true} 和 {@link #executeStreamingRequest}。
     */
    private String thinkWithHistoryStreamInternal(List<ChatMessage> messages, String systemPrompt,
                                                   String ephemeralContext, Consumer<String> onDelta) {
        boolean useProfileRotation = !AuthProfileManager.getInstance().getAllProfiles().isEmpty();

        // ── 信号拦截（与 thinkWithHistory 相同）──
        AgentTask currentTask = TaskScheduler.CURRENT_TASK.get();
        if (currentTask != null) {
            try {
                String prefix = SignalInterceptor.checkAndDrain(currentTask);
                if (prefix != null) {
                    messages = new java.util.ArrayList<>(messages);
                    for (int i = 0; i < messages.size(); i++) {
                        ChatMessage msg = messages.get(i);
                        if ("user".equals(msg.role()) && !msg.isMultimodal()) {
                            messages.set(i, new ChatMessage("user", prefix + msg.contentAsString()));
                            break;
                        }
                    }
                    log.info("[SignalInterceptor] SIGUSR1 prefix injected into stream prompt for Agent#{}",
                            currentTask.pid());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("LLM stream request interrupted by signal", e);
            }
        }

        // ── 构建请求体（stream: true，含 ephemeral <system-context> 块）──
        String bodyStr = buildRequestBody(messages, systemPrompt, ephemeralContext, true);
        int msgCount = messages.size() + (systemPrompt != null && !systemPrompt.isBlank() ? 1 : 0);

        // ── 带熔断轮换的流式请求循环 ──
        int localRetries = useProfileRotation ? 3 : 1;

        for (int attempt = 0; attempt < localRetries; attempt++) {
            String requestApiKey;
            String requestBaseUrl;

            if (useProfileRotation) {
                AuthProfile profile = AuthProfileManager.getInstance().acquireHealthyProfile("openai");
                requestApiKey = profile.getApiKey();
                requestBaseUrl = normalizeBaseUrl(profile.getBaseUrl());
                try {
                    String result = executeStreamingRequest(requestBaseUrl, requestApiKey, bodyStr, msgCount, onDelta);
                    profile.reportSuccess();
                    return result;
                } catch (RuntimeException e) {
                    if (e.getMessage() != null && (e.getMessage().contains("429")
                            || e.getMessage().contains("Too Many Requests")
                            || e.getMessage().contains("503")
                            || e.getMessage().contains("Overloaded"))) {
                        log.warn("[OpenAiAdapter] Profile {} hit rate limit in stream. Triggering cooldown.",
                                profile.getProfileId());
                        profile.reportFailure();
                        continue;
                    } else {
                        throw e;
                    }
                }
            } else {
                if (apiKey == null || apiKey.isBlank()) {
                    throw new RuntimeException("[LLM FATAL] No API key configured — cannot perform stream inference");
                }
                return executeStreamingRequest(baseUrl, apiKey, bodyStr, msgCount, onDelta);
            }
        }
        throw new RuntimeException("[OpenAiAdapter] Auth profile rotation exhausted after " + localRetries + " stream attempts.");
    }

    /**
     * 构建请求体 JSON — 提取自 thinkWithHistory 和 thinkWithHistoryStream 的公共逻辑。
     *
     * @param stream true 使用流式，false 使用同步
     */
    private String buildRequestBody(List<ChatMessage> messages, String systemPrompt, boolean stream) {
        return buildRequestBody(messages, systemPrompt, "", stream);
    }

    /**
     * 构建请求体 JSON — 提取自 thinkWithHistory 和 thinkWithHistoryStream 的公共逻辑。
     * <p>
     * 4 参版本额外接受 ephemeralContext：非空时由 {@link #appendEphemeralBlock} 追加为
     * {@code <system-context>} 块到最后一条 user message（send-time only，不回写 messages 入参）。
     * 借鉴 OpenWorker engine.py:966-985：context_provider() 返回值作为 ephemeral 块，
     * 永不持久化到对话历史。
     *
     * @param stream           true 使用流式，false 使用同步
     * @param ephemeralContext 每轮易变上下文（send-time only，永不持久化）
     */
    private String buildRequestBody(List<ChatMessage> messages, String systemPrompt,
                                    String ephemeralContext, boolean stream) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", stream);

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
                m.add("content", contentArray);
            } else {
                m.addProperty("content", msg.contentAsString());
            }
            msgArray.add(m);
        }

        // ── ephemeral <system-context> 块：send-time only，追加到最后一条 user 消息 ──
        appendEphemeralBlock(msgArray, ephemeralContext);

        body.add("messages", msgArray);
        return gson.toJson(body);
    }

    /**
     * 把 ephemeralContext 作为 {@code <system-context>} 块追加到 msgArray 中最后一条 user 消息。
     * <p>
     * 借鉴 OpenWorker engine.py:971-984：send-time only，作用于请求体内的 JSON 对象，
     * 不回写 {@code messages} 入参或持久化历史。匹配三种 content 形态：
     * <ul>
     *   <li>字符串 → 末尾追加 block</li>
     *   <li>多模态 JsonArray → 追加一个 {type:text, text:block} part</li>
     *   <li>其它/缺失 → 直接设为 block（兜底）</li>
     * </ul>
     * package-private 以便单测直接验证。
     */
    static void appendEphemeralBlock(JsonArray msgArray, String ephemeralContext) {
        if (ephemeralContext == null || ephemeralContext.isBlank()) return;
        String block = "\n\n<system-context>\n" + ephemeralContext + "\n</system-context>";
        for (int i = msgArray.size() - 1; i >= 0; i--) {
            JsonObject m = msgArray.get(i).getAsJsonObject();
            if (!m.has("role") || !"user".equals(m.get("role").getAsString())) continue;
            JsonElement contentElem = m.get("content");
            if (contentElem != null && contentElem.isJsonPrimitive() && contentElem.getAsJsonPrimitive().isString()) {
                m.addProperty("content", contentElem.getAsString() + block);
            } else if (contentElem != null && contentElem.isJsonArray()) {
                JsonObject textPart = new JsonObject();
                textPart.addProperty("type", "text");
                textPart.addProperty("text", block);
                contentElem.getAsJsonArray().add(textPart);
            } else {
                m.addProperty("content", block);
            }
            break;
        }
    }

    /**
     * 执行流式 HTTP 请求 — SSE 解析，逐 token 回调。
     * <p>
     * 使用 {@code BodyHandlers.ofInputStream()} 获取流式响应体，
     * 逐行读取 SSE {@code data:} 行，解析 JSON 提取 {@code delta.content}，
     * 调用 {@code onDelta.accept(content)} 推送每个 token 片段。
     * <p>
     * 虚拟线程环境下阻塞式 BufferedReader.readLine() 不占用 OS 线程。
     */
    private String executeStreamingRequest(String requestBaseUrl, String requestApiKey,
                                            String bodyStr, int msgCount, Consumer<String> onDelta) {
        final int MAX_TIMEOUT_RETRIES = 2;
        for (int attempt = 0; attempt <= MAX_TIMEOUT_RETRIES; attempt++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(requestBaseUrl + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + requestApiKey)
                    .header("Accept", "text/event-stream")
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                    .build();

            try {
                long startNanos = System.nanoTime();
                HttpResponse<InputStream> response = getOrCreateClient(requestBaseUrl)
                        .send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    String errorBody;
                    try (InputStream errStream = response.body()) {
                        byte[] errBytes = errStream.readAllBytes();
                        errorBody = new String(errBytes, StandardCharsets.UTF_8);
                    }
                    String truncated = errorBody.length() > 300 ? errorBody.substring(0, 300) + "..." : errorBody;
                    int code = response.statusCode();
                    // 429 / 503 速率限制：退避重试（单 key 场景下 profile 轮换无效，靠退避等 RPM 恢复）
                    if ((code == 429 || code == 503) && attempt < MAX_TIMEOUT_RETRIES) {
                        long waitMs = (long) (2000 * Math.pow(2, attempt)); // 指数退避: 2s, 4s
                        log.warn("[OpenAiAdapter] 流式请求命中速率限制 (HTTP {}, attempt {}/{}), {}ms 后退避重试... body={}",
                                code, attempt + 1, MAX_TIMEOUT_RETRIES + 1, waitMs, truncated);
                        try { Thread.sleep(waitMs); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("LLM stream request interrupted during rate-limit backoff", ie);
                        }
                        continue;
                    }
                    log.error("Stream API returned non-200: status={}, body={}", code, truncated);
                    throw new RuntimeException("LLM stream request failed: HTTP " + code + ": " + truncated);
                }

                // ── SSE 流解析 ──
                StringBuilder fullResponse = new StringBuilder();
                StreamCancellationHook cancelHook = StreamCancellationHook.current();
                if (cancelHook != null) {
                    cancelHook.bind(response.body());
                }
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // ── 中断检查：Stop 按钮能在此处打断流式读取 ──
                        if (cancelHook != null && cancelHook.isCancelled()) {
                            log.info("[OpenAiAdapter] Stream cancelled by user after {} chars, returning partial response",
                                    fullResponse.length());
                            break;
                        }
                        if (!line.startsWith("data: ")) continue;
                        String data = line.substring(6).trim();
                        if (data.isEmpty() || "[DONE]".equals(data)) continue;

                        try {
                            JsonObject chunk = JsonParser.parseString(data).getAsJsonObject();
                            JsonArray choices = chunk.getAsJsonArray("choices");
                            if (choices == null || choices.isEmpty()) continue;
                            JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
                            if (delta != null && delta.has("content") && !delta.get("content").isJsonNull()) {
                                String content = delta.get("content").getAsString();
                                fullResponse.append(content);
                                onDelta.accept(content);
                            }
                        } catch (Exception parseEx) {
                            log.debug("SSE chunk parse skip: {}",
                                    data.length() > 100 ? data.substring(0, 100) + "..." : data);
                        }
                    }
                } catch (java.io.IOException ioe) {
                    // InputStream 被 cancel() 关闭时 readLine() 抛 IOException — 预期行为
                    if (cancelHook != null && cancelHook.isCancelled()) {
                        log.info("[OpenAiAdapter] Stream InputStream closed by cancel(), returning partial ({} chars)",
                                fullResponse.length());
                        // 落入下方的 partial response 返回逻辑
                    } else {
                        throw new RuntimeException("LLM stream IO error: " + ioe.getMessage(), ioe);
                    }
                } finally {
                    if (cancelHook != null) {
                        cancelHook.unbind();
                    }
                }

                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
                boolean wasCancelled = cancelHook != null && cancelHook.isCancelled();
                SemanticEtw.getInstance().logEvent("LLM", wasCancelled ? "CALL_STREAM_CANCELLED" : "CALL_STREAM",
                        "model=" + model + " status=200 latencyMs=" + elapsedMs
                                + " msgCount=" + msgCount + " responseLen=" + fullResponse.length()
                                + " cancelled=" + wasCancelled);
                log.debug("[OpenAiAdapter] Stream {} ({} chars in {}ms, msgCount={})",
                        wasCancelled ? "cancelled" : "completed", fullResponse.length(), elapsedMs, msgCount);

                // 被取消时返回 partial response（即使为空也返回，让 QueryEngine 知道被中断了）
                if (wasCancelled) {
                    return fullResponse.toString();
                }

                if (fullResponse.isEmpty()) {
                    log.warn("[OpenAiAdapter] Stream returned empty response (msgCount={}), falling back to non-streaming", msgCount);
                    throw new RuntimeException("LLM stream returned empty response (possible non-streaming API)");
                }
                return fullResponse.toString();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("LLM stream request interrupted", e);
            } catch (java.net.http.HttpTimeoutException e) {
                if (attempt < MAX_TIMEOUT_RETRIES) {
                    long waitSeconds = 3L * (attempt + 1);
                    log.warn("[OpenAiAdapter] LLM 流式请求超时 (attempt {}/{}), {}s 后重试...",
                            attempt + 1, MAX_TIMEOUT_RETRIES + 1, waitSeconds);
                    try { Thread.sleep(waitSeconds * 1000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("LLM stream request interrupted during retry wait", ie);
                    }
                    continue;
                }
                throw new RuntimeException("LLM stream request failed: " + e.getMessage(), e);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("LLM stream request failed: " + e.getMessage(), e);
            }
        }
        throw new RuntimeException("LLM stream request failed after timeout retries");
    }

    /**
     * 基于多轮对话历史向 LLM 发送推理请求。
     * <p>
     * 流程：信号拦截 → 构建请求体 → HTTP 调用 → 解析响应 → 遥测记录
     */
    @Override
    public String thinkWithHistory(List<ChatMessage> messages, String systemPrompt) {
        return thinkWithHistoryInternal(messages, systemPrompt, "");
    }

    /**
     * 基于多轮对话历史向 LLM 发送推理请求（含 ephemeral 系统上下文块）。
     * <p>
     * ephemeralContext 由 {@link #buildRequestBody} 追加为 {@code <system-context>} 块到最后一条
     * user message（send-time only，永不持久化）。借鉴 OpenWorker engine.py:966-985。
     */
    @Override
    public String thinkWithHistory(List<ChatMessage> messages, String systemPrompt, String ephemeralContext) {
        return thinkWithHistoryInternal(messages, systemPrompt, ephemeralContext);
    }

    /**
     * thinkWithHistory 的实际实现 — 3 参 / 4 参公共入口。
     * 流程：信号拦截 → 构建请求体 → HTTP 调用 → 解析响应 → 遥测记录
     */
    private String thinkWithHistoryInternal(List<ChatMessage> messages, String systemPrompt, String ephemeralContext) {
        // ── Auth Profile 轮换：优先从 AuthProfileManager 获取健康 Key ──
        // 如果 AuthProfileManager 中有注册的 Profile，使用轮换机制；
        // 否则回退到构造器传入的硬编码 apiKey（向后兼容）
        boolean useProfileRotation = !AuthProfileManager.getInstance().getAllProfiles().isEmpty();

        // 信号拦截：在发出真实 LLM 请求前检查挂起的信号
        AgentTask currentTask = TaskScheduler.CURRENT_TASK.get();
        if (currentTask != null) {
            try {
                String prefix = SignalInterceptor.checkAndDrain(currentTask);
                if (prefix != null) {
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

        // ── 构建请求体（stream: false，含 ephemeral <system-context> 块）──
        // 复用 buildRequestBody 消除重复逻辑；msgCount 用于遥测/日志（原 msgArray.size()）。
        String bodyStr = buildRequestBody(messages, systemPrompt, ephemeralContext, false);
        int msgCount = messages.size() + (systemPrompt != null && !systemPrompt.isBlank() ? 1 : 0);

        // ── 带熔断轮换的请求循环 ──
        int localRetries = useProfileRotation ? 3 : 1;

        for (int attempt = 0; attempt < localRetries; attempt++) {
            // 确定本次请求使用的 Key 和 URL
            String requestApiKey;
            String requestBaseUrl;

            if (useProfileRotation) {
                AuthProfile profile = AuthProfileManager.getInstance().acquireHealthyProfile("openai");
                requestApiKey = profile.getApiKey();
                requestBaseUrl = normalizeBaseUrl(profile.getBaseUrl());

                log.debug("Sending request to {}/v1/chat/completions (model={}, messages={}, profile={})",
                        requestBaseUrl, model, msgCount, profile.getProfileId());

                try {
                    String result = executeHttpRequest(requestBaseUrl, requestApiKey, bodyStr, msgCount);
                    profile.reportSuccess();
                    return result;
                } catch (RuntimeException e) {
                    if (e.getMessage() != null && (e.getMessage().contains("429")
                            || e.getMessage().contains("Too Many Requests")
                            || e.getMessage().contains("503")
                            || e.getMessage().contains("Overloaded"))) {
                        log.warn("[OpenAiAdapter] Profile {} hit rate limit (429/503). Triggering cooldown.",
                                profile.getProfileId());
                        profile.reportFailure();
                        // 循环继续，下一次 acquireHealthyProfile 会自动避开这把坏掉的 Key
                        continue;
                    } else {
                        // 非限流错误，直接抛给上层的 11 层自愈系统
                        throw e;
                    }
                }
            } else {
                // 回退模式：使用构造器传入的硬编码 apiKey
                if (apiKey == null || apiKey.isBlank()) {
                    throw new RuntimeException("[LLM FATAL] No API key configured — cannot perform inference");
                }
                requestApiKey = apiKey;
                requestBaseUrl = baseUrl;

                log.debug("Sending request to {}/v1/chat/completions (model={}, messages={})",
                        requestBaseUrl, model, msgCount);

                return executeHttpRequest(requestBaseUrl, requestApiKey, bodyStr, msgCount);
            }
        }

        throw new RuntimeException("[OpenAiAdapter] Auth profile rotation exhausted after " + localRetries + " attempts.");
    }

    /**
     * 执行实际的 HTTP 请求并解析响应。
     * <p>
     * 对超时错误（HttpTimeoutException）自动重试最多 2 次，
     * 每次重试前等待递增时间（3s, 6s），避免 LLM 服务短暂过载时立即失败。
     */
    private String executeHttpRequest(String requestBaseUrl, String requestApiKey, String bodyStr, int msgCount) {
        final int MAX_TIMEOUT_RETRIES = 2;
        for (int attempt = 0; attempt <= MAX_TIMEOUT_RETRIES; attempt++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(requestBaseUrl + "/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + requestApiKey)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                    .build();

            try {
                long startNanos = System.nanoTime();
                HttpResponse<String> response = getOrCreateClient(requestBaseUrl).send(request, HttpResponse.BodyHandlers.ofString());
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

                SemanticEtw.getInstance().logEvent("LLM", "CALL",
                        "model=" + model + " status=" + response.statusCode()
                        + " latencyMs=" + elapsedMs + " msgCount=" + msgCount);

                if (response.statusCode() != 200) {
                    String errorBody = response.body().length() > 300
                            ? response.body().substring(0, 300) + "..."
                            : response.body();
                    int code = response.statusCode();
                    // 429 / 503 速率限制：退避重试（指数退避 2s, 4s，等 RPM 恢复）
                    if ((code == 429 || code == 503) && attempt < MAX_TIMEOUT_RETRIES) {
                        long waitMs = (long) (2000 * Math.pow(2, attempt));
                        log.warn("[OpenAiAdapter] 请求命中速率限制 (HTTP {}, attempt {}/{}), {}ms 后退避重试... body={}",
                                code, attempt + 1, MAX_TIMEOUT_RETRIES + 1, waitMs, errorBody);
                        try { Thread.sleep(waitMs); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("LLM request interrupted during rate-limit backoff", ie);
                        }
                        continue;
                    }
                    log.error("API returned non-200: status={}, body={}", code, errorBody);
                    throw new RuntimeException("LLM request failed: HTTP " + code + ": " + errorBody);
                }

                return parseResponse(response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("请求被中断");
                throw new RuntimeException("LLM request interrupted", e);
            } catch (RuntimeException e) {
                throw e;
            } catch (java.net.http.HttpTimeoutException e) {
                if (attempt < MAX_TIMEOUT_RETRIES) {
                    long waitSeconds = 3L * (attempt + 1);
                    log.warn("[OpenAiAdapter] LLM 请求超时 (attempt {}/{}), {}s 后重试...",
                            attempt + 1, MAX_TIMEOUT_RETRIES + 1, waitSeconds);
                    try { Thread.sleep(waitSeconds * 1000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("LLM request interrupted during retry wait", ie);
                    }
                    continue;
                }
                log.error("[OpenAiAdapter] LLM 请求超时，已重试 {} 次仍失败", MAX_TIMEOUT_RETRIES);
                throw new RuntimeException("LLM request failed: " + e.getMessage(), e);
            } catch (Exception e) {
                log.error("Request failed: {}", e.getMessage(), e);
                throw new RuntimeException("LLM request failed: " + e.getMessage(), e);
            }
        }
        throw new RuntimeException("LLM request failed after timeout retries");
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

            HttpResponse<String> response = getOrCreateClient(embeddingBaseUrl).send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 || response.statusCode() == 401;
        } catch (Exception e) {
            log.debug("可用性检查失败: {}", e.getMessage());
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

            log.debug("已收到响应（{} 字符）", content.length());
            return content;
        } catch (Exception e) {
            log.error("JSON 解析错误: {} | body={}", e.getMessage(),
                    responseBody.length() > 200 ? responseBody.substring(0, 200) + "..." : responseBody);
            throw new RuntimeException("LLM response parse error: " + e.getMessage(), e);
        }
    }

    /** 规范化基础 URL：去除尾部斜杠与多余的 /v1，空值回退到 OpenAI 官方地址。
     *  <p>本 adapter 内部会拼 {@code /v1/chat/completions}，故 baseUrl 不应带 /v1；
     *  用户常填 {@code https://host/v1}，此处自动剥离避免拼成 {@code /v1/v1/...}。 */
    private static String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return "https://api.openai.com";
        }
        String normalized = url.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/v1")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        return normalized;
    }
}
