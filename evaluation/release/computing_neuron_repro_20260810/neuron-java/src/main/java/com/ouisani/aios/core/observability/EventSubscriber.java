package com.ouisani.aios.core.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 可观测事件订阅者抽象基类，参考 LMCache 的 {@code EventSubscriber}。
 * <p>
 * 子类通过 {@link #getSubscriptions()} 声明关心的 {@link EventType} 及对应回调，
 * {@link #register(ObservabilityEventBus)} 会遍历该映射自动完成订阅。
 * {@link #shutdown()} 默认空实现，子类可按需覆写以释放资源。
 */
public abstract class EventSubscriber {

    /** 子类共享的 SLF4J Logger。 */
    protected final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * 返回该订阅者关心的事件类型与回调映射。
     * <p>
     * 在 {@link #register(ObservabilityEventBus)} 时被调用一次，EventBus 会直接持有这些回调。
     *
     * @return 事件类型 → 回调 的映射
     */
    public abstract Map<EventType, Consumer<ObservabilityEvent>> getSubscriptions();

    /**
     * 遍历 {@link #getSubscriptions()}，将所有回调注册到指定 EventBus。
     *
     * @param bus 目标 EventBus
     */
    public void register(ObservabilityEventBus bus) {
        getSubscriptions().forEach(bus::subscribe);
    }

    /**
     * 关闭钩子，默认空实现。子类可覆写以释放资源，由 {@link ObservabilityEventBus#stop()} 调用。
     */
    public void shutdown() {
        // 默认空实现
    }
}
