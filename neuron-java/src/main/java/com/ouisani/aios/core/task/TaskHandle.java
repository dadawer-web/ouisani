package com.ouisani.aios.core.task;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 任务句柄 — 对标 Claude Code 的 TaskHandle。
 * <p>
 * 包含任务 ID 和可选的清理回调。
 * <p>
 * OS 类比：相当于 Linux 的进程描述符 (task_struct 指针)。
 *
 * @param taskId   唯一任务 ID（前缀 + 8位随机 base36）
 * @param cleanup  清理回调（任务终止时调用）
 */
public record TaskHandle(
        String taskId,
        Runnable cleanup
) {
    public TaskHandle(String taskId) {
        this(taskId, null);
    }

    /**
     * 生成任务 ID — 格式：{type_prefix}{8位随机base36}
     * 例如：b3k9f2x1 (bash), a7m2p4q8 (agent), d1n5r8w3 (dream)
     */
    public static TaskHandle generate(TaskType type) {
        String prefix = type.idPrefix();
        StringBuilder sb = new StringBuilder(prefix);
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < 8; i++) {
            sb.append(Integer.toString(rng.nextInt(36), 36));
        }
        return new TaskHandle(sb.toString());
    }

    /**
     * 执行清理（如果存在）。
     */
    public void cleanUp() {
        if (cleanup != null) {
            try {
                cleanup.run();
            } catch (Exception e) {
                System.err.println("[TaskHandle] Cleanup failed for " + taskId + ": " + e.getMessage());
            }
        }
    }
}
