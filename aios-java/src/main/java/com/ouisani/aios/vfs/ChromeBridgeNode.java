package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsNode;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Chrome 浏览器桥接节点 — AIOS 的"打破第四面墙"浏览器感知能力。
 * <p>
 * 挂载在 {@code /dev/host/browser}，这是一个基于 WebSocket 的双向设备节点。
 * 它在后台启动一个专用的 WebSocket 端口，等待 Chrome 浏览器扩展连入。
 *
 * <h3>工作原理</h3>
 * <pre>
 *   AIOS Kernel                    Chrome Extension
 *   ┌──────────┐    WebSocket     ┌──────────────┐
 *   │ Chrome   │ ◄──────────────► │ AIOS Bridge  │
 *   │ Bridge   │   ws://host:port │ Extension     │
 *   │ Node     │                  └──────────────┘
 *   └──────────┘                        │
 *       │                               │ Chrome API
 *       ▼                               ▼
 *   sys_read("/dev/host/browser/active_tab")
 *   → {"title":"GitHub", "url":"https://github.com/..."}
 * </pre>
 *
 * <h3>VFS 路径映射</h3>
 * <table>
 *   <tr><th>VFS 路径</th><th>操作</th><th>说明</th></tr>
 *   <tr><td>/dev/host/browser</td><td>read</td><td>连接状态</td></tr>
 *   <tr><td>/dev/host/browser</td><td>write</td><td>发送指令到浏览器</td></tr>
 *   <tr><td>/dev/host/browser/active_tab</td><td>read</td><td>获取当前活动标签页</td></tr>
 *   <tr><td>/dev/host/browser/tabs</td><td>read</td><td>获取所有标签页</td></tr>
 *   <tr><td>/dev/host/browser/navigate</td><td>write</td><td>导航到指定 URL</td></tr>
 * </table>
 */
