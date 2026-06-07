package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.user.sdk.AbstractAgent;
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
 */
public class AutoMedicAgent extends AbstractAgent {

    private static final Logger log = LoggerFactory.getLogger(AutoMedicAgent.class);

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

    /**
     * 核心修复逻辑 — 拦截崩溃事件，诊断并热修复。
     * <p>
     * 处理流程：
     * <ol>
     *   <li>解析崩溃 JSON，提取失败节点 ID、脚本路径、异常堆栈</li>
     *   <li>从 VFS 读取原始代码（病历）</li>
     *   <li>调用 LLM 进行 Bug 修复（手术）</li>
     *   <li>去除 Markdown 代码块标记，将修复后的代码覆盖写入 VFS（注入新基因）</li>
     *   <li>发送唤醒信号（电击复活）</li>
     * </ol>
     *
     * @param crashJson 崩溃事件 JSON，包含 failed_node_id / vfs_script_path / error_stacktrace
     */
    private void handleCrash(String crashJson) {
        // ── Step 1: 解析崩溃 JSON ──
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
            // ignore, default to 0
        }

        if (failedNodeId == null || failedNodeId.isBlank()) {
            log.error("[AutoMedic] Cannot extract failed_node_id from crash JSON. Aborting.");
            return;
        }

        // ── Step 1.5: 熔断机制 (Circuit Breaker) ──
        if (retryCount >= 3) {
            System.err.printf("[AutoMedic] CRITICAL: Node %s failed 3 times. Circuit breaker activated. Initiating Human-in-the-Loop escalation!%n",
                    failedNodeId);
            log.error("[AutoMedic] Circuit breaker activated for node '{}': retry_count={}. Human-in-the-Loop required.", failedNodeId, retryCount);

            // 组装结构化告警 JSON
            String escapedStacktrace = (errorStacktrace != null ? errorStacktrace : "")
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
            String escapedNodeId = failedNodeId.replace("\"", "\\\"");
            String alertJson = "{\"type\": \"HUMAN_INTERVENTION\", \"nodeId\": \"" + escapedNodeId
                    + "\", \"message\": \"AutoMedic gave up. Human intervention required.\", \"dump\": \"" + escapedStacktrace + "\"}";

            EventBus.instance().broadcast("sys.human_intervention_required", alertJson);
            System.out.println("[AutoMedic] Rescue signal broadcasted to dashboard.");
            return;
        }

        System.out.printf("[AutoMedic] Intercepted core dump from %s (retry=%d). Initiating diagnostic sequence on %s...%n",
                failedNodeId, retryCount, vfsScriptPath != null ? vfsScriptPath : "N/A");
        log.warn("[AutoMedic] Core dump intercepted: node={}, retry={}, script={}", failedNodeId, retryCount, vfsScriptPath);

        if (vfsScriptPath == null || vfsScriptPath.isBlank()) {
            System.out.println("[AutoMedic]   ⚠ No script path provided. Cannot auto-repair without source code.");
            log.error("[AutoMedic] Missing vfs_script_path for node '{}'. Cannot repair.", failedNodeId);
            return;
        }

        // ── Step 2: 读取病历 — 获取原始代码 ──
        System.out.printf("[AutoMedic]   ├─ Reading patient record from %s...%n", vfsScriptPath);
        String originalCode;
        try {
            originalCode = sdk.readFile(this.agentId, vfsScriptPath);
        } catch (Exception e) {
            System.out.printf("[AutoMedic]   ⚠ Failed to read script from VFS: %s%n", e.getMessage());
            log.error("[AutoMedic] VFS read failed for '{}': {}", vfsScriptPath, e.getMessage());
            return;
        }

        if (originalCode == null || originalCode.isBlank()) {
            System.out.println("[AutoMedic]   ⚠ Script is empty. Nothing to repair.");
            log.error("[AutoMedic] Script at '{}' is empty.", vfsScriptPath);
            return;
        }

        // ── Step 3: 大模型手术 — 强化 Bug 修复 ──
        System.out.printf("[AutoMedic]   ├─ Initiating LLM surgery on %s (attempt %d/3)...%n", failedNodeId, retryCount);
        log.info("[AutoMedic] Starting LLM repair for node '{}', attempt {}/3, script: '{}'", failedNodeId, retryCount, vfsScriptPath);

        String debugPrompt = "你是一个极其严谨的高级后端专家。这个节点代码在沙箱中抛出了异常。这是它第 ["
                + retryCount + "] 次尝试修复。\n源代码:\n" + originalCode
                + "\n异常堆栈:\n" + (errorStacktrace != null ? errorStacktrace : "Unknown error")
                + "\n请找出 Bug 并重写代码。注意：1. 确保所有第三方库都已经正确 import；2. 不要使用沙箱中不存在的库；3. 严格只输出纯净的代码文本，绝不能包含任何 Markdown 标记 (如 ```python) 和任何解释性文字！";

