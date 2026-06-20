package com.ouisani.aios.core.recovery;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.user.apps.omnifactory.AutoMedicAgent;
import com.ouisani.aios.user.apps.omnifactory.WorkflowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 代码手术策略 — 对应 AutoMedic 的 CODE_ERROR 诊断结果。
 * <p>
 * 当 AutoMedic 诊断节点崩溃原因为代码错误时，此策略：
 * 1. 读取 Core Dump 中的异常信息
 * 2. 调用 AutoMedic.emergencySurgery() 进行 LLM 手术
 * 3. 将修复后的代码写入 VFS
 * 4. 通过 WorkflowEngine.resumeNode() 热重启节点
 * <p>
 * OS 类比：Linux 的 kexec — 在崩溃的内核上直接引导新内核，
 * 而不需要经历完整的 BIOS 重启过程。
 */
public class CodeSurgeryStrategy implements RecoveryStrategy {

    private static final Logger log = LoggerFactory.getLogger(CodeSurgeryStrategy.class);

    @Override public String name() { return "code_surgery"; }

    @Override
    public boolean shouldApply(RecoveryContext context) {
        // 适用于：工具错误、编辑错误、解析错误（这些通常是代码 Bug）
        RecoveryOrchestrator.ErrorCategory cat = context.category();
        return cat == RecoveryOrchestrator.ErrorCategory.TOOL_ERROR
                || cat == RecoveryOrchestrator.ErrorCategory.EDIT_ERROR
                || cat == RecoveryOrchestrator.ErrorCategory.PARSE_ERROR
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

        log.info("[CodeSurgery] 开始代码手术: nodeId={}, dumpPath={}", nodeId, dumpPath);

        try {
            // 1. 调用 AutoMedic 进行诊断和修复
            // 注意：这里不传 WorkflowNode 和 WorkflowContext，因为策略层不应该依赖 DAG 内部对象
            // 改用轻量级方式：只传 dump 路径让 Medic 读取并修复
            String dumpContent = java.nio.file.Files.readString(java.nio.file.Path.of(dumpPath));

            // 2. 尝试从 VFS 读取节点的脚本
            String vfsScriptPath = "/factory/" + nodeId + ".py";
            String originalCode = null;
            try {
                originalCode = VfsManager.instance().readText(vfsScriptPath);
            } catch (Exception ignored) {}

            if (originalCode == null || originalCode.isBlank()) {
                // 没有代码可修复 → 只注入反思提示
                String hint = "注意：你上次执行时出错（" + context.exception().getMessage() + "）。请仔细检查你的代码逻辑。";
                return RecoveryResult.ok("Reflection hint injected (no code to patch)", hint);
            }

            // 3. LLM 手术 — 使用 AiosSdk
            com.ouisani.aios.user.sdk.AiosSdk sdk = com.ouisani.aios.user.sdk.AiosSdk.getInstance();
            String surgeryPrompt = String.format("""
                你是一个极其严谨的高级后端专家。这个节点代码在沙箱中抛出了异常。

                源代码：
                ---
                %s
                ---

                异常信息：
                ---
                %s
                ---

                请找出 Bug 并重写代码。注意：
                1. 确保所有第三方库都已经正确 import
                2. 不要使用沙箱中不存在的库
                3. 严格只输出纯净的代码文本，绝不能包含任何 Markdown 标记！
                """, originalCode, context.exception().getMessage());

            String fixedCode = sdk.think("AutoMedic-001", surgeryPrompt);
            if (fixedCode == null || fixedCode.isBlank()) {
                return RecoveryResult.failed("LLM surgery returned empty result");
            }

            // 基因提纯 — 剥离 Markdown 包装壳
            fixedCode = fixedCode.replaceAll("^```(python|java|bash)?\\s*", "").replaceAll("```$", "").trim();

            // 4. 写入 VFS
            VfsManager.instance().writeText(vfsScriptPath, fixedCode);
            log.info("[CodeSurgery] 修复代码已写入 VFS: {}", vfsScriptPath);

            // 5. 构造 MedicalReport 并调用 resumeNode
            AutoMedicAgent.MedicalReport report = new AutoMedicAgent.MedicalReport(
                    AutoMedicAgent.Outcome.HEALED,
                    "Code surgery completed",
                    fixedCode, vfsScriptPath,
                    "AutoMedic 已修复你的代码，请确保修复后的逻辑正确。",
                    null
            );

            boolean resumed = WorkflowEngine.instance().resumeNode(nodeId, report, workflowId);
            if (resumed) {
                return RecoveryResult.ok("Code surgery successful, node restarted");
            } else {
                return RecoveryResult.failed("Node restart failed after code surgery");
            }

        } catch (Exception e) {
            log.error("[CodeSurgery] 代码手术失败: {}", e.getMessage());
            return RecoveryResult.failed("Code surgery failed: " + e.getMessage());
        }
    }
}
