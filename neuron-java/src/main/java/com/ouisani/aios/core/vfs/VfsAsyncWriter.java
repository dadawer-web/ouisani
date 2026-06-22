package com.ouisani.aios.core.vfs;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * VFS 异步写入器 — Actor 模式的磁盘 I/O 批处理引擎。
 * <p>
 * 借鉴 ECC (Everything Claude Code) 的 Actor 模式设计：
 * 用 MPSC (Multi-Producer Single-Consumer) 无锁队列 + 单一 DiskIOWorker 后台线程，
 * 解决 DAG 引擎几百个 Node 并发写入 VFS 时的锁竞争问题。
 *
 * <h3>核心设计</h3>
 * <ul>
 *   <li><b>无锁队列</b>: {@link LinkedTransferQueue} — 所有 Agent 像发邮件一样把写操作扔进队列</li>
 *   <li><b>单一 Actor</b>: 一个后台虚拟线程批量消费队列，串行落盘，零锁竞争</li>
 *   <li><b>批量刷盘</b>: 每次最多处理 BATCH_SIZE 条写操作，减少 I/O 系统调用次数</li>
 *   <li><b>背压保护</b>: 队列超过 HIGH_WATERMARK 时拒绝新请求(返回 false)，防止 OOM</li>
 * </ul>
 *
 * <h3>为什么虚拟线程不能解决这个问题</h3>
 * 虚拟线程只解决了"阻塞线程数量"的问题(可以创建百万个虚拟线程)，
 * 但没有解决"并发修改同一个资源(如文件/数据库)时的锁竞争"问题。
 * 当 DAG 引擎几百个 Node 并发调用 FileWriteTool 写入 VfsManager 时，
 * VfsManager 的 ReentrantReadWriteLock 会成为严重瓶颈。
 * <p>
 * Actor 模式将所有磁盘 I/O 序列化到单一消费者线程，彻底消除锁竞争。
 *
 * <h3>OS 类比: Linux Kernel pdflush / writeback threads</h3>
 * 类似 Linux 内核的 pdflush/writeback 线程：
 * 业务线程(业务 Agent)把脏页(写请求)扔到队列，
 * 后台 writeback 线程(单一 Actor)批量刷盘。
 * 业务线程不阻塞在磁盘 I/O 上，吞吐量产生数量级飞跃。
 *
 * @see VfsManager
 * @see VfsJournal
 */
public final class VfsAsyncWriter {

    private static final Logger log = LoggerFactory.getLogger(VfsAsyncWriter.class);

    private static final VfsAsyncWriter INSTANCE = new VfsAsyncWriter();

    /** 批量处理的最大写操作数 */
    private static final int BATCH_SIZE = 64;

    /** 队列高水位线 — 超过则拒绝新请求(背压) */
    private static final int HIGH_WATERMARK = 10_000;

    /** 消费者线程空闲超时(毫秒) — 超时后检查是否应该退出 */
    private static final long IDLE_TIMEOUT_MS = 100;

    /** 无锁队列 — MPSC (Multi-Producer Single-Consumer) */
    private final LinkedTransferQueue<WriteRequest> queue = new LinkedTransferQueue<>();

    /** Actor 线程 */
    private Thread actorThread;

    /** 运行状态标志 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 统计指标 */
    private final AtomicLong totalSubmitted = new AtomicLong(0);
    private final AtomicLong totalProcessed = new AtomicLong(0);
    private final AtomicLong totalRejected = new AtomicLong(0);
    private final AtomicLong totalErrors = new AtomicLong(0);
    private final AtomicLong totalBatches = new AtomicLong(0);
    private final AtomicLong totalFlushCycles = new AtomicLong(0);

    /** 同步等待队列 — 用于同步写入模式 */
    private final Map<Long, java.util.concurrent.CompletableFuture<Boolean>> pendingFutures
            = new ConcurrentHashMap<>();

    private final AtomicLong futureIdGenerator = new AtomicLong(0);

    private VfsAsyncWriter() {}

    public static VfsAsyncWriter getInstance() { return INSTANCE; }

    /**
     * 写请求记录。
     *
     * @param path      VFS 路径
     * @param content   写入内容
     * @param futureId  同步等待的 future ID(异步模式为 -1)
     */
    private record WriteRequest(String path, String content, long futureId) {}

    /**
     * 启动 Actor 线程。
     * <p>
     * 应该在 VfsManager.init() 之后调用。
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            actorThread = Thread.ofPlatform()
                    .name("vfs-async-writer", 0)
                    .daemon(true)
                    .start(this::actorLoop);
            log.info("[VfsAsyncWriter] Actor 线程已启动 (batchSize={}, highWatermark={})",
                    BATCH_SIZE, HIGH_WATERMARK);
        }
    }

    /**
     * 停止 Actor 线程。
     * <p>
     * 会等待队列中剩余的写操作全部处理完毕。
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            // 等待队列排空
            while (!queue.isEmpty()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (actorThread != null) {
                actorThread.interrupt();
                try {
                    actorThread.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            log.info("[VfsAsyncWriter] Actor 线程已停止 (processed={}, rejected={})",
                    totalProcessed.get(), totalRejected.get());
        }
    }

    /**
     * 异步写入 — 将写操作扔进队列，立即返回。
     * <p>
     * 业务 Agent 线程不阻塞在磁盘 I/O 上。
     * 写操作由后台 Actor 线程串行处理。
     *
     * @param path    VFS 路径
     * @param content 写入内容
     * @return true 如果成功加入队列; false 如果队列满(背压)
     */
    public boolean submitAsync(String path, String content) {
        if (!running.get()) {
            // Actor 未启动 → 直接同步写入(降级模式)
            return VfsManager.instance().writeText(path, content);
        }

        // 背压检查
        if (queue.size() >= HIGH_WATERMARK) {
            totalRejected.incrementAndGet();
            log.warn("[VfsAsyncWriter] 队列已满(>{})，拒绝写入: {}", HIGH_WATERMARK, path);
            SemanticEtw.getInstance().logEvent("VFS", "ASYNC_WRITE_REJECTED",
                    "path=" + path + " queueSize=" + queue.size());
            return false;
        }

        queue.offer(new WriteRequest(path, content, -1));
        totalSubmitted.incrementAndGet();
        return true;
    }

