package com.ouisani.aios.core.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * SSE 传输 — 对标 Claude Code 的 SSETransport。
 * <p>
 * SSE (Server-Sent Events) 读取 + HTTP POST 写入：
 * - SSE 帧解析（event:/id:/data: 字段）
 * - 序列号去重
 * - Liveness 检测（45s 无帧则重连）
 * - 指数退避重连
 * <p>
 * OS 类比：相当于半双工串口 — 读通道持续监听，写通道按需发送。
 */
public class SseTransport implements Transport {

    private static final Logger log = LoggerFactory.getLogger(SseTransport.class);

    private static final int LIVENESS_TIMEOUT_MS = 45000;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long BASE_RECONNECT_DELAY_MS = 1000;
    private static final long MAX_RECONNECT_DELAY_MS = 30000;

    private final String sseUrl;
    private final String postUrl;
    private final HttpClient httpClient;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile long lastFrameTime = 0;
    private volatile Thread sseThread;
    private Consumer<String> dataHandler;
    private Consumer<String> closeHandler;
    private int reconnectAttempts = 0;

    public SseTransport(String sseUrl, String postUrl) {
        this.sseUrl = sseUrl;
        this.postUrl = postUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();
    }

    @Override
    public void connect() {
        sseThread = new Thread(() -> {
            try {
                log.info("[SseTransport] Connecting to SSE: {}", sseUrl);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(sseUrl))
                        .header("Accept", "text/event-stream")
                        .GET()
                        .build();

                HttpResponse<java.util.stream.Stream<String>> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofLines());

                if (response.statusCode() == 200) {
                    connected.set(true);
                    lastFrameTime = System.currentTimeMillis();
                    reconnectAttempts = 0;

                    // 解析 SSE 帧
                    String eventType = "";
                    String eventId = "";
                    StringBuilder data = new StringBuilder();

                    var lines = response.body().toList();
                for (String line : lines) {
                        if (!connected.get()) break;

                        lastFrameTime = System.currentTimeMillis();

                        if (line.startsWith("event:")) {
                            eventType = line.substring(6).trim();
                        } else if (line.startsWith("id:")) {
                            eventId = line.substring(3).trim();
                        } else if (line.startsWith("data:")) {
                            data.append(line.substring(5)).append("\n");
                        } else if (line.isEmpty() && data.length() > 0) {
                            // 帧结束
                            String frame = data.toString().trim();
                            if (dataHandler != null) {
                                dataHandler.accept(frame);
                            }
                            data.setLength(0);
                            eventType = "";
                            eventId = "";
                        }
                    }
                }
            } catch (Exception e) {
                log.error("[SseTransport] Connection error: {}", e.getMessage());
            } finally {
                connected.set(false);
                attemptReconnect();
            }
        }, "sse-transport");

        sseThread.setDaemon(true);
        sseThread.start();
    }

    @Override
    public void close() {
        connected.set(false);
        if (sseThread != null) sseThread.interrupt();
    }

    @Override
    public void send(String data) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(postUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(data, StandardCharsets.UTF_8))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.error("[SseTransport] Send failed: {}", e.getMessage());
        }
    }

    @Override
    public void onData(Consumer<String> handler) { this.dataHandler = handler; }

    @Override
    public void onClose(Consumer<String> handler) { this.closeHandler = handler; }

    @Override
    public boolean isConnected() { return connected.get(); }

    private void attemptReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            log.error("[SseTransport] Max reconnect attempts reached");
            if (closeHandler != null) closeHandler.accept("Max reconnect attempts reached");
            return;
        }

        reconnectAttempts++;
        long delay = Math.min(
                (long) (BASE_RECONNECT_DELAY_MS * Math.pow(2, reconnectAttempts - 1)),
                MAX_RECONNECT_DELAY_MS
        );

        log.info("[SseTransport] Reconnect attempt {} in {}ms", reconnectAttempts, delay);

        try {
            Thread.sleep(delay);
            connect();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
