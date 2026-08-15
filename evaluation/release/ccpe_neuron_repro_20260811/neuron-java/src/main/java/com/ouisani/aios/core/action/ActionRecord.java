package com.ouisani.aios.core.action;

import com.ouisani.aios.core.snapshot.StateDiff;
import com.ouisani.aios.core.syscall.ResultState;
import com.ouisani.aios.core.syscall.SyscallRequest;

/**
 * 受治理动作的执行记录 — 写入 undo 栈，供 undo/审计/GC 使用。
 *
 * @param requestId       动作唯一标识（beforeAction 生成）
 * @param agentId         发起 Agent
 * @param snapshotId      动作前快照 ID；SAFE 为 null
 * @param request         原始 syscall 请求
 * @param riskLevel       风险等级
 * @param startedAtMs     beforeAction 时间戳
 * @param completedAtMs   afterAction 时间戳；未完成为 0
 * @param resultState     最终结果状态（来自 response）
 * @param autoRolledBack  afterAction 是否因违反期望而自动回滚
 * @param undone          是否已被 undo
 * @param diff            执行前后 diff；无 diff 为 null
 */
public record ActionRecord(
        String requestId,
        String agentId,
        String snapshotId,
        SyscallRequest request,
        RiskLevel riskLevel,
        long startedAtMs,
        long completedAtMs,
        ResultState resultState,
        boolean autoRolledBack,
        boolean undone,
        StateDiff diff
) {
    /** beforeAction 阶段的初始记录（未完成）。 */
    static ActionRecord initial(String requestId, String agentId, String snapshotId,
                                SyscallRequest request, RiskLevel riskLevel, long startedAtMs) {
        return new ActionRecord(requestId, agentId, snapshotId, request, riskLevel,
                startedAtMs, 0L, null, false, false, null);
    }

    /** afterAction 阶段更新为完成态。 */
    ActionRecord withCompletion(long completedAtMs, ResultState resultState,
                                boolean autoRolledBack, StateDiff diff) {
        return new ActionRecord(requestId, agentId, snapshotId, request, riskLevel,
                startedAtMs, completedAtMs, resultState, autoRolledBack, undone, diff);
    }

    /** 标记为已 undo。 */
    ActionRecord withUndone() {
        return new ActionRecord(requestId, agentId, snapshotId, request, riskLevel,
                startedAtMs, completedAtMs, resultState, autoRolledBack, true, diff);
    }

    /** 是否持有可恢复快照（REVERSIBLE 且有 snapshotId 且未 undo）。 */
    boolean isUndoable() {
        return riskLevel == RiskLevel.REVERSIBLE && snapshotId != null && !undone;
    }
}
