package com.ouisani.aios.core.a2a;

import com.ouisani.aios.core.team.TeamRegistry;
import com.ouisani.aios.core.team.MailMessage;
import com.ouisani.aios.core.network.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * A2A 联邦管理器 — 借鉴 Agent Zero 的 A2A 协议。
 * <p>
 * 管理跨节点 Agent 联邦，支持：
 * - 节点注册/注销
 * - 跨节点消息路由
 * - 内部 MailMessage 与外部 A2A 消息的桥接
 * - 节点发现和能力匹配
 * <p>
 * OS 类比：分布式系统的 RPC 框架 — 将本地调用透明地路由到远程节点。
 */
public class A2aFederation {
    private static final Logger log = LoggerFactory.getLogger(A2aFederation.class);

    private static final A2aFederation INSTANCE = new A2aFederation();
    private static final long HEARTBEAT_TIMEOUT_MS = 60_000L;

    /** 本节点 ID */
    private volatile String localNodeId = "aios-local-" + System.currentTimeMillis() % 10000;

    /** 已注册的远程节点：nodeId → A2aNodeDescriptor */
    private final ConcurrentHashMap<String, A2aNodeDescriptor> remoteNodes = new ConcurrentHashMap<>();

    /** 待回复的请求：messageId → CompletableFuture */
    private final ConcurrentHashMap<String, CompletableFuture<A2aMessage>> pendingRequests = new ConcurrentHashMap<>();

    /** 消息处理器 */
    private final List<A2aMessageHandler> messageHandlers = new CopyOnWriteArrayList<>();

    private A2aFederation() {}

    public static A2aFederation getInstance() { return INSTANCE; }

    // ── 节点管理 ──

    /**
     * 注册远程节点。
     */
    public void registerRemoteNode(A2aNodeDescriptor descriptor) {
        remoteNodes.put(descriptor.getNodeId(), descriptor);
        log.info("[A2A] 远程节点已注册: nodeId='{}', endpoint='{}', capabilities={}",
                descriptor.getNodeId(), descriptor.getEndpoint(), descriptor.getCapabilities());
        EventBus.instance().broadcast("sys.a2a.node_registered",
                "{\"nodeId\":\"" + descriptor.getNodeId() + "\"}");
    }

    /**
     * 注销远程节点。
     */
    public void deregisterRemoteNode(String nodeId) {
        A2aNodeDescriptor removed = remoteNodes.remove(nodeId);
        if (removed != null) {
            log.info("[A2A] 远程节点已注销: nodeId='{}'", nodeId);
            EventBus.instance().broadcast("sys.a2a.node_deregistered",
                    "{\"nodeId\":\"" + nodeId + "\"}");
        }
    }

    /**
     * 获取所有远程节点。
     */
    public Collection<A2aNodeDescriptor> getRemoteNodes() {
        return Collections.unmodifiableCollection(remoteNodes.values());
    }

    /**
     * 按能力查找节点。
     */
    public List<A2aNodeDescriptor> findNodesByCapability(A2aProtocol.Capability cap) {
        return remoteNodes.values().stream()
                .filter(n -> n.hasCapability(cap) && n.isAlive(HEARTBEAT_TIMEOUT_MS))
                .toList();
    }

    // ── 消息路由 ──

    /**
     * 发送 A2A 消息到远程节点。
     * <p>
     * 如果目标节点是本节点，直接桥接到内部 TeamRegistry。
     * 如果是远程节点，通过 HTTP/WebSocket 发送。
     */
    public CompletableFuture<A2aMessage> sendMessage(A2aMessage message) {
        // 本地路由
        if (localNodeId.equals(message.getTargetNodeId())) {
            return routeToLocal(message);
        }

        // 远程路由
        CompletableFuture<A2aMessage> future = new CompletableFuture<>();
        pendingRequests.put(message.getMessageId(), future);

        A2aNodeDescriptor targetNode = remoteNodes.get(message.getTargetNodeId());
        if (targetNode == null) {
            future.completeExceptionally(new RuntimeException("Target node not found: " + message.getTargetNodeId()));
            pendingRequests.remove(message.getMessageId());
            return future;
        }

        // 通过 HTTP 发送到远程节点
        sendOverHttp(targetNode.getEndpoint(), message);

        // 设置超时
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(() -> {
            if (!future.isDone()) {
                future.completeExceptionally(new RuntimeException("A2A request timeout"));
                pendingRequests.remove(message.getMessageId());
            }
        }, 30, TimeUnit.SECONDS);
        scheduler.shutdown();

        return future;
    }

