package com.ouisani.aios.core.network;

import io.javalin.http.sse.SseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private static final class Holder {
        static final EventBus INSTANCE = new EventBus();
    }

    private final ConcurrentHashMap<String, SseClient> clients = new ConcurrentHashMap<>();

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

    public void broadcast(String eventType, String payload) {
        if (clients.isEmpty()) {
            return;
        }
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

    public int activeClientCount() {
        return clients.size();
    }

    private static String clientId(SseClient client) {
        return "sse-" + System.identityHashCode(client) + "-" + System.nanoTime() % 10000;
    }
}
