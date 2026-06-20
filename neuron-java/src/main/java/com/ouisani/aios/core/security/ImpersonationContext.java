package com.ouisani.aios.core.security;

import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 身份模拟与权限降级上下文 — AIOS 的安全沙箱边界。
 * <p>
 * 当外界的不同用户（如管理员、普通访客）向 AIOS 发出请求时，
 * 当前处理该任务的 Agent 必须绑定一个 {@link SecurityToken}，
 * 并置于 ImpersonationContext 中。BpfManager 在拦截时严格校验该 Token。
 * <p>
 * <h3>核心安全保证</h3>
 * <ol>
 *   <li><b>权限降级</b>：访客让 Agent 去格式化系统 → Agent 在"访客 ImpersonationContext"下运行 → 被拒绝</li>
 *   <li><b>权限隔离</b>：不同用户的请求在同一 Agent 上执行时，令牌切换保证互不干扰</li>
 *   <li><b>防泄漏</b>：try-with-resources / try-finally 保证令牌始终被恢复</li>
 *   <li><b>审计追踪</b>：所有冒充操作写入 SemanticEtw</li>
 * </ol>
 *
 * <h3>OS 类比: Windows Impersonation + Linux Capabilities</h3>
 * Windows 的 ImpersonateLoggedOnUser 允许线程临时切换到另一个用户的安全上下文，
 * Linux 的 capset() 允许进程修改自己的 capability 集。ImpersonationContext
 * 将两者融合：线程绑定 SecurityToken，BpfManager 在 Syscall 入口处校验。
 *
 * <h3>用法</h3>
 * <pre>{@code
 * // 方式 1: try-with-resources（推荐）
 * try (var ctx = ImpersonationContext.impersonate(guestToken)) {
 *     // 此代码块以访客令牌运行
 *     // 任何特权操作都会被 BpfManager 拦截
 * }
 *
 * // 方式 2: runAs 回调
 * ImpersonationContext.runAs(adminToken, () -> {
 *     // 以管理员令牌运行
 * });
 *
 * // 方式 3: 访客降级
 * ImpersonationContext.runAsGuest(() -> {
 *     // 以受限令牌运行 — 无法写入受保护路径、无法加载模块
 * });
 * }</pre>
 *
 * @see SecurityToken
 * @see BpfManager
 * @see SemanticEtw
 */
public final class ImpersonationContext {

    private static final Logger log = LoggerFactory.getLogger(ImpersonationContext.class);

    /**
     * 当前线程的冒充令牌。
     * 当设置时，安全检查使用此令牌替代任务的 primary token。
     */
    public static final ThreadLocal<SecurityToken> CURRENT_TOKEN = new ThreadLocal<>();

    /**
     * 当前线程的冒充来源（谁触发了这次冒充）。
     * 用于审计追踪：区分"管理员请求"和"访客请求"。
     */
    public static final ThreadLocal<String> IMPERSONATION_SOURCE = new ThreadLocal<>();

    /**
     * 当前线程的冒充深度（嵌套冒充计数）。
     * 防止递归冒充导致的安全漏洞。
     */
    public static final ThreadLocal<Integer> IMPERSONATION_DEPTH = ThreadLocal.withInitial(() -> 0);

    /** 最大允许的冒充嵌套深度 */
    private static final int MAX_IMPERSONATION_DEPTH = 8;

    private ImpersonationContext() {}

    // ════════════════════════════════════════════════════════════════
    //  核心 API: runAs / impersonate
    // ════════════════════════════════════════════════════════════════

    /**
     * 以指定令牌执行操作，保证执行后恢复原令牌。
     * <p>
     * 这是 AIOS 的安全边界：Agent 在处理用户请求时，
     * 必须通过此方法绑定用户的 SecurityToken，确保权限降级。
     *
     * @param token  要冒充的安全令牌
     * @param action 要执行的操作
     * @throws IllegalStateException 如果冒充嵌套深度超过限制
     */
    public static void runAs(SecurityToken token, Runnable action) {
        SecurityToken previous = CURRENT_TOKEN.get();
        String previousSource = IMPERSONATION_SOURCE.get();
        int depth = IMPERSONATION_DEPTH.get();

        if (depth >= MAX_IMPERSONATION_DEPTH) {
            String msg = "Impersonation depth exceeded " + MAX_IMPERSONATION_DEPTH
                    + " — possible privilege escalation attack";
            log.error("[Security] {}", msg);
            SemanticEtw.getInstance().logEvent("SECURITY", "IMPERSONATION_DEPTH_EXCEEDED",
                    "depth=" + depth + " token=" + (token != null ? token.ownerId() : "null"));
            throw new IllegalStateException(msg);
        }

        try {
            CURRENT_TOKEN.set(token);
            IMPERSONATION_DEPTH.set(depth + 1);

            String sourceInfo = token != null ? token.ownerId() + "(level=" + token.privilegeLevel() + ")" : "null";
            IMPERSONATION_SOURCE.set(sourceInfo);

            // 审计追踪
            SemanticEtw.getInstance().logEvent("SECURITY", "IMPERSONATE",
                    "from=" + (previous != null ? previous.ownerId() : "none")
                    + " to=" + sourceInfo
                    + " depth=" + (depth + 1));

            log.info("[Security] Impersonation: {} → {} (depth={})",
                    previous != null ? previous.ownerId() : "none",
                    sourceInfo, depth + 1);

            action.run();
        } finally {
            if (previous != null) {
                CURRENT_TOKEN.set(previous);
            } else {
                CURRENT_TOKEN.remove();
            }
            if (previousSource != null) {
                IMPERSONATION_SOURCE.set(previousSource);
            } else {
                IMPERSONATION_SOURCE.remove();
            }
            IMPERSONATION_DEPTH.set(depth);
            log.debug("[Security] Impersonation reverted (depth={})", depth);
        }
    }

