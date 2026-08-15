package com.ouisani.aios.core.team;

import com.ouisani.aios.core.network.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

/**
 * Generation 批量任务追踪器 — 将一次用户请求产生的所有子任务归为一个 Generation，
 * 当 Generation 内所有任务完成时触发完成事件。
 * <p>
 * 借鉴 Apix 的 {@code team_task_manager.py} 中的 Generation 机制，并适配 Java 并发模型。
 * <p>
 * <b>核心数据结构（双向索引）：</b>
 * <ul>
 *   <li>{@code generationTasks}: generationId → 任务 ID 集合（正向索引）</li>
 *   <li>{@code taskGeneration}: taskId → generationId（反向索引）</li>
 * </ul>
 * <p>
 * <b>工作流程：</b>
 * <ol>
 *   <li>Lead Agent 收到用户请求，创建 generationId</li>
 *   <li>分解为多个子任务，每个子任务通过 {@link #registerTask} 关联到该 generation</li>
 *   <li>子任务完成/失败/取消时调用 {@link #finishTask}，从 generation 集合中移除</li>
 *   <li>当 generation 集合为空时，触发 {@code onGenerationCompleted} 回调</li>
 *   <li>Lead Agent 收到回调后查询所有子任务结果，汇总回复用户</li>
 * </ol>
 * <p>
 * <b>OS 类比</b>：相当于 Linux 的 {@code waitpid(-1, WNOHANG)} 批量等待子进程，
 * 或 {@code completion} 机制的批量完成通知。
 *
 * @see TeamManager#createTaskWithGeneration
 */
public class GenerationTracker {

    private static final Logger log = LoggerFactory.getLogger(GenerationTracker.class);

    /** generationId → 该 generation 中尚未完成的任务 ID 集合 */
    private final Map<String, Set<String>> generationTasks = new ConcurrentHashMap<>();

    /** taskId → generationId（反向索引，用于任务完成时查找所属 generation） */
    private final Map<String, String> taskGeneration = new ConcurrentHashMap<>();

    /** 状态锁 — 保护 generationTasks 和 taskGeneration 的读改写操作 */
    private final ReentrantLock stateLock = new ReentrantLock();

    /** Generation 完成回调 — 参数为 (generationId, historyId) */
    private volatile BiConsumer<String, String> onGenerationCompleted;

    /**
     * 设置 Generation 完成回调。
     * <p>
     * 当一个 generation 内所有任务完成时，回调被调用。
     * 通常由 {@link TeamManager} 设置，用于通过 EventBus 广播完成事件。
     *
     * @param callback 完成回调，参数为 (generationId, historyId)
     */
    public void setOnGenerationCompleted(BiConsumer<String, String> callback) {
        this.onGenerationCompleted = callback;
    }

    /**
     * 创建新的 Generation ID。
     *
     * @return 新的 generationId（UUID）
     */
    public String createGeneration() {
        return "gen_" + UUID.randomUUID().toString().substring(0, 12);
    }

    /**
     * 注册任务到 Generation — 将任务关联到指定 generation。
     * <p>
     * 如果 generation 尚不存在，自动创建。
     *
     * @param generationId generation ID
     * @param taskId       任务 ID
     */
    public void registerTask(String generationId, String taskId) {
        stateLock.lock();
        try {
            generationTasks.computeIfAbsent(generationId, k -> ConcurrentHashMap.newKeySet()).add(taskId);
            taskGeneration.put(taskId, generationId);
            log.trace("[GenerationTracker] 任务已注册: gen={}, task={}", generationId, taskId);
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * 完成任务 — 从 generation 集合中移除任务，当集合为空时触发完成回调。
     * <p>
     * 这是 Generation 机制的核心方法，在任务完成/失败/取消时调用。
     *
     * @param taskId     已完成的任务 ID
     * @param historyId  关联的会话历史 ID（用于回调）
     * @return 完成结果：若 generation 全部完成则包含 generationId，否则为 null
     */
    public GenerationFinishResult finishTask(String taskId, String historyId) {
        String generationId;
        boolean generationFinished;

        stateLock.lock();
        try {
            generationId = taskGeneration.remove(taskId);
            if (generationId == null) {
                // 任务不在任何 generation 中（可能是未关联 generation 的独立任务）
                return null;
            }

            Set<String> taskIds = generationTasks.get(generationId);
            if (taskIds == null) {
                // generation 已被清理（理论上不应发生）
                return null;
            }

            taskIds.remove(taskId);

            if (taskIds.isEmpty()) {
                // generation 内所有任务已完成
                generationTasks.remove(generationId);
                generationFinished = true;
            } else {
                generationFinished = false;
            }
        } finally {
            stateLock.unlock();
        }

        log.debug("[GenerationTracker] 任务完成: gen={}, task={}, remaining={}, generationFinished={}",
                generationId, taskId,
                generationFinished ? 0 : generationTasks.get(generationId).size(),
                generationFinished);

        if (generationFinished) {
            // 在锁外触发回调，避免回调中再次获取锁导致死锁
            BiConsumer<String, String> callback = this.onGenerationCompleted;
            if (callback != null) {
                try {
                    callback.accept(generationId, historyId);
                } catch (Exception e) {
                    log.warn("[GenerationTracker] Generation 完成回调异常: gen={}", generationId, e);
                }
            }
            return new GenerationFinishResult(generationId, true);
        }

        return new GenerationFinishResult(generationId, false);
    }

    /**
     * 获取 Generation 中尚未完成的任务 ID 集合。
     *
     * @param generationId generation ID
     * @return 任务 ID 集合的不可变副本，generation 不存在则返回空集
     */
    public Set<String> getGenerationTasks(String generationId) {
        stateLock.lock();
        try {
            Set<String> tasks = generationTasks.get(generationId);
            return tasks != null ? Set.copyOf(tasks) : Set.of();
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * 获取任务所属的 Generation ID。
     *
     * @param taskId 任务 ID
     * @return generationId，未关联则返回 null
     */
    public String getTaskGeneration(String taskId) {
        return taskGeneration.get(taskId);
    }

    /**
     * 获取 Generation 中尚未完成的任务数量。
     *
     * @param generationId generation ID
     * @return 剩余任务数，generation 不存在则返回 0
     */
    public int getRemainingTaskCount(String generationId) {
        stateLock.lock();
        try {
            Set<String> tasks = generationTasks.get(generationId);
            return tasks != null ? tasks.size() : 0;
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * 获取所有活跃的 Generation ID。
     *
     * @return generation ID 集合
     */
    public Set<String> getActiveGenerations() {
        return Set.copyOf(generationTasks.keySet());
    }

    /**
     * 清理已完成的 Generation（集合为空的已被自动清理，此方法用于强制清理）。
     *
     * @return 被清理的 generation 数量
     */
    public int cleanupFinishedGenerations() {
        int cleaned = 0;
        stateLock.lock();
        try {
            Iterator<Map.Entry<String, Set<String>>> it = generationTasks.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Set<String>> entry = it.next();
                if (entry.getValue().isEmpty()) {
                    it.remove();
                    cleaned++;
                }
            }
        } finally {
            stateLock.unlock();
        }
        if (cleaned > 0) {
            log.debug("[GenerationTracker] 已清理 {} 个完成的 generation", cleaned);
        }
        return cleaned;
    }

    /**
     * Generation 完成结果 — 描述任务完成后的 generation 状态。
     *
     * @param generationId       所属 generation ID
     * @param generationFinished 该 generation 是否已全部完成
     */
    public record GenerationFinishResult(String generationId, boolean generationFinished) {}
}
