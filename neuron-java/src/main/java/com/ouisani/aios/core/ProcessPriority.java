package com.ouisani.aios.core;

/**
 * 进程优先级 — AIOS Agent 进程的调度优先级，灵感来自 Windows 优先级类和 POSIX nice 值。
 * <p>
 * OS 类比: Linux 的 nice 值 + 实时调度策略。
 * <ul>
 *   <li>{@link #REALTIME} — 内核级特权：绕过 cgroup Token 限制，永不触发 OOM。
 *       保留给系统 PID（&lt; 100）或 "sys_" 前缀的 Agent。</li>
 *   <li>{@link #HIGH} — 高优先级：优先于 NORMAL 任务调度。</li>
 *   <li>{@link #NORMAL} — 用户 Agent 的默认优先级。</li>
 *   <li>{@link #IDLE} — 最低优先级：仅在没有其他工作时运行。</li>
 * </ul>
 */
public enum ProcessPriority {

    /** Highest privilege: cgroup token limits completely bypassed. */
    REALTIME,

    /** High priority: preferred scheduling over NORMAL. */
    HIGH,

    /** Default priority for user agents. */
    NORMAL,

    /** Lowest priority: background/idle work only. */
    IDLE
}
