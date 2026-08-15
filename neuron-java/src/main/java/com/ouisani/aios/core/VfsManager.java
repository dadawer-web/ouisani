package com.ouisani.aios.core;

import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.drivers.llm.OpenAiAdapter;
import com.ouisani.aios.vfs.MutableFileNode;
import com.ouisani.aios.vfs.ProcFsNode;
import com.ouisani.aios.vfs.SemanticNode;
import com.ouisani.aios.vfs.VectorNode;
import com.ouisani.aios.vfs.GraphNode;
import com.ouisani.aios.vfs.CameraNode;
import com.ouisani.aios.vfs.DisplayNode;
import com.ouisani.aios.vfs.HttpNode;
import com.ouisani.aios.vfs.WebhookNode;
import com.ouisani.aios.vfs.AudioNode;
import com.ouisani.aios.vfs.HostSourceNode;
import com.ouisani.aios.vfs.ShmNode;
import com.ouisani.aios.vfs.RegistryFsNode;
import com.ouisani.aios.vfs.GuiDomNode;
import com.ouisani.aios.vfs.GuiActionNode;
import com.ouisani.aios.vfs.RemoteDeviceMountNode;
import com.ouisani.aios.vfs.DesktopNotifyNode;
import com.ouisani.aios.vfs.ChromeBridgeNode;
import com.ouisani.aios.vfs.IndexNode;
import com.ouisani.aios.vfs.OverlayNode;
import com.ouisani.aios.core.vfs.VfsJournal;
import com.ouisani.aios.core.vfs.VfsLockManager;
import com.ouisani.aios.core.vfs.FileAccessRecorder;
import com.ouisani.aios.core.security.VfsRateLimiter;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 虚拟文件系统管理器 — AIOS 的 VFS 层，统一管理所有虚拟设备、文件和命名空间。
 * <p>
 * 类比 Linux VFS（Virtual File System）：将语义搜索、向量记忆、图形记忆、
 * 摄像头、显示器、网络等异构资源抽象为统一的文件节点（VfsNode），
 * 通过路径树（pathTree）提供 mount/resolve/read/write 等标准文件操作。
 * <p>
 * 支持 Agent 命名空间隔离（类比 Linux mount namespace）、
 * VSS 快照（类比 Windows Volume Shadow Copy）、WAL 日志恢复、
 * 远程设备动态挂载等特性。
 */
public final class VfsManager {

    private static final Logger log = LoggerFactory.getLogger(VfsManager.class);

    /** Agent 根路径绑定 — 类比 Linux 的 chroot，每个 Agent 线程可绑定独立的 VFS 根 */
    public static final ThreadLocal<String> AGENT_ROOT = ThreadLocal.withInitial(() -> "/");

    private static final class Holder {
        static final VfsManager INSTANCE = new VfsManager();
    }

    /** 单例获取 */
    public static VfsManager instance() {
        return Holder.INSTANCE;
    }

    /** 读写锁 — 保护路径树的并发访问，读多写少场景 */
    final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    /** 路径树 — VFS 核心数据结构，绝对路径 → VfsNode 映射 */
    final Map<String, VfsNode> pathTree = new ConcurrentHashMap<>();
    /** Agent 命名空间映射 — agentId → VFS 根路径 */
    private final Map<Integer, String> agentNamespaces = new ConcurrentHashMap<>();
    /** 物理工作目录映射 — VFS 路径前缀 → 物理目录路径，用于将 VFS 写入桥接到物理磁盘 */
    private final Map<String, String> physicalWorkspaceMap = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;
    private volatile LlmProvider defaultLlmProvider;
    private volatile TaskScheduler taskScheduler;
    private volatile Javalin javalinApp;

    // ── 磁盘降级模式标志（借鉴 Langflow Noop 设计） ──
    private volatile boolean diskDegraded = false;

    // ── 文件访问记录器（注入式，借鉴 CompactCutoffGuard 注入模式） ──
    // null 时所有 hook 跳过，零回归
    private volatile FileAccessRecorder fileAccessRecorder;

    public boolean isDiskDegraded() { return diskDegraded; }

    /** 注入文件访问记录器；传 null 关闭 hook */
    public void configureFileAccessRecorder(FileAccessRecorder recorder) {
        this.fileAccessRecorder = recorder;
        log.info("FileAccessRecorder 已配置: {}", recorder == null ? "<disabled>" : "enabled");
    }

    private VfsManager() {
    }

    public void configureLlmProvider(LlmProvider provider) {
        this.defaultLlmProvider = provider;
        log.info("LlmProvider 已配置: {}", provider.name());
    }

    public void configureTaskScheduler(TaskScheduler scheduler) {
        this.taskScheduler = scheduler;
        log.info("TaskScheduler 已配置，用于 /proc 文件系统");
    }

    /**
     * 获取 VFS 路径锁管理器 — 提供树状细粒度文件锁，防止多 Agent 并发冲突。
     * <p>
     * 借鉴 Apix 的 {@code file_system_manager.py}，支持：
     * <ul>
     *   <li>祖先破坏性锁检测（DELETE/MOVE 阻塞后代）</li>
     *   <li>后代锁检测（DELETE/MOVE 等待后代解锁）</li>
     *   <li>多文件有序加锁（避免 AB-BA 死锁）</li>
     * </ul>
     * <p>
     * 用法：
     * <pre>{@code
     * try (var lock = VfsManager.instance().lockManager().fileLock(
     *         "/factory/app.py", "agent_1", VfsLockManager.LockEvent.MODIFY)) {
     *     VfsManager.instance().writeText("/factory/app.py", content);
     * }
     * }</pre>
     *
     * @return VFS 锁管理器单例
     * @see VfsLockManager
     */
    public VfsLockManager lockManager() {
        return VfsLockManager.instance();
    }

