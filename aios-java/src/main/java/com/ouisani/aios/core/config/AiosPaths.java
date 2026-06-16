package com.ouisani.aios.core.config;

/**
 * AIOS 路径配置中心 — 消除所有硬编码物理路径。
 * <p>
 * 所有路径通过以下优先级解析：
 * <ol>
 *   <li>环境变量（如 {@code AIOS_HOME}）</li>
 *   <li>系统属性（如 {@code aios.home}）</li>
 *   <li>默认值（基于用户主目录）</li>
 * </ol>
 * <p>
 * Agent 眼里只能看到 VFS 虚拟路径（如 {@code /home/agent/}），
 * 物理路径转换在 VFS 挂载点底层完成。
 * <p>
 * OS 类比：相当于 Linux 的 {@code /etc/fstab} — 定义挂载点映射，
 * 用户态程序只看到挂载后的虚拟路径。
 *
 * @see com.ouisani.aios.core.VfsManager
 */
public final class AiosPaths {

    private AiosPaths() {}

    // ════════════════════════════════════════════════════════════════
    //  AIOS_HOME — 根路径
    // ════════════════════════════════════════════════════════════════

    /**
     * AIOS 安装根目录。
     * <p>
     * 解析优先级：{@code AIOS_HOME} 环境变量 → {@code aios.home} 系统属性 → {@code ~/aios-java}
     */
    public static String aiosHome() {
        String home = envOrProp("AIOS_HOME", "aios.home");
        if (home != null && !home.isEmpty()) return home;
        return System.getProperty("user.home") + "/aios-java";
    }

    // ════════════════════════════════════════════════════════════════
    //  工作区路径
    // ════════════════════════════════════════════════════════════════

    /** 工作区根目录 */
    public static String workspaces() {
        return resolve("AIOS_WORKSPACES", "aios.workspaces", aiosHome() + "/workspaces");
    }

    /** 技能库目录 */
    public static String skillsDir() {
        return resolve("AIOS_SKILLS_DIR", "aios.skills.dir", aiosHome() + "/aios_skills");
    }

    /** 角色卡目录 */
    public static String rolesDir() {
        return resolve("AIOS_ROLES_DIR", "aios.roles.dir", aiosHome() + "/aios_roles");
    }

    /** OpenClaw 插件目录 */
    public static String openclawPluginsDir() {
        return resolve("AIOS_OPENCLAW_PLUGINS", "aios.openclaw.plugins", aiosHome() + "/openclaw_plugins");
    }

    /** OpenClaw 会话目录 */
    public static String openclawSessionsDir() {
        return resolve("AIOS_OPENCLAW_SESSIONS", "aios.openclaw.sessions", aiosHome() + "/openclaw_sessions");
    }

    // ════════════════════════════════════════════════════════════════
    //  系统路径
    // ════════════════════════════════════════════════════════════════

    /** 插件安装目录 */
    public static String pluginsDir() {
        return resolve("AIOS_PLUGINS_DIR", "aios.plugins.dir", "/opt/aios/plugins");
    }

    /** .env 配置文件路径 */
    public static String envFile() {
        return resolve("AIOS_ENV_FILE", "aios.env.file",
                System.getProperty("user.home") + "/.aios/.env");
    }

    /** WASI sysroot 路径 */
    public static String wasiSysroot() {
        return resolve("AIOS_WASI_SYSROOT", "aios.wasi.sysroot", "/opt/wasi-sysroot");
    }

    // ════════════════════════════════════════════════════════════════
    //  临时/缓存路径 — 使用 java.io.tmpdir
    // ════════════════════════════════════════════════════════════════

    /** 系统临时目录（基于 java.io.tmpdir） */
    public static String tmpDir() {
        return System.getProperty("java.io.tmpdir", "/tmp");
    }

    /** AIOS 临时子目录 */
    public static String aiosTmp() {
        return tmpDir() + "/aios";
    }

    /** VFS WAL 日志路径 */
    public static String vfsJournal() {
        return aiosTmp() + "/vfs.journal";
    }

    /** 追踪数据目录 */
    public static String traceDir() {
        return aiosTmp() + "/trace";
    }

    /** 沙箱脚本临时文件 */
    public static String sandboxScript() {
        return aiosTmp() + "/agent_script.py";
    }

    // ════════════════════════════════════════════════════════════════
    //  持久化路径
    // ════════════════════════════════════════════════════════════════

    /** 巨石状态目录 */
    public static String bouldersDir() {
        return resolve("AIOS_BOULDERS_DIR", "aios.boulders.dir",
                System.getProperty("user.home") + "/.aios/boulders");
    }

    /** 交换分区目录 */
    public static String swapDir() {
        return resolve("AIOS_SWAP_DIR", "aios.swap.dir", aiosHome() + "/var/swap");
    }

    /** 快照目录 */
    public static String snapshotDir() {
        return resolve("AIOS_SNAPSHOT_DIR", "aios.snapshot.dir", aiosHome() + "/var/snapshot");
    }

    /** JIT 编译缓存目录 */
    public static String jitCacheDir() {
        return resolve("AIOS_JIT_CACHE", "aios.jit.cache", aiosHome() + "/var/cache/jit");
    }

    /** 记忆持久化目录 */
    public static String memoryDbDir() {
        return resolve("AIOS_MEMORY_DB", "aios.memory.db", aiosHome() + "/var/db/memory");
    }

    /** 崩溃诊断目录 */
    public static String crashDir() {
        return resolve("AIOS_CRASH_DIR", "aios.crash.dir", aiosHome() + "/var/crash");
    }

    // ════════════════════════════════════════════════════════════════
    //  VFS 虚拟路径 — Agent 可见的路径
    // ════════════════════════════════════════════════════════════════

    /** Agent 工作目录（VFS 虚拟路径，不等于物理路径） */
    public static final String AGENT_HOME = "/home/agent/";

    /** Agent 只读系统目录 */
    public static final String AGENT_PROC = "/proc/";

    /** Agent 设备目录 */
    public static final String AGENT_DEV = "/dev/";

    // ── 内部工具方法 ──

    private static String resolve(String envKey, String propKey, String defaultValue) {
        String val = envOrProp(envKey, propKey);
        return (val != null && !val.isEmpty()) ? val : defaultValue;
    }

    private static String envOrProp(String envKey, String propKey) {
        String val = System.getenv(envKey);
        if (val != null && !val.isEmpty()) return val;
        val = System.getProperty(propKey);
        return (val != null && !val.isEmpty()) ? val : null;
    }
}
