package com.ouisani.aios.core.permission;

/**
 * Spawn 权限继承上下文 — 携带当前线程所属 agent 的有效 {@link PermissionProfile}，
 * 供动态 spawn 的子 agent 继承父权限（强制权限非递增）。
 * <p>
 * <b>动机（LIM 新型攻击面）</b>：传统 cgroup/capability 模型假设进程启动时 capability 绑定。
 * LIM agent 在运行时动态 spawn 子 agent，若子拿全新 DEFAULT 权限，则父被降权
 * （如 reviewer {@code *:deny + 只读 allowlist}）时子反而获完整默认权限 = <b>通过 spawn 实现权限升级</b>，
 * 无传统 {@code NoNewPrivs} 等价物。本类把父的有效 profile 经 InheritableThreadLocal 传播到子线程，
 * 使 {@code AgentTool} spawn 时能把父 profile 注入子 {@code com.ouisani.aios.core.tool.QueryEngine}，
 * 强制「子权限 ⊆ 父权限」。
 * <p>
 * <b>OS 类比</b>：Linux 的 ambient authority / 继承的 credentials。
 * {@code execve} 时子进程继承父的 effective uid/gid 与 capability 集合（受 {@code NoNewPrivs} 约束），
 * AIOS 的 SpawnPrivilegeContext 同理：spawn 子 agent 时子继承父的有效权限画像，且永不递增。
 * <p>
 * <b>线程模型</b>：{@link InheritableThreadLocal}，虚拟线程自动继承（与
 * {@code com.ouisani.aios.core.ipc.CallerContext} / {@code TraceContext} 一致）。
 * {@code Thread.startVirtualThread} 创建的子虚拟线程在构造期继承父线程的值，
 * 故 {@code AgentTool} 同步 spawn 分支无需手动 activate（对比 {@code DelegationGuard}
 * 用普通 ThreadLocal 需手动 {@code activate}）。
 * <p>
 * <b>包位置选择</b>：本类置于 {@code core/permission} 而非 {@code core/ipc}，避免
 * {@code core/ipc.CallerContext} 承载 {@code PermissionProfile} 引入的 {@code core/ipc ↔ core/permission}
 * 包循环（{@code PermissionChecker} 已 import {@code core.ipc.TraceContext}）。tenantId 仍走
 * {@code CallerContext}（无循环）。
 * <p>
 * <b>set/clear 职责</b>：由 {@code QueryEngine} 在 tool 执行前 {@code set}（发布自身有效 profile）、
 * finally {@code clear}（防线程池复用泄漏）。内核守护进程不经此路径 → {@code current()} 返回 null
 * → {@code AgentTool} 按「无限制」处理（{@code PermissionProfile.empty()}）。
 *
 * @see PermissionChecker#currentProfile()
 * @see com.ouisani.aios.core.tool.AgentTool
 */
public final class SpawnPrivilegeContext {

    private static final InheritableThreadLocal<PermissionProfile> CURRENT = new InheritableThreadLocal<>();

    private SpawnPrivilegeContext() {
    }

    /**
     * 获取当前线程的有效权限画像；内核守护进程 / headless / 直测未 set 时返回 null。
     * <p>
     * 返回 null 表示「无限制可继承」——调用方应归一化为 {@link PermissionProfile#empty()}。
     */
    public static PermissionProfile current() {
        return CURRENT.get();
    }

    /**
     * 设置当前线程的有效权限画像。由 {@code QueryEngine} 在 tool 执行前调用。
     * <p>
     * {@code profile=null} 时 {@code remove}（而非 set null），避免 null 影子覆盖子线程
     * 本应继承的父线程值（{@code InheritableThreadLocal} 对 null 仍会建立继承快照）。
     * {@code PermissionProfile.empty()} 视为「无限制」，原样存储——下游
     * {@link PermissionChecker#currentProfile()} 已对 empty 归一化为 null，二者一致。
     */
    public static void set(PermissionProfile profile) {
        if (profile == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(profile);
        }
    }

    /** 清除当前线程的有效权限画像。由 {@code QueryEngine} 在 tool 执行后 finally 调用，防线程池泄漏。 */
    public static void clear() {
        CURRENT.remove();
    }
}
