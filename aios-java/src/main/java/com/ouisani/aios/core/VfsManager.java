package com.ouisani.aios.core;

import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.llm.OpenAiAdapter;
import com.ouisani.aios.vfs.ProcFsNode;
import com.ouisani.aios.vfs.SemanticNode;
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

            if (defaultLlmProvider != null) {
                SemanticNode semanticNode = new SemanticNode("/dev/semantic", defaultLlmProvider);
                pathTree.put("/dev/semantic", semanticNode);
                log.info("VFS mounted: /dev/semantic [SEMANTIC] provider={}", defaultLlmProvider.name());
            } else {
                log.warn("No LlmProvider configured, /dev/semantic not mounted");
            }

            if (taskScheduler != null) {
                pathTree.put("/proc/agents", ProcFsNode.agents(taskScheduler));
                log.info("VFS mounted: /proc/agents [PROCFS] dynamic agent list");
            } else {
                log.warn("No TaskScheduler configured, /proc/agents not mounted");
            }

            pathTree.put("/proc/cgroups", ProcFsNode.cgroups());
            log.info("VFS mounted: /proc/cgroups [PROCFS] dynamic cgroup tree");

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
}
