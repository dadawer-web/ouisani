package com.ouisani.aios.core.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

public class OpenAiAdapter implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiAdapter.class);

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int timeoutSeconds;
    private final HttpClient httpClient;
    private final Gson gson;

    private final String embeddingApiKey;
    private final String embeddingBaseUrl;
    private final String embeddingModel;

    public OpenAiAdapter(String apiKey, String baseUrl, String model, int timeoutSeconds) {
        this.apiKey = apiKey;
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
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

    public OpenAiAdapter(String apiKey, String baseUrl, String model) {
        this(apiKey, baseUrl, model, 120);
    }

    public OpenAiAdapter(String apiKey) {
        this(apiKey, "https://api.openai.com", "gpt-4o-mini");
    }

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

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

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
    public String think(String prompt, String systemPrompt) {
        List<ChatMessage> messages = List.of(ChatMessage.user(prompt));
        return thinkWithHistory(messages, systemPrompt);
    }

    @Override
    public String thinkWithHistory(List<ChatMessage> messages, String systemPrompt) {
        if (apiKey == null || apiKey.isBlank()) {
            String error = "[LLM ERROR] No API key configured";
            log.error(error);
            return error;
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
            m.addProperty("content", msg.content());
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
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                String errorBody = response.body().length() > 300
                        ? response.body().substring(0, 300) + "..."
                        : response.body();
                String errorMsg = "[LLM ERROR] HTTP " + response.statusCode() + ": " + errorBody;
                log.error("API returned non-200: status={}, body={}", response.statusCode(), errorBody);
                return errorMsg;
            }

            return parseResponse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            String errorMsg = "[LLM ERROR] Request interrupted";
            log.warn("Request interrupted");
            return errorMsg;
        } catch (Exception e) {
            String errorMsg = "[LLM ERROR] Request failed: " + e.getMessage();
            log.error("Request failed: {}", e.getMessage(), e);
            return errorMsg;
        }
    }

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

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
            return "[LLM ERROR] Invalid JSON response";
        }
    }

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
