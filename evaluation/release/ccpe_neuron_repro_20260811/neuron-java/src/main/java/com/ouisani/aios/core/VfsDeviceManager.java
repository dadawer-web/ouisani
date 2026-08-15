package com.ouisani.aios.core;

import com.ouisani.aios.vfs.IndexNode;
import com.ouisani.aios.vfs.OverlayNode;
import com.ouisani.aios.vfs.RemoteDeviceMountNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 远程设备管理、OverlayFS 覆盖层管理与目录索引管理 — 从 {@link VfsManager} 抽离的静态工具类。
 * <p>
 * 这些方法原本位于 {@code VfsManager} 中（超过 1000 LOC），为遵循 ratchet budget 工程纪律
 * 抽离为独立类。所有方法均为 package-private 静态方法，由 {@link VfsManager} 通过
 * 轻量级委托包装器调用，传入 {@code pathTree} 与 {@code rwLock}（以及需要时传入
 * {@code VfsManager} 实例以访问实例方法）。
 */
final class VfsDeviceManager {

    private static final Logger log = LoggerFactory.getLogger(VfsDeviceManager.class);

    private VfsDeviceManager() {
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
     * @param pathTree    VFS 路径树
     * @param rwLock      读写锁
     * @param initialized VFS 是否已初始化
     * @param deviceId    unique identifier for the remote device
     * @param deviceType  type hint (e.g., "sensor", "actuator", "vcp_node")
     * @return the mounted RemoteDeviceMountNode
     */
    static RemoteDeviceMountNode mountRemoteDevice(
            Map<String, VfsNode> pathTree, ReentrantReadWriteLock rwLock,
            boolean initialized, String deviceId, String deviceType) {
        rwLock.writeLock().lock();
        try {
            if (!initialized) {
                throw new IllegalStateException("VFS 未初始化，无法挂载远程设备");
            }

            String vfsPath = "/dev/remote/" + deviceId;

            // Check if the node already exists (device reconnection)
            VfsNode existing = pathTree.get(vfsPath);
            if (existing instanceof RemoteDeviceMountNode existingNode) {
                log.info("[VFS] 远程设备 '{}' 已挂载于 {}，返回已有节点", deviceId, vfsPath);
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
     * @param pathTree VFS 路径树
     * @param deviceId the device identifier
     * @return the RemoteDeviceMountNode, or null if not found
     */
    static RemoteDeviceMountNode getRemoteDevice(Map<String, VfsNode> pathTree, String deviceId) {
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
     * The Agent process will receive a {@link com.ouisani.aios.vfs.DeviceOfflineException}
     * on its next {@code sys_read}, or EOF if the node has been
     * permanently removed.
     *
     * @param pathTree VFS 路径树
     * @param rwLock   读写锁
     * @param deviceId the device identifier to unmount
     * @return true if the device was found and unmounted
     */
    static boolean unmountRemoteDevice(
            Map<String, VfsNode> pathTree, ReentrantReadWriteLock rwLock, String deviceId) {
        rwLock.writeLock().lock();
        try {
            String vfsPath = "/dev/remote/" + deviceId;
            VfsNode node = pathTree.get(vfsPath);

            if (node instanceof RemoteDeviceMountNode rdmn) {
                rdmn.markPermanentlyUnmounted();
                pathTree.remove(vfsPath);

                log.info("[VFS] 远程设备已卸载: {} [REMOTE_DEVICE]", vfsPath);
                System.out.println("  \u001B[31m[VFS] Remote device '" + deviceId + "' unmounted from " + vfsPath + "\u001B[0m");
                return true;
            }

            log.warn("[VFS] 远程设备卸载失败: '{}' 未找到或非 RemoteDeviceMountNode", vfsPath);
            return false;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * List all currently mounted remote devices.
     *
     * @param pathTree VFS 路径树
     * @return map of deviceId -> vfsPath for all remote device nodes
     */
    static Map<String, String> listRemoteDevices(Map<String, VfsNode> pathTree) {
        Map<String, String> devices = new LinkedHashMap<>();
        String prefix = "/dev/remote/";
        for (Map.Entry<String, VfsNode> entry : pathTree.entrySet()) {
            if (entry.getKey().startsWith(prefix) && entry.getValue() instanceof RemoteDeviceMountNode rdmn) {
                devices.put(rdmn.deviceId(), entry.getKey());
            }
        }
        return devices;
    }

    // ════════════════════════════════════════════════════════════════
    //  渐进式披露 (Progressive Disclosure) — IndexNode 支持
    // ════════════════════════════════════════════════════════════════

    /**
     * 为指定目录生成或刷新 IndexNode（渐进式披露索引）。
     * <p>
     * 借鉴 Google Knowledge Catalog 的 OKF 标准：每个目录下自动生成
     * 一个 index.md，包含该目录下所有文件的语义摘要。
     * Agent 访问目录时优先获取索引，按需深入读取具体文件，大幅降低 Token 消耗。
     *
     * @param pathTree VFS 路径树
     * @param rwLock   读写锁
     * @param vfs      VfsManager 实例（用于 exists 等实例方法）
     * @param dirPath  目录路径
     * @return 生成的索引内容，目录不存在则返回 null
     */
    static String indexDirectory(
            Map<String, VfsNode> pathTree, ReentrantReadWriteLock rwLock,
            VfsManager vfs, String dirPath) {
        if (!vfs.exists(dirPath)) {
            log.warn("[VFS] indexDirectory: 目录不存在 '{}'", dirPath);
            return null;
        }

        IndexNode indexNode = IndexNode.getOrCreate(dirPath);
        indexNode.regenerate();

        // 将 IndexNode 挂载到 VFS（index.md）
        String indexPath = dirPath.endsWith("/") ? dirPath + "index.md" : dirPath + "/index.md";
        rwLock.writeLock().lock();
        try {
            pathTree.put(indexPath, indexNode);
        } finally {
            rwLock.writeLock().unlock();
        }

        log.info("[VFS] 目录索引已生成: {}", indexPath);
        return indexNode.read();
    }

    /**
     * 读取目录的索引内容（如果存在）。
     * 如果索引不存在，自动生成。
     *
     * @param pathTree VFS 路径树
     * @param rwLock   读写锁
     * @param vfs      VfsManager 实例（用于 resolve 等实例方法）
     * @param dirPath  目录路径
     * @return 索引内容，目录不存在则返回 null
     */
    static String readDirectoryIndex(
            Map<String, VfsNode> pathTree, ReentrantReadWriteLock rwLock,
            VfsManager vfs, String dirPath) {
        String indexPath = dirPath.endsWith("/") ? dirPath + "index.md" : dirPath + "/index.md";

        // 检查索引是否已存在且未过期
        Optional<VfsNode> existing = vfs.resolve(indexPath);
        if (existing.isPresent() && existing.get() instanceof IndexNode idx) {
            if (!idx.isStale()) {
                return idx.read();
            }
            idx.regenerate();
            return idx.read();
        }

        // 索引不存在，自动生成
        return vfs.indexDirectory(dirPath);
    }

    // ════════════════════════════════════════════════════════════════
    //  VFS OverlayFS — 上下文覆盖层（借鉴 Docker 镜像层）
    // ════════════════════════════════════════════════════════════════

    /**
     * 创建 OverlayFS 挂载点 — 借鉴 Docker 镜像层和 Linux OverlayFS。
     * <p>
     * 底层（lower）挂载为只读，多个 Agent 共享。
     * 上层（upper）挂载为读写，Agent 独占。
     * 读取时先查上层，未命中再查底层。
     *
     * @param pathTree  VFS 路径树
     * @param rwLock    读写锁
     * @param vfs       VfsManager 实例（用于 writeText 实例方法）
     * @param mountPath 挂载路径（如 /containers/agent_1001/workspace）
     * @param lowerDir  底层只读目录（如 /factory/myproject）
     * @param upperDir  上层读写目录（如 /containers/agent_1001/overlay）
     * @return 创建的 OverlayNode
     */
    static OverlayNode createOverlay(
            Map<String, VfsNode> pathTree, ReentrantReadWriteLock rwLock,
            VfsManager vfs, String mountPath, String lowerDir, String upperDir) {
        // 确保上层目录存在
        vfs.writeText(upperDir + "/.keep", "");

        OverlayNode overlay = OverlayNode.create(mountPath, lowerDir, upperDir);

        rwLock.writeLock().lock();
        try {
            pathTree.put(mountPath, overlay);
        } finally {
            rwLock.writeLock().unlock();
        }

        log.info("[VFS] OverlayFS 已挂载: {} (lower={}, upper={})", mountPath, lowerDir, upperDir);
        return overlay;
    }

    /**
     * 销毁 OverlayFS 挂载点。
     *
     * @param pathTree  VFS 路径树
     * @param rwLock    读写锁
     * @param mountPath 挂载路径
     */
    static void destroyOverlay(
            Map<String, VfsNode> pathTree, ReentrantReadWriteLock rwLock, String mountPath) {
        rwLock.writeLock().lock();
        try {
            pathTree.remove(mountPath);
            OverlayNode.destroy(mountPath);
        } finally {
            rwLock.writeLock().unlock();
        }
        log.info("[VFS] OverlayFS 已卸载: {}", mountPath);
    }

    /**
     * 获取指定挂载点的 OverlayNode。
     *
     * @param pathTree  VFS 路径树
     * @param rwLock    读写锁
     * @param mountPath 挂载路径
     * @return OverlayNode，不存在则返回 null
     */
    static OverlayNode getOverlay(
            Map<String, VfsNode> pathTree, ReentrantReadWriteLock rwLock, String mountPath) {
        rwLock.readLock().lock();
        try {
            VfsNode node = pathTree.get(mountPath);
            return node instanceof OverlayNode o ? o : null;
        } finally {
            rwLock.readLock().unlock();
        }
    }
}
