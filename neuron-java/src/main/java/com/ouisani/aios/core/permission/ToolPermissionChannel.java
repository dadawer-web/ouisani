package com.ouisani.aios.core.permission;

import com.ouisani.aios.core.network.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具级权限审批通道 — 把 {@link PermissionChecker} 的 ASK 决策真正暴露给人类裁决。
 * <p>
 * <b>设计借鉴</b>：镜像 {@link com.ouisani.aios.core.security.PrivilegeSyscallFilter#requestHumanApproval}
 * 的 CompletableFuture 阻塞审批模式，但结果为三态（支持 standing scoped approvals）：
 * <ul>
 *   <li>{@link ApprovalResponse#ALLOW_ONCE} — 本次放行，下次再问</li>
 *   <li>{@link ApprovalResponse#ALWAYS_TARGET} — 永久放行此 target（调用方据此调
 *       {@link PermissionChecker#grantTargetApproval} 记账）</li>
 *   <li>{@link ApprovalResponse#DENY} — 拒绝</li>
 * </ul>
 * <p>
 * <b>零回归 fallback</b>：当 EventBus 上无审批订阅者时（含所有单测、headless 运行），
 * {@link #requestApproval} 立即返回 {@link ApprovalResponse#ALLOW_ONCE}，行为与
 * QueryEngine 原「ASK 自动放行」完全一致。仅当前端通过
 * {@code /api/permission/stream} WS 连接并订阅 {@link #CHANNEL} 时，
 * {@code subscriberCount > 0} 才激活真正的阻塞审批。
 * <p>
 * <b>线程模型</b>：QueryEngine 在虚拟线程上调用 {@link #requestApproval}，
 * {@code CompletableFuture.get} 不 pin 虚拟线程，阻塞是友好的。
 * <p>
 * OS 类比：相当于 Linux 的 {@code /proc/sys/kernel/hotplug} — 内核遇到需要用户态介入的事件，
 * 通过该通道通知用户态守护进程；用户态回填结果后内核继续。
 */
public final class ToolPermissionChannel {

    private static final Logger log = LoggerFactory.getLogger(ToolPermissionChannel.class);

    /** EventBus 审批请求通道 — WS 端点订阅此通道转发给前端。 */
    public static final String CHANNEL = "permission.tool.ask";

    /** 审批等待超时（秒）— 超时默认拒绝，避免 agent loop 无限阻塞。 */
    static final long APPROVAL_TIMEOUT_SECONDS = 120;

    /** 待审批请求：requestId → CompletableFuture<ApprovalResponse>。 */
    private static final ConcurrentHashMap<String, CompletableFuture<ApprovalResponse>> pending =
            new ConcurrentHashMap<>();

    private ToolPermissionChannel() {}

    /** 审批三态结果 — 支持 standing scoped approvals 的「永久此 target」。 */
    public enum ApprovalResponse {
        /** 本次放行，下次仍询问。 */
        ALLOW_ONCE,
        /** 永久放行此 target — 调用方应调 PermissionChecker.grantTargetApproval 记账。 */
        ALWAYS_TARGET,
        /** 拒绝执行。 */
        DENY;

        /** 容错解析：未知字符串 → ALLOW_ONCE（保守放行，不破坏流水线）。 */
        public static ApprovalResponse safeValueOf(String s) {
            if (s == null) return ALLOW_ONCE;
            try {
                return ApprovalResponse.valueOf(s.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("[ToolPermission] 未知审批决策 '{}'，保守按 ALLOW_ONCE 处理", s);
                return ALLOW_ONCE;
            }
        }
    }

    /**
     * 请求人类审批一次工具调用。
     * <p>
     * 流程：
     * <ol>
     *   <li>EventBus 无订阅者 → 立即返回 {@link ApprovalResponse#ALLOW_ONCE}（零回归 fallback）</li>
     *   <li>否则广播 {@link #CHANNEL} 携带 {requestId, agentId, toolName, target, description}，</li>
     *   <li>阻塞 {@code future.get(120s)}；超时/异常 → {@link ApprovalResponse#DENY}</li>
     * </ol>
     *
     * @param agentId     发起 Agent ID
     * @param toolName    工具名
     * @param target      目标标识（可为 null；非 null 时前端展示「永久允许此 target」选项）
     * @param description 决策消息（来自 PermissionDecision.message）
     * @return 审批结果
     */
    public static ApprovalResponse requestApproval(String agentId, String toolName,
                                                   String target, String description) {
        return requestApproval(agentId, toolName, target, description, APPROVAL_TIMEOUT_SECONDS);
    }

    /**
     * 可指定超时的审批请求 — 供测试用短超时验证 timeout → DENY 路径。
     */
    static ApprovalResponse requestApproval(String agentId, String toolName,
                                            String target, String description,
                                            long timeoutSeconds) {
        EventBus bus = EventBus.instance();

        // 零回归 fallback：无审批订阅者时自动放行（与 QueryEngine 原行为一致）
        if (bus.subscriberCount(CHANNEL) == 0) {
            log.debug("[ToolPermission] 无审批订阅者，自动放行: agent={}, tool={}", agentId, toolName);
            return ApprovalResponse.ALLOW_ONCE;
        }

        String requestId = "perm_" + UUID.randomUUID();
        CompletableFuture<ApprovalResponse> future = new CompletableFuture<>();
        pending.put(requestId, future);

        String payload = String.format(
                "{\"requestId\":\"%s\",\"agentId\":\"%s\",\"toolName\":\"%s\","
                        + "\"target\":\"%s\",\"description\":\"%s\",\"timestamp\":%d}",
                requestId,
                escapeJson(agentId),
                escapeJson(toolName),
                escapeJson(target),
                escapeJson(description),
                System.currentTimeMillis());

        try {
            bus.broadcast(CHANNEL, payload);
            log.info("[ToolPermission] 已广播审批请求: requestId={}, agent={}, tool={}, target={}",
                    requestId, agentId, toolName, target);
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("[ToolPermission] 审批超时 ({}s)，默认拒绝: requestId={}, tool={}",
                    timeoutSeconds, requestId, toolName);
            return ApprovalResponse.DENY;
        } catch (ExecutionException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("[ToolPermission] 审批等待异常: requestId={}, error={}",
                    requestId, e.getMessage());
            return ApprovalResponse.DENY;
        } finally {
            pending.remove(requestId);
        }
    }

    /**
     * 提交审批结果 — 供 WS 端点（{@code PermissionApprovalRoutes}）在前端回填时调用。
     *
     * @param requestId 请求 ID（来自 {@link #CHANNEL} 广播的 payload）
     * @param response  审批决策
     * @return true 如果 requestId 存在并已处理；false 如果 ID 不存在或已过期
     */
    public static boolean respond(String requestId, ApprovalResponse response) {
        CompletableFuture<ApprovalResponse> future = pending.get(requestId);
        if (future == null) {
            log.warn("[ToolPermission] 审批 ID 不存在或已过期: {}", requestId);
            return false;
        }
        return future.complete(response);
    }

    /** 待处理的审批请求数量 — 监控/测试用。 */
    public static int pendingCount() {
        return pending.size();
    }

    /** JSON 字符串转义 — 避免 toolName/description 含引号破坏 payload。 */
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
