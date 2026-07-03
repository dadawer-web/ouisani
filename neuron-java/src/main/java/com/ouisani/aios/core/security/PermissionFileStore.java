package com.ouisani.aios.core.security;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.ouisani.aios.core.config.AiosPaths;
import com.ouisani.aios.core.permission.Decision;
import com.ouisani.aios.core.permission.PermissionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 权限裁决文件持久化 — 镜像 jcode {@code safety.rs:355-416} 的 queue/history 持久化层。
 * <p>
 * 两个 JSON 文件位于 {@link AiosPaths#safetyDir()}：
 * <ul>
 *   <li>{@code queue.json} — 待裁决请求列表（{@link PermissionRequest} 数组），
 *       同步超时未裁决的请求持久化于此，等待外部进程回填</li>
 *   <li>{@code history.json} — 已裁决决策审计链（{@link Decision} 数组），
 *       每次裁决完成（auto/user_sync/file_async/timeout）追加一条</li>
 * </ul>
 * <p>
 * <b>并发控制</b>：单 JVM 内 {@code synchronized} 文件级锁。跨进程并发需 {@code FileChannel.lock()}
 * （neuron-java 暂无外部 poller 进程，延后引入）。
 * <p>
 * 持久化样板参考 {@link com.ouisani.aios.core.plan.VersionedPlanPersistence}：
 * Gson + record + {@code Files.writeString}/{@code Files.readString}。
 */
public final class PermissionFileStore {

    private static final Logger log = LoggerFactory.getLogger(PermissionFileStore.class);
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    /** 文件级锁 — 保证 queue.json 与 history.json 各自的读改写原子性。 */
    private static final Object QUEUE_LOCK = new Object();
    private static final Object HISTORY_LOCK = new Object();

    private PermissionFileStore() {}

    // ════════════════════════════════════════════════════════════════
    //  路径
    // ════════════════════════════════════════════════════════════════

    /** queue.json 路径 — 待裁决请求列表。 */
    public static Path queuePath() {
        return Path.of(AiosPaths.safetyDir(), "queue.json");
    }

    /** history.json 路径 — 已裁决决策审计链。 */
    public static Path historyPath() {
        return Path.of(AiosPaths.safetyDir(), "history.json");
    }

    // ════════════════════════════════════════════════════════════════
    //  queue.json 读写
    // ════════════════════════════════════════════════════════════════

    /** 读取 queue.json — 文件不存在或解析失败返回空列表。 */
    public static List<PermissionRequest> readQueue() {
        synchronized (QUEUE_LOCK) {
            return readJsonList(queuePath(), PermissionRequest.class);
        }
    }

    /** 全量写入 queue.json。 */
    public static void writeQueue(List<PermissionRequest> queue) {
        synchronized (QUEUE_LOCK) {
            writeJsonList(queuePath(), queue);
        }
    }

    /**
     * 入队一个待裁决请求 — 读 queue → push → 写回。
     * <p>
     * 镜像 jcode {@code safety.rs} 的 queue push 逻辑。
     */
    public static void enqueueRequest(PermissionRequest request) {
        if (request == null) return;
        synchronized (QUEUE_LOCK) {
            List<PermissionRequest> queue = readJsonList(queuePath(), PermissionRequest.class);
            queue.add(request);
            writeJsonList(queuePath(), queue);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  history.json 读写
    // ════════════════════════════════════════════════════════════════

    /** 读取 history.json — 文件不存在或解析失败返回空列表。 */
    public static List<Decision> readHistory() {
        synchronized (HISTORY_LOCK) {
            return readJsonList(historyPath(), Decision.class);
        }
    }

    /** 追加一条决策到 history.json — 读 history → push → 写回。 */
    public static void appendHistory(Decision decision) {
        if (decision == null) return;
        synchronized (HISTORY_LOCK) {
            List<Decision> history = readJsonList(historyPath(), Decision.class);
            history.add(decision);
            writeJsonList(historyPath(), history);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  record_permission_via_file — 镜像 safety.rs:379-416
    // ════════════════════════════════════════════════════════════════

    /**
     * 通过文件回填裁决结果 — 镜像 jcode {@code safety.rs:379-416} {@code record_permission_via_file}。
     * <p>
     * 给没有 live SafetySystem 实例的外部进程（如 IMAP 邮件回复轮询器、Dashboard 异步回调）
     * 跨进程写入裁决结果的通道：
     * <ol>
     *   <li>读 queue.json → {@code removeIf(r -> requestId 匹配)} → 写回</li>
     *   <li>读 history.json → push 新 {@link Decision} → 写回</li>
     * </ol>
     *
     * @param requestId  对应 {@link PermissionRequest#requestId()}
     * @param approved   true=批准，false=拒绝
     * @param decidedVia 裁决来源（{@code "user_sync"}/{@code "file_async"}/{@code "timeout"}）
     * @param reason     裁决原因（可选，null 转空串）
     * @return true 如果 queue 中找到并移除了该 requestId；false 如果未找到
     */
    public static boolean recordPermissionViaFile(String requestId, boolean approved,
                                                   String decidedVia, String reason) {
        if (requestId == null || requestId.isBlank()) return false;
        boolean removed;
        synchronized (QUEUE_LOCK) {
            List<PermissionRequest> queue = readJsonList(queuePath(), PermissionRequest.class);
            int before = queue.size();
            queue.removeIf(r -> requestId.equals(r.requestId()));
            removed = queue.size() < before;
            if (removed) writeJsonList(queuePath(), queue);
        }
        if (removed) {
            Decision decision = new Decision(
                    requestId, "", approved, System.currentTimeMillis(),
                    decidedVia != null ? decidedVia : "unknown",
                    reason != null ? reason : "",
                    null, null  // PermissionRequest 已从 queue 移除，urgency/tier 未知
            );
            appendHistory(decision);
            log.info("[PermissionFileStore] recorded via file: requestId={} approved={} via={}",
                    requestId, approved, decidedVia);
        } else {
            log.warn("[PermissionFileStore] requestId not found in queue: {}", requestId);
        }
        return removed;
    }

    // ════════════════════════════════════════════════════════════════
    //  expire_stale — 镜像 safety.rs:420-470
    // ════════════════════════════════════════════════════════════════

    /**
     * 清理过期未裁决请求 — 镜像 jcode {@code safety.rs:420-470} {@code expire_dead_session_requests}。
     * <p>
     * queue 中 {@link PermissionRequest#createdAtMs()} 超过 {@code maxAgeMs} 的项：
     * <ol>
     *   <li>追加 {@link Decision}(approved=false, decidedVia={@code via}) 到 history</li>
     *   <li>从 queue 移除</li>
     * </ol>
     *
     * @param maxAgeMs 最大存活毫秒数
     * @param via      过期清理标记（通常 {@code "timeout"}）
     * @return 被清理的 requestId 列表
     */
    public static List<String> expireStale(long maxAgeMs, String via) {
        List<String> expired = new ArrayList<>();
        long now = System.currentTimeMillis();
        synchronized (QUEUE_LOCK) {
            List<PermissionRequest> queue = readJsonList(queuePath(), PermissionRequest.class);
            List<PermissionRequest> survivors = new ArrayList<>();
            for (PermissionRequest req : queue) {
                if (now - req.createdAtMs() > maxAgeMs) {
                    expired.add(req.requestId());
                    Decision decision = new Decision(
                            req.requestId(), req.action(), false, now,
                            via != null ? via : "timeout",
                            "expired after " + maxAgeMs + "ms",
                            req.urgency(), req.tier()
                    );
                    // history 写入需在 HISTORY_LOCK 内，但不能在 QUEUE_LOCK 持有时嵌套
                    // — 用临时列表收集，循环结束后释放 QUEUE_LOCK 再写 history
                    appendHistoryInternal(decision);
                } else {
                    survivors.add(req);
                }
            }
            if (!expired.isEmpty()) {
                writeJsonList(queuePath(), survivors);
            }
        }
        if (!expired.isEmpty()) {
            log.info("[PermissionFileStore] expired {} stale requests via={}", expired.size(), via);
        }
        return expired;
    }

    // ════════════════════════════════════════════════════════════════
    //  内部工具
    // ════════════════════════════════════════════════════════════════

    /** appendHistory 内部入口 — 不重新获取 HISTORY_LOCK（供 expireStale 在 QUEUE_LOCK 内调用）。 */
    private static void appendHistoryInternal(Decision decision) {
        synchronized (HISTORY_LOCK) {
            List<Decision> history = readJsonList(historyPath(), Decision.class);
            history.add(decision);
            writeJsonList(historyPath(), history);
        }
    }

    private static <T> List<T> readJsonList(Path path, Class<T> elementClass) {
        try {
            if (!Files.exists(path)) return new ArrayList<>();
            String json = Files.readString(path);
            if (json == null || json.isBlank()) return new ArrayList<>();
            java.lang.reflect.Type listType = TypeToken.getParameterized(List.class, elementClass).getType();
            List<T> list = GSON.fromJson(json, listType);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            log.warn("[PermissionFileStore] read failed {}: {}", path, e.getMessage());
            return new ArrayList<>();
        }
    }

    private static <T> void writeJsonList(Path path, List<T> list) {
        try {
            Files.createDirectories(path.getParent());
            String json = GSON.toJson(list != null ? list : new ArrayList<>());
            Files.writeString(path, json);
        } catch (IOException e) {
            log.error("[PermissionFileStore] write failed {}: {}", path, e.getMessage());
        }
    }
}
