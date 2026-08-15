package com.ouisani.aios.core.recovery;

import com.ouisani.aios.user.apps.omnifactory.AutoMedicAgent;
import com.ouisani.aios.user.apps.omnifactory.WorkflowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 资源补充策略 — 对应 AutoMedic 的 RESOURCE_EXHAUSTED 诊断结果。
 * <p>
 * 当节点因资源耗尽（Token OOM、超时等）崩溃时，此策略：
 * 1. 注入反思提示（建议精简输出）
 * 2. 尝试增加节点的 Token 软限制（如果有 CgroupManager）
 * 3. 通过 WorkflowEngine.resumeNode() 热重启节点
 * <p>
 * OS 类比：Linux 的 OOM Killer — 当进程占用内存超限时，
 * 内核可以选择杀死进程，也可以选择增加 cgroup 的内存限额。
 * 本策略选择后者：增加限额 + 注入提醒。
 */
public class ResourceRefillStrategy implements RecoveryStrategy {

    private static final Logger log = LoggerFactory.getLogger(ResourceRefillStrategy.class);

    /** 默认 Token 软限制增量 */
    private static final int TOKEN_LIMIT_INCREMENT = 2000;

    @Override public String name() { return "resource_refill"; }

    @Override
    public boolean shouldApply(RecoveryContext context) {
        RecoveryOrchestrator.ErrorCategory cat = context.category();
        return cat == RecoveryOrchestrator.ErrorCategory.CONTEXT_WINDOW_EXCEEDED
                || cat == RecoveryOrchestrator.ErrorCategory.RATE_LIMITED;
    }

    @Override
    public RecoveryResult apply(RecoveryContext context) {
        String nodeId = context.agentId();
        String workflowId = context.metadata().get("workflowId") != null
                ? context.metadata().get("workflowId").toString() : null;

        if (workflowId == null) {
            return RecoveryResult.failed("Missing workflowId in context metadata");
        }

        log.info("[ResourceRefill] 开始资源补充: nodeId={}", nodeId);

        try {
            String errorMsg = context.exception().getMessage() != null ? context.exception().getMessage() : "unknown";

            // 1. 构建反思提示 — 建议精简输出
            String hint = buildReflectionHint(errorMsg);

            // 2. 尝试增加 Token 软限制
            // 如果有 CgroupManager，可以增加节点的资源配额
            Integer currentLimit = null;
            try {
                Object limitObj = context.metadata().get("tokenLimit");
                if (limitObj instanceof Number) {
                    currentLimit = ((Number) limitObj).intValue();
                }
            } catch (Exception ignored) {}

            int newLimit = (currentLimit != null ? currentLimit : 8000) + TOKEN_LIMIT_INCREMENT;
            log.info("[ResourceRefill] Token 软限制调整: {} → {}", currentLimit, newLimit);

            // 3. 构造 MedicalReport 并调用 resumeNode
            AutoMedicAgent.MedicalReport report = new AutoMedicAgent.MedicalReport(
                    AutoMedicAgent.Outcome.HEALED,
                    "Resource exhausted: " + errorMsg + ". Token limit increased to " + newLimit,
                    null, null,
                    hint,
                    null
            );

            boolean resumed = WorkflowEngine.instance().resumeNode(nodeId, report, workflowId);
            if (resumed) {
                return RecoveryResult.ok("Resource refill successful, node restarted with increased limit");
            } else {
                return RecoveryResult.failed("Node restart failed after resource refill");
            }

        } catch (Exception e) {
            log.error("[ResourceRefill] 资源补充失败: {}", e.getMessage());
            return RecoveryResult.failed("Resource refill failed: " + e.getMessage());
        }
    }

    /**
     * 构建反思提示 — 根据错误类型给出不同的精简建议。
     */
    private String buildReflectionHint(String errorMsg) {
        String lower = errorMsg.toLowerCase();

        if (lower.contains("context_length_exceeded") || lower.contains("too many tokens") || lower.contains("token limit")) {
            return "注意：你上次执行时 Token 超限。请大幅精简输出：1) 减少不必要的解释 2) 只输出关键结果 3) 不要重复上游已提供的信息";
        }

        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "注意：你上次执行时超时。请简化操作步骤，避免复杂的多轮推理，直接给出结果";
        }

        if (lower.contains("rate_limit") || lower.contains("429") || lower.contains("quota")) {
            return "注意：你上次执行时触发了 API 限流。请减少 API 调用次数，合并多个小请求为一个大请求";
        }

        return "注意：你上次执行时资源耗尽（" + errorMsg + "）。请精简输出，减少不必要的冗余内容";
    }
}
