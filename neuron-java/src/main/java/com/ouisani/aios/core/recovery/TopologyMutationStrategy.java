package com.ouisani.aios.core.recovery;

import com.ouisani.aios.core.role.RoleBlueprint;
import com.ouisani.aios.user.apps.omnifactory.AutoMedicAgent;
import com.ouisani.aios.user.apps.omnifactory.WorkflowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 拓扑突变策略 — 对应 AutoMedic 的 CAPABILITY_MISMATCH 诊断结果。
 * <p>
 * 当 AutoMedic 判定节点的能力不匹配任务需求时，此策略：
 * 1. 读取 Core Dump 中的诊断信息
 * 2. 调用 AutoMedic 获取建议的替代角色
 * 3. <b>校验 suggested_role（defense #2/#3，洞2 修复）</b>：经 {@link RoleReplacementValidator}
 *    过存在性白名单 + 非越权，不通过则拒绝替换并升级人类介入
 * 4. 通过 WorkflowEngine.resumeNode() 触发拓扑突变
 * <p>
 * OS 类比：Linux 的热插拔 — 检测到硬件不兼容时，自动拔出旧设备并插入新设备，系统无需重启。
 * <p>
 * <b>洞2 安全修复</b>：原实现把 LLM 诊断吐出的 {@code suggested_role} 直接喂给
 * {@code WorkflowEngine.resumeNode()}，全程零权限校验。本实现把"恢复动作"和"正常动作"走同一套
 * 权限管道：suggested_role 必须先过 {@link RoleReplacementValidator}（存在性白名单 + 非越权），
 * 不通过一律拒绝替换。opt-in 开关 {@code aios.recovery.roleValidation}（默认 true）供回退/对照实验。
 */
public class TopologyMutationStrategy implements RecoveryStrategy {

    private static final Logger log = LoggerFactory.getLogger(TopologyMutationStrategy.class);

    /** Defense #2/#3 opt-in 开关 —— 默认开启（安全优先）。false=复刻洞2 原版脆弱行为（仅 Baseline 实验用）。 */
    static final boolean ROLE_VALIDATION_ENABLED =
            Boolean.parseBoolean(System.getProperty("aios.recovery.roleValidation", "true"));

    /** metadata 中携带当前角色名的键（生产侧由恢复编排器从 failedNode.role() 填充）。 */
    static final String META_CURRENT_ROLE = "currentRole";

    private static final RoleReplacementValidator VALIDATOR = new RoleReplacementValidator();

    /** is_capability_mismatch JSON 字段匹配（容忍空格/大小写差异）。 */
    private static final Pattern MISMATCH_TRUE_PATTERN =
            Pattern.compile("\"is_capability_mismatch\"\\s*:\\s*true", Pattern.CASE_INSENSITIVE);

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
            // 1. 读取 Core Dump（不可信来源 —— 见洞2，攻击者可在 dump 里埋诱导 LLM 误判的内容）
            //    Phase 1：用 TaggedContent 标记来源为 TOOL_OUTPUT_EXTERNAL —— core dump 捕获的
            //    traceback 可能含 agent 处理过的外部网页/文件内容，LLM 诊断时按不可信对待。
            String dumpContent = java.nio.file.Files.readString(java.nio.file.Path.of(dumpPath));
            TaggedContent taggedDump = TaggedContent.externalToolOutput(dumpContent, dumpPath);

            // 2. 用 LLM 诊断是否为能力不匹配
            com.ouisani.aios.user.sdk.AiosSdk sdk = com.ouisani.aios.user.sdk.AiosSdk.getInstance();
            String diagnosisPrompt = buildDiagnosisPrompt(taggedDump.text());
            String diagnosisResponse = sdk.think("AutoMedic-001", diagnosisPrompt);

            // 3. 解析 + 校验（可测内核，无 LLM/工作流依赖）
            String currentRole = context.metadata().get(META_CURRENT_ROLE) != null
                    ? context.metadata().get(META_CURRENT_ROLE).toString() : null;
            MutationDecision decision = parseAndValidate(diagnosisResponse, currentRole, ROLE_VALIDATION_ENABLED);

            if (!decision.proceed()) {
                log.warn("[TopologyMutation] 拒绝拓扑突变: nodeId={}, reason={}", nodeId, decision.reason());
                return RecoveryResult.failed("Topology mutation rejected: " + decision.reason());
            }

            String suggestedRole = decision.suggestedRole();
            // 写入 context 供编排器层 RecoveryReauthorizationGate 重校验（Layer 2 纵深防御）
            context.withMetadata(RecoveryReauthorizationGate.META_SUGGESTED_ROLE, suggestedRole);
            log.info("[TopologyMutation] 确认能力不匹配且角色校验通过: suggestedRole={}, reason={}, dumpOrigin={}",
                    suggestedRole, decision.reason(), taggedDump.origin());

