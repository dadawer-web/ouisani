package com.ouisani.aios.core.security;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.syscall.SyscallRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

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
            String msg = "Agent '" + agentId + "' (priority=" + priority
                    + ") attempted privileged syscall '" + action
                    + "' — requires HIGH or above";
            log.warn("[Seccomp/Privilege] {}", msg);
            throw new SecurityException(msg);
        }
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
}