        String fixedCode;
        try {
            fixedCode = sdk.think(this.agentId, debugPrompt);
        } catch (Exception e) {
            System.out.printf("[AutoMedic]   ⚠ LLM surgery failed: %s%n", e.getMessage());
            log.error("[AutoMedic] LLM repair failed for node '{}': {}", failedNodeId, e.getMessage());
            return;
        }

        if (fixedCode == null || fixedCode.isBlank()) {
            System.out.println("[AutoMedic]   ⚠ LLM returned empty fix. Aborting.");
            log.error("[AutoMedic] LLM returned empty fix for node '{}'.", failedNodeId);
            return;
        }

        // ── Step 3.5: 基因提纯 (Code Sanitizer) — 强行剥离 Markdown 包装壳 ──
        fixedCode = fixedCode.replaceAll("^```(python|java)?\\s*", "").replaceAll("```$", "").trim();

        // ── Step 4: 注入新基因 — 覆盖写入提纯后的代码 ──
        System.out.printf("[AutoMedic] Sanitized hot-patch injected for node %s. Rebooting sandbox...%n", failedNodeId);
        try {
            sdk.writeFile(this.agentId, vfsScriptPath, fixedCode);
        } catch (Exception e) {
            System.out.printf("[AutoMedic]   ⚠ Failed to write patched code: %s%n", e.getMessage());
            log.error("[AutoMedic] VFS write failed for '{}': {}", vfsScriptPath, e.getMessage());
            return;
        }

        // ── Step 5: 电击复活 — 唤醒失败节点 ──
        System.out.printf("[AutoMedic]   └─ Hot-patch applied. Sending wake-up signal to orchestrator for node restart...%n");
        log.info("[AutoMedic] Hot-patch applied to '{}'. Node '{}' ready for restart.",
                vfsScriptPath, failedNodeId);

        // 预留 WorkflowEngine.restartNode() 接口
        // WorkflowEngine.getInstance().restartNode(failedNodeId);
        System.out.printf("[AutoMedic] ✦ Node '%s' patched and queued for restart.%n", failedNodeId);
    }

    @Override
    protected void onMessage(String msg) {
        // AutoMedic 也通过消息队列接收崩溃通知（备用通道）
        log.debug("[AutoMedic] Message received: {}", msg.substring(0, Math.min(msg.length(), 80)));
    }

    // ════════════════════════════════════════════════════════════════
    //  Markdown 代码块剥离器 — 去除 LLM 输出中的 ```python ... ```
    // ════════════════════════════════════════════════════════════════

    /**
     * 去除 LLM 输出中可能带有的 Markdown 代码块标记。
     * <p>
     * LLM 经常返回 ```python\n...code...\n``` 格式，
     * 此方法提取其中的纯代码部分。
     *
     * @param text LLM 原始输出
     * @return 去除 Markdown 标记后的纯代码
     */
    private String stripMarkdownCodeBlock(String text) {
        if (text == null) return null;

        // 匹配 ```lang\n...code...\n``` 格式
        Pattern codeBlockPattern = Pattern.compile("```(?:\\w+)?\\s*\\n([\\s\\S]*?)\\n\\s*```");
        Matcher matcher = codeBlockPattern.matcher(text.trim());
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        // 如果没有代码块标记，直接返回原文（去除首尾空白）
        return text.trim();
    }

    // ════════════════════════════════════════════════════════════════
    //  JSON Field Extractor — 正则提取，兼容各种 LLM 输出格式
    // ════════════════════════════════════════════════════════════════

    /**
     * 从 JSON 字符串中提取指定字段的值。
     * <p>
     * 兼容 "key":"value" 和 "key": "value" 格式，
     * 也兼容 "key": value（无引号数字/布尔值）。
     */
    private String extractJsonField(String json, String key) {
        if (json == null || key == null) return null;

        // 先尝试 "key":"value" 格式
        Pattern stringPattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*?)\"");
        Matcher stringMatcher = stringPattern.matcher(json);
        if (stringMatcher.find()) {
            return stringMatcher.group(1);
        }

        // 再尝试 "key":value 格式（数字/布尔值）
        Pattern rawPattern = Pattern.compile("\"" + key + "\"\\s*:\\s*([^,}\\s]+)");
        Matcher rawMatcher = rawPattern.matcher(json);
        if (rawMatcher.find()) {
            return rawMatcher.group(1).trim();
        }

        return null;
    }
}