public non-sealed class ChromeBridgeNode implements VfsNode {

    private static final Logger log = LoggerFactory.getLogger(ChromeBridgeNode.class);

    private final String path;
    private int ownerUid;
    private int permissions;

    /** WebSocket 服务器 */
    private Javalin wsServer;
    private volatile int wsPort;

    /** 连接的浏览器扩展 */
    private final Map<String, BrowserSession> sessions = new ConcurrentHashMap<>();

    /** 浏览器响应回调 — 供 BrowserTool 接收执行结果 */
    private volatile Consumer<String> responseCallback;

    /** 浏览器状态缓存 */
    private volatile BrowserState cachedState = new BrowserState(false, null, List.of());

    // ── 统计 ──
    private final AtomicLong totalReads = new AtomicLong(0);
    private final AtomicLong totalWrites = new AtomicLong(0);
    private final AtomicLong totalCommands = new AtomicLong(0);

    public ChromeBridgeNode(String path) {
        this(path, 0, 0666);
    }

    public ChromeBridgeNode(String path, int ownerUid, int permissions) {
        this.path = path;
        this.ownerUid = ownerUid;
        this.permissions = permissions;
    }

    @Override
    public VfsNodeType nodeType() { return VfsNodeType.DEVICE; }

    @Override
    public String path() { return path; }

    @Override
    public int ownerUid() { return ownerUid; }

    @Override
    public void setOwnerUid(int uid) { this.ownerUid = uid; }

    @Override
    public int permissions() { return permissions; }

    @Override
    public void setPermissions(int perm) { this.permissions = perm; }

    // ════════════════════════════════════════════════════════════════
    //  读操作 — 返回浏览器状态
    // ════════════════════════════════════════════════════════════════

    /**
     * 读取浏览器状态 — 根据子路径返回不同信息。
     */
    @Override
    public String read() {
        totalReads.incrementAndGet();
        return readSubPath("");
    }

    /**
     * 读取子路径 — 支持 active_tab, tabs 等。
     */
    public String readSubPath(String subPath) {
        BrowserState state = cachedState;

        if (subPath.equals("active_tab") || subPath.equals("/active_tab")) {
            if (!state.connected || state.activeTab == null) {
                return "{\"error\":\"browser_not_connected\",\"connected\":false}";
            }
            TabInfo tab = state.activeTab;
            return "{\"title\":\"" + escape(tab.title) + "\","
                    + "\"url\":\"" + escape(tab.url) + "\","
                    + "\"favIconUrl\":\"" + escape(tab.favIconUrl != null ? tab.favIconUrl : "") + "\","
                    + "\"connected\":true}";
        }

        if (subPath.equals("tabs") || subPath.equals("/tabs")) {
            if (!state.connected) {
                return "{\"error\":\"browser_not_connected\",\"connected\":false}";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("{\"connected\":true,\"tabs\":[");
            for (int i = 0; i < state.tabs.size(); i++) {
                if (i > 0) sb.append(",");
                TabInfo tab = state.tabs.get(i);
                sb.append("{\"title\":\"").append(escape(tab.title)).append("\",")
                  .append("\"url\":\"").append(escape(tab.url)).append("\",")
                  .append("\"active\":").append(tab.equals(state.activeTab)).append("}");
            }
            sb.append("]}");
            return sb.toString();
        }

        // 默认：返回连接状态
        return "{\"path\":\"" + path + "\","
                + "\"type\":\"CHROME_BRIDGE\","
                + "\"connected\":" + state.connected + ","
                + "\"wsPort\":" + wsPort + ","
                + "\"sessions\":" + sessions.size() + ","
                + "\"activeTab\":" + (state.activeTab != null
                    ? "{\"title\":\"" + escape(state.activeTab.title) + "\",\"url\":\"" + escape(state.activeTab.url) + "\"}"
                    : "null") + ","
                + "\"totalReads\":" + totalReads.get() + ","
                + "\"totalWrites\":" + totalWrites.get() + ","
                + "\"totalCommands\":" + totalCommands.get() + "}";
    }

    // ════════════════════════════════════════════════════════════════
    //  写操作 — 发送指令到浏览器
    // ════════════════════════════════════════════════════════════════

    /**
     * 写入指令 — 向浏览器扩展发送命令。
     * <p>
     * JSON 格式：
     * <pre>
     * {"action":"navigate", "url":"https://github.com"}
     * {"action":"execute_script", "script":"document.title"}
     * {"action":"get_active_tab"}
     * </pre>
     */
    @Override
    public boolean write(String payload) {
        if (payload == null || payload.isBlank()) return false;

        totalWrites.incrementAndGet();

        String action = extractField(payload, "action");

        if (action.isEmpty()) {
            log.warn("[ChromeBridge] No action specified in payload");
            return false;
        }

        totalCommands.incrementAndGet();

        // 向所有连接的浏览器会话通过 WebSocket 发送指令
        boolean sent = false;
        for (BrowserSession session : sessions.values()) {
            try {
                if (session.wsContext != null) {
                    // 通过 Javalin WebSocket 发送命令到浏览器扩展
                    session.wsContext.send(payload);
                    session.lastCommand = payload;
                    session.lastCommandTime = System.currentTimeMillis();
                    sent = true;
                    log.debug("[ChromeBridge] Command sent to session {}: action={}", session.id, action);
                }
            } catch (Exception e) {
                log.warn("[ChromeBridge] Failed to send to session {}: {}", session.id, e.getMessage());
            }
        }

        if (sent) {
            log.info("[ChromeBridge] Command sent: action={}", action);
        } else {
            log.warn("[ChromeBridge] No browser connected, command dropped: action={}", action);
        }

        return sent;
    }

    /**
     * 注册浏览器响应回调 — 供 BrowserTool 接收执行结果。
     */
    public void setResponseCallback(Consumer<String> callback) {
        this.responseCallback = callback;
    }

    // ════════════════════════════════════════════════════════════════
    //  WebSocket 服务器管理
    // ════════════════════════════════════════════════════════════════

    /**
     * 启动 WebSocket 服务器 — 等待 Chrome 扩展连入。
     *
     * @param port WebSocket 端口号
     */
    public void startWebSocket(int port) {
        this.wsPort = port;

        wsServer = Javalin.create(config -> {
            config.showJavalinBanner = false;
        });

        // WebSocket 端点
        wsServer.ws("/ws/browser", ws -> {
            ws.onConnect(ctx -> {
                String sessionId = "chrome-" + System.nanoTime();
                BrowserSession session = new BrowserSession(sessionId, ctx);
                sessions.put(sessionId, session);

                cachedState = new BrowserState(true, cachedState.activeTab, cachedState.tabs);

                log.info("[ChromeBridge] Browser extension connected: sessionId={}", sessionId);
                System.out.printf("  [ChromeBridge] Browser extension connected: %s%n", sessionId);
            });

            ws.onMessage(ctx -> {
                String message = ctx.message();
                handleBrowserMessage(message);
            });

            ws.onClose(ctx -> {
                // 移除断开连接的会话
                sessions.entrySet().removeIf(e -> {
                    if (e.getValue().wsContext == ctx) {
                        log.info("[ChromeBridge] Browser extension disconnected: {}", e.getKey());
                        return true;
                    }
                    return false;
                });

                if (sessions.isEmpty()) {
                    cachedState = new BrowserState(false, null, List.of());
                }
            });
        });

        // HTTP 端点 — 供浏览器扩展轮询
        wsServer.get("/browser/status", ctx -> {
            ctx.contentType("application/json");
            ctx.result(readSubPath(""));
        });

        wsServer.get("/browser/active_tab", ctx -> {
            ctx.contentType("application/json");
            ctx.result(readSubPath("active_tab"));
        });

        try {
            wsServer.start("0.0.0.0", port);
            log.info("[ChromeBridge] WebSocket server started on port {}", port);
            System.out.printf("  ✓ [ChromeBridge] WebSocket server on port %d (ws://localhost:%d/ws/browser)%n", port, port);
        } catch (Exception e) {
            log.error("[ChromeBridge] Failed to start WebSocket server: {}", e.getMessage());
        }
    }

    /**
     * 停止 WebSocket 服务器。
     */
    public void stopWebSocket() {
        if (wsServer != null) {
            wsServer.stop();
            sessions.clear();
            cachedState = new BrowserState(false, null, List.of());
            log.info("[ChromeBridge] WebSocket server stopped");
        }
    }

    /**
     * 处理浏览器扩展发来的消息。
     * <p>
     * 消息格式：
     * <pre>
     * {"type":"tab_update", "title":"GitHub", "url":"https://github.com/...", "tabs":[...]}
     * </pre>
     */
    private void handleBrowserMessage(String message) {
        if (message == null || message.isEmpty()) return;

        String type = extractField(message, "type");

        if ("tab_update".equals(type) || "state_update".equals(type)) {
            String title = extractField(message, "title");
            String url = extractField(message, "url");
            String favIconUrl = extractField(message, "favIconUrl");

            TabInfo activeTab = new TabInfo(title, url, favIconUrl);

            // 解析标签页列表
            List<TabInfo> tabs = new ArrayList<>();
            tabs.add(activeTab);

            cachedState = new BrowserState(true, activeTab, tabs);

            log.debug("[ChromeBridge] State updated: title='{}', url='{}'", title, url);
        }

        // 处理浏览器扩展的命令执行响应
        if ("command_result".equals(type) || "action_result".equals(type)) {
            if (responseCallback != null) {
                try {
                    responseCallback.accept(message);
                } catch (Exception e) {
                    log.warn("[ChromeBridge] Response callback error: {}", e.getMessage());
                }
            }
        }
    }

    // ── 内部辅助 ──

    private String extractField(String json, String key) {
        String pattern = "\"" + key + "\"";
        int start = json.indexOf(pattern);
        if (start < 0) return "";
        start = json.indexOf(":", start) + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length()) return "";

        if (json.charAt(start) == '"') {
            start++;
            int end = json.indexOf("\"", start);
            return end > start ? json.substring(start, end) : "";
        }

        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        return json.substring(start, end).strip();
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    public boolean isConnected() { return cachedState.connected; }
    public int sessionCount() { return sessions.size(); }

    // ════════════════════════════════════════════════════════════════
    //  数据结构
    // ════════════════════════════════════════════════════════════════

    /** 浏览器标签页信息 */
    public record TabInfo(
            String title,
            String url,
            String favIconUrl
    ) {}

    /** 浏览器状态快照 */
    public record BrowserState(
            boolean connected,
            TabInfo activeTab,
            List<TabInfo> tabs
    ) {}

    /** 浏览器会话 */
    public static class BrowserSession {
        final String id;
        final io.javalin.websocket.WsContext wsContext;
        volatile String lastCommand;
        volatile long lastCommandTime;

        BrowserSession(String id, io.javalin.websocket.WsContext wsContext) {
            this.id = id;
            this.wsContext = wsContext;
        }
    }
}
