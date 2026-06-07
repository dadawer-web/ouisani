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
 * 安全令牌 — AIOS 的 Windows 风格访问令牌。
 * <p>
 * 每个令牌携带所有者 ID、数值特权级别（0=最高, 3=最低）和一组权能字符串。
 * 令牌作为主令牌绑定到 {@link AgentTask}，也可通过
 * {@link ImpersonationContext#runAs} 在受控范围内临时冒充。
 *
 * <h3>特权级别：</h3>
 * <ul>
 *   <li>0 — 内核级 (REALTIME)：拥有所有权限，绕过所有检查</li>
 *   <li>1 — 系统级：大部分权限，可访问密钥</li>
 *   <li>2 — 用户级：标准权限</li>
 *   <li>3 — 受限级：最低权限</li>
 * </ul>
 *
 * <h3>OS 类比: Windows Access Token + Linux Capabilities</h3>
 * Windows 的访问令牌 (Access Token) 携带 SID 和特权列表，
 * Linux 的 Capabilities 将 root 权限拆分为细粒度权能。
 * SecurityToken 融合两者：数值级别控制粗粒度访问，
 * 权能字符串控制细粒度操作。
 *
 * <h3>标准权能：</h3>
 * <ul>
 *   <li>{@code SE_REALTIME} — 绕过 cgroup 限制</li>
 *   <li>{@code SE_SECRET_ACCESS} — 访问包含 "secret" 的路径</li>
 *   <li>{@code SE_REGISTRY_WRITE} — 修改语义注册表</li>
 *   <li>{@code SE_HANDLE_OPEN} — 打开 VFS 句柄</li>
 *   <li>{@code SE_ALL} — 所有权限（上帝模式）</li>
 * </ul>
 *
 * @see ImpersonationContext
 * @see BpfManager
 */
public final class SecurityToken {

    private static final Logger log = LoggerFactory.getLogger(SecurityToken.class);

    // ── 标准权能常量 ──
    public static final String SE_REALTIME = "SE_REALTIME";
    public static final String SE_SECRET_ACCESS = "SE_SECRET_ACCESS";
    public static final String SE_REGISTRY_WRITE = "SE_REGISTRY_WRITE";
    public static final String SE_HANDLE_OPEN = "SE_HANDLE_OPEN";
    public static final String SE_ALL = "SE_ALL";

    private final String ownerId;
    private final int privilegeLevel; // 0=最高(内核), 3=最低(受限)
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

    /** 检查此令牌是否拥有指定权能（SE_ALL 可绕过所有检查） */
    public boolean hasCapability(String capability) {
        return capabilities.contains(SE_ALL) || capabilities.contains(capability);
    }

    /** 检查此令牌的特权级别是否不超过给定阈值 */
    public boolean isPrivilegeLevelAtMost(int maxLevel) {
        return privilegeLevel <= maxLevel;
    }

    /** 创建内核级令牌 — 拥有所有权限 */
    public static SecurityToken kernelToken(String ownerId) {
        return new SecurityToken(ownerId, 0, Set.of(SE_ALL));
    }

    /** 创建系统级令牌 */
    public static SecurityToken systemToken(String ownerId) {
        return new SecurityToken(ownerId, 1, Set.of(SE_HANDLE_OPEN, SE_SECRET_ACCESS, SE_REGISTRY_WRITE));
    }

    /** 创建用户级令牌 — 标准权限 */
    public static SecurityToken userToken(String ownerId) {
        return new SecurityToken(ownerId, 2, Set.of(SE_HANDLE_OPEN));
    }

    /** 创建受限令牌 — 最低权限 */
    public static SecurityToken restrictedToken(String ownerId) {
        return new SecurityToken(ownerId, 3, Set.of());
    }

    /** 根据 Agent 的进程优先级创建令牌 */
    public static SecurityToken forAgent(AgentTask task) {
        if (task.processPriority() == ProcessPriority.REALTIME) {
            return kernelToken("agent_" + task.pid());
        }
        return userToken("agent_" + task.pid());
    }

    /**
     * 获取当前线程的有效安全令牌。
     * 优先检查冒充上下文，然后回退到当前任务的主令牌。
     *
     * @return 有效的 SecurityToken，若无则返回 null
     */
    public static SecurityToken getEffective() {
        // 优先级 1: 冒充上下文
        SecurityToken impersonated = ImpersonationContext.CURRENT_TOKEN.get();
        if (impersonated != null) {
            return impersonated;
        }

        // 优先级 2: 当前任务的主令牌
        AgentTask currentTask = TaskScheduler.CURRENT_TASK.get();
        if (currentTask != null) {
            return currentTask.primaryToken();
        }

        return null;
    }

    /** 检查当前有效令牌是否拥有指定权能 */
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
