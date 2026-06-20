package com.ouisani.aios.core.security;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.syscall.SyscallRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    /** 待审批请求：approvalId → CompletableFuture<Boolean>（true=批准，false=拒绝） */
    private static final ConcurrentHashMap<String, CompletableFuture<Boolean>> pendingApprovals = new ConcurrentHashMap<>();

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
