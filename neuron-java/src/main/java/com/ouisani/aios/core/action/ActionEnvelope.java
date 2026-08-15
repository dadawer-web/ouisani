package com.ouisani.aios.core.action;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 不可变的 Agent 行动信封。
 *
 * <p>Action Gate 只信任这个对象中的身份和参数摘要。审批、审计以及执行前
 * 的二次校验都使用同一个 digest，避免“审批了 A、实际执行 B”的参数漂移。</p>
 */
public record ActionEnvelope(
        String tenantId,
        String workflowId,
        String runId,
        String traceId,
        String agentId,
        String parentAgentId,
        String delegationId,
        String nodeId,
        int attempt,
        String toolName,
        String actionType,
        String target,
        String parametersJson,
        String actionDigest,
        long createdAtMs,
        long expiresAtMs
) {

    public ActionEnvelope {
        toolName = requireNonBlank(toolName, "toolName");
        agentId = requireNonBlank(agentId, "agentId");
        actionType = actionType == null || actionType.isBlank() ? "execute" : actionType;
        target = target == null ? "" : target;
        parametersJson = parametersJson == null ? "{}" : parametersJson;
        if (createdAtMs <= 0) createdAtMs = System.currentTimeMillis();
        if (attempt < 0) attempt = 0;
        if (actionDigest == null || actionDigest.isBlank()) {
            actionDigest = digestOf(tenantId, workflowId, runId, traceId, agentId,
                    parentAgentId, delegationId, nodeId, attempt, toolName,
                    actionType, target, parametersJson);
        }
    }

    /** 为工具调用构造标准信封。 */
    public static ActionEnvelope forTool(String tenantId, String workflowId, String runId,
                                          String traceId, String agentId, String parentAgentId,
                                          String delegationId, String nodeId, int attempt,
                                          String toolName, String actionType, String target,
                                          String parametersJson) {
        return new ActionEnvelope(tenantId, workflowId, runId, traceId, agentId,
                parentAgentId, delegationId, nodeId, attempt, toolName, actionType,
                target, parametersJson, null, System.currentTimeMillis(), 0L);
    }

    /** 仅根据执行时观测到的参数重新计算摘要。 */
    public String recomputeDigest(String currentParametersJson) {
        return digestOf(tenantId, workflowId, runId, traceId, agentId,
                parentAgentId, delegationId, nodeId, attempt, toolName,
                actionType, target, currentParametersJson == null ? "{}" : currentParametersJson);
    }

    public boolean isExpired(long nowMs) {
        return expiresAtMs > 0 && nowMs >= expiresAtMs;
    }

    public boolean hasWorkflowIdentity() {
        return workflowId != null && !workflowId.isBlank();
    }

    public boolean isReadOnlyAction() {
        return "read".equalsIgnoreCase(actionType) || "query".equalsIgnoreCase(actionType);
    }

    public static String digestOf(String tenantId, String workflowId, String runId,
                                  String traceId, String agentId, String parentAgentId,
                                  String delegationId, String nodeId, int attempt,
                                  String toolName, String actionType, String target,
                                  String parametersJson) {
        // Length-prefixing makes the digest unambiguous even when fields contain separators.
        String canonical = field(tenantId) + field(workflowId) + field(runId) + field(traceId)
                + field(agentId) + field(parentAgentId) + field(delegationId) + field(nodeId)
                + field(Integer.toString(attempt)) + field(toolName) + field(actionType)
                + field(target) + field(parametersJson == null ? "{}" : parametersJson);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String field(String value) {
        String s = value == null ? "" : value;
        return s.length() + ":" + s;
    }

    private static String requireNonBlank(String value, String name) {
        String result = Objects.requireNonNull(value, name + " must not be null");
        if (result.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return result;
    }
}