    /**
     * 冒充一个安全令牌，返回 AutoCloseable 用于 try-with-resources。
     *
     * @param token 要冒充的令牌
     * @return AutoCloseable，关闭时恢复原令牌
     */
    public static AutoCloseable impersonate(SecurityToken token) {
        SecurityToken previous = CURRENT_TOKEN.get();
        String previousSource = IMPERSONATION_SOURCE.get();
        int depth = IMPERSONATION_DEPTH.get();

        if (depth >= MAX_IMPERSONATION_DEPTH) {
            throw new IllegalStateException(
                    "Impersonation depth exceeded " + MAX_IMPERSONATION_DEPTH);
        }

        CURRENT_TOKEN.set(token);
        IMPERSONATION_DEPTH.set(depth + 1);
        String sourceInfo = token != null ? token.ownerId() + "(level=" + token.privilegeLevel() + ")" : "null";
        IMPERSONATION_SOURCE.set(sourceInfo);

        SemanticEtw.getInstance().logEvent("SECURITY", "IMPERSONATE",
                "from=" + (previous != null ? previous.ownerId() : "none")
                + " to=" + sourceInfo + " depth=" + (depth + 1));

        log.info("[Security] Impersonation: {} → {} (depth={})",
                previous != null ? previous.ownerId() : "none", sourceInfo, depth + 1);

        // 返回 AutoCloseable — 恢复原始令牌
        return () -> {
            if (previous != null) {
                CURRENT_TOKEN.set(previous);
            } else {
                CURRENT_TOKEN.remove();
            }
            if (previousSource != null) {
                IMPERSONATION_SOURCE.set(previousSource);
            } else {
                IMPERSONATION_SOURCE.remove();
            }
            IMPERSONATION_DEPTH.set(depth);
            log.debug("[Security] Impersonation reverted (depth={})", depth);
        };
    }

    // ════════════════════════════════════════════════════════════════
    //  便捷 API: 预定义角色冒充
    // ════════════════════════════════════════════════════════════════

    /**
     * 以访客令牌执行操作 — 最高级别的权限降级。
     * <p>
     * 访客令牌拥有 privilegeLevel=3（受限），无任何权能。
     * 任何写入受保护路径、加载模块、RPA 操作都会被 BpfManager 拦截。
     * <p>
     * 适用场景：处理来自不可信来源的请求（如外部 API 调用、匿名用户）。
     *
     * @param action 要执行的操作
     */
    public static void runAsGuest(Runnable action) {
        runAs(SecurityToken.restrictedToken("guest"), action);
    }

    /**
     * 以普通用户令牌执行操作 — 标准权限。
     * <p>
     * 用户令牌拥有 privilegeLevel=2，仅有 SE_HANDLE_OPEN 权能。
     * 可以读取 VFS、调用 LLM，但不能修改系统配置或加载模块。
     *
     * @param ownerId 用户标识
     * @param action  要执行的操作
     */
    public static void runAsUser(String ownerId, Runnable action) {
        runAs(SecurityToken.userToken(ownerId), action);
    }

    /**
     * 以系统令牌执行操作 — 管理员权限。
     * <p>
     * 系统令牌拥有 privilegeLevel=1，可以访问密钥、修改注册表。
     * 但不能绕过 cgroup 限制（需要 SE_REALTIME）。
     *
     * @param ownerId 管理员标识
     * @param action  要执行的操作
     */
    public static void runAsAdmin(String ownerId, Runnable action) {
        runAs(SecurityToken.systemToken(ownerId), action);
    }

    /**
     * 以内核令牌执行操作 — 最高权限（谨慎使用）。
     * <p>
     * 内核令牌拥有 privilegeLevel=0 和 SE_ALL 权能，可以绕过所有限制。
     * 仅用于系统初始化和内核守护进程。
     *
     * @param action 要执行的操作
     */
    public static void runAsKernel(Runnable action) {
        runAs(SecurityToken.kernelToken("kernel_op"), action);
    }

    // ════════════════════════════════════════════════════════════════
    //  查询 API
    // ════════════════════════════════════════════════════════════════

    /**
     * 获取当前冒充令牌（可能为 null）。
     */
    public static SecurityToken current() {
        return CURRENT_TOKEN.get();
    }

    /**
     * 获取当前冒充来源描述。
     */
    public static String currentSource() {
        return IMPERSONATION_SOURCE.get();
    }

    /**
     * 获取当前冒充深度。
     */
    public static int currentDepth() {
        return IMPERSONATION_DEPTH.get();
    }

    /**
     * 清除当前线程的冒充令牌。
     */
    public static void clear() {
        CURRENT_TOKEN.remove();
        IMPERSONATION_SOURCE.remove();
        IMPERSONATION_DEPTH.remove();
    }

    /**
     * 检查当前令牌是否具有指定权能。
     * 便捷方法，等价于 {@code SecurityToken.effectiveHasCapability(capability)}。
     */
    public static boolean hasCapability(String capability) {
        return SecurityToken.effectiveHasCapability(capability);
    }

    /**
     * 检查当前令牌是否为访客级别（privilegeLevel >= 3）。
     */
    public static boolean isGuestContext() {
        SecurityToken token = SecurityToken.getEffective();
        return token != null && token.privilegeLevel() >= 3;
    }

    /**
     * 检查当前令牌是否为管理员级别（privilegeLevel <= 1）。
     */
    public static boolean isAdminContext() {
        SecurityToken token = SecurityToken.getEffective();
        return token != null && token.privilegeLevel() <= 1;
    }
}