    /**
     * 注册物理工作目录映射 — 将 VFS 路径前缀绑定到物理磁盘目录。
     * <p>
     * 注册后，所有通过 writeText 写入该前缀下的新文件，
     * 将自动创建 HostSourceNode 并写入物理磁盘，而非内存中的 MutableFileNode。
     * 类比 Linux 的 bind mount：mount --bind /physical/dir /vfs/dir
     * <p>
     * 支持按 workflowId 隔离：使用 {@code /factory/{workflowId}} 前缀，
     * 不同工作流的文件映射到各自独立的物理目录，避免互相覆盖。
     *
     * @param vfsPrefix    VFS 路径前缀（如 "/factory" 或 "/factory/wf_a1b2c3d4"）
     * @param physicalDir  物理磁盘目录（如 "/home/user/workspaces/wf_a1b2c3d4_name/factory"）
     */
    public void registerPhysicalWorkspace(String vfsPrefix, String physicalDir) {
        physicalWorkspaceMap.put(vfsPrefix, physicalDir);
        log.info("[VFS] 物理工作目录已注册: {} → {}", vfsPrefix, physicalDir);
        System.out.printf("  🔗 [VFS] 物理工作空间: " + vfsPrefix + " → " + physicalDir + "%n");
    }

    /**
     * 注销物理工作目录映射 — 工作流结束后清理，防止映射泄漏。
     *
     * @param vfsPrefix 要注销的 VFS 路径前缀
     */
    public void unregisterPhysicalWorkspace(String vfsPrefix) {
        String removed = physicalWorkspaceMap.remove(vfsPrefix);
        if (removed != null) {
            log.info("[VFS] 物理工作目录已注销: {} → {}", vfsPrefix, removed);
        }
    }

    /**
     * 查找 VFS 路径对应的物理工作目录。
     * <p>
     * 优先匹配最长前缀（最具体的映射），确保按 workflowId 隔离的映射
     * 优先于全局映射。例如 {@code /factory/wf_a1b2c3d4} 优先于 {@code /factory}。
     *
     * @param vfsPath VFS 路径（如 "/factory/wf_a1b2c3d4/agent_1.py"）
     * @return 物理目录路径，如果没有映射则返回 null
     */
    public String findPhysicalWorkspace(String vfsPath) {
        String bestMatch = null;
        String bestPrefix = "";
        for (Map.Entry<String, String> entry : physicalWorkspaceMap.entrySet()) {
            String prefix = entry.getKey();
            if (vfsPath.startsWith(prefix) && prefix.length() > bestPrefix.length()) {
                bestPrefix = prefix;
                bestMatch = entry.getValue();
            }
        }
        return bestMatch;
    }

    /**
     * 将命令字符串中的 VFS 虚拟路径前缀翻译为宿主机物理路径。
     * <p>
     * 用于 BashTool 等宿主机执行场景：LLM 经 VFS 写文件（如 {@code /factory/agent_1.py}），
     * 但 bash 在宿主机执行时 {@code /factory} 不存在，必须翻译为物理目录才能找到文件。
     * <p>
     * 按注册前缀长度降序匹配（最具体的优先，如 {@code /factory/wf_xxx} 优先于 {@code /factory}），
     * 仅替换作为独立路径起点的前缀（前缀前不能是 {@code \w - .} 字符，避免 {@code /factoryX}、
     * {@code my_factory} 等误匹配）。正则风格与 {@code AiosAppManager.applyKernelPathTranslation}
     * 保持一致（已生产验证）。
     *
     * @param command 含 VFS 路径的命令字符串（如 {@code python3 -u /factory/agent_1.py}）
     * @return 翻译后的命令；无映射或无匹配时原样返回
     */
    public String translateVfsPathsInCommand(String command) {
        if (command == null || command.isEmpty() || physicalWorkspaceMap.isEmpty()) {
            return command;
        }
        // 按前缀长度降序，先匹配最具体的（/factory/wf_xxx 优先于 /factory）
        List<String> prefixes = new ArrayList<>(physicalWorkspaceMap.keySet());
        prefixes.sort((a, b) -> Integer.compare(b.length(), a.length()));

        String result = command;
        for (String prefix : prefixes) {
            String physical = physicalWorkspaceMap.get(prefix);
            if (physical == null || physical.isEmpty()) continue;
            // Shell commands use POSIX syntax.  On Windows the workspace is
            // stored with backslashes, which Git Bash would interpret as
            // escapes instead of path separators.
            String physicalForShell = System.getProperty("os.name", "")
                    .toLowerCase(Locale.ROOT).contains("win")
                    ? physical.replace('\\', '/')
                    : physical;
            // 仅替换作为独立路径起点的前缀：
            //   - 前缀前不能是 \w - . 字符（避免 my_factory、x/factory 误匹配）
            //   - 前缀后必须跟 / 、结尾或非路径字符（避免 /factoryX 被替换成 {PHYSICAL}X 破坏路径）
            String regex = "(?<![\\w\\-\\.])" + java.util.regex.Pattern.quote(prefix) + "(?=/|$|[^\\w/.-])";
            result = java.util.regex.Pattern.compile(regex)
                    .matcher(result)
                    .replaceAll(java.util.regex.Matcher.quoteReplacement(physicalForShell));
        }
        return result;
    }

    public TaskScheduler getTaskScheduler() {
        return taskScheduler;
    }

    public void configureJavalin(Javalin app) {
        this.javalinApp = app;
        log.info("Javalin 已配置，用于 Webhook 端点");
    }

    public LlmProvider getLlmProvider() {
        return defaultLlmProvider;
    }

