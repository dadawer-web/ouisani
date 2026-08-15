package com.ouisani.aios.core.cache.kvstate;

import com.ouisani.aios.core.VfsManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * KV Cache VFS 持久化存储 — 将 KV Cache 引用持久化到 VFS {@code /dev/kvcache} 路径。
 * <p>
 * 借鉴 LMCache 的 LocalDiskBackend：将 KV Cache 序列化到磁盘。
 * AIOS 利用 VFS 作为统一的存储抽象，将 KV Cache 引用写入 {@code /dev/kvcache/} 路径。
 * <p>
 * <h3>落地场景</h3>
 * 当你今天关闭了"408 考研伴学平台"的工作流，AIOS 会指挥本地大模型
 * 把当前所有的 KV Cache 序列化，写进 {@code /vfs/dev/kvcache/408_session.json}。
 * <p>
 * 明天你再打开项目时，AIOS 将这个文件重新读取，把 KV Cache 引用
 * 推回推理引擎。你的 AI 直接"秒醒"，拥有昨天完整的短期记忆，
 * 完全不需要重新"预热（Prefill）"。
 * <p>
 * <h3>序列化格式</h3>
 * 使用紧凑的行格式（每行一个 KV Cache 引用），便于增量追加和流式读取：
 * <pre>
 * lmcache://global_ast_v1|qwen2.5-32b|0|8192|a1b2c3d4...|1700000000000|1700000000000|0
 * lmcache://api_docs_v2|qwen2.5-32b|8192|16384|e5f6g7h8...|1700000001000|1700000001000|1
 * </pre>
 *
 * @see KvCacheRegistry
 * @see KvCacheRef
 */
public final class KvCacheVfsStore {

    private static final Logger log = LoggerFactory.getLogger(KvCacheVfsStore.class);

    /** VFS 挂载路径 */
    public static final String KVCACHE_VFS_PATH = "/dev/kvcache";

    /** 文件后缀 */
    public static final String FILE_SUFFIX = ".kvcache";

    // ── Singleton ──

    private static final class Holder {
        static final KvCacheVfsStore INSTANCE = new KvCacheVfsStore();
    }

    public static KvCacheVfsStore instance() {
        return Holder.INSTANCE;
    }

    private KvCacheVfsStore() {
    }

    // ════════════════════════════════════════════════════════════════
    //  持久化
    // ════════════════════════════════════════════════════════════════

    /**
     * 将所有 KV Cache 引用持久化到 VFS。
     * <p>
     * 借鉴 LMCache 的 LocalDiskBackend.put：将 MemoryObj 序列化到磁盘。
     * AIOS 将 KvCacheRef 列表序列化为行格式文本，写入 VFS。
     *
     * @param workspaceId 工作区标识（用于生成文件名）
     * @return true 如果持久化成功
     */
    public boolean saveToVfs(String workspaceId) {
        return saveToVfs(workspaceId, KvCacheRegistry.instance().snapshot());
    }

