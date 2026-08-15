package com.ouisani.aios.core.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 集群对等节点 — AIOS 集群中一个远程节点的网络连接抽象。
 * <p>
 * 类比 Raft 论文中节点间的 RPC 通信通道：
 * <ul>
 *   <li>每个 ClusterPeer 代表一个远程 AIOS 节点的连接</li>
 *   <li>通过 TCP Socket 进行可靠的消息传输</li>
 *   <li>支持异步发送和接收 RaftMessage</li>
 * </ul>
 *
 * <h3>通信协议</h3>
 * <pre>
 * ┌──────────┬──────────┬──────────────────────┐
 * │ magic(4) │ len(4)   │ JSON payload (len B) │
 │ 0xA105   │ int32 BE │ RaftMessage JSON     │
 * └──────────┴──────────┴──────────────────────┘
 * </pre>
 */
public class ClusterPeer {

    private static final Logger log = LoggerFactory.getLogger(ClusterPeer.class);
    private static final int MAGIC = 0xA105;

    private final String nodeId;
    private final String host;
    private final int port;

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private volatile boolean connected = false;

    /** 消息接收回调 */
    private Consumer<RaftMessage> messageHandler;

    /** 接收线程 */
    private Thread receiverThread;

    /** 发送线程池 */
    private ExecutorService sendExecutor;

    /** 统计 */
    private final ConcurrentHashMap<String, Long> lastMessageTime = new ConcurrentHashMap<>();
    private long messagesSent = 0;
    private long messagesReceived = 0;

