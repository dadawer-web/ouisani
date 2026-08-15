package com.ouisani.aios.core.remote;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Modal REST 执行器 — 通过 HTTP POST 直连已部署的 Modal Web Endpoint。
 * <p>
 * 与 {@link ModalExecutor}（走 {@code modal run} CLI）互补：REST 路径无需本地安装 modal 包，
 * 参数传递标准化（JSON body），适合生产 serverless 调用与容器化部署。
 * <p>
 * <b>请求构造</b>：
 * <pre>{@code
 * POST <endpointUrl>   // config.modalAppPath 字段复用为 endpoint URL
 * Content-Type: application/json
 * Modal-Token-Id: <tokenId>        // config.modalTokenId
 * Modal-Token-Secret: <tokenSecret> // config.modalTokenSecret
 * [Modal-Workspace: <workspace>]   // config.modalWorkspace（非空时）
 *
 * {"command": "...", "workingDir": "...", <MODAL_ARG_ 业务参数>}
 * }</pre>
 * <p>
 * <b>鉴权</b>：Modal Web Endpoint 用 {@code Modal-Token-Id} + {@code Modal-Token-Secret} 两个 header
 * （非 Bearer）。Token 经 config 字段传入（不进 env、不进 body，避免日志/响应泄露）。
 * <p>
 * <b>响应解析</b>：优先按 JSON 解析取 {@code result} 字段；解析失败则取末行非空内容
 * （与 {@link ModalExecutor#parseStdout} 一致，兼容纯文本响应）。
 * <p>
 * <b>错误映射</b>：
 * <ul>
 *   <li>2xx → {@link RemoteResult#success}</li>
 *   <li>非 2xx → {@link RemoteResult#failure}（exitCode = HTTP status）</li>
 *   <li>{@link HttpTimeoutException} → {@link RemoteResult#timeout}</li>
 *   <li>其他异常 → {@link RemoteResult#failure}（exitCode = -1）</li>
 *   <li>config 缺 endpoint/token → {@link RemoteResult#configError}（不抛，与 ModalExecutor 一致）</li>
 * </ul>
 * <p>
 * <b>异步语义</b>：本执行器无原生异步（HTTP 同步 send），继承 {@link RemoteExecutor} 的
 * default 同步回退实现。REST 异步（webhook 回调）留作 R4.2+。
 *
 * @see RemoteExecutor
 * @see ModalExecutor
 */
public final class ModalRestExecutor implements RemoteExecutor {

    private static final Logger log = LoggerFactory.getLogger(ModalRestExecutor.class);
    private static final Gson GSON = new Gson();

    /** env 中前缀为 MODAL_ARG_ 的项被剥离前缀后作为业务参数加入 JSON body（与 ModalExecutor 一致）。 */
    private static final String MODAL_ARG_PREFIX = "MODAL_ARG_";

    private final HttpClient httpClient;

    /** 生产构造器：用默认 {@link HttpClient}。 */
    public ModalRestExecutor() {
        this(HttpClient.newHttpClient());
    }

    /**
     * 注入构造器：测试用本地 {@code com.sun.net.httpserver.HttpServer} 桩时注入
     * 指向该桩的 HttpClient。
     */
    public ModalRestExecutor(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public RemoteResult execute(RemoteExecutorConfig config, String command, String workingDir) {
        if (config == null) return RemoteResult.configError("config is null");
        if (command == null || command.isBlank()) return RemoteResult.configError("command is empty");
        if (config.modalAppPath() == null || config.modalAppPath().isBlank()) {
            return RemoteResult.configError("modalAppPath (endpoint URL) required");
        }
        if (config.modalTokenId() == null || config.modalTokenId().isBlank()
                || config.modalTokenSecret() == null || config.modalTokenSecret().isBlank()) {
            return RemoteResult.configError("modalTokenId/modalTokenSecret required");
        }

        String jsonBody;
        HttpRequest request;
        try {
            jsonBody = buildJsonBody(config, command, workingDir);
            request = buildRequest(config, jsonBody);
        } catch (Exception e) {
            return RemoteResult.configError("build request failed: " + e.getMessage());
        }

        long start = System.currentTimeMillis();
        log.info("[ModalRestExecutor] POST: endpoint={}, fn={}, cmd={}",
                config.modalAppPath(), config.modalFunctionName(), command);

        try {
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - start;
            int status = resp.statusCode();
            String body = resp.body() == null ? "" : resp.body();

            if (status >= 200 && status < 300) {
                String parsed = parseResponseBody(body);
                log.info("[ModalRestExecutor] 成功 status={} ({}ms): endpoint={}",
                        status, elapsed, config.modalAppPath());
                return RemoteResult.success(parsed, elapsed);
            }

            log.warn("[ModalRestExecutor] 非 2xx status={} ({}ms): endpoint={}, body={}",
                    status, elapsed, config.modalAppPath(), body);
            return new RemoteResult(status, body, body, elapsed, false,
                    "HTTP " + status + (body.isBlank() ? "" : ": " + body.trim()));
        } catch (HttpTimeoutException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("[ModalRestExecutor] 超时 ({}ms): endpoint={}", elapsed, config.modalAppPath());
            return RemoteResult.timeout(elapsed);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("[ModalRestExecutor] 异常 ({}ms): endpoint={}, err={}",
                    elapsed, config.modalAppPath(), e.getMessage());
            return new RemoteResult(-1, "", e.getMessage() == null ? "" : e.getMessage(),
                    elapsed, false, "request failed: " + e.getMessage());
        }
    }

    @Override
    public String type() {
        return "modal-rest";
    }

    // ════════════════════════════════════════════════════════════════
    //  请求构造（package-private 便于测试断言）
    // ════════════════════════════════════════════════════════════════

    /**
     * 构造 HTTP POST 请求 — 含 Modal 鉴权 header + JSON body。
     *
     * @param config   执行器配置（modalAppPath=endpoint URL，modalTokenId/Secret/Workspace）
     * @param jsonBody 已序列化的 JSON 请求体
     */
    static HttpRequest buildRequest(RemoteExecutorConfig config, String jsonBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(config.modalAppPath()))
                .timeout(Duration.ofSeconds(Math.max(1, config.timeoutSeconds())))
                .header("Content-Type", "application/json")
                .header("Modal-Token-Id", config.modalTokenId())
                .header("Modal-Token-Secret", config.modalTokenSecret())
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        if (config.modalWorkspace() != null && !config.modalWorkspace().isBlank()) {
            builder.header("Modal-Workspace", config.modalWorkspace());
        }
        return builder.build();
    }

    /**
     * 构造 JSON 请求体 — {@code {"command": ..., "workingDir": ..., <MODAL_ARG_ 业务参数>}}。
     * <p>
     * 与 {@link ModalExecutor#buildModalArgv} 的 argsMap 构造逻辑一致：command + workingDir +
     * config.env() 中前缀为 {@code MODAL_ARG_} 的项（剥离前缀后作为 key）。
     */
    static String buildJsonBody(RemoteExecutorConfig config, String command, String workingDir) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("command", command);
        body.put("workingDir", workingDir == null ? "" : workingDir);
        if (config.env() != null) {
            for (Map.Entry<String, String> e : config.env().entrySet()) {
                if (e.getKey() != null && e.getKey().startsWith(MODAL_ARG_PREFIX)) {
                    String argKey = e.getKey().substring(MODAL_ARG_PREFIX.length());
                    if (!argKey.isEmpty()) {
                        body.put(argKey, e.getValue() == null ? "" : e.getValue());
                    }
                }
            }
        }
        return GSON.toJson(body);
    }

    /**
     * 解析响应体 — 优先取 JSON 的 {@code result} 字段；解析失败则取末行非空内容。
     * <p>
     * 兼容两种 Modal endpoint 响应格式：(1) {@code {"result": "..."}} 标准 JSON；
     * (2) 纯文本（函数 return 的字符串序列化）。全空返回 {@code ""}。
     */
    static String parseResponseBody(String body) {
        if (body == null || body.isBlank()) return "";
        String trimmed = body.trim();
        // 尝试 JSON 解析取 result 字段
        try {
            JsonElement elem = JsonParser.parseString(trimmed);
            if (elem.isJsonObject()) {
                JsonObject obj = elem.getAsJsonObject();
                if (obj.has("result")) {
                    JsonElement result = obj.get("result");
                    return result.isJsonNull() ? "" : result.getAsString();
                }
            }
        } catch (Exception ignored) {
            // 非 JSON，走纯文本末行回退
        }
        // 回退：取末行非空内容（与 ModalExecutor.parseStdout 一致）
        String[] lines = trimmed.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                return lines[i];
            }
        }
        return "";
    }
}