    /**
     * 同步写入 — 将写操作扔进队列，等待 Actor 处理完毕后返回。
     * <p>
     * 用于需要确认写入结果的场景(如配置文件写入)。
     *
     * @param path    VFS 路径
     * @param content 写入内容
     * @return true 如果写入成功
     */
    public boolean submitSync(String path, String content) {
        if (!running.get()) {
            return VfsManager.instance().writeText(path, content);
        }

        if (queue.size() >= HIGH_WATERMARK) {
            totalRejected.incrementAndGet();
            return false;
        }

        long futureId = futureIdGenerator.incrementAndGet();
        var future = new java.util.concurrent.CompletableFuture<Boolean>();
        pendingFutures.put(futureId, future);

        queue.offer(new WriteRequest(path, content, futureId));
        totalSubmitted.incrementAndGet();

        try {
            // 等待 Actor 处理完毕(虚拟线程友好，CompletableFuture.get 不 pin 虚拟线程)
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            pendingFutures.remove(futureId);
            log.warn("[VfsAsyncWriter] 同步写入超时或失败: path={}, error={}", path, e.getMessage());
            return false;
        }
    }

    /**
     * Actor 主循环 — 单线程消费队列，批量落盘。
     * <p>
     * 这是整个 Actor 模式的核心：
     * <ul>
     *   <li>从队列批量拉取写操作(最多 BATCH_SIZE 条)</li>
     *   <li>串行执行每个写操作(零锁竞争)</li>
     *   <li>对同步请求，通过 CompletableFuture 通知结果</li>
     * </ul>
     */
    private void actorLoop() {
        log.info("[VfsAsyncWriter] Actor 循环启动");

        List<WriteRequest> batch = new ArrayList<>(BATCH_SIZE);

        while (running.get() || !queue.isEmpty()) {
            try {
                // 阻塞等待第一条(带超时，以便检查 running 标志)
                WriteRequest first = queue.poll(IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    totalFlushCycles.incrementAndGet();
                    continue;
                }

                batch.add(first);

                // 批量拉取更多(非阻塞)
                WriteRequest req;
                while (batch.size() < BATCH_SIZE && (req = queue.poll()) != null) {
                    batch.add(req);
                }

                // 串行处理批量写操作
                processBatch(batch);
                totalBatches.incrementAndGet();

                batch.clear();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[VfsAsyncWriter] Actor 循环异常: {}", e.getMessage(), e);
                totalErrors.incrementAndGet();
                batch.clear();
            }
        }

        log.info("[VfsAsyncWriter] Actor 循环结束 (processed={}, batches={})",
                totalProcessed.get(), totalBatches.get());
    }

    /**
     * 处理一批写操作。
     * <p>
     * 串行执行每个写操作，对同步请求通过 future 返回结果。
     */
    private void processBatch(List<WriteRequest> batch) {
        VfsManager vfs = VfsManager.instance();

        for (WriteRequest req : batch) {
            boolean success = false;
            try {
                // 直接调用 VfsManager.writeText
                // 由于 Actor 是单线程，不存在锁竞争
                success = vfs.writeText(req.path(), req.content());
            } catch (Exception e) {
                log.warn("[VfsAsyncWriter] 写入失败: path={}, error={}", req.path(), e.getMessage());
                totalErrors.incrementAndGet();
            } finally {
                totalProcessed.incrementAndGet();

                // 对同步请求，通知结果
                if (req.futureId() != -1) {
                    var future = pendingFutures.remove(req.futureId());
                    if (future != null) {
                        future.complete(success);
                    }
                }
            }
        }
    }

    /**
     * 强制刷新 — 等待队列中所有写操作处理完毕。
     * <p>
     * 类似 Linux 内核的 sync() 系统调用。
     */
    public void flush() {
        long startSize = queue.size();
        while (!queue.isEmpty()) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (startSize > 0) {
            log.debug("[VfsAsyncWriter] flush 完成: 等待了 {} 条写操作", startSize);
        }
    }

    /**
     * 获取队列深度。
     */
    public int queueDepth() {
        return queue.size();
    }

    /**
     * 获取统计信息。
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("running", running.get());
        stats.put("queue_depth", queue.size());
        stats.put("total_submitted", totalSubmitted.get());
        stats.put("total_processed", totalProcessed.get());
        stats.put("total_rejected", totalRejected.get());
        stats.put("total_errors", totalErrors.get());
        stats.put("total_batches", totalBatches.get());
        stats.put("total_flush_cycles", totalFlushCycles.get());
        stats.put("pending_sync_requests", pendingFutures.size());
        return stats;
    }

    /**
     * 判断 Actor 是否正在运行。
     */
    public boolean isRunning() {
        return running.get();
    }
}
