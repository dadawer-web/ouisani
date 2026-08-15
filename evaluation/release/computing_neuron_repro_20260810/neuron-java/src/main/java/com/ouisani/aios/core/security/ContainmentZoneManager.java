package com.ouisani.aios.core.security;

import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 隔离区访问管理器 — 物理级数据流动边界控制。
 * <p>
 * 借鉴 PAI 的 ContainmentGuard.hook.ts，在文件读写操作前检查
 * 当前 Agent 的 {@link SecurityToken} 是否有权访问目标路径所属的
 * {@link ContainmentZone}。
 *
 * <h3>访问策略</h3>
 * <table>
 *   <tr><th>Zone</th><th>Guest(3)</th><th>User(2)</th><th>Admin(1)</th><th>Kernel(0)</th></tr>
 *   <tr><td>SYSTEM</td><td>读</td><td>读</td><td>读写</td><td>读写</td></tr>
 *   <tr><td>MEMORY</td><td>读</td><td>读</td><td>读写</td><td>读写</td></tr>
 *   <tr><td>WORK</td><td>读写</td><td>读写</td><td>读写</td><td>读写</td></tr>
 *   <tr><td>SECRETS</td><td>拒绝</td><td>拒绝</td><td>拒绝</td><td>读写</td></tr>
 * </table>
 *
 * <h3>OS 类比: SELinux Type Enforcement</h3>
 * 类似 SELinux 的类型强制：每个路径属于一个类型(zone)，
 * 每个令牌有允许访问的类型集合。跨类型访问被内核拦截。
 *
 * @see ContainmentZone
 * @see ImpersonationContext
 * @see SecurityToken
 */
public final class ContainmentZoneManager {

    private static final Logger log = LoggerFactory.getLogger(ContainmentZoneManager.class);

    private static final ContainmentZoneManager INSTANCE = new ContainmentZoneManager();

    private ContainmentZoneManager() {}

    public static ContainmentZoneManager instance() { return INSTANCE; }

    /**
     * 文件操作类型。
     */
    public enum Operation { READ, WRITE, EXECUTE }

    /**
     * 检查当前线程的 SecurityToken 是否有权对指定路径执行指定操作。
     * <p>
     * 如果路径不属于任何 zone（自由区域），总是允许。
     * 如果路径属于某个 zone，按访问策略矩阵检查。
     *
     * @param path      VFS 路径
     * @param operation 操作类型 (READ/WRITE/EXECUTE)
     * @return true 如果允许访问
     */
    public boolean checkAccess(String path, Operation operation) {
        ContainmentZone zone = ContainmentZone.forPath(path);
        if (zone == null) {
            return true; // 自由区域，允许访问
        }

        SecurityToken token = SecurityToken.getEffective();
        int level = (token != null) ? token.privilegeLevel() : 3; // 无令牌视为访客

        boolean allowed = isAllowed(zone, operation, level);

        if (!allowed) {
            String agentId = (token != null) ? token.ownerId() : "anonymous";
            String msg = String.format("Zone access denied: agent=%s, zone=%s, op=%s, path=%s, level=%d",
                    agentId, zone, operation, path, level);
            log.warn("[Containment] {}", msg);
            SemanticEtw.getInstance().logEvent("SECURITY", "ZONE_ACCESS_DENIED",
                    "agent=" + agentId + " zone=" + zone + " op=" + operation
                            + " path=" + path + " level=" + level);
        }

        return allowed;
    }

    /**
     * 检查访问权限，不允许时抛出 SecurityException。
     *
     * @param path      VFS 路径
     * @param operation 操作类型
     * @throws SecurityException 如果访问被拒绝
     */
    public void enforceAccess(String path, Operation operation) throws SecurityException {
        if (!checkAccess(path, operation)) {
            SecurityToken token = SecurityToken.getEffective();
            ContainmentZone zone = ContainmentZone.forPath(path);
            String agentId = (token != null) ? token.ownerId() : "anonymous";
            throw new SecurityException(String.format(
                    "Containment Zone violation: agent '%s' cannot %s zone %s at path '%s'",
                    agentId, operation, zone, path));
        }
    }

    /**
     * 访问策略矩阵实现。
     * <p>
     * SECRETS zone: 仅 level=0 (kernel) 可访问。
     * SYSTEM zone: 读允许 level<=2，写需要 level<=1。
     * MEMORY zone: 读允许 level<=2，写需要 level<=1。
     * WORK zone: 读写允许所有 level。
     */
    private boolean isAllowed(ContainmentZone zone, Operation operation, int level) {
        return switch (zone) {
            case SECRETS -> level == 0; // 仅内核可访问

            case SYSTEM -> switch (operation) {
                case READ -> level <= 2;       // user 及以上可读
                case WRITE -> level <= 1;      // admin 及以上可写
                case EXECUTE -> level <= 1;    // admin 及以上可执行
            };

            case MEMORY -> switch (operation) {
                case READ -> level <= 2;       // user 及以上可读
                case WRITE -> level <= 1;      // admin 及以上可写
                case EXECUTE -> false;         // 记忆区不可执行
            };

            case WORK -> switch (operation) {
                case READ -> true;             // 所有 level 可读
                case WRITE -> true;            // 所有 level 可写
                case EXECUTE -> level <= 2;    // user 及以上可执行
            };
        };
    }
}
