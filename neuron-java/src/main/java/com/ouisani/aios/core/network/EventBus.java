package com.ouisani.aios.core.network;

import com.ouisani.aios.core.observability.UpstreamMeta;
import com.ouisani.aios.core.observability.UpstreamMetaContext;
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

    /**
     * UpstreamMeta 伴生事件通道名 — 调用方通过 {@link #broadcast(String, String, UpstreamMeta)}
     * 或 {@link #broadcastWithCurrentMeta(String, String)} 发起的广播会额外在此通道多发一条
     * 伴生事件，payload 为 {@link UpstreamMeta#toJsonLine()}。
     * <p>
     * 订阅者 opt-in：不订阅此通道则零影响，订阅后可获取每次上游调用的元数据快照。
     */
    public static final String COMPANION_UPSTREAM_META_CHANNEL = "sys.upstream.meta";

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
        log.info("[EventBus] SSE 客户端已连接: {} (总数: {})", id, clients.size());

        client.onClose(() -> {
            clients.remove(id);
            log.info("[EventBus] SSE 客户端已断开: {} (总数: {})", id, clients.size());
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
        log.info("[EventBus] 订阅者已注册: 通道 '{}' (订阅者总数: {}), subId={}",
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
            log.info("[EventBus] 已取消订阅: 通道 '{}' (剩余: {})",
                    eventType, handlers.size());
        }
    }

    public void broadcast(String eventType, String payload) {
        // Notify SSE clients
        if (!clients.isEmpty()) {
            log.debug("[EventBus] 正在广播事件: type={}, payloadLen={}, clients={}",
                    eventType, payload.length(), clients.size());
            clients.forEach((id, client) -> {
                try {
                    client.sendEvent(eventType, payload);
                } catch (Exception e) {
                    clients.remove(id);
                    log.warn("[EventBus] 发送失败，移除客户端 {}: {}", id, e.getMessage());
                }
            });
        }

        // Notify internal subscribers — 虚拟线程异步执行，避免慢处理器阻塞调用方
        List<Consumer<String>> handlers = subscribers.get(eventType);
        if (handlers != null && !handlers.isEmpty()) {
            for (Consumer<String> handler : handlers) {
                Thread.startVirtualThread(() -> {
                    try {
                        handler.accept(payload);
                    } catch (Exception e) {
                        log.warn("[EventBus] 订阅者处理器错误，通道 '{}': {}", eventType, e.getMessage());
                    }
                });
            }
        }
    }

    /**
     * 广播事件并附带上游调用元数据（伴生事件策略）。
     * <p>
     * 原 {@code eventType} 通道的 payload 字节级不变；额外在
     * {@code sys.upstream.meta} 通道多发一条伴生事件，payload 为
     * {@link UpstreamMeta#toJsonLine()}。订阅者 opt-in：
     * <ul>
     *   <li>不需要 meta 的订阅者：不订阅 {@code sys.upstream.meta}，零影响</li>
     *   <li>需要 meta 的订阅者：订阅 {@code sys.upstream.meta} 通道</li>
     * </ul>
     * <p>
     * <b>设计权衡</b>：选择伴生事件而非合并字段到原 payload，原因：
     * <ol>
     *   <li>现有 SSE 客户端 schema 不破坏（如 GUI DOM、CostTracker 等）</li>
     *   <li>原 payload 不保证是 JSON 对象（可能是任意字符串），无法保证合并可行</li>
     *   <li>订阅者 opt-in，零回归</li>
     * </ol>
     *
     * @param eventType 事件通道
     * @param payload   事件载荷（JSON 字符串，原通道字节级不变）
     * @param meta      上游调用元数据（null 时不发伴生事件，等价于调用 {@link #broadcast(String, String)})
     */
    public void broadcast(String eventType, String payload, UpstreamMeta meta) {
        broadcast(eventType, payload);
        if (meta != null) {
            broadcast(COMPANION_UPSTREAM_META_CHANNEL, meta.toJsonLine());
        }
    }

    /**
     * 广播事件并自动从 {@link UpstreamMetaContext#current()} 读取上游调用元数据。
     * <p>
     * 便捷方法，等价于：
     * <pre>{@code
     * broadcast(eventType, payload, UpstreamMetaContext.current());
     * }</pre>
     * <p>
     * <b>虚拟线程约束</b>：仅在同一调用链上的同步代码中可用。
     * EventBus 内部订阅者在虚拟线程上执行，看不到 {@link UpstreamMetaContext#current()}，
     * 必须通过伴生事件 payload 获取 meta。
     *
     * @param eventType 事件通道
     * @param payload   事件载荷
     */
    public void broadcastWithCurrentMeta(String eventType, String payload) {
        broadcast(eventType, payload, UpstreamMetaContext.current());
    }

    public int activeClientCount() {
        return clients.size();
    }

    public int subscriberCount(String eventType) {
        List<Consumer<String>> handlers = subscribers.get(eventType);
        return handlers != null ? handlers.size() : 0;
    }

    /**
     * 获取责任链事件分发器 — 提供按优先级排序、支持 accept 中断和单 handler 超时的事件处理模式。
     * <p>
     * 借鉴 Apix 的 {@code event_registry.py}，与 {@link #broadcast} 的纯 pub/sub 模式互补：
     * <ul>
     *   <li><b>{@link #broadcast}</b>：所有订阅者平等并发执行，互不影响</li>
     *   <li><b>{@link ChainEventDispatcher}</b>：handler 按优先级排序，顺序执行，
     *       高优先级 handler 可通过 {@link ChainEventItem#accept()} 中断低优先级 handler</li>
     * </ul>
     * <p>
     * <b>适用场景</b>：安全审查、权限校验等需要顺序决策的场景。
     *
     * @return 责任链事件分发器单例
     * @see ChainEventDispatcher
     */
    public ChainEventDispatcher chainDispatcher() {
        return ChainEventDispatcher.instance();
    }

    private static String clientId(SseClient client) {
        return "sse-" + System.identityHashCode(client) + "-" + System.nanoTime() % 10000;
    }
}
