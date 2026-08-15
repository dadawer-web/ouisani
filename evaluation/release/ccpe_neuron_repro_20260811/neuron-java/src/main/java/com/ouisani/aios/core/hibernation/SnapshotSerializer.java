package com.ouisani.aios.core.hibernation;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.cache.kvstate.KvCacheRef;
import com.ouisani.aios.core.cache.kvstate.KvCacheRegistry;
import com.ouisani.aios.core.cache.kvstate.KvCacheVfsStore;
import com.ouisani.aios.core.ipc.SharedMemoryManager;
import com.ouisani.aios.core.ipc.SemanticMemoryBlock;
import com.ouisani.aios.core.ipc.VariablePool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 快照序列化器 — 将 {@link AgentSnapshot} 序列化为 {@code .aios_snapshot} 文件。
 * <p>
 * 借鉴 LMCache 的序列化机制：
 * <ul>
 *   <li>NaiveSerializer/NaiveDeserializer — 透传序列化</li>
 *   <li>CacheGenSerializer — 分层量化编码</li>
 * </ul>
 * <p>
 * AIOS 使用 Java 序列化 + GZIP 压缩，将 AgentSnapshot 写入 VFS。
 * <p>
 * <h3>文件格式</h3>
 * <pre>
 * /vfs/dev/kvcache/{workspaceId}.aios_snapshot  (GZIP 压缩的 Java 序列化对象)
 * </pre>
 *
 * @see HibernationManager
 * @see AgentSnapshot
 */
public final class SnapshotSerializer {

    private static final Logger log = LoggerFactory.getLogger(SnapshotSerializer.class);

    /** VFS 存储路径 */
    public static final String SNAPSHOT_VFS_PATH = "/dev/kvcache";

    /** 文件后缀 */
    public static final String FILE_SUFFIX = ".aios_snapshot";

    // ── Singleton ──

    private static final class Holder {
        static final SnapshotSerializer INSTANCE = new SnapshotSerializer();
    }

    public static SnapshotSerializer instance() {
        return Holder.INSTANCE;
    }

    private SnapshotSerializer() {
    }

    // ════════════════════════════════════════════════════════════════
    //  序列化 / 反序列化
    // ════════════════════════════════════════════════════════════════

    /**
     * 将 AgentSnapshot 序列化为 GZIP 压缩的字节数组。
     * <p>
     * 借鉴 LMCache 的 serializeForTransfer：使用 GZIP 压缩减少磁盘占用。
     *
     * @param snapshot Agent 快照
     * @return GZIP 压缩的字节数组
     */
    public byte[] serialize(AgentSnapshot snapshot) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             GZIPOutputStream gzip = new GZIPOutputStream(baos);
             ObjectOutputStream oos = new ObjectOutputStream(gzip)) {

            oos.writeObject(snapshot);
            oos.flush();
            gzip.finish();

            byte[] data = baos.toByteArray();
            log.debug("[SnapshotSerializer] 已序列化: {}, size={} bytes (compressed)",
                    snapshot.workspaceId(), data.length);
            return data;

        } catch (IOException e) {
            throw new RuntimeException("Serialization failed: " + e.getMessage(), e);
        }
    }

    /**
     * 从 GZIP 压缩的字节数组反序列化为 AgentSnapshot。
     *
     * @param data GZIP 压缩的字节数组
     * @return AgentSnapshot
     */
    public AgentSnapshot deserialize(byte[] data) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             GZIPInputStream gzip = new GZIPInputStream(bais);
             ObjectInputStream ois = new ObjectInputStream(gzip)) {

            AgentSnapshot snapshot = (AgentSnapshot) ois.readObject();
            log.debug("[SnapshotSerializer] 已反序列化: {}, size={} bytes",
                    snapshot.workspaceId(), data.length);
            return snapshot;

        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Deserialization failed: " + e.getMessage(), e);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  VFS 持久化
    // ════════════════════════════════════════════════════════════════

    /**
     * 将 AgentSnapshot 持久化到 VFS。
     * <p>
     * 将序列化后的字节数组以 Base64 编码写入 VFS 路径
     * {@code /dev/kvcache/{workspaceId}.aios_snapshot}。
     *
     * @param snapshot Agent 快照
     * @return true 如果持久化成功
     */
    public boolean saveToVfs(AgentSnapshot snapshot) {
        String vfsPath = buildPath(snapshot.workspaceId());
        try {
            byte[] data = serialize(snapshot);
            // VFS 的 write 接受 String，使用 Base64 编码二进制数据
            String base64 = Base64.getEncoder().encodeToString(data);

            VfsManager vfs = VfsManager.instance();
            vfs.writeText(vfsPath, base64);

            log.info("[SnapshotSerializer] 已持久化快照 → {} ({} bytes → {} base64 chars)",
                    vfsPath, data.length, base64.length());
            return true;

        } catch (Exception e) {
            log.error("[SnapshotSerializer] 持久化失败: workspace={}, error={}",
                    snapshot.workspaceId(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * 从 VFS 加载 AgentSnapshot。
     *
     * @param workspaceId 工作区标识
     * @return AgentSnapshot，如果文件不存在返回 null
     */
    public AgentSnapshot loadFromVfs(String workspaceId) {
        String vfsPath = buildPath(workspaceId);
        try {
            VfsManager vfs = VfsManager.instance();
            if (!vfs.exists(vfsPath)) {
                log.debug("[SnapshotSerializer] 快照文件不存在: {}", vfsPath);
                return null;
            }
            String base64 = vfs.readText(vfsPath);
            if (base64 == null || base64.isBlank()) {
                return null;
            }
            byte[] data = Base64.getDecoder().decode(base64);
            AgentSnapshot snapshot = deserialize(data);

            log.info("[SnapshotSerializer] 已加载快照 ← {} ({} base64 chars → {} bytes)",
                    vfsPath, base64.length(), data.length);
            return snapshot;

        } catch (Exception e) {
            log.error("[SnapshotSerializer] 加载失败: workspace={}, error={}",
                    workspaceId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 删除 VFS 中的快照文件。
     *
     * @param workspaceId 工作区标识
     * @return true 如果删除成功
     */
    public boolean deleteFromVfs(String workspaceId) {
        String vfsPath = buildPath(workspaceId);
        try {
            VfsManager vfs = VfsManager.instance();
            if (vfs.exists(vfsPath)) {
                vfs.writeText(vfsPath, "");
                log.info("[SnapshotSerializer] 已删除快照: {}", vfsPath);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("[SnapshotSerializer] 删除失败: workspace={}, error={}",
                    workspaceId, e.getMessage());
            return false;
        }
    }

    /**
     * 检查快照是否存在。
     *
     * @param workspaceId 工作区标识
     * @return true 如果存在
     */
    public boolean snapshotExists(String workspaceId) {
        try {
            return VfsManager.instance().exists(buildPath(workspaceId));
        } catch (Exception e) {
            return false;
        }
    }

    private String buildPath(String workspaceId) {
        return SNAPSHOT_VFS_PATH + "/" + workspaceId + FILE_SUFFIX;
    }
}
