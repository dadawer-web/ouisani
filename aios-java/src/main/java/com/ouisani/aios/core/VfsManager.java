package com.ouisani.aios.core;

import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.llm.OpenAiAdapter;
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
import com.ouisani.aios.vfs.DeviceOfflineException;
import com.ouisani.aios.vfs.DesktopNotifyNode;
import com.ouisani.aios.vfs.ChromeBridgeNode;
import com.ouisani.aios.core.vfs.VfsJournal;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class VfsManager {

    private static final Logger log = LoggerFactory.getLogger(VfsManager.class);

    public static final ThreadLocal<String> AGENT_ROOT = ThreadLocal.withInitial(() -> "/");

    private static final class Holder {
        static final VfsManager INSTANCE = new VfsManager();
    }

    public static VfsManager instance() {
        return Holder.INSTANCE;
    }

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Map<String, VfsNode> pathTree = new ConcurrentHashMap<>();
    private final Map<Integer, String> agentNamespaces = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;
    private volatile LlmProvider defaultLlmProvider;
    private volatile TaskScheduler taskScheduler;
    private volatile Javalin javalinApp;

    private VfsManager() {
    }

    public void configureLlmProvider(LlmProvider provider) {
        this.defaultLlmProvider = provider;
        log.info("LlmProvider configured: {}", provider.name());
    }

    public void configureTaskScheduler(TaskScheduler scheduler) {
        this.taskScheduler = scheduler;
        log.info("TaskScheduler configured for /proc filesystem");
    }

    public TaskScheduler getTaskScheduler() {
        return taskScheduler;
    }

    public void configureJavalin(Javalin app) {
        this.javalinApp = app;
        log.info("Javalin configured for webhook endpoints");
    }

    public LlmProvider getLlmProvider() {
        return defaultLlmProvider;
    }

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
                log.info("VFS mounted: /dev/semantic [SEMANTIC] provider={}", defaultLlmProvider.name());

                VectorNode vectorNode = new VectorNode("/dev/vec_mem", defaultLlmProvider);
                pathTree.put("/dev/vec_mem", vectorNode);
                log.info("VFS mounted: /dev/vec_mem [VECTOR] provider={}", defaultLlmProvider.name());

                // ── Persistent Long-Term Memory (Dream Daemon target) ──
                VectorNode memoryDb = new VectorNode("/var/db/memory", defaultLlmProvider);
                pathTree.put("/var/db/memory", memoryDb);
                log.info("VFS mounted: /var/db/memory [VECTOR] persistent long-term memory (Dream Daemon target)");

                GraphNode graphNode = new GraphNode("/dev/graph_mem", defaultLlmProvider);
                pathTree.put("/dev/graph_mem", graphNode);
                log.info("VFS mounted: /dev/graph_mem [GRAPH] provider={}", defaultLlmProvider.name());
            } else {
                log.warn("No LlmProvider configured, /dev/semantic, /dev/vec_mem and /dev/graph_mem not mounted");
            }

            if (taskScheduler != null) {
                pathTree.put("/proc/agents", ProcFsNode.agents(taskScheduler));
                log.info("VFS mounted: /proc/agents [PROCFS] dynamic agent list");
            } else {
                log.warn("No TaskScheduler configured, /proc/agents not mounted");
            }

            pathTree.put("/proc/cgroups", ProcFsNode.cgroups());
            log.info("VFS mounted: /proc/cgroups [PROCFS] dynamic cgroup tree");

            // ── Semantic Registry ──
            pathTree.put("/proc/registry", new RegistryFsNode("/proc/registry"));
            log.info("VFS mounted: /proc/registry [REGISTRY] global semantic registry");

            // ── Virtual hardware devices ──
            pathTree.put("/dev/camera0", new CameraNode("/dev/camera0"));
            log.info("VFS mounted: /dev/camera0 [CAMERA] read-only virtual camera");

            pathTree.put("/dev/display0", new DisplayNode("/dev/display0"));
            log.info("VFS mounted: /dev/display0 [DISPLAY] write-only virtual display");

            pathTree.put("/dev/audio0", new AudioNode("/dev/audio0"));
            log.info("VFS mounted: /dev/audio0 [AUDIO] write-only TTS device");

            // ── GUI / Desktop Automation (OSWorld) ──
            mountDirectory("/dev/gui");

            pathTree.put("/dev/gui/dom", new GuiDomNode("/dev/gui/dom"));
            log.info("VFS mounted: /dev/gui/dom [GUI_DOM] read-only screen UI element tree");

            pathTree.put("/dev/gui/action", new GuiActionNode("/dev/gui/action"));
            log.info("VFS mounted: /dev/gui/action [GUI_ACTION] write-only desktop automation");

            // ── Network devices ──
            mountDirectory("/dev/net");

            pathTree.put("/dev/net/http", new HttpNode("/dev/net/http"));
            log.info("VFS mounted: /dev/net/http [HTTP] bidirectional HTTP client");

            if (javalinApp != null) {
                pathTree.put("/dev/net/webhook_1", new WebhookNode("/dev/net/webhook_1", "1", javalinApp));
                log.info("VFS mounted: /dev/net/webhook_1 [WEBHOOK] POST /webhook/1");
            } else {
                log.warn("No Javalin configured, /dev/net/webhook_1 not mounted");
            }

            // ── Shared Memory (SHM IPC) ──
            mountDirectory("/dev/shm");
            pathTree.put("/dev/shm/blackboard", new ShmNode("/dev/shm/blackboard", "blackboard"));
            log.info("VFS mounted: /dev/shm/blackboard [SHM] shared memory blackboard");

            // ── Remote Device Mount Point ──
            mountDirectory("/dev/remote");
            log.info("VFS mounted: /dev/remote [REMOTE_DEVICE] dynamic remote device mount point");

            // ── Host Physical Layer (/dev/host/) ──
            mountDirectory("/dev/host");
            pathTree.put("/dev/host/notify", new DesktopNotifyNode("/dev/host/notify"));
            log.info("VFS mounted: /dev/host/notify [DEVICE] native desktop notification (write-only)");

            ChromeBridgeNode chromeBridge = new ChromeBridgeNode("/dev/host/browser");
            pathTree.put("/dev/host/browser", chromeBridge);
            log.info("VFS mounted: /dev/host/browser [DEVICE] Chrome browser bridge (WebSocket)");
            // 启动浏览器 WebSocket 桥接（端口 19999）
            if (javalinApp != null) {
                chromeBridge.startWebSocket(19999);
            }

            // ── WAL Journal: open and recover ──
            VfsJournal.getInstance().open();
            int replayed = VfsJournal.getInstance().recoverAll();
            if (replayed > 0) {
                log.info("[VFS Journal] Replaying {} ops from WAL... Crash consistency restored!", replayed);
            }

            // ── VSS Shadow Copy directory ──
            mountDirectory("/shadow");
            log.info("VFS mounted: /shadow [VSS] shadow copy root");

            initialized = true;
            log.info("VFS root filesystem initialized: /, /bin, /dev, /mem, /proc, /tmp, /containers, /var/crash");
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public Optional<VfsNode> resolve(String path, String agentRoot) {
        rwLock.readLock().lock();
        try {
            if (!initialized) {
                log.warn("VFS not initialized");
                return Optional.empty();
            }

            String resolved = translatePath(path, agentRoot);
            if (resolved.isEmpty()) {
                return Optional.empty();
            }

            VfsNode node = pathTree.get(resolved);
            if (node != null) {
                log.trace("VFS resolve: '{}' -> '{}' [{}]", path, resolved, node.nodeType());
            } else {
                log.trace("VFS resolve: '{}' -> '{}' not found", path, resolved);
            }
            return Optional.ofNullable(node);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public Optional<VfsNode> resolve(String path) {
        return resolve(path, AGENT_ROOT.get());
    }

    public boolean mount(String dirPath, String name, VfsNode node, int callerUid) {
        rwLock.writeLock().lock();
        try {
            if (!initialized) {
                log.warn("VFS not initialized, cannot mount");
                return false;
            }

            String fullPath = dirPath.equals("/") ? "/" + name : dirPath + "/" + name;

            if (pathTree.containsKey(fullPath)) {
                log.warn("VFS mount failed: '{}' already exists", fullPath);
                return false;
            }

            pathTree.put(fullPath, node);
            log.info("VFS mounted: {} [{}]", fullPath, node.nodeType());
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
                log.warn("VFS not initialized, cannot mount host file");
                return false;
            }

            if (pathTree.containsKey(vfsPath)) {
                log.warn("VFS mount failed: '{}' already exists", vfsPath);
                return false;
            }

            HostSourceNode node = new HostSourceNode(vfsPath, physicalPath);
            pathTree.put(vfsPath, node);
            System.out.printf("  🔗 [VFS] Physical host path '%s' mounted to virtual node '%s'%n", physicalPath, vfsPath);
            log.info("[VFS] Host file mounted: vfsPath='{}', physicalPath='{}'", vfsPath, physicalPath);
            return true;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public boolean unmount(String path, int callerUid) {
        rwLock.writeLock().lock();
        try {
            if (!pathTree.containsKey(path)) {
                log.warn("VFS unmount failed: '{}' not found", path);
                return false;
            }

            pathTree.remove(path);
            log.info("VFS unmounted: {}", path);
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
            log.warn("[VSS] Snapshot failed: path '{}' not found", path);
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

        log.info("[VSS] Shadow copy created: '{}' → '{}' (frozen, read-only)",
                path, shadowPath);

        return shadowPath;
    }

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
            log.warn("PATH ESCAPE BLOCKED: '{}' escapes root '{}' (resolved: '{}')", path, agentRoot, canonicalized);
            return "";
        }

        return canonicalized;
    }

    public String resolvePath(String path) {
        return translatePath(path, AGENT_ROOT.get());
    }

    public boolean createContainerNamespace(int agentId) {
        rwLock.writeLock().lock();
        try {
            if (!initialized) return false;

            String agentDirName = "agent_" + agentId;
            String agentRoot = "/containers/" + agentDirName;

            if (agentNamespaces.containsKey(agentId)) {
                log.debug("Container namespace already exists: {}", agentRoot);
                return true;
            }

            mountDirectory(agentRoot);
            mountDirectory(agentRoot + "/bin");
            mountDirectory(agentRoot + "/dev");
            mountDirectory(agentRoot + "/proc");
            mountDirectory(agentRoot + "/tmp");
            mountDirectory(agentRoot + "/dev/mem");

            agentNamespaces.put(agentId, agentRoot);
            log.info("Mount namespace created: {} (CLONE_NEWNS)", agentRoot);
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

    public boolean destroyContainerNamespace(int agentId) {
        rwLock.writeLock().lock();
        try {
            String agentRoot = agentNamespaces.remove(agentId);
            if (agentRoot == null) return false;

            pathTree.keySet().removeIf(key -> key.startsWith(agentRoot));
            log.info("Mount namespace destroyed: {}", agentRoot);
            return true;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

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
     * Called by {@code SyscallServer} when a new remote device connects
     * via the {@code /ws/remote/{deviceId}} WebSocket endpoint. Creates
     * a {@link RemoteDeviceMountNode} and mounts it at
     * {@code /dev/remote/{deviceId}}.
     * <p>
     * If a node already exists at that path (e.g., the device
     * reconnected), the existing node is returned instead.
     *
     * @param deviceId   unique identifier for the remote device
     * @param deviceType type hint (e.g., "sensor", "actuator", "vcp_node")
     * @return the mounted RemoteDeviceMountNode
     */
    public RemoteDeviceMountNode mountRemoteDevice(String deviceId, String deviceType) {
        rwLock.writeLock().lock();
        try {
            if (!initialized) {
                throw new IllegalStateException("VFS not initialized, cannot mount remote device");
            }

            String vfsPath = "/dev/remote/" + deviceId;

            // Check if the node already exists (device reconnection)
            VfsNode existing = pathTree.get(vfsPath);
            if (existing instanceof RemoteDeviceMountNode existingNode) {
                log.info("[VFS] Remote device '{}' already mounted at {}, returning existing node", deviceId, vfsPath);
                return existingNode;
            }

            // Create and mount the new remote device node
            RemoteDeviceMountNode node = new RemoteDeviceMountNode(
                    vfsPath, deviceId, deviceType, 512, 0, 0666);
            pathTree.put(vfsPath, node);

            log.info("[VFS] Remote device mounted: {} [REMOTE_DEVICE] type={}", vfsPath, deviceType);
            System.out.println("  \u001B[36m[VFS] Remote device '" + deviceId + "' mounted at " + vfsPath
                    + " (type=" + deviceType + ")\u001B[0m");

            return node;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Look up an existing remote device node by deviceId.
     *
     * @param deviceId the device identifier
     * @return the RemoteDeviceMountNode, or null if not found
     */
    public RemoteDeviceMountNode getRemoteDevice(String deviceId) {
        String vfsPath = "/dev/remote/" + deviceId;
        VfsNode node = pathTree.get(vfsPath);
        if (node instanceof RemoteDeviceMountNode rdmn) {
            return rdmn;
        }
        return null;
    }

    /**
     * Unmount a remote device from the VFS.
     * <p>
     * Called by {@code SyscallServer} when a remote device disconnects.
     * Marks the node as permanently unmounted (which causes subsequent
     * reads to return EOF and writes to fail), then removes it from
     * the VFS path tree.
     * <p>
     * The Agent process will receive a {@link DeviceOfflineException}
     * on its next {@code sys_read}, or EOF if the node has been
     * permanently removed.
     *
     * @param deviceId the device identifier to unmount
     * @return true if the device was found and unmounted
     */
    public boolean unmountRemoteDevice(String deviceId) {
        rwLock.writeLock().lock();
        try {
            String vfsPath = "/dev/remote/" + deviceId;
            VfsNode node = pathTree.get(vfsPath);

            if (node instanceof RemoteDeviceMountNode rdmn) {
                rdmn.markPermanentlyUnmounted();
                pathTree.remove(vfsPath);

                log.info("[VFS] Remote device unmounted: {} [REMOTE_DEVICE]", vfsPath);
                System.out.println("  \u001B[31m[VFS] Remote device '" + deviceId + "' unmounted from " + vfsPath + "\u001B[0m");
                return true;
            }

            log.warn("[VFS] Remote device unmount failed: '{}' not found or not a RemoteDeviceMountNode", vfsPath);
            return false;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * List all currently mounted remote devices.
     *
     * @return map of deviceId → vfsPath for all remote device nodes
     */
    public Map<String, String> listRemoteDevices() {
        Map<String, String> devices = new LinkedHashMap<>();
        String prefix = "/dev/remote/";
        for (Map.Entry<String, VfsNode> entry : pathTree.entrySet()) {
            if (entry.getKey().startsWith(prefix) && entry.getValue() instanceof RemoteDeviceMountNode rdmn) {
                devices.put(rdmn.deviceId(), entry.getKey());
            }
        }
        return devices;
    }
}
