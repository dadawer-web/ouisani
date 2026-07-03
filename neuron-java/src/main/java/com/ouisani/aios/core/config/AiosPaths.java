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
        // 尝试基于项目目录自动检测
        String projectDir = detectProjectRoot();
        if (projectDir != null) return projectDir;
        return System.getProperty("user.home") + "/aios-java";
    }

    /**
     * 自动检测项目根目录：从 classpath 向上查找包含 pom.xml 的目录。
     */
    private static String detectProjectRoot() {
        // 优先检查当前工作目录
        String cwd = System.getProperty("user.dir");
        if (cwd != null && java.nio.file.Files.exists(java.nio.file.Path.of(cwd, "pom.xml"))) {
            return cwd;
        }
        // 检查父目录
        if (cwd != null) {
            java.nio.file.Path parent = java.nio.file.Path.of(cwd).getParent();
            if (parent != null && java.nio.file.Files.exists(parent.resolve("pom.xml"))) {
                return parent.toString();
            }
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    //  工作区路径
    // ════════════════════════════════════════════════════════════════

    /** 工作区根目录 */
    public static String workspaces() {
        return resolve("AIOS_WORKSPACES", "aios.workspaces", aiosHome() + "/workspaces");
    }

    /**
     * 为指定工作流生成集装箱目录路径。
     * <p>
     * 目录命名规则：{@code {timestamp}_{safeName}}，其中：
     * <ul>
     *   <li>timestamp — 精确到秒的时间戳（如 "20260617_143025"），保证唯一性和可排序性</li>
     *   <li>safeName — workflowName 的安全化版本（保留中文、字母数字和下划线，最长 40 字符）</li>
     * </ul>
     * <p>
     * 示例：{@code workspaces/20260617_143025_搜索2026大模型趋势}
     * <p>
     * 同时在目录下写入 {@code task.meta} 文件，记录原始任务名、创建时间等元信息。
     *
     * @param workflowId   工作流唯一标识（UUID 或时间戳）
     * @param workflowName 工作流名称（可能包含中文和特殊字符）
     * @return 集装箱目录的绝对路径
     */
    public static String workspaceForWorkflow(String workflowId, String workflowName) {
        // 1. 生成时间戳前缀：yyyyMMdd_HHmmss 格式，可排序且人类可读
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        String timestamp = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        // 2. 安全化 workflowName：保留中文、字母数字和下划线，去掉文件系统不安全字符
        String safeName = workflowName
                .replaceAll("[\\\\/:*?\"<>|]", "")      // 去掉文件系统非法字符
                .replaceAll("\\s+", "_")                   // 空格替换为下划线
                .replaceAll("_+", "_")                     // 合并连续下划线
                .replaceAll("^_|_$", "");                  // 去掉首尾下划线

        // 3. 截断到 40 字符，避免目录名过长
        if (safeName.length() > 40) {
            safeName = safeName.substring(0, 40);
        }

        // 4. 如果安全化后为空，使用默认名
        if (safeName.isEmpty()) {
            safeName = "workflow";
        }

        String dirPath = workspaces() + "/" + timestamp + "_" + safeName;

        // 5. 写入 task.meta 元信息文件，记录原始任务名和创建时间
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(dirPath);
            java.nio.file.Files.createDirectories(dir);
            String metaContent = "# AIOS Task Metadata\n"
                    + "workflowId: " + workflowId + "\n"
                    + "workflowName: " + workflowName + "\n"
                    + "createdAt: " + now.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "\n"
                    + "directory: " + dirPath + "\n";
            java.nio.file.Files.writeString(dir.resolve("task.meta"), metaContent);
        } catch (java.io.IOException e) {
            // 写入失败不阻断主流程
            System.err.println("[AiosPaths] task.meta 写入失败: " + e.getMessage());
        }

        return dirPath;
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
        String envPath = resolve("AIOS_ENV_FILE", "aios.env.file", null);
        if (envPath != null) return envPath;
        // 优先查找项目根目录下的 .env
        String home = aiosHome();
        if (java.nio.file.Files.exists(java.nio.file.Path.of(home, ".env"))) {
            return home + "/.env";
        }
        return System.getProperty("user.home") + "/.aios/.env";
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

    /** Overnight 长跑运行目录 — manifest 与任务卡片的 VFS 持久化根路径 */
    public static String overnightDir() {
        return resolve("AIOS_OVERNIGHT_DIR", "aios.overnight.dir", aiosHome() + "/var/run/overnight");
    }

    /** VersionedPlan 持久化目录 — 镜像 jcode swarm_persistence state_dir */
    public static String planDir() {
        return resolve("AIOS_PLAN_DIR", "aios.plan.dir", aiosHome() + "/var/run/plan");
    }

    /** 本地 ONNX 模型目录 — 镜像 jcode ~/.jcode/models/all-MiniLM-L6-v2（含 model.onnx + tokenizer.json） */
    public static String modelsDir() {
        return resolve("AIOS_MODELS_DIR", "aios.models.dir",
                aiosHome() + "/var/models/all-MiniLM-L6-v2");
    }

    /** 安全裁决持久化目录 — 镜像 jcode ~/.jcode/safety（queue.json + history.json） */
    public static String safetyDir() {
        return resolve("AIOS_SAFETY_DIR", "aios.safety.dir", aiosHome() + "/var/safety");
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
