package com.ouisani.aios.core.security;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.syscall.SyscallRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Privilege-based syscall filter — Linux capability / Windows privilege model.
 * <p>
 * Certain high-risk syscalls require the calling Agent to hold at least
 * {@link ProcessPriority#HIGH} priority. Lower-priority agents that attempt
 * these operations are blocked with a {@link SecurityException}.
 *
 * <h3>High-risk syscalls (require HIGH or above):</h3>
 * <ul>
 *   <li>{@code vfs.mount} — mount a new VFS node (filesystem manipulation)</li>
 *   <li>{@code tool.run_docker} — execute code in a Docker sandbox</li>
 *   <li>{@code apt.install} — install a WASM plugin (code injection risk)</li>
 *   <li>{@code apt.remove} — remove a plugin (system integrity risk)</li>
 *   <li>{@code bin.kill} — kill a process (lifecycle manipulation)</li>
 *   <li>{@code coreutils.kill} — legacy kill alias</li>
 * </ul>
 */
public class PrivilegeSyscallFilter implements SyscallFilter {

    private static final Logger log = LoggerFactory.getLogger(PrivilegeSyscallFilter.class);

    /** Actions that require HIGH priority or above. */
    private static final Set<String> HIGH_RISK_ACTIONS = Set.of(
            "vfs.mount",
            "tool.run_docker",
            "apt.install",
            "apt.remove",
            "bin.kill",
            "coreutils.kill"
    );

    /** Agent IDs that bypass privilege checks (kernel / root). */
    private static final Set<String> EXEMPT_AGENTS = Set.of("root_cli", "kernel");

    @Override
    public void preFilter(String agentId, SyscallRequest request) throws SecurityException {
        String action = request.fullAction();

        // Only check high-risk actions
        if (!isHighRisk(action)) {
            return;
        }

        // Kernel / root agents bypass privilege checks
        if (isExempt(agentId)) {
            return;
        }

        // Resolve the Agent's priority from the TaskScheduler
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
     * Resolve the Agent's priority from the TaskScheduler's current task context.
     * Falls back to NORMAL if the agent is not currently scheduled.
     */
    private ProcessPriority resolvePriority(String agentId) {
        AgentTask currentTask = TaskScheduler.CURRENT_TASK.get();
        if (currentTask != null) {
            return currentTask.processPriority();
        }
        // No task context (e.g. dashboard CLI) — treat as NORMAL
        return ProcessPriority.NORMAL;
    }
}
