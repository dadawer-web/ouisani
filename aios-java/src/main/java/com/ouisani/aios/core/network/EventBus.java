package com.ouisani.aios.core.network;

import io.javalin.http.sse.SseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * AIOS 事件总线 — 内核级发布/订阅事件系统。
 * <p>
 * OS 类比：相当于 Linux 的 netlink + udev 事件机制 —
 * 内核组件通过 {@link #broadcast} 发布事件，外部 SSE 客户端和内部订阅者
 * 通过 {@link #subscribe} 接收事件。支持两种消费者：
 * <ul>
 *   <li>SSE 客户端（通过 /kernel/stream 端点连接的外部浏览器）</li>
 *   <li>内部订阅者（通过 Consumer 回调注册的内核组件）</li>
 * </ul>
 */
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private static final class Holder {
        static final EventBus INSTANCE = new EventBus();
    }

    private final ConcurrentHashMap<String, SseClient> clients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Consumer<String>>> subscribers = new ConcurrentHashMap<>();

    private EventBus() {
    }

    public static EventBus instance() {
        return Holder.INSTANCE;
    }

    public void register(SseClient client) {
        String id = clientId(client);
        clients.put(id, client);
        log.info("[EventBus] SSE client connected: {} (total: {})", id, clients.size());

        client.onClose(() -> {
            clients.remove(id);
            log.info("[EventBus] SSE client disconnected: {} (total: {})", id, clients.size());
        });
    }

    /**
     * Subscribe to a specific event channel. The handler will be invoked
     * whenever {@link #broadcast(String, String)} is called with a matching
     * event type.
     *
     * @param eventType the event channel to subscribe to (e.g. "system_metrics")
     * @param handler   callback invoked with the payload
     * @return a subscription ID that can be used to unsubscribe
     */
    public String subscribe(String eventType, Consumer<String> handler) {
        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(handler);
        String subId = eventType + ":" + System.identityHashCode(handler);
        log.info("[EventBus] Subscriber registered for channel '{}' (total subscribers: {}), subId={}",
                eventType, subscribers.get(eventType).size(), subId);
        return subId;
    }

    /**
     * Unsubscribe a handler from a specific event channel.
     * Must be called when a WebSocket connection closes to prevent memory leaks.
     *
     * @param eventType the event channel to unsubscribe from
     * @param handler   the exact handler instance to remove
     */
    public void unsubscribe(String eventType, Consumer<String> handler) {
        List<Consumer<String>> handlers = subscribers.get(eventType);
        if (handlers != null && handlers.remove(handler)) {
            log.info("[EventBus] Subscriber removed from channel '{}' (remaining: {})",
                    eventType, handlers.size());
        }
    }

    public void broadcast(String eventType, String payload) {
        // Notify SSE clients
        if (!clients.isEmpty()) {
            log.debug("[EventBus] Broadcasting event: type={}, payloadLen={}, clients={}",
                    eventType, payload.length(), clients.size());
            clients.forEach((id, client) -> {
                try {
                    client.sendEvent(eventType, payload);
                } catch (Exception e) {
                    clients.remove(id);
                    log.warn("[EventBus] Failed to send to client {}, removing: {}", id, e.getMessage());
                }
            });
        }

        // Notify internal subscribers
        List<Consumer<String>> handlers = subscribers.get(eventType);
        if (handlers != null && !handlers.isEmpty()) {
            for (Consumer<String> handler : handlers) {
                try {
                    handler.accept(payload);
                } catch (Exception e) {
                    log.warn("[EventBus] Subscriber handler error on '{}': {}", eventType, e.getMessage());
                }
            }
        }
    }

    public int activeClientCount() {
        return clients.size();
    }

    public int subscriberCount(String eventType) {
        List<Consumer<String>> handlers = subscribers.get(eventType);
        return handlers != null ? handlers.size() : 0;
    }

    private static String clientId(SseClient client) {
        return "sse-" + System.identityHashCode(client) + "-" + System.nanoTime() % 10000;
    }
}
