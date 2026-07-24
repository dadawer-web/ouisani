package com.ouisani.aios.core.action;

import com.ouisani.aios.core.snapshot.EnvironmentSnapshot;
import com.ouisani.aios.core.syscall.SyscallRequest;

/**
 * beforeAction 返回的动作上下文，传给 afterAction。
 *
 * @param requestId   动作唯一标识
 * @param agentId     发起 Agent
 * @param riskLevel   风险等级
 * @param snapshotId  动作前快照 ID；SAFE 为 null
 * @param before      动作前快照对象；SAFE 为 null
 * @param request     原始 syscall 请求（afterAction 用于日志/审计）
 */
public record ActionContext(
        String requestId,
        String agentId,
        RiskLevel riskLevel,
        String snapshotId,
        EnvironmentSnapshot before,
        SyscallRequest request
) {
}
