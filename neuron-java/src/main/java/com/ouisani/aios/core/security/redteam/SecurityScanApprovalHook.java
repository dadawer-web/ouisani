package com.ouisani.aios.core.security.redteam;

import com.ouisani.aios.core.hook.HookManager;
import com.ouisani.aios.core.network.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 安全扫描审批钩子 — God Hand 强制人机审批机制。
 * <p>
 * 当 SecurityScanTool 被触发时，此钩子拦截执行，将风险评分强制标记为
 * CRITICAL (1.0)，通过 EventBus 向前端广播审批请求，阻塞等待人类决策。
 * <p>
 * <h3>工作流程</h3>
 * <pre>
 *   SecurityScanTool.call()
 *     → HookManager.trigger(PRE_TOOL_USE)
 *       → SecurityScanApprovalHook.handle()
 *         → 风险评分 = 1.0 (CRITICAL)
 *         → EventBus.publish("sys.security.approval_request")
 *         → 前端弹出鲜红色警告卡片
 *         → 阻塞等待 CompletableFuture
 *           → 用户点击 [授权] → resume(true)
 *           → 用户点击 [拒绝] → resume(false)
 *         → 返回 HookResult
 * </pre>
 * <p>
 * OS 类比：相当于 Linux 的 SELinux AVC — 安全决策点，
 * 每次敏感操作都必须经过强制访问控制审批。
 *
 * @see HookManager
 * @see SecurityScanTool
 */
public class SecurityScanApprovalHook implements HookManager.HookHandler {

    private static final Logger log = LoggerFactory.getLogger(SecurityScanApprovalHook.class);

    /** 审批请求超时时间（默认 5 分钟） */
    private static final long APPROVAL_TIMEOUT_MINUTES = 5;

    /** 待审批请求映射 — requestId → CompletableFuture */
    private final Map<String, CompletableFuture<Boolean>> pendingApprovals = new ConcurrentHashMap<>();

    /** EventBus 通道名 */
    private static final String APPROVAL_REQUEST_CHANNEL = "sys.security.approval_request";
    private static final String APPROVAL_RESPONSE_CHANNEL = "sys.security.approval_response";

    public SecurityScanApprovalHook() {
        // 订阅审批响应通道
        EventBus.instance().subscribe(APPROVAL_RESPONSE_CHANNEL, this::handleApprovalResponse);
        log.info("[SecurityScanApprovalHook] God Hand 审批机制已激活");
    }

    /**
     * 注册到 HookManager — 拦截 PreToolUse 事件。
     */
    public void register() {
        HookManager.instance().register(
                HookManager.HookEvent.PRE_TOOL_USE,
                this,
                10 // 最高优先级 — 在其他钩子之前执行
        );
    }

