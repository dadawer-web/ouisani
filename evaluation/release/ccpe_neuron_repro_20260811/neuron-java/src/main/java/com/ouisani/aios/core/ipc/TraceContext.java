package com.ouisani.aios.core.ipc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * 端到端 Trace ID 管理器 — 借鉴分布式追踪的 Trace ID 概念。
 * <p>
 * 在 VariablePool 的 TASK 级别强制注入 x-aios-trace-id。
 * 任何通过 Mailbox 发送的消息、落盘的日志、以及写入 VFS 的文件，必须带上这个 ID。
 * <p>
 * OS 类比：Linux 的 tracefs/ftrace — 内核级追踪标识，贯穿整个调用链。
 * <p>
 * 使用 InheritableThreadLocal 实现线程级 Trace ID 传播，
 * 虚拟线程自动继承父线程的 Trace ID，确保跨 Agent 调用的 Trace ID 一致性。
 */
public class TraceContext {

    private static final Logger log = LoggerFactory.getLogger(TraceContext.class);

    /** VariablePool 中的 Trace ID 键名 */
    public static final String TRACE_ID_KEY = "x-aios-trace-id";

    /** InheritableThreadLocal：线程级 Trace ID 传播（虚拟线程自动继承） */
    private static final InheritableThreadLocal<String> CURRENT_TRACE_ID = new InheritableThreadLocal<>();

    /**
     * 生成新的 Trace ID。
     */
    public static String generateTraceId() {
        return "trace-" + UUID.randomUUID().toString().substring(0, 12);
    }

    /**
     * 在 Trace ID 上下文中执行代码 — 自动传播 Trace ID。
     * <p>
     * 如果当前线程已有 Trace ID，则继承；否则生成新的。
     *
     * @param task    要执行的任务
     * @param taskId  任务 ID（用于写入 VariablePool）
     * @return 任务的返回值
     */
    public static <T> T withTrace(Callable<T> task, String taskId) {
        String traceId = getCurrentTraceId();
        if (traceId == null) {
            traceId = generateTraceId();
        }

        // 写入 VariablePool TASK 作用域
        if (taskId != null) {
            VariablePool.getInstance().set(VariablePool.Scope.TASK, taskId, TRACE_ID_KEY, traceId);
        }

        final String finalTraceId = traceId;
        String oldTraceId = CURRENT_TRACE_ID.get();
        CURRENT_TRACE_ID.set(finalTraceId);
        try {
            log.debug("[TraceContext] Trace ID 已注入: {} (task={})", finalTraceId, taskId);
            return task.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            // 恢复之前的 Trace ID（支持嵌套调用）
            if (oldTraceId != null) {
                CURRENT_TRACE_ID.set(oldTraceId);
            } else {
                CURRENT_TRACE_ID.remove();
            }
        }
    }

    /**
     * 在 Trace ID 上下文中执行代码（无返回值版本）。
     */
    public static void withTrace(Runnable task, String taskId) {
        withTrace(() -> {
            task.run();
            return null;
        }, taskId);
    }

    /**
     * 获取当前线程的 Trace ID。
     * 如果当前线程没有 Trace ID，返回 null。
     */
    public static String getCurrentTraceId() {
        return CURRENT_TRACE_ID.get();
    }

    /**
     * 设置当前线程的 Trace ID（低级 API，优先使用 withTrace）。
     */
    public static void setCurrentTraceId(String traceId) {
        CURRENT_TRACE_ID.set(traceId);
    }

    /**
     * 从 VariablePool 获取指定任务的 Trace ID。
     */
    public static String getTraceIdForTask(String taskId) {
        return VariablePool.getInstance().get(VariablePool.Scope.TASK, taskId, TRACE_ID_KEY, String.class);
    }

    /**
     * 为指定任务设置 Trace ID（如果没有的话）。
     */
    public static String ensureTraceId(String taskId) {
        String existing = getTraceIdForTask(taskId);
        if (existing != null) {
            return existing;
        }
        String newTraceId = getCurrentTraceId();
        if (newTraceId == null) {
            newTraceId = generateTraceId();
        }
        VariablePool.getInstance().set(VariablePool.Scope.TASK, taskId, TRACE_ID_KEY, newTraceId);
        return newTraceId;
    }

    /**
     * 在日志中添加 Trace ID 前缀。
     * 用法：log.info(TraceContext.logPrefix(taskId) + "some message");
     */
    public static String logPrefix(String taskId) {
        String traceId = taskId != null ? getTraceIdForTask(taskId) : getCurrentTraceId();
        return traceId != null ? "[" + traceId + "] " : "";
    }
}
