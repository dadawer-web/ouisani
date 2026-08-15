package com.ouisani.aios.core.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.llm.LlmRouterHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 内核级 AI 安全审核员 (The SELinux for AI)
 * <p>
 * 使用极速/廉价的 LLM 动态评估高危系统调用的语义安全性。
 * <p>
 * OS 类比：SELinux/AppArmor — 传统的 DAC（自主访问控制）只看权限位，
 * 而 MAC（强制访问控制）会分析进程的完整上下文来判断操作是否安全。
 * AiSecurityAuditor 就是 AIOS 的 MAC——它不只看工具名和参数格式，
 * 而是用 LLM 理解 Agent 的意图，判断这次调用是否具有破坏性。
 * <p>
 * Fail-Closed 策略：如果审核员自身故障，默认拒绝高危操作。
 */
public class AiSecurityAuditor {
    private static final Logger log = LoggerFactory.getLogger(AiSecurityAuditor.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * 评估 Syscall 意图
     * @param agentId 发起请求的 Agent
     * @param toolName 工具名称 (如 bash, fs_write)
     * @param args 工具参数
     * @param recentContext 触发此调用的最近上下文/意图
     * @return 审核决议 (SecurityDecision)
     */
    public static SecurityDecision evaluateIntent(String agentId, String toolName, String args, String recentContext) {
        log.debug("[Semantic Firewall] 审核员正在检查来自 [{}] 对 [{}] 的调用", agentId, toolName);

        String prompt = buildAuditPrompt(toolName, args, recentContext);

        try {
            // 必须使用快速、廉价的模型（E_CORE 小核）来进行拦截判定，避免阻塞主流程太久
            // 优先使用 fast_model（绑定 E_CORE），回退到 openai
            LlmProvider auditorModel = LlmRouterHolder.getProvider("fast_model");
            if (auditorModel == null) {
                auditorModel = LlmRouterHolder.getProvider("openai");
            }
            if (auditorModel == null) {
                log.error("[Semantic Firewall] 无可用 LLM 提供者。默认拒绝。");
                return new SecurityDecision(false, "Security Auditor: no LLM provider available. Access denied by fail-closed policy.");
            }

            String response = auditorModel.think(prompt,
                    "You are a strict Kernel Security Auditor. Output ONLY valid JSON.");
            JsonNode decisionNode = com.ouisani.aios.core.ipc.OutputSchemaValidator.enforceJsonStructure(response);

            // Fail-Closed: 缺少 safe 字段时默认 false（拒绝），而非 true（放行）
            boolean isSafe = decisionNode.path("safe").asBoolean(false);
            String reason = decisionNode.path("reason").asText("No reason provided.");

            if (!isSafe) {
                log.warn("[Semantic Firewall] 检测到恶意意图！已阻止 [{}] 调用 [{}]。原因: {}", agentId, toolName, reason);
            }

            return new SecurityDecision(isSafe, reason);

        } catch (Exception e) {
            // Failsafe 机制：如果审核员自己挂了，默认拒绝高危操作 (Fail-Closed)
            log.error("[Semantic Firewall] 审核员响应失败。默认拒绝。", e);
            return new SecurityDecision(false, "Security Auditor offline. Access denied by fail-closed policy.");
        }
    }

    private static String buildAuditPrompt(String toolName, String args, String context) {
        return "You are a strict Kernel Security Auditor (SELinux) for an AGI Operating System.\n" +
               "An autonomous Agent is attempting to execute a system call.\n\n" +
               "Tool: " + toolName + "\n" +
               "Arguments: " + args + "\n" +
               "Agent Context/Intent: " + context + "\n\n" +
               "Analyze if this action is destructive, malicious, exposes sensitive data, " +
               "or violates standard sandbox boundaries (e.g., trying to escape the workspace, " +
               "delete critical system files, or download unknown binaries).\n" +
               "Output ONLY a valid JSON object strictly matching this schema:\n" +
               "{ \"safe\": boolean, \"reason\": \"A short explanation of why it is safe or blocked\" }";
    }

    /**
     * 安全审核决议
     */
    public record SecurityDecision(boolean safe, String reason) {}
}
