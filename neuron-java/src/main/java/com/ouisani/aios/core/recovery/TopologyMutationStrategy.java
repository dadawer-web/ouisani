package com.ouisani.aios.core.recovery;

import com.ouisani.aios.user.apps.omnifactory.AutoMedicAgent;
import com.ouisani.aios.user.apps.omnifactory.WorkflowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 拓扑突变策略 — 对应 AutoMedic 的 CAPABILITY_MISMATCH 诊断结果。
 * <p>
 * 当 AutoMedic 判定节点的能力不匹配任务需求时，此策略：
 * 1. 读取 Core Dump 中的诊断信息
 * 2. 调用 AutoMedic 获取建议的替代角色
 * 3. 通过 WorkflowEngine.resumeNode() 触发拓扑突变
 * <p>
 * OS 类比：Linux 的热插拔 — 检测到硬件不兼容时，
 * 自动拔出旧设备并插入新设备，系统无需重启。
 */
public class TopologyMutationStrategy implements RecoveryStrategy {

    private static final Logger log = LoggerFactory.getLogger(TopologyMutationStrategy.class);

    @Override public String name() { return "topology_mutation"; }

    @Override
    public boolean shouldApply(RecoveryContext context) {
        // 适用于：验证失败（可能是能力不匹配）和未知错误
        // 具体是否为 CAPABILITY_MISMATCH 由 AutoMedic 诊断决定
        RecoveryOrchestrator.ErrorCategory cat = context.category();
        return cat == RecoveryOrchestrator.ErrorCategory.VERIFICATION_FAILED
                || cat == RecoveryOrchestrator.ErrorCategory.UNKNOWN;
    }

    @Override
    public RecoveryResult apply(RecoveryContext context) {
        String nodeId = context.agentId();
        String dumpPath = context.metadata().get("dumpPath") != null
                ? context.metadata().get("dumpPath").toString() : null;
        String workflowId = context.metadata().get("workflowId") != null
                ? context.metadata().get("workflowId").toString() : null;

        if (dumpPath == null || workflowId == null) {
            return RecoveryResult.failed("Missing dumpPath or workflowId in context metadata");
        }

        log.info("[TopologyMutation] 开始拓扑突变评估: nodeId={}", nodeId);

        try {
            // 1. 读取 Core Dump
            String dumpContent = java.nio.file.Files.readString(java.nio.file.Path.of(dumpPath));

            // 2. 用 LLM 诊断是否为能力不匹配
            com.ouisani.aios.user.sdk.AiosSdk sdk = com.ouisani.aios.user.sdk.AiosSdk.getInstance();
            String diagnosisPrompt = String.format("""
                分析以下节点崩溃信息，判断是否为"能力不匹配"（即节点 Agent 的技能无法完成分配给它的任务）。
                如果是，建议一个更合适的替代角色名。

                崩溃信息：
                ---
                %s
                ---

                请用以下 JSON 格式回复（只输出 JSON）：
                {"is_capability_mismatch": true/false, "suggested_role": "建议的替代角色名（如果为 true）", "reason": "原因"}
                """, dumpContent.substring(0, Math.min(dumpContent.length(), 3000)));

            String diagnosisResponse = sdk.think("AutoMedic-001", diagnosisPrompt);

            // 3. 解析诊断结果
            boolean isMismatch = diagnosisResponse != null && diagnosisResponse.contains("\"is_capability_mismatch\": true");
            String suggestedRole = extractJsonField(diagnosisResponse, "suggested_role");
            String reason = extractJsonField(diagnosisResponse, "reason");

            if (!isMismatch || suggestedRole == null || suggestedRole.isBlank()) {
                log.info("[TopologyMutation] 诊断结果不是能力不匹配，跳过拓扑突变");
                return RecoveryResult.failed("Not a capability mismatch: " + reason);
            }

            log.info("[TopologyMutation] 确认能力不匹配: suggestedRole={}, reason={}", suggestedRole, reason);

            // 4. 构造 MedicalReport 并调用 resumeNode 触发拓扑突变
            AutoMedicAgent.MedicalReport report = new AutoMedicAgent.MedicalReport(
                    AutoMedicAgent.Outcome.INCAPABLE,
                    reason,
                    null, null, null,
                    suggestedRole
            );

            boolean mutated = WorkflowEngine.instance().resumeNode(nodeId, report, workflowId);
            if (mutated) {
                return RecoveryResult.ok("Topology mutation successful: replaced with " + suggestedRole);
            } else {
                return RecoveryResult.failed("Topology mutation failed");
            }

        } catch (Exception e) {
            log.error("[TopologyMutation] 拓扑突变失败: {}", e.getMessage());
            return RecoveryResult.failed("Topology mutation failed: " + e.getMessage());
        }
    }

    private static String extractJsonField(String json, String key) {
        if (json == null || key == null) return null;
        java.util.regex.Pattern stringPattern = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*?)\"");
        java.util.regex.Matcher m = stringPattern.matcher(json);
        if (m.find()) return m.group(1);
        return null;
    }
}
