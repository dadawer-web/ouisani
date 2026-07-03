package com.ouisani.aios.core.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 责任链事件分发器 — 按优先级顺序调用 handler，支持 accept 中断和单 handler 超时。
 * <p>
 * 借鉴 Apix 的 {@code event_registry.py} + {@code event_handler_base.py}，
 * 适配 Java 21 虚拟线程模型。与 {@link EventBus} 的纯 pub/sub 模式互补：
 * <ul>
 *   <li><b>EventBus</b>：所有订阅者平等并发执行，互不影响</li>
 *   <li><b>ChainEventDispatcher</b>：handler 按优先级排序，顺序执行，
 *       高优先级 handler 可通过 {@link ChainEventItem#accept()} 中断低优先级 handler</li>
 * </ul>
 * <p>
 * <b>派发流程</b>：
 * <ol>
 *   <li>获取该事件类型的 handler 列表（已按 {@code (-priority, registerOrder)} 排序）</li>
 *   <li>按顺序调用每个 handler：
 *     <ul>
 *       <li>若事件已被 {@code accept}，停止后续调用</li>
 *       <li>在虚拟线程中执行 handler，带独立超时</li>
 *       <li>handler 超时：记录警告，根据 {@code stopWhenError} 决定是否中断链</li>
 *       <li>handler 异常：记录警告，根据 {@code stopWhenError} 决定是否中断链</li>
 *     </ul>
 *   </li>
 * </ol>
 * <p>
 * <b>OS 类比</b>：相当于 Linux 内核的 notifier chain —
 * 内核组件注册回调到链表，事件发生时按优先级顺序调用，高优先级可终止链。
 *
 * @see ChainEventItem
 * @see ChainHandlerEntry
 */
public class ChainEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ChainEventDispatcher.class);

    private static final class Holder {
        static final ChainEventDispatcher INSTANCE = new ChainEventDispatcher();
    }

    public static ChainEventDispatcher instance() {
        return Holder.INSTANCE;
    }

    /** 默认 handler 超时 */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /** 事件类型 → handler 列表（已排序） */
    private final ConcurrentHashMap<String, List<ChainHandlerEntry>> handlers = new ConcurrentHashMap<>();

    /** 全局注册顺序计数器 */
    private final AtomicLong registerCounter = new AtomicLong(0);

    private ChainEventDispatcher() {}

    /**
     * 注册责任链 handler — 按优先级插入并保持排序。
     * <p>
     * 排序规则：{@code (-priority, registerOrder)}，即优先级大的在前，
     * 同优先级按注册顺序先注册先执行。
     *
     * @param eventType     事件类型（频道名）
     * @param handler       处理函数
     * @param priority      优先级（越大越先执行）
     * @param timeout       单 handler 超时
     * @param stopWhenError handler 异常时是否中断链
     * @return 订阅 ID（可用于取消订阅）
     */
    public String subscribe(String eventType, Consumer<ChainEventItem> handler,
                             int priority, Duration timeout, boolean stopWhenError) {
        ChainHandlerEntry entry = new ChainHandlerEntry(
                priority,
                registerCounter.getAndIncrement(),
                timeout != null ? timeout : DEFAULT_TIMEOUT,
                stopWhenError,
                handler
        );

        // CopyOnWriteArrayList 保证遍历时的线程安全
        List<ChainHandlerEntry> list = handlers.computeIfAbsent(
                eventType, k -> new CopyOnWriteArrayList<>());

        // 插入并排序
        synchronized (list) {
            list.add(entry);
            list.sort(Comparator
                    .comparingInt((ChainHandlerEntry e) -> -e.priority())  // 优先级降序
                    .thenComparingLong(ChainHandlerEntry::registerOrder)); // 注册顺序升序
        }

        String subId = eventType + ":chain:" + entry.registerOrder();
        log.info("[ChainEventDispatcher] 责任链 handler 已注册: 通道 '{}', priority={}, stopWhenError={}, subId={}",
                eventType, priority, stopWhenError, subId);
        return subId;
    }

    /**
     * 注册责任链 handler（默认 30s 超时，异常不中断链）。
     *
     * @param eventType 事件类型
     * @param handler   处理函数
     * @param priority  优先级
     * @return 订阅 ID
     */
    public String subscribe(String eventType, Consumer<ChainEventItem> handler, int priority) {
        return subscribe(eventType, handler, priority, DEFAULT_TIMEOUT, false);
    }

    /**
     * 注册责任链 handler（默认优先级 1，30s 超时，异常不中断链）。
     *
     * @param eventType 事件类型
     * @param handler   处理函数
     * @return 订阅 ID
     */
    public String subscribe(String eventType, Consumer<ChainEventItem> handler) {
        return subscribe(eventType, handler, 1, DEFAULT_TIMEOUT, false);
    }

    /**
     * 取消注册 — 移除指定 handler。
     *
     * @param eventType 事件类型
     * @param handler   要移除的处理函数
     */
    public void unsubscribe(String eventType, Consumer<ChainEventItem> handler) {
        List<ChainHandlerEntry> list = handlers.get(eventType);
        if (list != null) {
            synchronized (list) {
                list.removeIf(e -> e.callback() == handler);
            }
            log.info("[ChainEventDispatcher] 已取消注册: 通道 '{}' (剩余: {})", eventType, list.size());
        }
    }

    /**
     * 派发事件 — 按优先级顺序调用 handler，支持 accept 中断和单 handler 超时。
     * <p>
     * <b>行为</b>：
     * <ol>
     *   <li>创建 {@link ChainEventItem}（accepted=false）</li>
     *   <li>按优先级顺序遍历 handler</li>
     *   <li>若事件已 accepted，停止</li>
     *   <li>在虚拟线程中执行 handler，带独立超时</li>
     *   <li>handler 可调用 {@code event.accept()} 中断后续链</li>
     *   <li>handler 超时/异常：根据 stopWhenError 决定是否中断</li>
     * </ol>
     *
     * @param eventType    事件类型
     * @param content      事件内容
     * @param generationId 关联的 generation ID（可为 null）
     * @return 最终的事件项（可能已被 accept）
     */
    public ChainEventItem dispatch(String eventType, String content, String generationId) {
        ChainEventItem event = new ChainEventItem(eventType, content, generationId);
        return dispatchEvent(event);
    }

    /**
     * 派发事件（无 generationId）。
     *
     * @param eventType 事件类型
     * @param content   事件内容
     * @return 最终的事件项
     */
    public ChainEventItem dispatch(String eventType, String content) {
        return dispatch(eventType, content, null);
    }

    /**
     * 派发已有的事件项 — 核心派发逻辑。
     * <p>
     * handler 在虚拟线程中执行，通过 {@link ChainEventItem#accept()} 修改
     * 可变的 {@code accepted} 标志（volatile），派发循环在 handler 完成后读取该标志。
     *
     * @param event 事件项（可变）
     * @return 最终的事件项（可能已被 accept）
     */
    public ChainEventItem dispatchEvent(ChainEventItem event) {
        List<ChainHandlerEntry> list = handlers.get(event.eventType());
        if (list == null || list.isEmpty()) {
            return event;
        }

        log.debug("[ChainEventDispatcher] 派发事件: type={}, handlers={}", event.eventType(), list.size());

        for (ChainHandlerEntry entry : list) {
            // 检查是否已被高优先级 handler accept
            if (event.accepted()) {
                log.debug("[ChainEventDispatcher] 事件已被 accept，停止后续 handler: type={}", event.eventType());
                break;
            }

            // 在虚拟线程中执行 handler，带超时
            Thread vt = Thread.startVirtualThread(() -> {
                try {
                    entry.callback().accept(event);
                } catch (Exception e) {
                    log.warn("[ChainEventDispatcher] handler 异常: type={}, priority={}, error={}",
                            event.eventType(), entry.priority(), e.getMessage());
                    // 异常时根据 stopWhenError 决定是否中断链
                    if (entry.stopWhenError()) {
                        event.accept(); // 用 accept 中断链
                    }
                }
            });

            // 等待 handler 完成或超时
            try {
                long timeoutMillis = entry.timeout().toMillis();
                vt.join(timeoutMillis);
                if (vt.isAlive()) {
                    // handler 超时
                    log.warn("[ChainEventDispatcher] handler 超时 ({}ms): type={}, priority={}",
                            timeoutMillis, event.eventType(), entry.priority());
                    vt.interrupt();
                    if (entry.stopWhenError()) {
                        log.info("[ChainEventDispatcher] stopWhenError=true，中断链: type={}", event.eventType());
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[ChainEventDispatcher] 派发被中断: type={}", event.eventType());
                break;
            }
        }

        return event;
    }

    /**
     * 获取指定事件类型的 handler 数量。
     *
     * @param eventType 事件类型
     * @return handler 数量
     */
    public int handlerCount(String eventType) {
        List<ChainHandlerEntry> list = handlers.get(eventType);
        return list != null ? list.size() : 0;
    }

    /**
     * 清除指定事件类型的所有 handler。
     *
     * @param eventType 事件类型
     */
    public void clear(String eventType) {
        handlers.remove(eventType);
        log.info("[ChainEventDispatcher] 已清除通道 '{}' 的所有 handler", eventType);
    }
}
