package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.user.sdk.AbstractAgent;
import com.ouisani.aios.user.sdk.AiosSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自愈热修复智能体 (AutoMedic Agent) — 监听系统崩溃事件并自动修复。
 * <p>
 * OS 类比：相当于 Linux 的 kdump + kexec 热修复 — 当内核检测到子进程崩溃时，
 * AutoMedic 拦截崩溃广播，读取"病历"（原始代码），通过 LLM 进行"手术"（Bug 修复），
 * 然后将修复后的代码热写入 VFS 并发送唤醒信号。
 * <p>
 * 修复流程：
 * <pre>
 *   EventBus: "sys.kernel.panic" 广播
 *     │
 *     ▼  handleCrash(crashJson)
 *   解析 JSON → 提取 failed_node_id / vfs_script_path / error_stacktrace
 *     │
 *     ├─ 读取病历: sdk.readFile(vfs_script_path) → 原始代码
 *     ├─ LLM 手术: sdk.think(代码+异常 → 修复代码)
 *     ├─ 去除 Markdown 标记 + 注入新基因: sdk.writeFile(vfs_script_path, fixedCode)
 *     └─ 电击复活: WorkflowEngine.restartNode() / 日志
 * </pre>
 * <p>
 * V2 增强：支持同步紧急手术（emergencySurgery），直接被 WorkflowEngine 调用。
 * 三种诊断结果：
 * <ul>
 *   <li>HEALED — 修复成功，可以热重启</li>
 *   <li>INCAPABLE — 节点无能，需要拓扑突变</li>
 *   <li>FAILED — 修复失败，节点标记 FAILED</li>
 * </ul>
 */
public class AutoMedicAgent extends AbstractAgent {

    private static final Logger log = LoggerFactory.getLogger(AutoMedicAgent.class);

    // ════════════════════════════════════════════════════════════════
    //  Medical Report — 诊断报告（AutoMedic 的手术记录）
    // ════════════════════════════════════════════════════════════════

    /**
     * 诊断结果枚举。
     */
    public enum Outcome {
        /** 修复成功，可以热重启 */
        HEALED,
        /** 节点无能，需要拓扑突变（插入替代节点） */
        INCAPABLE,
        /** 修复失败，节点标记 FAILED */
        FAILED
    }

    /**
     * 医疗报告 — AutoMedic 的手术记录。
     *
     * @param outcome       诊断结果
     * @param diagnosis     诊断描述
     * @param patchedCode   修复后的代码（HEALED 时非空）
     * @param patchedVfsPath 修复代码的 VFS 路径
     * @param reflectionHint 反思提示（注入到节点的上下文中）
     * @param suggestedRole  建议的替代角色（INCAPABLE 时非空）
     */
    public record MedicalReport(
            Outcome outcome,
            String diagnosis,
            String patchedCode,
            String patchedVfsPath,
            String reflectionHint,
            String suggestedRole
    ) {}

    public AutoMedicAgent() {
        super("AutoMedic-001", ProcessPriority.HIGH, 100000);
    }

    @Override
    protected void onStart() {
        System.out.println("[AutoMedic] Self-Healing Daemon activated. Monitoring EventBus for sys.kernel.panic...");
        log.info("[AutoMedic] Self-Healing Daemon activated.");

        // 订阅内核崩溃广播
        EventBus.instance().subscribe("sys.kernel.panic", this::handleCrash);
        System.out.println("[AutoMedic] Subscribed to 'sys.kernel.panic' event channel.");
        log.info("[AutoMedic] Subscribed to sys.kernel.panic event channel.");
    }

    // ════════════════════════════════════════════════════════════════
    //  紧急手术 — 同步模式，被 WorkflowEngine 直接调用
    // ════════════════════════════════════════════════════════════════

