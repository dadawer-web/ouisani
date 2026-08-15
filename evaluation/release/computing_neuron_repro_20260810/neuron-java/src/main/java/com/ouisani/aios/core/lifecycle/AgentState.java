package com.ouisani.aios.core.lifecycle;

/**
 * Agent 生命周期状态枚举。
 * 借鉴 Paperclip 的 Agent 状态机 + Linux 进程状态。
 *
 * <pre>
 *   PENDING_APPROVAL → IDLE → RUNNING → PAUSED → TERMINATED
 *                                ↕
 *                              ERROR
 * </pre>
 *
 * OS 类比：
 *   PENDING_APPROVAL = 进程等待 exec 权限
 *   IDLE             = TASK_INTERRUPTIBLE（可中断睡眠，等待唤醒）
 *   RUNNING          = TASK_RUNNING（CPU 上执行）
 *   PAUSED           = SIGSTOP（挂起，可 SIGCONT 恢复）
 *   ERROR            = EXIT_ZOMBIE（异常退出，可回收或重启）
 *   TERMINATED       = EXIT_DEAD（最终状态，不可逆）
 */
public enum AgentState {

    /** 待审批 — Agent 已创建但尚未激活，需董事会/管理员审批 */
    PENDING_APPROVAL("pending_approval", false),

    /** 空闲 — Agent 已激活，等待被唤醒执行任务 */
    IDLE("idle", true),

    /** 运行中 — Agent 正在执行任务 */
    RUNNING("running", true),

    /** 暂停 — Agent 被挂起（手动/预算/系统原因），可恢复 */
    PAUSED("paused", false),

    /** 错误 — Agent 运行异常，可清除错误恢复到 IDLE */
    ERROR("error", false),

    /** 终止 — Agent 已永久停止，不可恢复 */
    TERMINATED("terminated", false);

    private final String label;
    private final boolean active; // 是否可被调度

    AgentState(String label, boolean active) {
        this.label = label;
        this.active = active;
    }

    public String label() { return label; }
    public boolean isActive() { return active; }

    /**
     * 判断从当前状态转换到目标状态是否合法。
     * 状态机转换规则：
     * <pre>
     *   PENDING_APPROVAL → IDLE              (审批通过)
     *   IDLE             → RUNNING           (心跳唤醒)
     *   IDLE             → PAUSED            (手动暂停)
     *   IDLE             → TERMINATED        (终止)
     *   RUNNING          → IDLE              (任务完成)
     *   RUNNING          → PAUSED            (手动/预算暂停)
     *   RUNNING          → ERROR             (运行异常)
     *   PAUSED           → IDLE              (恢复)
     *   PAUSED           → TERMINATED        (终止)
     *   ERROR            → IDLE              (清除错误)
     *   ERROR            → TERMINATED        (终止)
     *   TERMINATED       → (无，终态不可逆)
     * </pre>
     */
    public boolean canTransitionTo(AgentState target) {
        return switch (this) {
            case PENDING_APPROVAL -> target == IDLE || target == TERMINATED;
            case IDLE             -> target == RUNNING || target == PAUSED || target == TERMINATED;
            case RUNNING          -> target == IDLE || target == PAUSED || target == ERROR;
            case PAUSED           -> target == IDLE || target == TERMINATED;
            case ERROR            -> target == IDLE || target == TERMINATED;
            case TERMINATED       -> false; // 终态不可逆
        };
    }

    /** 从标签字符串解析状态 */
    public static AgentState fromLabel(String label) {
        for (AgentState s : values()) {
            if (s.label.equals(label)) return s;
        }
        throw new IllegalArgumentException("Unknown AgentState label: " + label);
    }
}
