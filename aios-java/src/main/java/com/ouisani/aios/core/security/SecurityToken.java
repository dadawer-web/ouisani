package com.ouisani.aios.core.security;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.TaskScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Windows-style Security Token for AIOS.
 * <p>
 * Each token carries an owner ID, a numeric privilege level (0=highest, 3=lowest),
 * and a set of capability strings. Tokens are attached to {@link AgentTask}s as
 * their primary token, and can be temporarily impersonated via
 * {@link ImpersonationContext#runAs} for privilege escalation in a controlled scope.
 *
 * <h3>Privilege Levels:</h3>
 * <ul>
 *   <li>0 — Kernel (REALTIME): all capabilities, bypasses all checks</li>
 *   <li>1 — System: most capabilities, can access secrets</li>
 *   <li>2 — User: standard capabilities</li>
 *   <li>3 — Restricted: minimal capabilities</li>
 * </ul>
 *
 * <h3>Standard Capabilities:</h3>
 * <ul>
 *   <li>{@code SE_REALTIME} — bypass cgroup limits</li>
 *   <li>{@code SE_SECRET_ACCESS} — access paths containing "secret"</li>
 *   <li>{@code SE_REGISTRY_WRITE} — modify the semantic registry</li>
 *   <li>{@code SE_HANDLE_OPEN} — open VFS handles</li>
 *   <li>{@code SE_ALL} — all capabilities (god mode)</li>
 * </ul>
 */
public final class SecurityToken {

    private static final Logger log = LoggerFactory.getLogger(SecurityToken.class);

    // ── Standard Capability Constants ──
    public static final String SE_REALTIME = "SE_REALTIME";
    public static final String SE_SECRET_ACCESS = "SE_SECRET_ACCESS";
    public static final String SE_REGISTRY_WRITE = "SE_REGISTRY_WRITE";
    public static final String SE_HANDLE_OPEN = "SE_HANDLE_OPEN";
    public static final String SE_ALL = "SE_ALL";

    private final String ownerId;
    private final int privilegeLevel; // 0=highest (kernel), 3=lowest (restricted)
    private final Set<String> capabilities;

    public SecurityToken(String ownerId, int privilegeLevel, Set<String> capabilities) {
        this.ownerId = ownerId;
        this.privilegeLevel = privilegeLevel;
        Set<String> mutable = ConcurrentHashMap.newKeySet(capabilities.size());
        mutable.addAll(capabilities);
        this.capabilities = Collections.unmodifiableSet(mutable);
    }

    public String ownerId() {
        return ownerId;
    }

    public int privilegeLevel() {
        return privilegeLevel;
    }

    public Set<String> capabilities() {
        return capabilities;
    }

    public boolean hasCapability(String capability) {
        return capabilities.contains(SE_ALL) || capabilities.contains(capability);
    }

    /** Check if this token's privilege level is at or below the given threshold. */
    public boolean isPrivilegeLevelAtMost(int maxLevel) {
        return privilegeLevel <= maxLevel;
    }

    /**
     * Create a token for a REALTIME (kernel-level) agent with all capabilities.
     */
    public static SecurityToken kernelToken(String ownerId) {
        return new SecurityToken(ownerId, 0, Set.of(SE_ALL));
    }

    /**
     * Create a token for a system-level agent.
     */
    public static SecurityToken systemToken(String ownerId) {
        return new SecurityToken(ownerId, 1, Set.of(SE_HANDLE_OPEN, SE_SECRET_ACCESS, SE_REGISTRY_WRITE));
    }

    /**
     * Create a token for a normal user agent with standard capabilities.
     */
    public static SecurityToken userToken(String ownerId) {
        return new SecurityToken(ownerId, 2, Set.of(SE_HANDLE_OPEN));
    }

    /**
     * Create a restricted token with minimal capabilities.
     */
    public static SecurityToken restrictedToken(String ownerId) {
        return new SecurityToken(ownerId, 3, Set.of());
    }

    /**
     * Create a token based on the agent's process priority.
     */
    public static SecurityToken forAgent(AgentTask task) {
        if (task.processPriority() == ProcessPriority.REALTIME) {
            return kernelToken("agent_" + task.pid());
        }
        return userToken("agent_" + task.pid());
    }

    /**
     * Get the effective security token for the current thread.
     * Checks impersonation context first, then falls back to the current task's primary token.
     *
     * @return the effective SecurityToken, or null if none is available
     */
    public static SecurityToken getEffective() {
        // Priority 1: Impersonation context
        SecurityToken impersonated = ImpersonationContext.CURRENT_TOKEN.get();
        if (impersonated != null) {
            return impersonated;
        }

        // Priority 2: Current task's primary token
        AgentTask currentTask = TaskScheduler.CURRENT_TASK.get();
        if (currentTask != null) {
            return currentTask.primaryToken();
        }

        return null;
    }

    /**
     * Check if the current effective token has a specific capability.
     */
    public static boolean effectiveHasCapability(String capability) {
        SecurityToken token = getEffective();
        return token != null && token.hasCapability(capability);
    }

    @Override
    public String toString() {
        return "SecurityToken{owner='" + ownerId + "', level=" + privilegeLevel
                + ", capabilities=" + capabilities + "}";
    }
}