    /**
     * 紧急手术 — WorkflowEngine 节点崩溃时的同步修复入口。
     * <p>
     * 流程：
     * 1. 读取 Core Dump 文件
     * 2. 诊断崩溃原因（代码错误 vs 节点无能）
     * 3. 如果是代码错误 → LLM 手术 → 返回 HEALED
     * 4. 如果是节点无能 → 建议替代角色 → 返回 INCAPABLE
     * 5. 如果修复失败 → 返回 FAILED
     *
     * @param node      崩溃的节点
     * @param error     崩溃异常
     * @param dumpPath  Core Dump 文件路径
     * @param context   工作流上下文
     * @param workflowId 工作流 ID
     * @return MedicalReport 诊断报告
     */
    public static MedicalReport emergencySurgery(WorkflowNode node, Exception error,
                                                  String dumpPath, WorkflowContext context,
                                                  String workflowId) {
        log.info("[AutoMedic] 紧急手术启动: node={}, error={}", node.instanceId(), error.getMessage());

        AiosSdk sdk = AiosSdk.getInstance();

        // ── Step 1: 读取 Core Dump ──
        String dumpContent = null;
        try {
            dumpContent = java.nio.file.Files.readString(java.nio.file.Path.of(dumpPath));
        } catch (Exception e) {
            // 如果 dump 文件不可读，用异常信息代替
            dumpContent = "Exception: " + error.getClass().getName() + ": " + error.getMessage();
        }

        // ── Step 2: 诊断崩溃原因 ──
        String diagnosisPrompt = """
            你是一个系统诊断专家。分析以下节点崩溃的 Core Dump，判断崩溃原因属于哪一类：

            1. CODE_ERROR — 代码有 Bug（语法错误、缺少依赖、逻辑错误等），可以通过修复代码解决
            2. CAPABILITY_MISMATCH — 节点的能力不匹配任务需求（比如让 Python Agent 编译 C++），需要替换为更合适的角色
            3. RESOURCE_EXHAUSTED — 资源耗尽（Token OOM、超时等），需要调整参数重试

            请用以下 JSON 格式回复（只输出 JSON，不要其他内容）：
            {"category": "CODE_ERROR|CAPABILITY_MISMATCH|RESOURCE_EXHAUSTED", "reason": "具体原因", "suggested_role": "如果是 CAPABILITY_MISMATCH，建议的替代角色名"}

            Core Dump:
            ---
            %s
            ---
            """.formatted(dumpContent.substring(0, Math.min(dumpContent.length(), 4000)));

        String diagnosisResponse;
        try {
            diagnosisResponse = sdk.think("AutoMedic-001", diagnosisPrompt);
        } catch (Exception e) {
            log.error("[AutoMedic] 诊断 LLM 调用失败: {}", e.getMessage());
            return new MedicalReport(Outcome.FAILED, "Diagnosis LLM call failed: " + e.getMessage(),
                    null, null, null, null);
        }

        // 解析诊断结果
        String category = extractJsonField(diagnosisResponse, "category");
        String reason = extractJsonField(diagnosisResponse, "reason");
        String suggestedRole = extractJsonField(diagnosisResponse, "suggested_role");

        log.info("[AutoMedic] 诊断结果: category={}, reason={}", category, reason);

        // ── Step 3: 根据诊断结果采取行动 ──

        if ("CAPABILITY_MISMATCH".equals(category)) {
            // 节点无能 → 建议拓扑突变
            log.warn("[AutoMedic] 节点 '{}' 能力不匹配: {}。建议替代角色: {}",
                    node.instanceId(), reason, suggestedRole);
            return new MedicalReport(Outcome.INCAPABLE, reason, null, null, null,
                    suggestedRole != null ? suggestedRole : "General_Coder");
        }

        if ("RESOURCE_EXHAUSTED".equals(category)) {
            // 资源耗尽 → 注入反思提示，建议减少输出
            String hint = "注意：你上次执行时资源耗尽（" + reason + "）。请精简输出，减少不必要的冗余内容。";
            log.info("[AutoMedic] 资源耗尽，注入反思提示");
            return new MedicalReport(Outcome.HEALED, reason, null, null, hint, null);
        }

        // CODE_ERROR → LLM 手术修复代码
        return performCodeSurgery(node, error, dumpContent, reason, sdk);
    }

