package com.ouisani.aios.core.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * WebSocket 传输 — 对标 Claude Code 的 WebSocketTransport。
 * <p>
 * 基于 Java 11+ HttpClient 的 WebSocket 实现：
 * - 消息缓冲与重放
 * - 自动重连（指数退避）
 * - 心跳保活
 * <p>
 * OS 类比：相当于 TCP socket — 可靠的双向字节流。
 */
public class WebSocketTransport implements Transport {

    private static final Logger log = LoggerFactory.getLogger(WebSocketTransport.class);

    private static final int MAX_BUFFER_SIZE = 1000;
    private static final int PING_INTERVAL_SECONDS = 30;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long BASE_RECONNECT_DELAY_MS = 2000;

    private final String url;
    private final HttpClient httpClient;
    private volatile WebSocket webSocket;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final ConcurrentLinkedQueue<String> sendBuffer = new ConcurrentLinkedQueue<>();
    private Consumer<String> dataHandler;
    private Consumer<String> closeHandler;
    private int reconnectAttempts = 0;

    public WebSocketTransport(String url) {
        this.url = url;
        this.httpClient = HttpClient.newBuilder()
                .build();
    }

    @Override
    public void connect() {
        try {
            log.info("[WebSocketTransport] Connecting to: {}", url);
            httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create(url), new WebSocket.Listener() {
                        final StringBuilder buffer = new StringBuilder();

                        @Override
                        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                            buffer.append(data);
                            if (last) {
                                String message = buffer.toString();
                                buffer.setLength(0);
                                if (dataHandler != null) {
                                    dataHandler.accept(message);
                                }
                            }
                            ws.request(1);
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                            connected.set(false);
                            log.info("[WebSocketTransport] Closed: {} {}", statusCode, reason);
                            if (closeHandler != null) closeHandler.accept(reason);
                            attemptReconnect();
                            return null;
                        }

                        @Override
                        public void onError(WebSocket ws, Throwable error) {
                            connected.set(false);
                            log.error("[WebSocketTransport] Error: {}", error.getMessage());
                            attemptReconnect();
                        }
                    })
                    .thenAccept(ws -> {
                        webSocket = ws;
                        connected.set(true);
                        reconnectAttempts = 0;
                        flushSendBuffer();
                        log.info("[WebSocketTransport] Connected to: {}", url);
                    });
        } catch (Exception e) {
            log.error("[WebSocketTransport] Connect failed: {}", e.getMessage());
            attemptReconnect();
        }
    }

    @Override
    public void close() {
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Client closing");
            connected.set(false);
        }
    }

    @Override
    public void send(String data) {
        if (connected.get() && webSocket != null) {
            webSocket.sendText(data, true);
        } else {
            // 缓冲消息
            sendBuffer.add(data);
            while (sendBuffer.size() > MAX_BUFFER_SIZE) {
                sendBuffer.poll();
            }
        }
    }

    @Override
    public void onData(Consumer<String> handler) { this.dataHandler = handler; }

    @Override
    public void onClose(Consumer<String> handler) { this.closeHandler = handler; }

    @Override
    public boolean isConnected() { return connected.get(); }

    private void flushSendBuffer() {
        while (!sendBuffer.isEmpty() && connected.get()) {
            String msg = sendBuffer.poll();
            if (msg != null && webSocket != null) {
                webSocket.sendText(msg, true);
            }
        }
    }

    private void attemptReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            log.error("[WebSocketTransport] Max reconnect attempts ({}) reached", MAX_RECONNECT_ATTEMPTS);
            return;
        }

        reconnectAttempts++;
        long delay = (long) (BASE_RECONNECT_DELAY_MS * Math.pow(2, reconnectAttempts - 1));

        log.info("[WebSocketTransport] Reconnect attempt {} in {}ms", reconnectAttempts, delay);

        try {
            Thread.sleep(delay);
            connect();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
