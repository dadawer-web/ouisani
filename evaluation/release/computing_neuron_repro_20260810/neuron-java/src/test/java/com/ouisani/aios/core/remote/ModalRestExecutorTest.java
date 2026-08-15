package com.ouisani.aios.core.remote;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModalRestExecutor} 单元测试。
 * <p>
 * 用 JDK 自带 {@code com.sun.net.httpserver.HttpServer} 做本地 HTTP 桩，验证：
 * <ul>
 *   <li>请求构造（header 鉴权 + JSON body 业务参数）— 直接调 package-private buildRequest/buildJsonBody</li>
 *   <li>execute 全路径 — 本地 HttpServer 返回 canned 响应，验证 success/2xx/非2xx/超时/configError</li>
 * </ul>
 * HttpClient 是 final 类无法 mock，故用真实 HttpClient + 本地 HttpServer 桩。
 */
class ModalRestExecutorTest {

    private static final Gson GSON = new Gson();

    private HttpServer server;
    private int port;
    private HandlerConfig handlerConfig;
    private ModalRestExecutor executor;

    /** 桩 handler 的行为配置 — 每个测试 @BeforeEach 重置。 */
    private static class HandlerConfig {
        int status = 200;
        String body = "{\"result\":\"rest-out\"}";
        long sleepMs = 0; // 模拟慢响应触发超时
    }

    @BeforeEach
    void setUp() throws IOException {
        handlerConfig = new HandlerConfig();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", new StubHandler());
        server.start();
        executor = new ModalRestExecutor(HttpClient.newHttpClient());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private RemoteExecutorConfig configWithTimeout(long timeoutSeconds) {
        // 用 modalRest 工厂 + 覆盖 timeoutSeconds（工厂用默认 600）
        return new RemoteExecutorConfig("modal-rest", null, 22, null, null, null,
                null, null, 0, 0, 0, null,
                "http://127.0.0.1:" + port + "/exec", "train",
                "tid-123", "tsecret-456", "my-ws",
                timeoutSeconds, null);
    }

    private static class StubHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // 读取请求 body（消费掉，避免连接挂起）
            byte[] reqBody = exchange.getRequestBody().readAllBytes();
            HandlerConfig cfg = HandlerHolder.CFG;
            try {
                if (cfg.sleepMs > 0) {
                    Thread.sleep(cfg.sleepMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] respBody = cfg.body.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(cfg.status, respBody.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(respBody);
            }
            HandlerHolder.LAST_REQUEST_BODY = new String(reqBody);
        }
    }

    /** 把 HandlerConfig 传给 handler 的 holder（handler 是无状态 lambda 友好的静态类）。 */
    private static class HandlerHolder {
        static volatile HandlerConfig CFG;
        static volatile String LAST_REQUEST_BODY;
    }

    @Test
    @DisplayName("buildRequest 设置 Modal-Token-Id/Secret/Content-Type/Workspace header")
    void buildRequest_setsModalTokenHeaders() {
        RemoteExecutorConfig config = configWithTimeout(60);
        HttpRequest req = ModalRestExecutor.buildRequest(config, "{}");

        assertEquals("application/json", req.headers().firstValue("Content-Type").orElse(""));
        assertEquals("tid-123", req.headers().firstValue("Modal-Token-Id").orElse(""));
        assertEquals("tsecret-456", req.headers().firstValue("Modal-Token-Secret").orElse(""));
        assertEquals("my-ws", req.headers().firstValue("Modal-Workspace").orElse(""));
        assertEquals("POST", req.method());
    }

    @Test
    @DisplayName("buildJsonBody 含 command/workingDir + MODAL_ARG_ 业务参数")
    void buildJsonBody_includesCommandAndWorkingDir() {
        RemoteExecutorConfig config = new RemoteExecutorConfig("modal-rest", null, 22, null, null, null,
                null, null, 0, 0, 0, null,
                "http://localhost:1/exec", "train",
                "tid", "tsecret", null,
                60L, Map.of("MODAL_ARG_EPOCHS", "10", "MODAL_ARG_LR", "0.01"));
        String json = ModalRestExecutor.buildJsonBody(config, "python train.py", "/work");

        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("python train.py", obj.get("command").getAsString());
        assertEquals("/work", obj.get("workingDir").getAsString());
        assertEquals("10", obj.get("EPOCHS").getAsString());
        assertEquals("0.01", obj.get("LR").getAsString());
    }

    @Test
    @DisplayName("execute 2xx 返回 success，stdout 取 result 字段")
    void execute_success_returnsParsedResult() {
        HandlerHolder.CFG = handlerConfig; // 默认 200 + {"result":"rest-out"}
        handlerConfig.body = "{\"result\":\"rest-out\"}";

        RemoteResult r = executor.execute(configWithTimeout(10), "python train.py", "/work");

        assertTrue(r.success(), "errorMessage=" + r.errorMessage());
        assertEquals("rest-out", r.stdout());
    }

    @Test
    @DisplayName("execute 非 2xx 返回 failure，exitCode = HTTP status")
    void execute_non2xx_returnsFailure() {
        HandlerHolder.CFG = handlerConfig;
        handlerConfig.status = 500;
        handlerConfig.body = "internal error";

        RemoteResult r = executor.execute(configWithTimeout(10), "python train.py", null);

        assertFalse(r.success());
        assertEquals(500, r.exitCode());
        assertTrue(r.errorMessage().contains("500"));
    }

    @Test
    @DisplayName("execute 超时返回 timeout 结果")
    void execute_timeout_returnsTimeoutResult() {
        HandlerHolder.CFG = handlerConfig;
        handlerConfig.sleepMs = 3000; // 慢响应
        handlerConfig.status = 200;

        RemoteResult r = executor.execute(configWithTimeout(1), "python train.py", null);

        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("timed out"),
                "expected timeout message, got: " + r.errorMessage());
    }

    @Test
    @DisplayName("config 缺 endpoint/token → configError（不抛）")
    void missingEndpoint_returnsConfigError() {
        // 缺 endpoint URL（modalAppPath=null）
        RemoteExecutorConfig noEndpoint = new RemoteExecutorConfig("modal-rest", null, 22, null, null, null,
                null, null, 0, 0, 0, null,
                null, "train", "tid", "tsecret", null, 10L, null);
        RemoteResult r1 = executor.execute(noEndpoint, "cmd", null);
        assertFalse(r1.success());
        assertTrue(r1.errorMessage().contains("endpoint"));

        // 缺 token
        RemoteExecutorConfig noToken = RemoteExecutorConfig.modalRest(
                "http://127.0.0.1:" + port + "/exec", "train", null, null);
        RemoteResult r2 = executor.execute(noToken, "cmd", null);
        assertFalse(r2.success());
        assertTrue(r2.errorMessage().contains("modalTokenId"));
    }

    @Test
    @DisplayName("type 返回 modal-rest")
    void type_returnsModalRest() {
        assertEquals("modal-rest", executor.type());
    }

    @Test
    @DisplayName("parseResponseBody 兼容 JSON result 字段与纯文本末行")
    void parseResponseBody_handlesJsonAndText() {
        assertEquals("json-out", ModalRestExecutor.parseResponseBody("{\"result\":\"json-out\"}"));
        assertEquals("text-out", ModalRestExecutor.parseResponseBody("log line\ntext-out"));
        assertEquals("", ModalRestExecutor.parseResponseBody(""));
        // JSON 无 result 字段 → 回退末行（整个 JSON 串作为一行）
        assertEquals("{\"ok\":true}", ModalRestExecutor.parseResponseBody("{\"ok\":true}"));
    }
}