    /**
     * 代码手术 — LLM 修复代码 Bug。
     */
    private static MedicalReport performCodeSurgery(WorkflowNode node, Exception error,
                                                     String dumpContent, String diagnosis,
                                                     AiosSdk sdk) {
        // 尝试从 VFS 读取节点的脚本文件
        String vfsScriptPath = "/factory/" + node.instanceId() + ".py";
        String originalCode = null;
        try {
            originalCode = VfsManager.instance().readText(vfsScriptPath);
        } catch (Exception e) {
            // 尝试其他路径
            String[] altPaths = {
                    "/factory/" + node.role() + ".py",
                    "/factory/run_all.sh",
                    "/factory/main.py"
            };
            for (String altPath : altPaths) {
                try {
                    originalCode = VfsManager.instance().readText(altPath);
                    if (originalCode != null && !originalCode.isBlank()) {
                        vfsScriptPath = altPath;
                        break;
                    }
                } catch (Exception ignored) {}
            }
        }

        if (originalCode == null || originalCode.isBlank()) {
            // 没有代码可修复 → 注入反思提示
            String hint = "注意：你上次执行时出错（" + error.getMessage() + "）。诊断：" + diagnosis
                    + "。请仔细检查你的代码逻辑，确保所有依赖都已正确导入，所有文件路径都存在。";
            log.info("[AutoMedic] 无代码可修复，注入反思提示");
            return new MedicalReport(Outcome.HEALED, diagnosis, null, null, hint, null);
        }

        // LLM 手术
        String surgeryPrompt = String.format("""
            你是一个极其严谨的高级后端专家。这个节点代码在沙箱中抛出了异常。

            诊断结果：%s

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
            3. 确保所有引用的文件路径存在
            4. 严格只输出纯净的代码文本，绝不能包含任何 Markdown 标记 (如 ```python) 和任何解释性文字！
            """, diagnosis, originalCode, error.getMessage());

        String fixedCode;
        try {
            fixedCode = sdk.think("AutoMedic-001", surgeryPrompt);
        } catch (Exception e) {
            log.error("[AutoMedic] LLM 手术失败: {}", e.getMessage());
            return new MedicalReport(Outcome.FAILED, "LLM surgery failed: " + e.getMessage(),
                    null, null, null, null);
        }

        if (fixedCode == null || fixedCode.isBlank()) {
            return new MedicalReport(Outcome.FAILED, "LLM returned empty fix",
                    null, null, null, null);
        }

        // 基因提纯 — 剥离 Markdown 包装壳
        fixedCode = fixedCode.replaceAll("^```(python|java|bash)?\\s*", "").replaceAll("```$", "").trim();

        // 构建反思提示
        String hint = "注意：AutoMedic 已修复你的代码。诊断：" + diagnosis
                + "。请确保修复后的代码逻辑正确。";

        log.info("[AutoMedic] 代码手术成功: node={}, originalLen={}, fixedLen={}",
                node.instanceId(), originalCode.length(), fixedCode.length());

        return new MedicalReport(Outcome.HEALED, diagnosis, fixedCode, vfsScriptPath, hint, null);
    }

    // ════════════════════════════════════════════════════════════════
    //  EventBus 异步模式 — 监听 sys.kernel.panic 广播
    // ════════════════════════════════════════════════════════════════