    /**
     * 将指定的 KV Cache 引用列表持久化到 VFS。
     *
     * @param workspaceId 工作区标识
     * @param refs         KV Cache 引用列表
     * @return true 如果持久化成功
     */
    public boolean saveToVfs(String workspaceId, List<KvCacheRef> refs) {
        String vfsPath = buildPath(workspaceId);
        String serialized = serialize(refs);

        try {
            VfsManager vfs = VfsManager.instance();
            vfs.writeText(vfsPath, serialized);
            log.info("[KvCacheVfsStore] 已持久化 {} 个 KV Cache 引用 → {} ({} bytes)",
                    refs.size(), vfsPath, serialized.length());
            return true;
        } catch (Exception e) {
            log.error("[KvCacheVfsStore] 持久化失败: workspaceId={}, error={}",
                    workspaceId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 从 VFS 加载 KV Cache 引用。
     * <p>
     * 借鉴 LMCache 的 LocalDiskBackend.get_blocking：从磁盘读取并反序列化。
     *
     * @param workspaceId 工作区标识
     * @return KV Cache 引用列表，如果文件不存在返回空列表
     */
    public List<KvCacheRef> loadFromVfs(String workspaceId) {
        String vfsPath = buildPath(workspaceId);

        try {
            VfsManager vfs = VfsManager.instance();
            if (!vfs.exists(vfsPath)) {
                log.debug("[KvCacheVfsStore] 文件不存在: {}", vfsPath);
                return List.of();
            }
            String content = vfs.readText(vfsPath);
            List<KvCacheRef> refs = deserialize(content);
            log.info("[KvCacheVfsStore] 已加载 {} 个 KV Cache 引用 ← {}",
                    refs.size(), vfsPath);
            return refs;
        } catch (Exception e) {
            log.error("[KvCacheVfsStore] 加载失败: workspaceId={}, error={}",
                    workspaceId, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 从 VFS 加载并恢复到 KvCacheRegistry。
     *
     * @param workspaceId 工作区标识
     * @return 恢复的引用数量
     */
    public int restoreToRegistry(String workspaceId) {
        List<KvCacheRef> refs = loadFromVfs(workspaceId);
        if (refs.isEmpty()) {
            return 0;
        }
        KvCacheRegistry.instance().restore(refs);
        return refs.size();
    }

    /**
     * 删除指定工作区的 KV Cache 快照文件。
     *
     * @param workspaceId 工作区标识
     * @return true 如果删除成功
     */
    public boolean deleteSnapshot(String workspaceId) {
        String vfsPath = buildPath(workspaceId);
        try {
            VfsManager vfs = VfsManager.instance();
            if (vfs.exists(vfsPath)) {
                vfs.writeText(vfsPath, "");
                log.info("[KvCacheVfsStore] 已删除: {}", vfsPath);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("[KvCacheVfsStore] 删除失败: workspaceId={}, error={}",
                    workspaceId, e.getMessage());
            return false;
        }
    }

    /**
     * 检查指定工作区的快照是否存在。
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

    // ════════════════════════════════════════════════════════════════
    //  序列化 / 反序列化
    // ════════════════════════════════════════════════════════════════

    /**
     * 将 KV Cache 引用列表序列化为行格式文本。
     * <p>
     * 格式：每行一个引用，字段以 | 分隔。
     * <pre>
     * uri|modelId|tokenStart|tokenEnd|contentHash|createdAt|lastAccessedAt|refCount
     * </pre>
     */
    String serialize(List<KvCacheRef> refs) {
        StringBuilder sb = new StringBuilder();
        for (KvCacheRef ref : refs) {
            sb.append(ref.kvTensorUri()).append('|')
                    .append(ref.modelId()).append('|')
                    .append(ref.tokenStart()).append('|')
                    .append(ref.tokenEnd()).append('|')
                    .append(ref.contentHash()).append('|')
                    .append(ref.createdAt()).append('|')
                    .append(ref.lastAccessedAt()).append('|')
                    .append(ref.refCount()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 从行格式文本反序列化为 KV Cache 引用列表。
     */
    List<KvCacheRef> deserialize(String content) {
        List<KvCacheRef> refs = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return refs;
        }
        for (String line : content.split("\n")) {
            if (line.isBlank()) continue;
            KvCacheRef ref = parseLine(line);
            if (ref != null) {
                refs.add(ref);
            }
        }
        return refs;
    }

    private KvCacheRef parseLine(String line) {
        String[] parts = line.split("\\|", -1);
        if (parts.length < 8) {
            log.warn("[KvCacheVfsStore] 无效的行格式: {}", line);
            return null;
        }
        try {
            return new KvCacheRef(
                    parts[0],                          // kvTensorUri
                    parts[1],                          // modelId
                    Integer.parseInt(parts[2]),         // tokenStart
                    Integer.parseInt(parts[3]),         // tokenEnd
                    parts[4],                           // contentHash
                    Long.parseLong(parts[5]),          // createdAt
                    Long.parseLong(parts[6]),          // lastAccessedAt
                    Integer.parseInt(parts[7])          // refCount
            );
        } catch (NumberFormatException e) {
            log.warn("[KvCacheVfsStore] 解析失败: line={}, error={}", line, e.getMessage());
            return null;
        }
    }

    private String buildPath(String workspaceId) {
        return KVCACHE_VFS_PATH + "/" + workspaceId + FILE_SUFFIX;
    }
}
