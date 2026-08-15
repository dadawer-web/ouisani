package com.ouisani.aios.core.observability.subscribers.logging;

import com.ouisani.aios.core.observability.EventSubscriber;
import com.ouisani.aios.core.observability.EventType;
import com.ouisani.aios.core.observability.ObservabilityEvent;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 缓存 logging 订阅器，订阅全部缓存事件并以 SLF4J {@code debug} 级别输出日志。
 * <p>
 * 参考 LMCache 的 {@code L1LoggingSubscriber}。
 */
public class CacheLoggingSubscriber extends EventSubscriber {

    @Override
    public Map<EventType, Consumer<ObservabilityEvent>> getSubscriptions() {
        return Map.of(
                EventType.CACHE_HIT, this::log,
                EventType.CACHE_MISS, this::log,
                EventType.CACHE_EVICT, this::log,
                EventType.CACHE_PUT, this::log,
                EventType.CACHE_DECAY, this::log
        );
    }

    private void log(ObservabilityEvent event) {
        log.debug("[Cache] {} session={} metadata={}",
                event.eventType().code(), event.sessionId(), event.metadata());
    }
}