    /**
     * 核心修复逻辑 — 拦截崩溃事件，诊断并热修复（EventBus 异步模式）。
     */
    private void handleCrash(String crashJson) {
        String failedNodeId = extractJsonField(crashJson, "failed_node_id");
        String vfsScriptPath = extractJsonField(crashJson, "vfs_script_path");
        String errorStacktrace = extractJsonField(crashJson, "error_stacktrace");
        String retryCountStr = extractJsonField(crashJson, "retry_count");
        int retryCount = 0;
        try {
            if (retryCountStr != null && !retryCountStr.isBlank()) {
                retryCount = Integer.parseInt(retryCountStr);
            }
        } catch (NumberFormatException e) {
            // ignore
        }

        if (failedNodeId == null || failedNodeId.isBlank()) {
            log.error("[AutoMedic] Cannot extract failed_node_id from crash JSON. Aborting.");
            return;
        }

        // 熔断机制
        if (retryCount >= 3) {
            System.err.printf("[AutoMedic] CRITICAL: Node %s failed 3 times. Circuit breaker activated!%n",
                    failedNodeId);
            log.error("[AutoMedic] 节点 '{}' 熔断器已激活: retry_count={}", failedNodeId, retryCount);

            String escapedStacktrace = (errorStacktrace != null ? errorStacktrace : "")
                    .replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t");
            String alertJson = "{\"type\": \"HUMAN_INTERVENTION\", \"nodeId\": \""
                    + failedNodeId.replace("\"", "\\\"")
                    + "\", \"message\": \"AutoMedic gave up. Human intervention required.\", \"dump\": \""
                    + escapedStacktrace + "\"}";

            EventBus.instance().broadcast("sys.human_intervention_required", alertJson);
            return;
        }

        System.out.printf("[AutoMedic] Intercepted core dump from %s (retry=%d).%n",
                failedNodeId, retryCount);
        log.warn("[AutoMedic] 核心转储已拦截: node={}, retry={}", failedNodeId, retryCount);

        if (vfsScriptPath == null || vfsScriptPath.isBlank()) {
            log.error("[AutoMedic] 未提供节点 '{}' 的 vfs_script_path", failedNodeId);
            return;
        }

        // 读取病历
        String originalCode;
        try {
            originalCode = sdk.readFile(this.agentId, vfsScriptPath);
        } catch (Exception e) {
            log.error("[AutoMedic] 从 VFS 读取 '{}' 失败: {}", vfsScriptPath, e.getMessage());
            return;
        }

        if (originalCode == null || originalCode.isBlank()) {
            log.error("[AutoMedic] Script at '{}' is empty.", vfsScriptPath);
            return;
        }

        // LLM 手术
        String debugPrompt = "你是一个极其严谨的高级后端专家。这个节点代码在沙箱中抛出了异常。这是它第 ["
                + retryCount + "] 次尝试修复。\n源代码:\n" + originalCode
                + "\n异常堆栈:\n" + (errorStacktrace != null ? errorStacktrace : "Unknown error")
                + "\n请找出 Bug 并重写代码。注意：1. 确保所有第三方库都已经正确 import；2. 不要使用沙箱中不存在的库；3. 严格只输出纯净的代码文本！";

        String fixedCode;
        try {
            fixedCode = sdk.think(this.agentId, debugPrompt);
        } catch (Exception e) {
            log.error("[AutoMedic] 节点 '{}' LLM 修复失败: {}", failedNodeId, e.getMessage());
            return;
        }

        if (fixedCode == null || fixedCode.isBlank()) {
            log.error("[AutoMedic] LLM 为节点 '{}' 返回空修复", failedNodeId);
            return;
        }

        // 基因提纯
        fixedCode = fixedCode.replaceAll("^```(python|java)?\\s*", "").replaceAll("```$", "").trim();

        // 注入新基因
        try {
            sdk.writeFile(this.agentId, vfsScriptPath, fixedCode);
        } catch (Exception e) {
            log.error("[AutoMedic] VFS write failed for '{}': {}", vfsScriptPath, e.getMessage());
            return;
        }

        // 电击复活
        log.info("[AutoMedic] Hot-patch applied to '{}'. Node '{}' ready for restart.",
                vfsScriptPath, failedNodeId);
        System.out.printf("[AutoMedic] Node '%s' patched and queued for restart.%n", failedNodeId);
    }

    @Override
    protected void onMessage(String msg) {
        log.debug("[AutoMedic] Message received: {}", msg.substring(0, Math.min(msg.length(), 80)));
    }

    // ════════════════════════════════════════════════════════════════
    //  工具方法
    // ════════════════════════════════════════════════════════════════

    private static String extractJsonField(String json, String key) {
        if (json == null || key == null) return null;

        Pattern stringPattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*?)\"");
        Matcher stringMatcher = stringPattern.matcher(json);
        if (stringMatcher.find()) return stringMatcher.group(1);

        Pattern rawPattern = Pattern.compile("\"" + key + "\"\\s*:\\s*([^,}\\s]+)");
        Matcher rawMatcher = rawPattern.matcher(json);
        if (rawMatcher.find()) return rawMatcher.group(1).trim();

        return null;
    }
}