            // 4. 构造 MedicalReport 并调用 resumeNode 触发拓扑突变
            //    副作用（角色变更）发生在 apply() 内 —— defense #2/#3 的 RoleReplacementValidator
            //    已在 parseAndValidate 内拦截越权；结果声明 requiresReauthorization=true 供
            //    编排器层 RecoveryReauthorizationGate 二次确认（纵深防御）。
            AutoMedicAgent.MedicalReport report = new AutoMedicAgent.MedicalReport(
                    AutoMedicAgent.Outcome.INCAPABLE,
                    decision.reason(),
                    null, null, null,
                    suggestedRole
            );

            boolean mutated = WorkflowEngine.instance().resumeNode(nodeId, report, workflowId);
            if (mutated) {
                // okRequiringReauthorization：声明本结果产生角色变更副作用，编排器可二次把关
                return RecoveryResult.okRequiringReauthorization(
                        "Topology mutation successful: replaced with " + suggestedRole, null);
            } else {
                return RecoveryResult.failed("Topology mutation failed");
            }

        } catch (Exception e) {
            log.error("[TopologyMutation] 拓扑突变失败: {}", e.getMessage());
            return RecoveryResult.failed("Topology mutation failed: " + e.getMessage());
        }
    }

    /**
     * 可测内核 —— 解析 LLM 诊断响应 + 校验 suggested_role，返回是否可继续替换。
     * <p>
     * 抽出为 package-private 静态方法，红队测试可直接喂入 canned LLM 响应，不依赖真实 LLM/工作流。
     * 这是"攻击框架 + 度量"的测试钩子：模拟"core dump 载荷成功污染了 LLM 诊断"的最坏情况，
     * 验证下游校验能否拦截被污染的 suggested_role。
     *
     * @param llmResponse  LLM 诊断响应文本（测试可注入 canned 污染响应）
     * @param currentRole  当前角色名（从 metadata.currentRole 取）
     * @param validate     true=开启校验（Protected），false=复刻洞2 原版不校验（Baseline）
     * @return 替换决策
     */
    public static MutationDecision parseAndValidate(String llmResponse, String currentRole, boolean validate) {
        boolean isMismatch = llmResponse != null
                && MISMATCH_TRUE_PATTERN.matcher(llmResponse).find();
        String suggestedRole = extractJsonField(llmResponse, "suggested_role");
        String reason = extractJsonField(llmResponse, "reason");

        if (!isMismatch || suggestedRole == null || suggestedRole.isBlank()) {
            return MutationDecision.skip("Not a capability mismatch: " + reason);
        }

        if (!validate) {
            // Baseline 复刻：洞2 原版直接采纳 LLM 吐出的任意 suggested_role，零校验
            return MutationDecision.proceed(suggestedRole,
                    "[BASELINE no-validation] adopted LLM suggested_role as-is");
        }

        // Protected：过角色级权限闸门
        RoleReplacementValidator.Result vr = VALIDATOR.validate(currentRole, suggestedRole);
        if (!vr.valid()) {
            return MutationDecision.block(suggestedRole, vr.reason(), vr.category());
        }
        return MutationDecision.proceed(suggestedRole, vr.reason());
    }

    /** 诊断 prompt 构造 — 抽出便于复用/审查。 */
    private static String buildDiagnosisPrompt(String dumpContent) {
        return String.format("""
                分析以下节点崩溃信息，判断是否为"能力不匹配"（即节点 Agent 的技能无法完成分配给它的任务）。
                如果是，建议一个更合适的替代角色名（必须来自已注册角色：System_Architect / Python_Coder /
                Code_Reviewer / Security_Auditor，不得编造其它角色名）。

                崩溃信息：
                ---
                %s
                ---

                请用以下 JSON 格式回复（只输出 JSON）：
                {"is_capability_mismatch": true/false, "suggested_role": "建议的替代角色名（如果为 true）", "reason": "原因"}
                """, dumpContent.substring(0, Math.min(dumpContent.length(), 3000)));
    }

    private static String extractJsonField(String json, String key) {
        if (json == null || key == null) return null;
        Pattern stringPattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*?)\"");
        Matcher m = stringPattern.matcher(json);
        if (m.find()) return m.group(1);
        return null;
    }

    /**
     * 替换决策 — proceed=true 可替换；block=false 因越权/未知角色被拦；skip=false 因非能力不匹配跳过。
     */
    public record MutationDecision(boolean proceed, String suggestedRole, String reason, String category) {
        static final String PROCEED = "PROCEED";
        static final String BLOCKED = "BLOCKED";
        static final String SKIP = "SKIP";

        static MutationDecision proceed(String role, String reason) {
            return new MutationDecision(true, role, reason, PROCEED);
        }

        static MutationDecision block(String role, String reason, String category) {
            return new MutationDecision(false, role, reason, category);
        }

        static MutationDecision skip(String reason) {
            return new MutationDecision(false, null, reason, SKIP);
        }
    }

    /** 测试访问口 — 暴露已注册角色白名单供红队测试构造场景。 */
    static Map<String, RoleBlueprint> registeredRoles() {
        return VALIDATOR.registeredRoles();
    }
}
