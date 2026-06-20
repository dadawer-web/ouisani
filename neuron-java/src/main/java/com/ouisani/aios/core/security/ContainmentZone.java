package com.ouisani.aios.core.security;

import java.util.Set;

/**
 * 隔离区枚举 — VFS 路径的物理级安全边界。
 * <p>
 * 借鉴 PAI (Personal AI Infrastructure) 的 Containment Zones 设计，
 * 将 VFS 命名空间划分为硬性隔离区域，每个区域有明确的访问策略。
 * Agent 的 ImpersonationContext 决定它能访问哪些区域。
 *
 * <h3>区域定义</h3>
 * <ul>
 *   <li>{@link #SYSTEM} — 只读，存放配置文件和系统 Prompt</li>
 *   <li>{@link #MEMORY} — 记忆碎片区，挂载给 MemoryProvider</li>
 *   <li>{@link #WORK} — 任务临时涂鸦区，Agent 可读写</li>
 *   <li>{@link #SECRETS} — API Key 存放区，绝对禁止 Agent 读取</li>
 * </ul>
 *
 * <h3>OS 类比: Linux Mount Namespace + SELinux MLS</h3>
 * 类似 SELinux 的类型强制 (Type Enforcement)，每个路径属于一个 zone，
 * 每个令牌有允许访问的 zone 集合。跨 zone 访问被内核拦截。
 *
 * @see ContainmentZoneManager
 * @see ImpersonationContext
 */
public enum ContainmentZone {

    /** 系统区 — 只读，存放配置文件和系统 Prompt。写入需要管理员级别。 */
    SYSTEM(Set.of("/system/", "/proc/", "/sys/"), true, false, false),

    /** 记忆区 — 存放记忆碎片，挂载给 MemoryProvider。读取允许，写入需要系统级别。 */
    MEMORY(Set.of("/memory/", "/var/db/memory/", "/dev/vec_mem", "/dev/graph_mem", "/dev/semantic"), true, true, false),

    /** 工作区 — 任务的临时涂鸦区，Agent 可读写。 */
    WORK(Set.of("/workspace/", "/tmp/", "/containers/"), true, true, false),

    /** 密钥区 — 存放 API Key，绝对禁止 Agent 读取。仅内核级令牌可访问。 */
    SECRETS(Set.of("/secrets/"), false, false, false);

    private final Set<String> pathPrefixes;
    private final boolean defaultReadable;
    private final boolean defaultWritable;
    private final boolean defaultExecutable;

    ContainmentZone(Set<String> pathPrefixes, boolean defaultReadable,
                    boolean defaultWritable, boolean defaultExecutable) {
        this.pathPrefixes = pathPrefixes;
        this.defaultReadable = defaultReadable;
        this.defaultWritable = defaultWritable;
        this.defaultExecutable = defaultExecutable;
    }

    /** 该 zone 匹配的 VFS 路径前缀集合 */
    public Set<String> pathPrefixes() { return pathPrefixes; }

    /** 默认是否允许读取 */
    public boolean isDefaultReadable() { return defaultReadable; }

    /** 默认是否允许写入 */
    public boolean isDefaultWritable() { return defaultWritable; }

    /** 默认是否允许执行 */
    public boolean isDefaultExecutable() { return defaultExecutable; }

    /**
     * 判断给定路径是否属于此 zone。
     * 路径会被标准化（去除 ./ 和 ../）后与前缀匹配。
     *
     * @param path VFS 路径
     * @return true 如果路径属于此 zone
     */
    public boolean matches(String path) {
        if (path == null || path.isBlank()) return false;
        String normalized = path.startsWith("/") ? path : "/" + path;
        for (String prefix : pathPrefixes) {
            if (normalized.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * 根据路径查找所属 zone。
     *
     * @param path VFS 路径
     * @return 匹配的 zone，无匹配返回 null（表示自由区域）
     */
    public static ContainmentZone forPath(String path) {
        if (path == null || path.isBlank()) return null;
        for (ContainmentZone zone : values()) {
            if (zone.matches(path)) return zone;
        }
        return null;
    }
}