    public ClusterPeer(String nodeId, String host, int port) {
        this.nodeId = nodeId;
        this.host = host;
        this.port = port;
        this.sendExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "cluster-send-" + nodeId);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 使用已有 Socket 连接创建 Peer（服务端接受连接时使用）。
     */
    public ClusterPeer(String nodeId, Socket socket) throws IOException {
        this.nodeId = nodeId;
        this.host = socket.getInetAddress().getHostAddress();
        this.port = socket.getPort();
        this.socket = socket;
        this.sendExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "cluster-send-" + nodeId);
            t.setDaemon(true);
            return t;
        });
        initStreams();
        this.connected = true;
    }

    // ════════════════════════════════════════════════════════════════
    //  连接管理
    // ════════════════════════════════════════════════════════════════

    /**
     * 连接到远程节点。
     */
    public boolean connect() {
        if (connected) return true;
        try {
            socket = new Socket(host, port);
            initStreams();
            connected = true;
            log.info("[ClusterPeer] 已连接至 {} ({}:{})", nodeId, host, port);
            return true;
        } catch (IOException e) {
            log.warn("[ClusterPeer] 连接失败 {} ({}:{}): {}", nodeId, host, port, e.getMessage());
            connected = false;
            return false;
        }
    }

    /**
     * 断开连接。
     */
    public void disconnect() {
        connected = false;
        try {
            if (in != null) in.close();
            if (out != null) out.close();
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
        if (receiverThread != null) receiverThread.interrupt();
        sendExecutor.shutdownNow();
        log.info("[ClusterPeer] 已断开与 {} 的连接", nodeId);
    }

    /**
     * 启动消息接收循环。
     */
    public void startReceiving(Consumer<RaftMessage> handler) {
        this.messageHandler = handler;
        receiverThread = new Thread(() -> {
            while (connected && !Thread.currentThread().isInterrupted()) {
                try {
                    RaftMessage msg = receiveMessage();
                    if (msg != null) {
                        messagesReceived++;
                        lastMessageTime.put(msg.type().name(), System.currentTimeMillis());
                        if (messageHandler != null) {
                            messageHandler.accept(msg);
                        }
                    }
                } catch (Exception e) {
                    if (connected) {
                        log.warn("[ClusterPeer] 接收错误 来自 {}: {}", nodeId, e.getMessage());
                    }
                    break;
                }
            }
            connected = false;
        }, "cluster-recv-" + nodeId);
        receiverThread.setDaemon(true);
        receiverThread.start();
    }

    // ════════════════════════════════════════════════════════════════
    //  消息收发
    // ════════════════════════════════════════════════════════════════

    /**
     * 异步发送 Raft 消息。
     */
    public void sendAsync(RaftMessage message) {
        sendExecutor.submit(() -> {
            try {
                sendMessage(message);
            } catch (Exception e) {
                log.warn("[ClusterPeer] 发送失败 至 {}: {}", nodeId, e.getMessage());
            }
        });
    }

    /**
     * 同步发送 Raft 消息。
     */
    public synchronized void sendMessage(RaftMessage message) throws IOException {
        if (!connected || out == null) {
            throw new IOException("Not connected to " + nodeId);
        }

        String json = serializeMessage(message);
        byte[] data = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        out.writeInt(MAGIC);
        out.writeInt(data.length);
        out.write(data);
        out.flush();

        messagesSent++;
        lastMessageTime.put(message.type().name(), System.currentTimeMillis());
    }

    /**
     * 接收一条 Raft 消息（阻塞）。
     */
    private RaftMessage receiveMessage() throws IOException {
        if (!connected || in == null) return null;

        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new IOException("Invalid magic: 0x" + Integer.toHexString(magic));
        }

        int len = in.readInt();
        if (len <= 0 || len > 10 * 1024 * 1024) { // 最大 10MB
            throw new IOException("Invalid message length: " + len);
        }

        byte[] data = new byte[len];
        in.readFully(data);

        String json = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        return deserializeMessage(json);
    }

    // ── 序列化 ──

    private String serializeMessage(RaftMessage msg) {
        return "{\"type\":\"" + msg.type() + "\","
                + "\"term\":" + msg.term() + ","
                + "\"fromNodeId\":\"" + msg.fromNodeId() + "\","
                + "\"toNodeId\":\"" + msg.toNodeId() + "\","
                + "\"payload\":" + escapeJson(msg.payload()) + ","
                + "\"timestamp\":" + msg.timestamp() + "}";
    }

    private RaftMessage deserializeMessage(String json) {
        try {
            String type = extractJsonString(json, "type");
            long term = extractJsonLong(json, "term");
            String from = extractJsonString(json, "fromNodeId");
            String to = extractJsonString(json, "toNodeId");
            String payload = extractJsonPayload(json);
            long ts = extractJsonLong(json, "timestamp");

            return new RaftMessage(RaftMessage.Type.valueOf(type), term, from, to, payload, ts);
        } catch (Exception e) {
            log.warn("[ClusterPeer] 反序列化错误: {}", e.getMessage());
            return null;
        }
    }

    // 简易 JSON 解析（避免引入第三方库）
    private String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int start = json.indexOf(pattern);
        if (start < 0) return "";
        start += pattern.length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : "";
    }

    private long extractJsonLong(String json, String key) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start < 0) return 0;
        start += pattern.length();
        StringBuilder sb = new StringBuilder();
        while (start < json.length() && (Character.isDigit(json.charAt(start)) || json.charAt(start) == '-')) {
            sb.append(json.charAt(start++));
        }
        return sb.length() > 0 ? Long.parseLong(sb.toString()) : 0;
    }

    private String extractJsonPayload(String json) {
        String pattern = "\"payload\":";
        int start = json.indexOf(pattern);
        if (start < 0) return "";
        start += pattern.length();

        if (start < json.length() && json.charAt(start) == '"') {
            // String payload
            start++;
            int end = json.indexOf("\",", start);
            if (end < 0) end = json.indexOf("\"}", start);
            return end > start ? unescapeJson(json.substring(start, end)) : "";
        } else if (start < json.length() && json.charAt(start) == '{') {
            // Object payload — find matching brace
            int depth = 0;
            int i = start;
            while (i < json.length()) {
                if (json.charAt(i) == '{') depth++;
                if (json.charAt(i) == '}') depth--;
                if (depth == 0) return json.substring(start, i + 1);
                i++;
            }
        }
        return "";
    }

    private String escapeJson(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private String unescapeJson(String s) {
        return s.replace("\\n", "\n").replace("\\r", "\r")
                .replace("\\\"", "\"").replace("\\\\", "\\");
    }

    // ── Getters ──

    private void initStreams() throws IOException {
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
    }

    public String nodeId() { return nodeId; }
    public String host() { return host; }
    public int port() { return port; }
    public boolean isConnected() { return connected; }
    public long messagesSent() { return messagesSent; }
    public long messagesReceived() { return messagesReceived; }

    public long lastMessageTime(String type) {
        return lastMessageTime.getOrDefault(type, 0L);
    }
}