    @Override
    public HookManager.HookResult handle(HookManager.HookEvent event, Map<String, Object> data) {
        // 仅拦截 security_scan 工具
        String toolName = (String) data.getOrDefault("toolName", "");
        if (!"security_scan".equals(toolName)) {
            return HookManager.HookResult.ok();
        }

        String agentId = (String) data.getOrDefault("agentId", "unknown");
        String module = (String) data.getOrDefault("module", "unknown");
        String target = (String) data.getOrDefault("target", "unknown");
        String args = (String) data.getOrDefault("args", "");

        log.warn("[God Hand] Security_Auditor 企图使用 HackingTool 对 {} 发起 {} 扫描",
                target, module);

        // ── 强制风险评分 = 1.0 (CRITICAL) ──
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<Boolean> approvalFuture = new CompletableFuture<>();
        pendingApprovals.put(requestId, approvalFuture);

        // ── 通过 EventBus 向前端广播审批请求 ──
        String approvalPayload = buildApprovalRequestJson(
                requestId, agentId, module, target, args
        );
        EventBus.instance().broadcast(APPROVAL_REQUEST_CHANNEL, approvalPayload);

        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  🚨 【高危操作告警】God Hand 审批请求已发出                    ║");
        System.out.printf("  ║  Agent: %s%n", agentId);
        System.out.printf("  ║  模块:  %s%n", module);
        System.out.printf("  ║  目标:  %s%n", target);
        System.out.printf("  ║  参数:  %s%n", args);
        System.out.printf("  ║  请求ID: %s%n", requestId);
        System.out.println("  ║  等待人类审批中... (超时: " + APPROVAL_TIMEOUT_MINUTES + " 分钟)");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");

        // ── 阻塞等待人类决策 ──
        try {
            Boolean approved = approvalFuture.get(APPROVAL_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (approved != null && approved) {
                log.info("[God Hand] 审批通过 — 请求 {} 已授权", requestId);
                System.out.printf("  ✅ [God Hand] 审批通过 — Security_Auditor 获准执行 %s 扫描%n", module);
                return HookManager.HookResult.ok("APPROVED by God Hand");
            } else {
                log.info("[God Hand] 审批拒绝 — 请求 {} 被拒绝", requestId);
                System.out.printf("  ❌ [God Hand] 审批拒绝 — Security_Auditor 的 %s 扫描请求被拒绝%n", module);
                return HookManager.HookResult.deny("REJECTED by God Hand — 用户拒绝了此安全扫描请求");
            }
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("[God Hand] 审批超时 — 请求 {} 在 {} 分钟内未获响应", requestId, APPROVAL_TIMEOUT_MINUTES);
            System.out.printf("  ⏰ [God Hand] 审批超时 — 请求 %s 在 %d 分钟内未获响应，自动拒绝%n",
                    requestId, APPROVAL_TIMEOUT_MINUTES);
            return HookManager.HookResult.deny("APPROVAL TIMEOUT — 审批超时，自动拒绝");
        } catch (Exception e) {
            log.error("[God Hand] 审批流程异常: {}", e.getMessage());
            return HookManager.HookResult.deny("APPROVAL ERROR — " + e.getMessage());
        } finally {
            pendingApprovals.remove(requestId);
        }
    }

    /**
     * 处理审批响应 — 由前端通过 EventBus 发回。
     * <p>
     * 响应格式: {"requestId":"xxx","approved":true}
     */
    private void handleApprovalResponse(String responseJson) {
        try {
            String requestId = extractJsonField(responseJson, "requestId");
            boolean approved = "true".equalsIgnoreCase(extractJsonField(responseJson, "approved"));

            CompletableFuture<Boolean> future = pendingApprovals.get(requestId);
            if (future != null) {
                future.complete(approved);
                log.info("[God Hand] 收到审批响应 — 请求 {} → {}", requestId, approved ? "授权" : "拒绝");
            } else {
                log.warn("[God Hand] 收到未知请求的审批响应: {}", requestId);
            }
        } catch (Exception e) {
            log.error("[God Hand] 解析审批响应失败: {}", e.getMessage());
        }
    }

    /**
     * 构建审批请求 JSON — 供前端渲染警告卡片。
     */
    private String buildApprovalRequestJson(
            String requestId, String agentId, String module, String target, String args
    ) {
        return "{"
                + "\"type\":\"security_approval_request\","
                + "\"requestId\":\"" + requestId + "\","
                + "\"agentId\":\"" + agentId + "\","
                + "\"module\":\"" + module + "\","
                + "\"target\":\"" + target + "\","
                + "\"args\":\"" + args + "\","
                + "\"riskScore\":1.0,"
                + "\"riskLevel\":\"CRITICAL\","
                + "\"message\":\"Security_Auditor 企图使用 HackingTool 对 " + target + " 发起 " + module + " 扫描\","
                + "\"actions\":[\"拒绝\",\"授权并隔离网络执行\"]"
                + "}";
    }

    /**
     * 简易 JSON 字段提取 — 避免引入 JSON 库依赖。
     */
    private static String extractJsonField(String json, String field) {
        String needle = "\"" + field + "\":\"";
        int start = json.indexOf(needle);
        if (start < 0) {
            // 尝试不带引号的布尔值
            needle = "\"" + field + "\":";
            start = json.indexOf(needle);
            if (start < 0) return "";
            start += needle.length();
            int end = json.indexOf(",", start);
            if (end < 0) end = json.indexOf("}", start);
            return json.substring(start, end).trim();
        }
        start += needle.length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : "";
    }

    /**
     * 获取当前待审批请求数量。
     */
    public int pendingCount() {
        return pendingApprovals.size();
    }
}
