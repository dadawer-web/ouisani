package com.ouisani.aios.core.hibernation;

import java.util.List;

/**
 * 任务队列快照提供者 — 供 {@link HibernationManager} 注入,捕获正在处理的任务队列。
 * <p>
 * core/hibernation 定义此接口(返回 core 内建的 {@link AgentSnapshot.TaskState}),
 * user 态实现并注入,避免 core 反向依赖 user。无注入时 {@code captureTaskQueue}
 * 返回空列表(保持既有兼容行为)。
 */
public interface TaskQueueSnapshotProvider {

    /** 捕获当前活跃任务队列。无任务时返回空列表。 */
    List<AgentSnapshot.TaskState> capture();
}
