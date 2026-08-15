package com.ouisani.aios.core.action;

import com.ouisani.aios.core.audit.UnifiedAuditLog;
import com.ouisani.aios.core.permission.EscalationPolicy;
import com.ouisani.aios.core.permission.PermissionChecker;
import com.ouisani.aios.core.permission.PermissionDecision;
import com.ouisani.aios.core.permission.ToolPermissionChannel;
import com.ouisani.aios.core.recovery.RecoveryAuthorizationManager;
import com.ouisani.aios.core.tool.DelegationGuard;
import com.ouisani.aios.core.tool.Tool;
import com.ouisani.aios.core.tool.ToolContext;
import com.ouisani.aios.core.tool.ToolInput;
import com.ouisani.aios.core.tool.ToolOutput;
import com.ouisani.aios.core.ipc.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * 统一行动闸门：身份绑定 → 权限决策 → 人工审批 → digest 重校验 → 执行审计。
 *
 * <p>它不执行工具本身；调用方必须在 {@link #authorize} 返回 ALLOW 后才调用
 * 工具，并在调用前再次调用 {@link #validateDigest}。这样所有工具都共享一条
 * 可测试的策略边界，而不需要把工具实现改造成权限组件。</p>
 */
public final class ActionGate {

    private static final Logger log = LoggerFactory.getLogger(ActionGate.class);
    private static final ActionGate INSTANCE = new ActionGate();

    public enum Decision { ALLOW, DENY, REQUIRE_APPROVAL }

    public record GateResult(Decision decision, String reason, String actionDigest,
                             PermissionDecision permissionDecision) {
        public boolean allowed() { return decision == Decision.ALLOW; }
        public boolean denied() { return decision == Decision.DENY; }
        public boolean requiresApproval() { return decision == Decision.REQUIRE_APPROVAL; }
    }

    private ActionGate() {}

    public static ActionGate instance() { return INSTANCE; }

    /**
     * 对一个工具行动作出唯一决策。PermissionChecker 仍是策略事实来源，
     * Action Gate 负责把 ASK 变成人类审批并统一映射为三态产品协议。
     */
    public <I extends ToolInput> GateResult authorize(ActionEnvelope envelope,
                                                       Tool<I> tool, I input,
                                                       ToolContext context,
                                                       PermissionChecker checker) {
        if (envelope == null) return deny(null, "missing_action_envelope");
        if (envelope.agentId().isBlank()) return deny(envelope, "missing_agent_identity");
        if (!envelope.isReadOnlyAction() && !envelope.hasWorkflowIdentity()) {
            return deny(envelope, "missing_workflow_identity_for_side_effect");
        }
        if (envelope.isExpired(System.currentTimeMillis())) {
            return deny(envelope, "action_expired");
        }
        if (!validateDigest(envelope, envelope.parametersJson())) {
            return deny(envelope, "action_digest_invalid");
        }
        String recoveryDenial = RecoveryAuthorizationManager.instance().denialReason(envelope);
        if (recoveryDenial != null) {
            return deny(envelope, recoveryDenial);
        }

        PermissionDecision permission = checker.checkPermission(tool, input, context);
        if (permission.isDenied()) {
            audit(envelope, "DENY", permission.message());
            return new GateResult(Decision.DENY, permission.message(), envelope.actionDigest(), permission);
        }
        if (permission.isAllowed()) {
            audit(envelope, "ALLOW", permission.reason());
            return new GateResult(Decision.ALLOW, permission.message(), envelope.actionDigest(), permission);
        }

        // 深层子 Agent 对破坏性工具直接拒绝，不把社会工程风险转嫁给审批者。
        int depth = DelegationGuard.currentDepth();
        Set<String> chain = DelegationGuard.currentChain();
        EscalationPolicy.Verdict escalation = EscalationPolicy.evaluate(depth, tool.name());
        if (escalation == EscalationPolicy.Verdict.DENY_DEPTH) {
            PermissionDecision denied = PermissionDecision.deny(
                    "Escalation denied: destructive tool '" + tool.name()
                            + "' at spawn depth " + depth,
                    "escalation_depth", List.of());
            checker.recordDenial(tool.name(), input, denied, context);
            audit(envelope, "DENY", denied.message());
            return new GateResult(Decision.DENY, denied.message(), envelope.actionDigest(), denied);
        }

        audit(envelope, "REQUIRE_APPROVAL", permission.message());
        ToolPermissionChannel.ApprovalResponse response = ToolPermissionChannel.requestApproval(
                envelope.agentId(), tool.name(), envelope.target(), permission.message(),
                depth, List.copyOf(chain), depth > 0 || !chain.isEmpty(),
                envelope.actionDigest(), envelope.workflowId(), envelope.traceId());

        // 审批返回后，执行者必须重新检查摘要；这也是恢复/重试路径的边界。
        if (!validateDigest(envelope, envelope.parametersJson())) {
            audit(envelope, "DENY", "action_digest_changed_after_approval");
            return new GateResult(Decision.DENY, "Action parameters changed after approval",
                    envelope.actionDigest(), permission);
        }
        if (response == ToolPermissionChannel.ApprovalResponse.ALWAYS_TARGET) {
            checker.grantTargetApproval(tool.name(), envelope.target());
        }
        if (response == ToolPermissionChannel.ApprovalResponse.DENY) {
            audit(envelope, "DENY", "human_denied");
            return new GateResult(Decision.DENY, "Human denied action", envelope.actionDigest(), permission);
        }
        audit(envelope, "ALLOW", "human_approved");
        return new GateResult(Decision.ALLOW, "Human approved action", envelope.actionDigest(), permission);
    }

    /** 执行前的参数完整性校验。 */
    public boolean validateDigest(ActionEnvelope envelope, String currentParametersJson) {
        return envelope != null && envelope.actionDigest().equals(envelope.recomputeDigest(currentParametersJson));
    }

    /** 记录工具真实执行结果；不影响工具返回值。 */
    public void recordExecution(ActionEnvelope envelope, ToolOutput output, long durationMs) {
        if (envelope == null) return;
        String status = output != null && output.success() ? "EXECUTED" : "FAILED";
        audit(envelope, status, "durationMs=" + durationMs);
    }

    private GateResult deny(ActionEnvelope envelope, String reason) {
        if (envelope != null) audit(envelope, "DENY", reason);
        return new GateResult(Decision.DENY, reason,
                envelope == null ? null : envelope.actionDigest(), null);
    }

    private void audit(ActionEnvelope envelope, String decision, String reason) {
        if (envelope == null) return;
        String target = envelope.toolName() + "@" + envelope.target();
        String details = "decision=" + decision
                + ";digest=" + envelope.actionDigest()
                + ";reason=" + nullToEmpty(reason);
        UnifiedAuditLog.AuditContext context = new UnifiedAuditLog.AuditContext(
                envelope.tenantId(), envelope.workflowId(), envelope.runId(),
                envelope.traceId() == null ? TraceContext.getCurrentTraceId() : envelope.traceId(),
                envelope.agentId(), envelope.parentAgentId(), envelope.delegationId(),
                envelope.nodeId(), envelope.attempt());
        UnifiedAuditLog.append(new UnifiedAuditLog.TimelineEvent(
                UnifiedAuditLog.LAYER_PERMISSION, "ACTION_GATE", "ACTION_GATE_" + decision,
                envelope.agentId(), target, details, context));
        log.debug("[ActionGate] {} {} digest={}", decision, target, envelope.actionDigest());
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }
}
