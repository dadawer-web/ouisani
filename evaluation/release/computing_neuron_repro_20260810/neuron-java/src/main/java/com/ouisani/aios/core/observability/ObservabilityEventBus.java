package com.ouisani.aios.core.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * 统一可观测性事件总线，参考 LMCache 的 {@code EventBus}，适配 Java 21 与 AIOS 单例模式。
 * <p>
 * 热路径 {@link #publish} 非阻塞：事件入 {@link ConcurrentLinkedQueue} 后立即返回，
 * 由后台守护虚拟线程 drain 并分发到订阅者回调。队列满时尾丢弃并限速告警。
 * <p>
 * 单例模式 — 通过 {@link #instance()} 获取（Holder 静态内部类，与 AIOS 其他管理器一致）。
 * 默认以 {@link ObservabilityConfig#defaults()} 构造，可通过 {@link #configure(ObservabilityConfig)}
 * 在启动前应用实际配置。
 *
 * @see EventType
 * @see ObservabilityEvent
 * @see EventSubscriber
 */
public final class ObservabilityEventBus {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityEventBus.class);

    private static final class Holder {
        static final ObservabilityEventBus INSTANCE =
                new ObservabilityEventBus(ObservabilityConfig.defaults());
    }

    /**
     * 返回全局 EventBus 单例。
     *
     * @return EventBus 单例
     */
    public static ObservabilityEventBus instance() {
        return Holder.INSTANCE;
    }

    /** 事件队列 — 非阻塞热路径写入。 */
    private final Queue<ObservabilityEvent> queue = new ConcurrentLinkedQueue<>();
    /** 订阅者表：EventType → 回调列表。 */
    private final Map<EventType, List<Consumer<ObservabilityEvent>>> subscribers = new ConcurrentHashMap<>();
    /** 已通过 registerSubscriber 挂载的订阅者（用于 stop 时统一 shutdown）。 */
    private final List<EventSubscriber> registeredSubscribers = new CopyOnWriteArrayList<>();
    /** 回调 → 所属订阅者类名（用于异常计数归属）。 */
    private final Map<Consumer<ObservabilityEvent>, String> callbackOwners = new ConcurrentHashMap<>();

    private volatile ObservabilityConfig config;
    private final AtomicLong droppedCount = new AtomicLong();
    /** 订阅者回调异常计数：订阅者类名 → 累计次数。 */
    private final Map<String, LongAdder> subscriberExceptionCounts = new ConcurrentHashMap<>();

    /** drain 线程唤醒信号。 */
    private final Semaphore wakeSignal = new Semaphore(0);
    private volatile boolean running = false;
    private volatile boolean stopRequested = false;
    private Thread drainThread;

    /** 丢弃告警限速：上次告警时间戳（毫秒）。 */
    private volatile long lastDropWarnMillis = 0L;

    private ObservabilityEventBus(ObservabilityConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * 应用配置（应在 {@link #start()} 前调用）。{@code enabled} 与 {@code maxQueueSize} 实时生效。
     *
     * @param config 配置
     */
    public void configure(ObservabilityConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        log.info("[ObservabilityEventBus] 已应用配置: enabled={}, maxQueueSize={}, metrics={}, logging={}, tracing={}",
                config.enabled(), config.maxQueueSize(),
                config.metricsEnabled(), config.loggingEnabled(), config.tracingEnabled());
    }

    /**
     * 注册一个回调到指定事件类型（线程安全）。
     *
     * @param eventType 事件类型
     * @param callback  回调
     */
    public void subscribe(EventType eventType, Consumer<ObservabilityEvent> callback) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(callback, "callback");
        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(callback);
    }

    /**
     * 自动挂载一个 {@link EventSubscriber}：遍历其 {@link EventSubscriber#getSubscriptions()}
     * 注册所有回调，并记录归属以便异常计数。
     *
     * @param subscriber 订阅者
     */
    public void registerSubscriber(EventSubscriber subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        String owner = subscriber.getClass().getName();
        subscriber.getSubscriptions().forEach((eventType, callback) -> {
            callbackOwners.put(callback, owner);
            subscribe(eventType, callback);
        });
        registeredSubscribers.add(subscriber);
        log.info("[ObservabilityEventBus] 订阅者已挂载: {}", owner);
    }

    /**
     * 提交一条事件（非阻塞热路径）。
     * <p>
     * 若 EventBus 被禁用则为 no-op。队列满时尾丢弃该事件，累计丢弃计数并限速告警（至多每秒一次）。
     * {@code timestamp} 为 0 时由本方法打戳为当前毫秒时间。
     *
     * @param event 事件
     */
    public void publish(ObservabilityEvent event) {
        ObservabilityConfig cfg = config;
        if (!cfg.enabled()) {
            return;
        }

        if (queue.size() >= cfg.maxQueueSize()) {
            long dropped = droppedCount.incrementAndGet();
            long now = System.currentTimeMillis();
            if (now - lastDropWarnMillis >= 1_000L) {
                lastDropWarnMillis = now;
                log.warn("[ObservabilityEventBus] 队列已满 (maxQueueSize={}), 已累计丢弃 {} 个事件",
                        cfg.maxQueueSize(), dropped);
            }
            return;
        }

        ObservabilityEvent stamped = event.timestamp() == 0L
                ? new ObservabilityEvent(event.eventType(), System.currentTimeMillis(),
                        event.metadata(), event.sessionId())
                : event;
        queue.offer(stamped);
        wakeSignal.release();
    }

    /**
     * 启动后台 drain 虚拟线程。禁用或已运行时为 no-op。
     */
    public void start() {
        if (!config.enabled()) {
            return;
        }
        if (running) {
            return;
        }
        running = true;
        stopRequested = false;
        drainThread = Thread.startVirtualThread(this::drainLoop);
        log.info("[ObservabilityEventBus] 已启动 drain 虚拟线程 (maxQueueSize={})",
                config.maxQueueSize());
    }

    /**
     * 停止 drain 线程，flush 剩余事件，并依次关闭所有已挂载订阅者。未启动时安全调用。
     */
    public void stop() {
        if (!running) {
            return;
        }
        stopRequested = true;
        wakeSignal.release();
        Thread t = drainThread;
        if (t != null) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        drainAll();
        for (EventSubscriber sub : registeredSubscribers) {
            try {
                sub.shutdown();
            } catch (Exception e) {
                log.warn("[ObservabilityEventBus] 关闭订阅者 {} 异常: {}",
                        sub.getClass().getName(), e.toString());
            }
        }
        running = false;
        drainThread = null;
        log.info("[ObservabilityEventBus] 已停止");
    }

    // ── 自监控 ────────────────────────────────────────────────────────

    /**
     * 返回当前队列中待分发的事件数。
     *
     * @return 队列深度
     */
    public int queueDepth() {
        return queue.size();
    }

    /**
     * 返回因队列满而累计丢弃的事件数。
     *
     * @return 累计丢弃数
     */
    public long droppedEventsCount() {
        return droppedCount.get();
    }

    /**
     * 返回订阅者回调异常计数的快照（订阅者类名 → 累计异常次数）。
     *
     * @return 不可变快照
     */
    public Map<String, Long> subscriberExceptionCounts() {
        Map<String, Long> snapshot = new java.util.HashMap<>();
        subscriberExceptionCounts.forEach((k, v) -> snapshot.put(k, v.sum()));
        return Map.copyOf(snapshot);
    }

    // ── 内部 ──────────────────────────────────────────────────────────

    /** drain 循环：等待唤醒信号或超时后 drain 全部事件。 */
    private void drainLoop() {
        while (!stopRequested) {
            try {
                wakeSignal.tryAcquire(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            drainAll();
        }
    }

    /** 弹出并分发队列中所有事件到对应订阅者回调。 */
    private void drainAll() {
        ObservabilityEvent event;
        while ((event = queue.poll()) != null) {
            List<Consumer<ObservabilityEvent>> cbs = subscribers.get(event.eventType());
            if (cbs == null || cbs.isEmpty()) {
                continue;
            }
            for (Consumer<ObservabilityEvent> cb : cbs) {
                try {
                    cb.accept(event);
                } catch (Exception ex) {
                    String name = callbackOwners.getOrDefault(cb, cb.getClass().getName());
                    subscriberExceptionCounts.computeIfAbsent(name, k -> new LongAdder()).increment();
                    log.warn("[ObservabilityEventBus] 回调 {} 处理 {} 异常: {}",
                            name, event.eventType().code(), ex.toString());
                }
            }
        }
    }
}