    /**
     * 处理收到的 A2A 消息 — 从远程节点接收。
     * <p>
     * 桥接到内部 TeamRegistry 或直接处理。
     */
    public void handleIncomingMessage(A2aMessage message) {
        log.info("[A2A] 收到消息: type={}, from={}/{}, to={}/{}",
                message.getType(), message.getSenderNodeId(), message.getSenderAgentId(),
                message.getTargetNodeId(), message.getTargetAgentId());

        // 1. 检查是否是回复
        if (message.getReplyTo() != null) {
            CompletableFuture<A2aMessage> future = pendingRequests.remove(message.getReplyTo());
            if (future != null) {
                future.complete(message);
                return;
            }
        }

        // 2. 桥接到内部 TeamRegistry
        switch (message.getType()) {
            case TASK_DELEGATE -> bridgeToLocalAgent(message);
            case BROADCAST -> broadcastToLocalAgents(message);
            case STATUS_QUERY -> handleStatusQuery(message);
            case HEARTBEAT -> handleHeartbeat(message);
            default -> log.warn("[A2A] 未知消息类型: {}", message.getType());
        }

        // 3. 通知消息处理器
        for (A2aMessageHandler handler : messageHandlers) {
            try {
                handler.handle(message);
            } catch (Exception e) {
                log.warn("[A2A] 消息处理器异常: {}", e.getMessage());
            }
        }
    }

    // ── 内部桥接 ──

    /**
     * 将 A2A 消息桥接到本地 Agent。
     */
    private CompletableFuture<A2aMessage> routeToLocal(A2aMessage message) {
        CompletableFuture<A2aMessage> future = new CompletableFuture<>();

        if (message.getType() == A2aProtocol.MessageType.TASK_DELEGATE) {
            bridgeToLocalAgent(message);
        }

        return future;
    }

    private void bridgeToLocalAgent(A2aMessage message) {
        String targetAgentId = message.getTargetAgentId();
        if (targetAgentId == null || targetAgentId.isBlank()) {
            // 广播给所有 Agent
            TeamRegistry.getInstance().broadcast(
                    MailMessage.MessageType.TASK_ASSIGN, message.getPayload(), "A2A_" + message.getSenderNodeId());
            return;
        }

        // 精准路由到目标 Agent
        com.ouisani.aios.user.sdk.AbstractAgent agent = TeamRegistry.getInstance().findAgent(targetAgentId);
        if (agent != null) {
            MailMessage mail = new MailMessage(
                    "A2A_" + message.getSenderNodeId(),
                    targetAgentId,
                    MailMessage.MessageType.TASK_ASSIGN,
                    message.getPayload()
            );
            TeamRegistry.getInstance().dispatch(mail);
            log.info("[A2A] 消息已桥接到本地 Agent: target={}", targetAgentId);
        } else {
            log.warn("[A2A] 目标 Agent 不存在: {}", targetAgentId);
        }
    }

    private void broadcastToLocalAgents(A2aMessage message) {
        TeamRegistry.getInstance().broadcast(
                MailMessage.MessageType.STATUS_UPDATE, message.getPayload(),
                "A2A_" + message.getSenderNodeId());
    }

    private void handleStatusQuery(A2aMessage message) {
        // 收集本地 Agent 状态
        StringBuilder status = new StringBuilder();
        status.append("{\"nodeId\":\"").append(localNodeId).append("\",\"agents\":[");
        Collection<com.ouisani.aios.user.sdk.AbstractAgent> agents = TeamRegistry.getInstance().getAllAgents();
        boolean first = true;
        for (com.ouisani.aios.user.sdk.AbstractAgent agent : agents) {
            if (!first) status.append(",");
            status.append("{\"id\":\"").append(agent.getAgentId()).append("\"}");
            first = false;
        }
        status.append("]}");

        A2aMessage reply = message.createReply(status.toString());
        // 发送回复（简化：直接通过 HTTP）
        A2aNodeDescriptor senderNode = remoteNodes.get(message.getSenderNodeId());
        if (senderNode != null) {
            sendOverHttp(senderNode.getEndpoint(), reply);
        }
    }

    private void handleHeartbeat(A2aMessage message) {
        A2aNodeDescriptor node = remoteNodes.get(message.getSenderNodeId());
        if (node != null) {
            node.updateHeartbeat();
        }
    }

    // ── HTTP 传输 ──

    private void sendOverHttp(String endpoint, A2aMessage message) {
        // 异步 HTTP 发送
        Thread.startVirtualThread(() -> {
            try {
                java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(endpoint + A2aProtocol.HTTP_ENDPOINT))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(message.toJson()))
                        .timeout(java.time.Duration.ofSeconds(30))
                        .build();
                client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                log.warn("[A2A] HTTP 发送失败: endpoint={}, error={}", endpoint, e.getMessage());
            }
        });
    }

    // ── 消息处理器 ──

    /**
     * 注册 A2A 消息处理器。
     */
    public void registerHandler(A2aMessageHandler handler) {
        messageHandlers.add(handler);
    }

    /**
     * A2A 消息处理器接口。
     */
    @FunctionalInterface
    public interface A2aMessageHandler {
        void handle(A2aMessage message);
    }

    // ── Getters ──

    public String getLocalNodeId() { return localNodeId; }
    public void setLocalNodeId(String nodeId) { this.localNodeId = nodeId; }
    public int remoteNodeCount() { return remoteNodes.size(); }
}