    /**
     * 初始化 VFS 根文件系统 — 类比 Linux 的 mount_root_fs()。
     * <p>
     * 创建标准目录结构（/bin, /dev, /mem, /proc, /tmp, /containers, /var），
     * 挂载语义设备（/dev/semantic）、向量记忆（/dev/vec_mem）、图形记忆（/dev/graph_mem）、
     * 虚拟硬件（摄像头、显示器、音频）、网络设备、共享内存等。
     * 最后打开 WAL 日志并执行崩溃恢复。
     */
    public void init() {
        rwLock.writeLock().lock();
        try {
            if (initialized) return;

            pathTree.put("/", new VfsNode.DirectoryNode("/"));
            mountDirectory("/bin");
            mountDirectory("/dev");
            mountDirectory("/mem");
            mountDirectory("/proc");
            mountDirectory("/tmp");
            mountDirectory("/containers");
            mountDirectory("/var");
            mountDirectory("/var/crash");
            mountDirectory("/var/db");

            if (defaultLlmProvider != null) {
                SemanticNode semanticNode = new SemanticNode("/dev/semantic", defaultLlmProvider);
                pathTree.put("/dev/semantic", semanticNode);
                log.info("VFS 已挂载: /dev/semantic [SEMANTIC] provider={}", defaultLlmProvider.name());

                VectorNode vectorNode = new VectorNode("/dev/vec_mem", defaultLlmProvider);
                pathTree.put("/dev/vec_mem", vectorNode);
                log.info("VFS 已挂载: /dev/vec_mem [VECTOR] provider={}", defaultLlmProvider.name());

                // ── Persistent Long-Term Memory (Dream Daemon target) ──
                VectorNode memoryDb = new VectorNode("/var/db/memory", defaultLlmProvider);
                pathTree.put("/var/db/memory", memoryDb);
                log.info("VFS 已挂载: /var/db/memory [VECTOR] 持久化长期记忆 (Dream Daemon 目标)");

                GraphNode graphNode = new GraphNode("/dev/graph_mem", defaultLlmProvider);
                pathTree.put("/dev/graph_mem", graphNode);
                log.info("VFS 已挂载: /dev/graph_mem [GRAPH] provider={}", defaultLlmProvider.name());
            } else {
                log.warn("未配置 LlmProvider，/dev/semantic、/dev/vec_mem 和 /dev/graph_mem 未挂载");
            }

            if (taskScheduler != null) {
                pathTree.put("/proc/agents", ProcFsNode.agents(taskScheduler));
                log.info("VFS 已挂载: /proc/agents [PROCFS] 动态 Agent 列表");
            } else {
                log.warn("未配置 TaskScheduler，/proc/agents 未挂载");
            }

            pathTree.put("/proc/cgroups", ProcFsNode.cgroups());
            log.info("VFS 已挂载: /proc/cgroups [PROCFS] 动态 cgroup 树");

            // ── Semantic Registry ──
            pathTree.put("/proc/registry", new RegistryFsNode("/proc/registry"));
            log.info("VFS 已挂载: /proc/registry [REGISTRY] 全局语义注册表");

            // ── Virtual hardware devices ──
            pathTree.put("/dev/camera0", new CameraNode("/dev/camera0"));
            log.info("VFS 已挂载: /dev/camera0 [CAMERA] 只读虚拟摄像头");

            pathTree.put("/dev/display0", new DisplayNode("/dev/display0"));
            log.info("VFS 已挂载: /dev/display0 [DISPLAY] 只写虚拟显示器");

            pathTree.put("/dev/audio0", new AudioNode("/dev/audio0"));
            log.info("VFS 已挂载: /dev/audio0 [AUDIO] 只写 TTS 音频设备");

            // ── GUI / Desktop Automation (OSWorld) ──
            mountDirectory("/dev/gui");

            pathTree.put("/dev/gui/dom", new GuiDomNode("/dev/gui/dom"));
            log.info("VFS 已挂载: /dev/gui/dom [GUI_DOM] 只读屏幕 UI 元素树");

            pathTree.put("/dev/gui/action", new GuiActionNode("/dev/gui/action"));
            log.info("VFS 已挂载: /dev/gui/action [GUI_ACTION] 只写桌面自动化");

            // ── Network devices ──
            mountDirectory("/dev/net");

            pathTree.put("/dev/net/http", new HttpNode("/dev/net/http"));
            log.info("VFS 已挂载: /dev/net/http [HTTP] 双向 HTTP 客户端");

            if (javalinApp != null) {
                pathTree.put("/dev/net/webhook_1", new WebhookNode("/dev/net/webhook_1", "1", javalinApp));
                log.info("VFS 已挂载: /dev/net/webhook_1 [WEBHOOK] POST /webhook/1");
            } else {
                log.warn("未配置 Javalin，/dev/net/webhook_1 未挂载");
            }

            // ── Shared Memory (SHM IPC) ──
            mountDirectory("/dev/shm");
            pathTree.put("/dev/shm/blackboard", new ShmNode("/dev/shm/blackboard", "blackboard"));
            log.info("VFS 已挂载: /dev/shm/blackboard [SHM] 共享内存黑板");

            // ── Remote Device Mount Point ──
            mountDirectory("/dev/remote");
            log.info("VFS 已挂载: /dev/remote [REMOTE_DEVICE] 动态远程设备挂载点");

            // ── Host Physical Layer (/dev/host/) ──
            mountDirectory("/dev/host");
            pathTree.put("/dev/host/notify", new DesktopNotifyNode("/dev/host/notify"));
            log.info("VFS 已挂载: /dev/host/notify [DEVICE] 原生桌面通知 (只写)");

            ChromeBridgeNode chromeBridge = new ChromeBridgeNode("/dev/host/browser");
            pathTree.put("/dev/host/browser", chromeBridge);
            log.info("VFS 已挂载: /dev/host/browser [DEVICE] Chrome 浏览器桥接 (WebSocket)");
            // 启动浏览器 WebSocket 桥接（端口 19999）
            if (javalinApp != null) {
                chromeBridge.startWebSocket(19999);
            }

            // ── WAL Journal: open and recover ──
            VfsJournal.getInstance().open();
            int replayed = VfsJournal.getInstance().recoverAll();
            if (replayed > 0) {
                log.info("[VFS Journal] 正在回放 {} 条 WAL 操作... 崩溃一致性已恢复！", replayed);
            }

            // ── VSS Shadow Copy directory ──
            mountDirectory("/shadow");
            log.info("VFS 已挂载: /shadow [VSS] 影子副本根目录");

            initialized = true;
            log.info("VFS 根文件系统已初始化: /, /bin, /dev, /mem, /proc, /tmp, /containers, /var/crash");
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * 解析 VFS 路径 — 类比 Linux 的 path_lookup()。
     * <p>
     * 根据路径和 Agent 根路径进行路径翻译和权限检查，返回对应的 VfsNode。
     *
     * @param path      请求的虚拟路径
     * @param agentRoot Agent 的 VFS 根路径（用于命名空间隔离）
     * @return 找到的 VfsNode，不存在则返回 empty
     */
    public Optional<VfsNode> resolve(String path, String agentRoot) {
        rwLock.readLock().lock();
        try {
            if (!initialized) {
                log.warn("VFS 未初始化");
                return Optional.empty();
            }

            String resolved = translatePath(path, agentRoot);
            if (resolved.isEmpty()) {
                return Optional.empty();
            }

            VfsNode node = pathTree.get(resolved);
            if (node != null) {
                log.trace("VFS 解析: '{}' -> '{}' [{}]", path, resolved, node.nodeType());
            } else {
                log.trace("VFS resolve: '{}' -> '{}' not found", path, resolved);
            }
            return Optional.ofNullable(node);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /** 使用当前线程的 AGENT_ROOT 解析路径 */
    public Optional<VfsNode> resolve(String path) {
        return resolve(path, AGENT_ROOT.get());
    }

    /**
     * 挂载 VFS 节点 — 类比 Linux mount() 系统调用。
     *
     * @param dirPath    父目录路径
     * @param name       节点名称
     * @param node       要挂载的 VfsNode
     * @param callerUid  调用者 UID（用于权限检查）
     * @return true 挂载成功，false 路径已存在
     */
    public boolean mount(String dirPath, String name, VfsNode node, int callerUid) {
        VfsRateLimiter.instance().checkWrite(dirPath + "/" + name); // 资源层限流：超限抛 SecurityException + 双写审计
        rwLock.writeLock().lock();
        try {
            if (!initialized) {
                log.warn("VFS 未初始化，无法挂载");
                return false;
            }

            String fullPath = dirPath.equals("/") ? "/" + name : dirPath + "/" + name;

            if (pathTree.containsKey(fullPath)) {
                log.warn("VFS 挂载失败: '{}' 已存在", fullPath);
                return false;
            }

            pathTree.put(fullPath, node);
            log.info("VFS 已挂载: {} [{}]", fullPath, node.nodeType());
            return true;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public boolean mount(String dirPath, String name, VfsNode node) {
        return mount(dirPath, name, node, 0);
    }

    /**
     * 将宿主机物理文件路径映射到 VFS 虚拟节点
     */
    public boolean mountHostFile(String vfsPath, String physicalPath) {
        rwLock.writeLock().lock();
        try {
            if (!initialized) {
                log.warn("VFS 未初始化，无法挂载宿主文件");
                return false;
            }

            if (pathTree.containsKey(vfsPath)) {
                log.warn("VFS 挂载失败: '{}' 已存在", vfsPath);
                return false;
            }

            HostSourceNode node = new HostSourceNode(vfsPath, physicalPath);
            pathTree.put(vfsPath, node);
            System.out.printf("  🔗 [VFS] 物理宿主路径 '%s' 已挂载至虚拟节点 '%s'%n", physicalPath, vfsPath);
            log.info("[VFS] 宿主文件已挂载: vfsPath='{}', physicalPath='{}'", vfsPath, physicalPath);
            return true;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * 卸载 VFS 节点 — 类比 Linux umount() 系统调用。
     *
     * @param path       要卸载的路径
     * @param callerUid  调用者 UID
     * @return true 卸载成功，false 路径不存在
     */
    public boolean unmount(String path, int callerUid) {
        rwLock.writeLock().lock();
        try {
            if (!pathTree.containsKey(path)) {
                log.warn("VFS 卸载失败: '{}' 未找到", path);
                return false;
            }

            pathTree.remove(path);
            log.info("VFS 已卸载: {}", path);
            return true;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public boolean unmount(String path) {
        return unmount(path, 0);
    }

    /**
     * Create a VSS (Volume Shadow Copy) snapshot of a VFS node.
     * <p>
     * Takes a frozen, read-only copy of the node at the given path and
     * mounts it under {@code /shadow/snap_<timestamp>/<original_path>}.
     * Other agents can then safely read the historical snapshot while
     * the original node continues to be read/written without interference.
     *
     * @param path the VFS path to snapshot (e.g. "/dev/graph0")
     * @return the shadow copy path (e.g. "/shadow/snap_20260603_143022/dev/graph0")
     */
    public String createVssSnapshot(String path) {
        var nodeOpt = resolve(path);
        if (nodeOpt.isEmpty()) {
            log.warn("[VSS] 快照失败: 路径 '{}' 未找到", path);
            return null;
        }

        VfsNode original = nodeOpt.get();
        VfsNode shadow = original.createShadowCopy();

        // Generate snapshot directory name with timestamp
        String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String snapDir = "/shadow/snap_" + timestamp;

        // Build shadow path: /shadow/snap_<ts>/<original_path>
        // e.g. /shadow/snap_20260603_143022/dev/graph0
        String shadowPath = snapDir + path;

        // Ensure the snapshot directory exists
        mountDirectory(snapDir);

        // Create intermediate directories matching the original path structure
        String[] segments = path.split("/");
        StringBuilder currentDir = new StringBuilder(snapDir);
        for (int i = 1; i < segments.length - 1; i++) {
            currentDir.append("/").append(segments[i]);
            mountDirectory(currentDir.toString());
        }

        // Mount the shadow node
        pathTree.put(shadowPath, shadow);

        log.info("[VSS] 影子副本已创建: '{}' → '{}' (冻结，只读)",
                path, shadowPath);

        return shadowPath;
    }

    /**
     * 路径翻译 — 类比 Linux 的 d_path()，将相对路径结合 Agent 根路径解析为绝对路径。
     * <p>
     * 包含路径净化（去除 . 和 ..）和越界检查（防止路径逃逸出 Agent 的 chroot）。
     *
     * @param path      原始路径
     * @param agentRoot Agent 的 VFS 根路径
     * @return 翻译后的绝对路径，越界则返回空字符串
     */
    public String translatePath(String path, String agentRoot) {
        if (agentRoot == null || agentRoot.equals("/") || agentRoot.isEmpty()) {
            return sanitizePath(path);
        }

        String clean = sanitizePath(path);
        if (clean.isEmpty()) return "";

        if (clean.charAt(0) != '/') return clean;

        String combined = agentRoot;
        if (!combined.endsWith("/")) combined += "/";
        combined += clean.substring(1);

        String canonicalized = sanitizePath(combined);

        if (!isWithinRoot(canonicalized, agentRoot)) {
            log.warn("路径逃逸已阻止: '{}' 逃逸出根目录 '{}' (解析为: '{}')", path, agentRoot, canonicalized);
            return "";
        }

        return canonicalized;
    }

    public String resolvePath(String path) {
        return translatePath(path, AGENT_ROOT.get());
    }

    /**
     * 创建容器命名空间 — 类比 Linux 的 clone(CLONE_NEWNS)。
     * <p>
     * 为指定 Agent 创建独立的 VFS 子树（/containers/agent_{id}/），
     * 包含独立的 /bin, /dev, /proc, /tmp, /dev/mem 目录。
     *
     * @param agentId Agent ID
     * @return true 创建成功或已存在
     */
    public boolean createContainerNamespace(int agentId) {
        rwLock.writeLock().lock();
        try {
            if (!initialized) return false;

            String agentDirName = "agent_" + agentId;
            String agentRoot = "/containers/" + agentDirName;

            if (agentNamespaces.containsKey(agentId)) {
                log.debug("容器命名空间已存在: {}", agentRoot);
                return true;
            }

            mountDirectory(agentRoot);
            mountDirectory(agentRoot + "/bin");
            mountDirectory(agentRoot + "/dev");
            mountDirectory(agentRoot + "/proc");
            mountDirectory(agentRoot + "/tmp");
            mountDirectory(agentRoot + "/dev/mem");

            agentNamespaces.put(agentId, agentRoot);
            log.info("挂载命名空间已创建: {} (CLONE_NEWNS)", agentRoot);
            return true;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public String getAgentRoot(int agentId) {
        return agentNamespaces.getOrDefault(agentId, "/");
    }

    public boolean hasNamespace(int agentId) {
        return agentNamespaces.containsKey(agentId);
    }

    /**
     * 销毁容器命名空间 — 类比 Linux 的 umount -a + cleanup。
     * 移除该 Agent 的所有 VFS 节点和命名空间映射。
     *
     * @param agentId Agent ID
     * @return true 销毁成功，false 命名空间不存在
     */
    public boolean destroyContainerNamespace(int agentId) {
        rwLock.writeLock().lock();
        try {
            String agentRoot = agentNamespaces.remove(agentId);
            if (agentRoot == null) return false;

            pathTree.keySet().removeIf(key -> key.startsWith(agentRoot));
            log.info("挂载命名空间已销毁: {}", agentRoot);
            return true;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /** 打印 VFS 目录树 — 类比 Linux 的 tree 命令 */
    public String tree() {
        return tree("/", 0, "/");
    }

    public String tree(String path, int depth, String agentRoot) {
        Optional<VfsNode> nodeOpt = resolve(path, agentRoot);
        if (nodeOpt.isEmpty()) return "";

        VfsNode node = nodeOpt.get();
        String indent = "  ".repeat(depth);
        String name = path.equals("/") ? "/" : path.substring(path.lastIndexOf('/') + 1);
        StringBuilder sb = new StringBuilder();
        sb.append(indent).append(name).append(" [").append(node.nodeType()).append("]\n");

        if (node instanceof VfsNode.DirectoryNode) {
            String prefix = path.equals("/") ? "/" : path + "/";
            pathTree.keySet().stream()
                    .filter(key -> {
                        String parent = key.substring(0, key.lastIndexOf('/') + 1);
                        if (path.equals("/")) parent = "/";
                        return key.startsWith(prefix) && !key.equals(path)
                                && key.indexOf('/', prefix.length()) == -1;
                    })
                    .sorted()
                    .forEach(childPath -> sb.append(tree(childPath, depth + 1, agentRoot)));
        }

        return sb.toString();
    }

    private void mountDirectory(String path) {
        pathTree.put(path, new VfsNode.DirectoryNode(path));
    }

    /** 路径净化 — 类比 Linux 的 canonicalize_path()，去除 . 和 ..，防止路径遍历攻击 */
    private String sanitizePath(String path) {
        if (path == null || path.isBlank()) return "/";
        String[] parts = path.split("/");
        Deque<String> stack = new ArrayDeque<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (!stack.isEmpty()) stack.pollLast();
            } else {
                stack.addLast(part);
            }
        }
        if (stack.isEmpty()) return "/";
        StringBuilder sb = new StringBuilder();
        for (String p : stack) {
            sb.append('/').append(p);
        }
        return sb.toString();
    }

    /** 路径越界检查 — 类比 Linux 的 chroot 边界检查，确保路径不会逃逸出 Agent 的根目录 */
    private boolean isWithinRoot(String path, String root) {
        if (root.equals("/") || root.isEmpty()) return true;
        if (path.equals(root)) return true;
        return path.length() > root.length()
                && path.startsWith(root)
                && path.charAt(root.length()) == '/';
    }

    // ════════════════════════════════════════════════════════════════
    //  Remote Device Dynamic Mount/Unmount
    // ════════════════════════════════════════════════════════════════

    /**
     * Dynamically mount a remote device into the VFS.
     * <p>
     * Delegates to {@link VfsDeviceManager#mountRemoteDevice}.
     *
     * @param deviceId   unique identifier for the remote device
     * @param deviceType type hint (e.g., "sensor", "actuator", "vcp_node")
     * @return the mounted RemoteDeviceMountNode
     */
    public RemoteDeviceMountNode mountRemoteDevice(String deviceId, String deviceType) {
        return VfsDeviceManager.mountRemoteDevice(pathTree, rwLock, initialized, deviceId, deviceType);
    }

    /**
     * Look up an existing remote device node by deviceId.
     * <p>
     * Delegates to {@link VfsDeviceManager#getRemoteDevice}.
     *
     * @param deviceId the device identifier
     * @return the RemoteDeviceMountNode, or null if not found
     */
    public RemoteDeviceMountNode getRemoteDevice(String deviceId) {
        return VfsDeviceManager.getRemoteDevice(pathTree, deviceId);
    }

    /**
     * Unmount a remote device from the VFS.
     * <p>
     * Delegates to {@link VfsDeviceManager#unmountRemoteDevice}.
     *
     * @param deviceId the device identifier to unmount
     * @return true if the device was found and unmounted
     */
    public boolean unmountRemoteDevice(String deviceId) {
        return VfsDeviceManager.unmountRemoteDevice(pathTree, rwLock, deviceId);
    }

    /**
     * 便捷读取方法 — 从 VFS 读取文本内容。
     * <p>
     * 安全边界：只读取 VFS 虚拟文件系统中的内容，永远不接触宿主机真实文件系统。
     * 如果节点不存在，返回 null。
     *
     * @param path VFS 虚拟路径
     * @return 文件文本内容，不存在则返回 null
     */
    public String readText(String path) {
        VfsRateLimiter.instance().checkRead(path); // 资源层限流：超限抛 SecurityException + 双写审计
        Optional<VfsNode> nodeOpt = resolve(path);
        if (nodeOpt.isEmpty()) {
            return null;
        }
        VfsNode node = nodeOpt.get();
        if (!node.checkRead(0)) {
            log.warn("[VFS] readText 被拒绝: 无读取权限 '{}'", path);
            return null;
        }
        String result = node.read();
        // hook FileAccessRecorder：仅记录非空成功读取
        if (fileAccessRecorder != null && result != null) {
            fileAccessRecorder.touchRead(path, System.currentTimeMillis());
        }
        return result;
    }

    /**
     * 便捷写入方法 — 向 VFS 写入文本内容。
     * <p>
     * 安全边界：只写入 VFS 虚拟文件系统，永远不接触宿主机真实文件系统。
     * 如果文件不存在，自动创建 MutableFileNode 并挂载。
     * 如果父目录不存在，自动创建中间目录。
     * <p>
     * <b>Provenance 追溯</b>：写入成功后自动调用 {@code ProvenanceHook.onWrite}
     * 追加一条版本记录到 {@code .aios/provenance.jsonl}（R1 数据模型）。
     * Best-effort — 记录失败不中断写主流程。
     *
     * @param path    VFS 虚拟路径
     * @param content 要写入的文本内容
     * @return true 写入成功
     */
    public boolean writeText(String path, String content) {
        return writeText(path, content, null);
    }

    /**
     * 带 {@code ownerTenantId} 的写入 — 新建节点时盖上租户戳。
     * <p>
     * 已存在的节点不改其 ownerTenantId（所有权一旦确定不随写入漂移）。
     * {@code ownerTenantId=null} 表示 legacy 写入（不戳，所有权校验对 null skip）。
     *
     * @param path           VFS 路径
     * @param content        要写入的文本内容
     * @param ownerTenantId  新建节点的归属租户 ID；null 表示不声明（legacy）
     * @return true 写入成功
     */
    public boolean writeText(String path, String content, String ownerTenantId) {
        VfsRateLimiter.instance().checkWrite(path); // 资源层限流：超限抛 SecurityException + 双写审计
        boolean success = writeTextInternal(path, content, ownerTenantId);
        // R1: Provenance 追溯 — best-effort，不影响主流程
        com.ouisani.aios.core.provenance.ProvenanceHook.onWrite(path, content, success);
        return success;
    }

    /**
     * writeText 的内部实现 — 原有逻辑，不挂 Provenance。
     * <p>
     * 由 {@link #writeText} 包装调用。也可直接调用以跳过 Provenance 记录
     * （如系统内部写入 .aios/provenance.jsonl 自身时避免递归）。
     */
    private boolean writeTextInternal(String path, String content, String ownerTenantId) {
        rwLock.writeLock().lock();
        try {
            if (!initialized) {
                log.warn("VFS 未初始化，无法 writeText");
                return false;
            }

            String resolved = translatePath(path, AGENT_ROOT.get());
            if (resolved.isEmpty()) {
                log.warn("[VFS] writeText 被拒绝: 检测到路径逃逸 '{}'", path);
                return false;
            }

            VfsNode existing = pathTree.get(resolved);
            if (existing != null) {
                // 节点已存在，直接写入（不改其 ownerTenantId — 所有权不漂移）
                if (!existing.checkWrite(0)) {
                    log.warn("[VFS] writeText 被拒绝: 无写入权限 '{}'", resolved);
                    return false;
                }
                boolean ok = existing.write(content);
                if (ok) {
                    log.debug("[VFS] writeText: 字符数 {} -> '{}'", content.length(), resolved);
                }
                return ok;
            }

            // 节点不存在，检查是否有物理工作目录映射
            String physicalDir = findPhysicalWorkspace(resolved);
            if (physicalDir != null) {
                // 有物理工作目录映射 → 创建 HostSourceNode，写入物理磁盘
                String parentPath = resolved.substring(0, resolved.lastIndexOf('/'));
                if (parentPath.isEmpty()) parentPath = "/";
                ensureDirectoryExists(parentPath);

                // 计算物理文件路径：physicalDir + VFS 路径去掉前缀
                String relativePath = resolved;
                for (Map.Entry<String, String> entry : physicalWorkspaceMap.entrySet()) {
                    if (resolved.startsWith(entry.getKey())) {
                        relativePath = resolved.substring(entry.getKey().length());
                        break;
                    }
                }
                String physicalFilePath = physicalDir + relativePath;

                HostSourceNode hostNode = new HostSourceNode(resolved, physicalFilePath);
                hostNode.setOwnerTenantId(ownerTenantId);
                boolean ok = hostNode.write(content);
                if (!ok) {
                    // 磁盘写入失败，降级为纯内存模式
                    diskDegraded = true;
                    log.warn("[VFS] 磁盘写入失败，已降级为纯内存模式。后续写入仅在内存中生效。");
                    // 回退到 MutableFileNode（内存）
                    MutableFileNode memNode = new MutableFileNode(resolved);
                    memNode.setOwnerTenantId(ownerTenantId);
                    memNode.write(content);
                    pathTree.put(resolved, memNode);
                    log.info("[VFS] writeText: 磁盘写入失败，已回退至 MutableFileNode '{}'，字符数 {}", resolved, content.length());
                    if (fileAccessRecorder != null) {
                        fileAccessRecorder.touchEdit(resolved, System.currentTimeMillis());
                    }
                    return true;
                }
                pathTree.put(resolved, hostNode);
                log.info("[VFS] writeText: 已创建 HostSourceNode '{}'，字符数 {} → 物理路径: {}", resolved, content.length(), physicalFilePath);
                if (fileAccessRecorder != null) {
                    fileAccessRecorder.touchEdit(resolved, System.currentTimeMillis());
                }
                return ok;
            }

            // 无物理映射 → 创建 MutableFileNode（内存）
            String parentPath = resolved.substring(0, resolved.lastIndexOf('/'));
            if (parentPath.isEmpty()) parentPath = "/";
            ensureDirectoryExists(parentPath);

            MutableFileNode newNode = new MutableFileNode(resolved);
            newNode.setOwnerTenantId(ownerTenantId);
            newNode.write(content);
            pathTree.put(resolved, newNode);
            log.info("[VFS] writeText: 已创建新 MutableFileNode '{}'，字符数 {}", resolved, content.length());
            if (fileAccessRecorder != null) {
                fileAccessRecorder.touchEdit(resolved, System.currentTimeMillis());
            }
            return true;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * 检查 VFS 路径是否存在。
     *
     * @param path VFS 虚拟路径
     * @return true 路径存在
     */
    public boolean exists(String path) {
        return resolve(path).isPresent();
    }

    /**
     * VFS 是否已初始化 — 供 PermissionChecker 等外部组件探测，
     * 避免对未初始化的 VFS 做所有权查询（{@link #resolve} 未初始化时返回 empty）。
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 给已存在的 VFS 节点盖租户戳 — 用于租户根目录注册或批量标记归属。
     * <p>
     * <b>不漂移原则</b>：仅当节点存在且当前 {@code ownerTenantId} 为 null（legacy 未声明）
     * 时才盖戳；已确定归属的节点不被覆盖。{@code tenantId=null/blank} 为 no-op。
     * 仅 {@link VfsNode.DirectoryNode} / {@link MutableFileNode} / {@link HostSourceNode}
     * 支持盖戳；不可变 {@link VfsNode.FileNode}（record）返回 false。
     *
     * @param path     VFS 路径
     * @param tenantId 租户 ID；null/blank 表示不盖戳（no-op）
     * @return true 节点存在、类型可变、且原 ownerTenantId 为 null 已成功盖戳；
     *         false 节点不存在 / tenantId 为空 / 节点类型不可变 / 已有归属不漂移
     */
    public boolean stampOwnerTenantId(String path, String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return false;
        rwLock.writeLock().lock();
        try {
            String resolved = translatePath(path, AGENT_ROOT.get());
            if (resolved.isEmpty()) return false;
            VfsNode node = pathTree.get(resolved);
            if (node == null) return false;
            return stampNodeIfLegacy(node, tenantId);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * 注册租户根目录 — 将一个 VFS 子树标记为某租户所有。
     * <p>
     * 类比 Linux per-tenant namespace：每个租户拥有独立 VFS 子树，根目录盖戳后，
     * {@code PermissionChecker.checkTenantOwnership} 即可基于 {@code ownerTenantId}
     * 拦截跨租户访问，取代脆弱的路径子串匹配。
     * <p>
     * 行为：确保根目录存在（不存在则创建），盖戳根目录及其下所有 legacy（ownerTenantId=null）节点。
     * 幂等：重复注册同 tenantId+root 不报错，已盖戳节点不重新盖戳（不漂移）。
     *
     * @param tenantId 租户 ID
     * @param vfsRoot  租户根 VFS 路径（如 "/tenants/tenantA"）
     */
    public void registerTenantRoot(String tenantId, String vfsRoot) {
        if (tenantId == null || tenantId.isBlank() || vfsRoot == null || vfsRoot.isBlank()) return;
        rwLock.writeLock().lock();
        try {
            if (!initialized) {
                log.warn("[VFS] registerTenantRoot 跳过：VFS 未初始化");
                return;
            }
            String resolved = translatePath(vfsRoot, AGENT_ROOT.get());
            if (resolved.isEmpty()) {
                log.warn("[VFS] registerTenantRoot 路径逃逸被拒: '{}'", vfsRoot);
                return;
            }
            ensureDirectoryExists(resolved);
            String prefix = resolved.endsWith("/") ? resolved : resolved + "/";
            int stamped = 0;
            for (Map.Entry<String, VfsNode> e : pathTree.entrySet()) {
                String p = e.getKey();
                if (!p.equals(resolved) && !p.startsWith(prefix)) continue;
                if (stampNodeIfLegacy(e.getValue(), tenantId)) stamped++;
            }
            log.info("[VFS] 租户根已注册: {} → tenant={} (新盖戳 {} 个 legacy 节点)", resolved, tenantId, stamped);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * 内部：仅当节点 {@code ownerTenantId} 为 null 时盖戳（不漂移）。
     * <p>
     * <b>必须在写锁内调用</b>（修改节点可变状态）。返回是否实际盖戳。
     */
    private boolean stampNodeIfLegacy(VfsNode node, String tenantId) {
        if (node instanceof VfsNode.DirectoryNode dir && dir.ownerTenantId() == null) {
            dir.setOwnerTenantId(tenantId);
            return true;
        }
        if (node instanceof MutableFileNode mf && mf.ownerTenantId() == null) {
            mf.setOwnerTenantId(tenantId);
            return true;
        }
        if (node instanceof HostSourceNode hs && hs.ownerTenantId() == null) {
            hs.setOwnerTenantId(tenantId);
            return true;
        }
        return false;
    }

    /** 递归确保目录路径存在 */
    private void ensureDirectoryExists(String dirPath) {
        if (pathTree.containsKey(dirPath)) return;
        // 先确保父目录
        int lastSlash = dirPath.lastIndexOf('/');
        if (lastSlash > 0) {
            String parent = dirPath.substring(0, lastSlash);
            ensureDirectoryExists(parent);
        }
        pathTree.put(dirPath, new VfsNode.DirectoryNode(dirPath));
    }

    public Map<String, String> listRemoteDevices() {
        return VfsDeviceManager.listRemoteDevices(pathTree);
    }

    /**
     * 列出 VFS 中指定前缀下的所有文件路径（非目录节点）。
     * <p>
     * 类比 Linux 的 {@code find /factory -type f}。
     *
     * @param prefix 路径前缀（如 "/factory"）
     * @return 匹配的文件路径列表（仅包含可读内容的节点）
     */
    public List<String> listFilesUnder(String prefix) {
        List<String> files = new ArrayList<>();
        rwLock.readLock().lock();
        try {
            for (Map.Entry<String, VfsNode> entry : pathTree.entrySet()) {
                String path = entry.getKey();
                if (path.startsWith(prefix) && entry.getValue().nodeType() == VfsNode.VfsNodeType.FILE) {
                    files.add(path);
                }
            }
        } finally {
            rwLock.readLock().unlock();
        }
        return files;
    }

    // ════════════════════════════════════════════════════════════════
    //  渐进式披露 (Progressive Disclosure) — IndexNode 支持
    // ════════════════════════════════════════════════════════════════

    /**
     * 为指定目录生成或刷新 IndexNode（渐进式披露索引）。
     * <p>
     * Delegates to {@link VfsDeviceManager#indexDirectory}.
     *
     * @param dirPath 目录路径
     * @return 生成的索引内容，目录不存在则返回 null
     */
    public String indexDirectory(String dirPath) {
        return VfsDeviceManager.indexDirectory(pathTree, rwLock, this, dirPath);
    }

    /**
     * 读取目录的索引内容（如果存在）。如果索引不存在，自动生成。
     * <p>
     * Delegates to {@link VfsDeviceManager#readDirectoryIndex}.
     *
     * @param dirPath 目录路径
     * @return 索引内容，目录不存在则返回 null
     */
    public String readDirectoryIndex(String dirPath) {
        return VfsDeviceManager.readDirectoryIndex(pathTree, rwLock, this, dirPath);
    }

    // ════════════════════════════════════════════════════════════════
    //  VFS OverlayFS — 上下文覆盖层（借鉴 Docker 镜像层）
    // ════════════════════════════════════════════════════════════════

    /**
     * 创建 OverlayFS 挂载点 — 借鉴 Docker 镜像层和 Linux OverlayFS。
     * <p>
     * Delegates to {@link VfsDeviceManager#createOverlay}.
     *
     * @param mountPath 挂载路径（如 /containers/agent_1001/workspace）
     * @param lowerDir  底层只读目录（如 /factory/myproject）
     * @param upperDir  上层读写目录（如 /containers/agent_1001/overlay）
     * @return 创建的 OverlayNode
     */
    public OverlayNode createOverlay(String mountPath, String lowerDir, String upperDir) {
        return VfsDeviceManager.createOverlay(pathTree, rwLock, this, mountPath, lowerDir, upperDir);
    }

    /**
     * 销毁 OverlayFS 挂载点。
     * <p>
     * Delegates to {@link VfsDeviceManager#destroyOverlay}.
     *
     * @param mountPath 挂载路径
     */
    public void destroyOverlay(String mountPath) {
        VfsDeviceManager.destroyOverlay(pathTree, rwLock, mountPath);
    }

    /**
     * 获取指定挂载点的 OverlayNode。
     * <p>
     * Delegates to {@link VfsDeviceManager#getOverlay}.
     *
     * @param mountPath 挂载路径
     * @return OverlayNode，不存在则返回 null
     */
    public OverlayNode getOverlay(String mountPath) {
        return VfsDeviceManager.getOverlay(pathTree, rwLock, mountPath);
    }
}
