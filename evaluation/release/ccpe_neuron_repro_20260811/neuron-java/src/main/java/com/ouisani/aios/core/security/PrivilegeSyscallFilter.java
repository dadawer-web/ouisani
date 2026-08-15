package com.ouisani.aios.core.security;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.permission.ActionTier;
import com.ouisani.aios.core.permission.Decision;
import com.ouisani.aios.core.permission.PermissionChecker;
import com.ouisani.aios.core.permission.PermissionRequest;
import com.ouisani.aios.core.permission.PermissionResult;
import com.ouisani.aios.core.permission.Urgency;
import com.ouisani.aios.core.syscall.SyscallRequest;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 特权级系统调用过滤器 — Linux Capabilities / Windows 特权模型。
 * <p>
 * 某些高危系统调用要求调用 Agent 至少持有 {@link ProcessPriority#HIGH} 优先级。
 * 低优先级 Agent 尝试执行这些操作时，将被阻止并抛出 {@link SecurityException}。
 *
 * <h3>OS 类比: Linux Capabilities + Windows Privileges</h3>
 * Linux 的 Capabilities 将 root 权限拆分为 CAP_NET_ADMIN、CAP_SYS_MODULE 等细粒度权能，
 * Windows 的 Privileges 控制 SeDebugPrivilege、SeLoadDriverPrivilege 等。
 * PrivilegeSyscallFilter 基于进程优先级实现类似的特权分级：
 * 只有 HIGH 及以上优先级的 Agent 才能执行高危操作。
 *
 * <h3>高危系统调用（需要 HIGH 或以上优先级）：</h3>
 * <ul>
 *   <li>{@code vfs.mount} — 挂载新的 VFS 节点（文件系统操作）</li>
 *   <li>{@code tool.run_docker} — 在 Docker 沙箱中执行代码</li>
 *   <li>{@code apt.install} — 安装 WASM 插件（代码注入风险）</li>
 *   <li>{@code apt.remove} — 卸载插件（系统完整性风险）</li>
 *   <li>{@code bin.kill} — 终止进程（生命周期操作）</li>
 *   <li>{@code coreutils.kill} — 旧版 kill 别名</li>
 * </ul>
 *
 * @see SyscallFilter
 * @see ProcessPriority
 */
public class PrivilegeSyscallFilter implements SyscallFilter {

    private static final Logger log = LoggerFactory.getLogger(PrivilegeSyscallFilter.class);

    /** 需要 HIGH 优先级或以上的操作 */
    private static final Set<String> HIGH_RISK_ACTIONS = Set.of(
            "vfs.mount",
            "tool.run_docker",
            "apt.install",
            "apt.remove",
            "bin.kill",
            "coreutils.kill"
    );

    /** 豁免特权检查的 Agent ID（内核 / root） */
    private static final Set<String> EXEMPT_AGENTS = Set.of("root_cli", "kernel");

    /** HITL 审批事件通道 */
    private static final String APPROVAL_EVENT = "sys.security.approval_required";

    /** 审批等待超时（秒）— 超时后默认拒绝 */
    private static final long APPROVAL_TIMEOUT_SECONDS = 60;

    /** 公开异步裁决请求通道 — 与 APPROVAL_EVENT（HITL 内部通道）并存，供外部订阅者（IMAP/Dashboard）监听 */
    private static final String PERMISSION_REQUEST_EVENT = "permission.request";

    /** 异步裁决同步等待超时（秒）— 超时后请求转入 Queued 态持久化到 queue.json */
    private static final long ASYNC_TIMEOUT_SECONDS = 5;

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    /** 待审批请求：approvalId → CompletableFuture<Boolean>（true=批准，false=拒绝） */
    private static final ConcurrentHashMap<String, CompletableFuture<Boolean>> pendingApprovals = new ConcurrentHashMap<>();

    /**
     * 权限通知器 — 镜像 jcode {@code safety.rs:11-29} 的 {@code OnceLock<PermissionNotifier>}。
     * <p>
     * 依赖反转：core 层不感知 user 层通知实现（IMAP/Slack/Dashboard），
     * 由 user 层在启动时通过 {@link #registerPermissionNotifier} 注入。
     * 未注册时 {@code dispatchPermissionNotification} 静默 no-op（不报错不阻断）。
     */
    private static final AtomicReference<PermissionNotifier> NOTIFIER = new AtomicReference<>();

    @Override
    public void preFilter(String agentId, SyscallRequest request) throws SecurityException {
        String action = request.fullAction();

        // 动态工具锻造是低风险操作（代码在沙箱中执行），不需要 HIGH 权限
        // 工具名称在 ToolPayload 中，需要检查 payload 类型
        if (request.payload() instanceof com.ouisani.aios.core.syscall.schema.ToolPayload toolPayload) {
            String toolName = toolPayload.toolName();
            if ("kernel.register_tool".equals(toolName) || "kernel.forge_tool".equals(toolName)) {
                return; // 放行
            }
        }

        // ── Containment Zone 检查 ──
        // 对 VFS 读写操作进行区域隔离检查，防止 Agent 跨 zone 访问敏感数据
        checkContainmentZone(action, request);

        // 只检查高危操作
        if (!isHighRisk(action)) {
            return;
        }

        // 内核 / root Agent 豁免特权检查
        if (isExempt(agentId)) {
            return;
        }

        // 从 TaskScheduler 解析 Agent 的优先级
        ProcessPriority priority = resolvePriority(agentId);

        if (priority != ProcessPriority.REALTIME && priority != ProcessPriority.HIGH) {
            // HITL 审批门：低优先级 Agent 触发高危操作时，请求人类审批
            if (!requestHumanApproval(agentId, request, action, priority)) {
                String msg = "Agent '" + agentId + "' (priority=" + priority
                        + ") attempted privileged syscall '" + action
                        + "' — requires HIGH or above (审批被拒绝或超时)";
                log.warn("[Seccomp/Privilege] {}", msg);
                throw new SecurityException(msg);
            }
            // 审批通过，放行
            log.info("[Seccomp/Privilege] Agent '{}' 的高危操作 '{}' 已获人类审批通过",
                    agentId, action);
        }
    }

    /**
     * 请求人类审批高危操作（Human-in-the-Loop）。
     * <p>
     * 流程：
     * <ol>
     *   <li>检查 EventBus 是否有审批订阅者；若无订阅者，直接拒绝（fallback）</li>
     *   <li>广播 {@code sys.security.approval_required} 事件，携带 agentId/action/namespace</li>
     *   <li>将请求存入 {@link #pendingApprovals}，阻塞等待 CompletableFuture</li>
     *   <li>超时 60 秒后默认拒绝</li>
     * </ol>
     * 阻塞等待在虚拟线程上是友好的（CompletableFuture.get 不 pin 虚拟线程）。
     *
     * @param agentId  发起 Agent
     * @param request  系统调用请求
     * @param action   完整动作名
     * @param priority Agent 当前优先级
     * @return true 如果审批通过；false 如果拒绝/超时/无订阅者
     */
    private boolean requestHumanApproval(String agentId, SyscallRequest request,
                                         String action, ProcessPriority priority) {
        EventBus bus = EventBus.instance();

        // Fallback：EventBus 无审批订阅者时，直接拒绝（保留原有拒绝逻辑）
        if (bus.subscriberCount(APPROVAL_EVENT) == 0) {
            log.warn("[Seccomp/Privilege] 无审批订阅者，直接拒绝 Agent '{}' 的高危操作 '{}'",
                    agentId, action);
            return false;
        }

        String approvalId = UUID.randomUUID().toString();
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pendingApprovals.put(approvalId, future);

        // 构造审批请求 JSON 负载
        String payload = String.format(
                "{\"approvalId\":\"%s\",\"agentId\":\"%s\",\"action\":\"%s\","
                        + "\"namespace\":\"%s\",\"priority\":\"%s\",\"timestamp\":%d}",
                approvalId, agentId, action, request.namespace(),
                priority.name(), System.currentTimeMillis());

        try {
            bus.broadcast(APPROVAL_EVENT, payload);
            log.info("[Seccomp/Privilege] 已广播审批请求: approvalId={}, agent={}, action={}",
                    approvalId, agentId, action);

            // 阻塞等待审批结果（虚拟线程友好，不消耗 OS 线程）
            Boolean approved = future.get(APPROVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(approved);
        } catch (TimeoutException e) {
            log.warn("[Seccomp/Privilege] 审批超时 ({}s)，默认拒绝: approvalId={}, agent={}, action={}",
                    APPROVAL_TIMEOUT_SECONDS, approvalId, agentId, action);
            return false;
        } catch (ExecutionException | InterruptedException e) {
            log.warn("[Seccomp/Privilege] 审批等待异常: approvalId={}, error={}",
                    approvalId, e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        } finally {
            pendingApprovals.remove(approvalId);
        }
    }

    /**
     * 提交审批结果 — 供 API 层调用。
     * <p>
     * 当人类在 Dashboard 上批准或拒绝某高危操作时，API 层调用此方法
     * 唤醒阻塞在 {@link #requestHumanApproval} 中的过滤器线程。
     *
     * @param approvalId 审批请求 ID（来自 {@code sys.security.approval_required} 事件）
     * @param approved   true=批准，false=拒绝
     * @return true 如果审批 ID 存在并已处理；false 如果 ID 不存在或已过期
     */
    public static boolean approve(String approvalId, boolean approved) {
        CompletableFuture<Boolean> future = pendingApprovals.get(approvalId);
        if (future == null) {
            log.warn("[Seccomp/Privilege] 审批 ID 不存在或已过期: {}", approvalId);
            return false;
        }
        return future.complete(approved);
    }

    /**
     * 获取待处理的审批请求数量 — 用于监控。
     */
    public static int getPendingApprovalCount() {
        return pendingApprovals.size();
    }

    // ════════════════════════════════════════════════════════════════
    //  异步文件裁决 — 镜像 jcode safety.rs request_permission + classify
    // ════════════════════════════════════════════════════════════════

    /**
     * 注册权限通知器 — 镜像 jcode {@code safety.rs:21-23} {@code register_permission_notifier}。
     * <p>
     * 单次注册语义（{@code compareAndSet(null, n)}）：仅第一次调用生效，后续调用被忽略。
     * 由 user 层（如 AiosShell bootstrap）在启动时注入具体通知实现（IMAP/Slack/Dashboard）。
     *
     * @param notifier 通知器实现
     * @return true 如果注册成功（首次调用）；false 如果已有注册或参数为 null
     */
    public static boolean registerPermissionNotifier(PermissionNotifier notifier) {
        if (notifier == null) return false;
        return NOTIFIER.compareAndSet(null, notifier);
    }

    /** 测试用 — 清除已注册的通知器，重置 OnceLock 单次语义。仅供单元测试调用。 */
    static void clearPermissionNotifierForTest() {
        NOTIFIER.set(null);
    }

    /** 分发权限通知 — 镜像 jcode {@code safety.rs:25-29} {@code dispatch_permission_notification}。未注册时静默 no-op。 */
    private static void dispatchPermissionNotification(String action, String description, String requestId) {
        PermissionNotifier notifier = NOTIFIER.get();
        if (notifier != null) {
            try {
                notifier.notify(action, description, requestId);
            } catch (Exception e) {
                log.warn("[Seccomp/Permission] notifier 异常: requestId={}, error={}", requestId, e.getMessage());
            }
        }
    }

    /**
     * 异步权限裁决主入口 — 镜像 jcode {@code safety.rs:187-199} {@code request_permission}。
     * <p>
     * 流程：
     * <ol>
     *   <li>{@link PermissionChecker#classify} 前置分级：AutoAllowed → 直接 Approved</li>
     *   <li>否则入 {@link #pendingApprovals}，注册 whenComplete 触发 history 写入</li>
     *   <li>广播 {@link #PERMISSION_REQUEST_EVENT}（公开异步通道）+ {@link #APPROVAL_EVENT}（HITL 通道，向后兼容）</li>
     *   <li>调用注入的 {@link PermissionNotifier}（依赖反转，core 不感知 user 层实现）</li>
     *   <li>同步等待 {@link #ASYNC_TIMEOUT_SECONDS} 秒：
     *     <ul>
     *       <li>正常 → Approved/Denied（whenComplete 已写 history，decidedVia="user_sync"）</li>
     *       <li>超时 → 持久化到 queue.json，返回 {@link PermissionResult.Queued}（decidedVia 留空，待外部回填）</li>
     *     </ul>
     *   </li>
     * </ol>
     * <p>
     * AutoAllowed 分支显式写 history（decidedVia="auto"），保证审计完整性。
     *
     * @param action      工具名或 syscall action
     * @param description 人类可读描述
     * @param urgency     紧急度
     * @param agentId     发起 Agent ID
     * @return PermissionResult（Approved/Denied/Queued/Timeout）
     */
    public static PermissionResult askPermission(String action, String description,
                                                 Urgency urgency, String agentId) {
        if (action == null || action.isBlank()) {
            return new PermissionResult.Denied("action is null or blank");
        }
        Urgency urg = urgency != null ? urgency : Urgency.DEFAULT;
        String requestId = "req_" + UUID.randomUUID();
        long now = System.currentTimeMillis();
        ActionTier tier = PermissionChecker.classify(action);

        // ── AutoAllowed 白名单直通 ──
        if (tier == ActionTier.AutoAllowed) {
            Decision decision = new Decision(
                    requestId, action, true, now, "auto",
                    "auto-allowed by SAFE_AUTO_TOOLS", urg, tier
            );
            PermissionFileStore.appendHistory(decision);
            auditDecision(decision);
            return new PermissionResult.Approved("auto-allowed");
        }

        // ── RequiresPermission：入队 + 同步等待 ──
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pendingApprovals.put(requestId, future);

        // whenComplete：future complete 时写 history（decidedVia="user_sync"）
        future.whenComplete((ok, ex) -> {
            boolean approved = Boolean.TRUE.equals(ok) && ex == null;
            Decision decision = new Decision(
                    requestId, action, approved, System.currentTimeMillis(),
                    "user_sync",
                    ex != null ? "exception: " + ex.getMessage() : (approved ? "approved" : "denied"),
                    urg, tier
            );
            PermissionFileStore.appendHistory(decision);
            auditDecision(decision);
        });

        // 广播公开异步通道 + HITL 通道（向后兼容）
        EventBus bus = EventBus.instance();
        String payload = String.format(
                "{\"requestId\":\"%s\",\"action\":\"%s\",\"description\":\"%s\","
                        + "\"urgency\":\"%s\",\"tier\":\"%s\",\"agentId\":\"%s\",\"timestamp\":%d}",
                requestId, escapeJson(action), escapeJson(description),
                urg.name(), tier.name(), agentId != null ? escapeJson(agentId) : "", now
        );
        bus.broadcast(PERMISSION_REQUEST_EVENT, payload);
        bus.broadcast(APPROVAL_EVENT, payload);

        // 依赖反转通知
        dispatchPermissionNotification(action, description, requestId);

        // 同步等待（虚拟线程友好，不 pin）
        try {
            Boolean approved = future.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(approved)
                    ? new PermissionResult.Approved("user approved")
                    : new PermissionResult.Denied("user denied");
        } catch (TimeoutException e) {
            // 超时 → 持久化到 queue.json，返回 Queued；不从 pendingApprovals 移除（允许后续 approve 补裁决）
            PermissionRequest queued = new PermissionRequest(
                    requestId, action, description, urg, tier, now, agentId
            );
            PermissionFileStore.enqueueRequest(queued);
            log.info("[Seccomp/Permission] 裁决超时转入 Queued: requestId={}, action={}", requestId, action);
            return new PermissionResult.Queued(requestId);
        } catch (ExecutionException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("[Seccomp/Permission] 裁决等待异常: requestId={}, error={}", requestId, e.getMessage());
            return new PermissionResult.Denied("wait error: " + e.getMessage());
        }
    }

    /**
     * 清理过期未裁决请求 — 镜像 jcode {@code safety.rs:420-470} {@code expire_dead_session_requests}。
     * <p>
     * 从 queue.json 清理超时项并写 history（decidedVia="timeout"），
     * 同时 complete 对应的 pendingApprovals future（缓解内存泄漏）。
     *
     * @param maxAgeMinutes 最大存活分钟数
     * @return 被清理的请求数
     */
    public static int sweepStale(long maxAgeMinutes) {
        List<String> expiredIds = PermissionFileStore.expireStale(maxAgeMinutes * 60_000L, "timeout");
        for (String id : expiredIds) {
            CompletableFuture<Boolean> fut = pendingApprovals.get(id);
            if (fut != null) {
                fut.complete(false);  // 触发 whenComplete 写 history(decidedVia="user_sync") + auditDecision
            }
        }
        return expiredIds.size();
    }

    /** 结构化审计 — 写 SemanticEtw，payload JSON 化（优于现状裸字符串）。 */
    private static void auditDecision(Decision decision) {
        try {
            SemanticEtw.getInstance().logEvent("SECURITY", "PERMISSION_DECISION", GSON.toJson(decision));
        } catch (Exception e) {
            log.debug("[Seccomp/Permission] auditDecision 异常: {}", e.getMessage());
        }
    }

    /** JSON 字符串转义 — 避免 action/description 含引号破坏 payload。 */
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * 权限通知器接口 — 镜像 jcode {@code safety.rs:11-29} {@code type PermissionNotifier = fn(&str, &str, &str)}。
     * <p>
     * 依赖反转注入点：core 层定义接口，user 层提供实现（IMAP/Slack/Dashboard）。
     * 通过 {@link #registerPermissionNotifier} 在启动时注入。
     */
    @FunctionalInterface
    public interface PermissionNotifier {
        /**
         * 通知外部裁决通道有新请求到达。
         *
         * @param action      工具名或 syscall action
         * @param description 人类可读描述
         * @param requestId   请求 ID（用于回填 {@link #approve}）
         */
        void notify(String action, String description, String requestId);
    }

    private boolean isHighRisk(String action) {
        return HIGH_RISK_ACTIONS.contains(action);
    }

    private boolean isExempt(String agentId) {
        if (agentId == null) return false;
        if (EXEMPT_AGENTS.contains(agentId)) return true;
        // System agents (sys_*) are always exempt
        return agentId.startsWith("sys_");
    }

    /**
     * 从 TaskScheduler 的当前任务上下文解析 Agent 的优先级。
     * 若 Agent 未被调度，则回退为 NORMAL。
     */
    private ProcessPriority resolvePriority(String agentId) {
        AgentTask currentTask = TaskScheduler.CURRENT_TASK.get();
        if (currentTask != null) {
            return currentTask.processPriority();
        }
        // 无任务上下文（如 dashboard CLI）— 视为 NORMAL
        return ProcessPriority.NORMAL;
    }

    /**
     * Containment Zone 检查 — 对 VFS 读写操作进行区域隔离。
     * <p>
     * 当 syscall 是 vfs.read / vfs.write / storage.read / storage.write 时，
     * 从 payload 中提取路径，调用 {@link ContainmentZoneManager} 检查
     * 当前 Agent 的 SecurityToken 是否有权访问该路径所属的 zone。
     * <p>
     * 被拒绝时抛出 SecurityException，触发 RecoveryOrchestrator 安全告警。
     *
     * @param action  完整的 syscall action (如 "vfs.read")
     * @param request syscall 请求
     */
    private void checkContainmentZone(String action, SyscallRequest request) {
        // 只对 VFS/存储读写操作进行 zone 检查
        boolean isRead = action.endsWith(".read") || action.endsWith(".vfs_read");
        boolean isWrite = action.endsWith(".write") || action.endsWith(".vfs_write")
                || action.endsWith(".create") || action.endsWith(".delete");

        if (!isRead && !isWrite) {
            return;
        }

        // 从 payload 中提取路径
        String path = request.paramString("path");
        if (path == null || path.isBlank()) {
            // 尝试从 RawPayload 的其他常见字段名提取
            path = request.paramString("file");
            if (path == null || path.isBlank()) {
                path = request.paramString("vfsPath");
            }
        }

        if (path == null || path.isBlank()) {
            return; // 无路径信息，跳过 zone 检查
        }

        ContainmentZoneManager.Operation op = isWrite
                ? ContainmentZoneManager.Operation.WRITE
                : ContainmentZoneManager.Operation.READ;

        ContainmentZoneManager.instance().enforceAccess(path, op);
    }
}
