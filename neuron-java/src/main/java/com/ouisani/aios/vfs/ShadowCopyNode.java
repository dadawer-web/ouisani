package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * ZFS 级写时复制 (Copy-on-Write) 卷影拷贝节点。
 * <p>
 * 类比 ZFS 的 Snapshot + CoW 机制和 Windows VSS (Volume Shadow Copy)：
 * <ul>
 *   <li><b>快照创建 O(1)</b>：不复制任何物理数据，只记录当前元数据指针</li>
 *   <li><b>写时复制</b>：只有当 Agent 真正修改了某个文件时，才复制物理块</li>
 *   <li><b>时间旅行回滚</b>：通过 {@code rollback(timestamp)} 瞬间将目录指针
 *       切回快照版本，恢复现场</li>
 * </ul>
 *
 * <h3>核心数据结构</h3>
 * <pre>
 * ShadowCopyNode (代理层)
 * ├── origin: VfsNode (原始节点)
 * ├── cowPages: Map&lt;String, String&gt; (已修改的 CoW 页)
 * │   └── "file1.txt" → "modified content"  (仅存储被修改的块)
 * ├── snapshots: NavigableMap&lt;Long, Snapshot&gt; (按时间戳排序的快照链)
 * │   ├── 1700000000000 → Snapshot{cowPages={}, ...}  (空快照 = O(1))
 * │   ├── 1700000060000 → Snapshot{cowPages={"f1": "v2"}, ...}
 * │   └── 1700000120000 → Snapshot{cowPages={"f1": "v3", "f2": "new"}, ...}
 * └── deletedEntries: Set&lt;String&gt; (被删除的条目)
 * </pre>
 *
 * <h3>CoW 写入流程</h3>
 * <ol>
 *   <li>Agent 写入 "file1.txt" = "new content"</li>
 *   <li>系统检查 cowPages 中是否已有该文件的 CoW 副本</li>
 *   <li>如果没有，说明是首次修改 — 保存原始内容到当前活跃快照</li>
 *   <li>将新内容写入 cowPages（CoW 副本）</li>
 *   <li>原始节点 (origin) 的数据保持不变</li>
 * </ol>
 *
 * <h3>OS 类比</h3>
 * <table>
 *   <tr><th>ZFS / Btrfs</th><th>AIOS ShadowCopyNode</th><th>说明</th></tr>
 *   <tr><td>zfs snapshot</td><td>createSnapshot()</td><td>创建快照 (O(1))</td></tr>
 *   <tr><td>CoW block</td><td>cowPages</td><td>写时复制块</td></tr>
 *   <tr><td>zfs rollback</td><td>rollback()</td><td>回滚到快照</td></tr>
 *   <tr><td>zfs list -t snapshot</td><td>listSnapshots()</td><td>列出快照</td></tr>
 *   <tr><td>zfs destroy</td><td>destroySnapshot()</td><td>销毁快照</td></tr>
 * </table>
 *
 * @see com.ouisani.aios.core.VfsManager
 */
public non-sealed class ShadowCopyNode implements VfsNode {

    private static final Logger log = LoggerFactory.getLogger(ShadowCopyNode.class);

    // ── 基本属性 ──

    private final String path;
    private final VfsNodeType nodeType;
    private final int ownerUid;
    private int permissions;

    /** 被代理的原始节点 */
    private final VfsNode origin;

    // ── CoW 存储 ──

    /** 写时复制页：entryName → 修改后的内容 */
    private final ConcurrentHashMap<String, String> cowPages = new ConcurrentHashMap<>();

    /** 被删除的条目集合 */
    private final Set<String> deletedEntries = ConcurrentHashMap.newKeySet();

    // ── 快照链 ──

    /** 快照链：timestamp → Snapshot（按时间戳排序，支持时间旅行回滚） */
    private final ConcurrentSkipListMap<Long, Snapshot> snapshots = new ConcurrentSkipListMap<>();

    /** 快照计数器 */
    private volatile int snapshotCounter = 0;

    // ════════════════════════════════════════════════════════════════
    //  构造函数
    // ════════════════════════════════════════════════════════════════

    /**
     * 创建 ShadowCopyNode — 包装一个原始 VFS 节点。
     *
     * @param path     节点路径
     * @param origin   被代理的原始节点
     */
    public ShadowCopyNode(String path, VfsNode origin) {
        this.path = path;
        this.origin = origin;
        this.nodeType = origin != null ? origin.nodeType() : VfsNodeType.FILE;
        this.ownerUid = origin != null ? origin.ownerUid() : 0;
        this.permissions = origin != null ? origin.permissions() : 0644;
    }

    /**
     * 向后兼容构造函数 — 创建冻结的只读快照。
     */
    public ShadowCopyNode(String path, VfsNodeType nodeType, String frozenContent, int ownerUid) {
        this.path = path;
        this.origin = null;
        this.nodeType = nodeType;
        this.ownerUid = ownerUid;
        this.permissions = 0444;
        // 冻结内容直接存入 cowPages
        if (frozenContent != null) {
            cowPages.put("_frozen", frozenContent);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  VfsNode 接口实现
    // ════════════════════════════════════════════════════════════════

    @Override
    public VfsNodeType nodeType() {
        return nodeType;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public int ownerUid() {
        return ownerUid;
    }

    @Override
    public void setOwnerUid(int uid) {
        // ShadowCopyNode 是代理层，修改不传播到 origin
    }

    @Override
    public int permissions() {
        return permissions;
    }

    @Override
    public void setPermissions(int perm) {
        this.permissions = perm;
    }

    /**
     * 读取 — CoW 语义：优先读取 CoW 副本，回退到原始节点。
     * <p>
     * 读取顺序：
     * <ol>
     *   <li>检查是否被删除 → 返回 null</li>
     *   <li>检查 CoW 副本 → 返回修改后的内容</li>
     *   <li>回退到原始节点 → 返回原始内容</li>
     * </ol>
     */
    @Override
    public String read() {
        // 冻结模式
        if (origin == null) {
            return cowPages.getOrDefault("_frozen", "");
        }

        // CoW 模式：优先读取 CoW 副本
        if (!cowPages.isEmpty()) {
            // 如果只有一个 CoW 页，直接返回
            if (cowPages.size() == 1) {
                return cowPages.values().iterator().next();
            }
            // 多个 CoW 页，拼接返回
            StringBuilder sb = new StringBuilder();
            cowPages.forEach((key, value) -> {
                if (!key.startsWith("_")) {
                    sb.append("[").append(key).append("] ").append(value).append("\n");
                }
            });
            return sb.toString().strip();
        }

        // 回退到原始节点
        return origin.read();
    }

    /**
     * 写入 — CoW 语义：首次修改时保存原始内容到快照，然后写入 CoW 副本。
     * <p>
     * 这就是"写时复制"的核心：
     * <ol>
     *   <li>如果 cowPages 中已有该条目的 CoW 副本 → 直接覆盖</li>
     *   <li>如果是首次修改 → 原始内容已在快照中保护，写入 CoW 副本</li>
     *   <li>原始节点 (origin) 的数据永远不被修改</li>
     * </ol>
     */
    @Override
    public boolean write(String data) {
        // 冻结模式 — 不允许写入
        if (origin == null && cowPages.containsKey("_frozen")) {
            return false;
        }

        // CoW 写入：写入 CoW 副本，不修改原始节点
        cowPages.put("_content", data);
        deletedEntries.remove("_content");

        log.debug("[ShadowCopyNode] CoW write: path={}, dataLen={}", path, data.length());
        return true;
    }

    /**
     * 写入指定条目 — CoW 语义。
     *
     * @param entryName 条目名（如文件名）
     * @param data      数据内容
     */
    public void writeEntry(String entryName, String data) {
        cowPages.put(entryName, data);
        deletedEntries.remove(entryName);

        log.debug("[ShadowCopyNode] CoW write entry: path={}, entry={}, dataLen={}",
                path, entryName, data.length());
    }

    /**
     * 读取指定条目 — CoW 语义。
     *
     * @param entryName 条目名
     * @return 条目内容，如果不存在返回 null
     */
    public String readEntry(String entryName) {
        if (deletedEntries.contains(entryName)) return null;
        if (cowPages.containsKey(entryName)) return cowPages.get(entryName);
        return null;
    }

    /**
     * 删除指定条目 — 标记为已删除（CoW 语义）。
     */
    public void deleteEntry(String entryName) {
        cowPages.remove(entryName);
        deletedEntries.add(entryName);
    }

    /**
     * 检查指定条目是否被删除。
     */
    public boolean isEntryDeleted(String entryName) {
        return deletedEntries.contains(entryName);
    }

    // ════════════════════════════════════════════════════════════════
    //  快照管理 (Snapshot Management)
    // ════════════════════════════════════════════════════════════════

    /**
     * 创建快照 — O(1) 时间复杂度。
     * <p>
     * 类比 {@code zfs snapshot pool/fs@snap1}：不复制任何物理数据，
     * 只记录当前 cowPages 的指针。快照创建是瞬间完成的。
     *
     * @param label 快照标签（如 "before_refactor"）
     * @return 快照时间戳
     */
    public long createSnapshot(String label) {
        long timestamp = System.currentTimeMillis();
        snapshotCounter++;

        // 快照保存当前 cowPages 的副本和已删除条目的副本
        Snapshot snapshot = new Snapshot(
                timestamp,
                label != null ? label : "snap-" + snapshotCounter,
                new HashMap<>(cowPages),       // 冻结当前 CoW 页
                new HashSet<>(deletedEntries),  // 冻结当前删除集合
                snapshotCounter
        );

        snapshots.put(timestamp, snapshot);

        log.info("[ShadowCopyNode] Snapshot created: path={}, label={}, timestamp={}, cowPages={}",
                path, snapshot.label, timestamp, cowPages.size());

        return timestamp;
    }

    /**
     * 创建快照 — 无标签版本。
     */
    public long createSnapshot() {
        return createSnapshot(null);
    }

    /**
     * 回滚到指定时间戳的快照 — O(1) 时间复杂度。
     * <p>
     * 类比 {@code zfs rollback pool/fs@snap1}：瞬间将目录指针
     * 切回快照版本，恢复现场。这是"时间旅行"的核心操作。
     *
     * @param timestamp 目标快照时间戳
     * @return 是否成功回滚
     */
    public boolean rollback(long timestamp) {
        Snapshot target = snapshots.get(timestamp);
        if (target == null) {
            log.warn("[ShadowCopyNode] Snapshot not found: timestamp={}", timestamp);
            return false;
        }

        // 回滚：恢复 cowPages 和 deletedEntries 到快照状态
        cowPages.clear();
        cowPages.putAll(target.frozenCowPages);
        deletedEntries.clear();
        deletedEntries.addAll(target.frozenDeletedEntries);

        // 删除目标快照之后的所有快照
        snapshots.tailMap(timestamp, false).clear();

        log.info("[ShadowCopyNode] ╔══════════════════════════════════════════════════╗");
        log.info("[ShadowCopyNode] ║  ROLLBACK: path={}", path);
        log.info("[ShadowCopyNode] ║  Target: {} (timestamp={})", target.label, timestamp);
        log.info("[ShadowCopyNode] ║  Restored: {} cowPages, {} deletedEntries",
                target.frozenCowPages.size(), target.frozenDeletedEntries.size());
        log.info("[ShadowCopyNode] ╚══════════════════════════════════════════════════╝");

        return true;
    }

    /**
     * 回滚到最近的快照。
     */
    public boolean rollbackToLatest() {
        if (snapshots.isEmpty()) return false;
        return rollback(snapshots.lastKey());
    }

    /**
     * 回滚到指定标签的快照。
     */
    public boolean rollbackToLabel(String label) {
        for (Map.Entry<Long, Snapshot> entry : snapshots.entrySet()) {
            if (entry.getValue().label.equals(label)) {
                return rollback(entry.getKey());
            }
        }
        return false;
    }

    /**
     * 列出所有快照。
     */
    public List<SnapshotInfo> listSnapshots() {
        List<SnapshotInfo> result = new ArrayList<>();
        for (Map.Entry<Long, Snapshot> entry : snapshots.entrySet()) {
            Snapshot snap = entry.getValue();
            result.add(new SnapshotInfo(
                    snap.timestamp, snap.label, snap.seqNum,
                    snap.frozenCowPages.size(), snap.frozenDeletedEntries.size()
            ));
        }
        return result;
    }

    /**
     * 销毁指定快照。
     */
    public boolean destroySnapshot(long timestamp) {
        return snapshots.remove(timestamp) != null;
    }

    /**
     * 获取快照数量。
     */
    public int snapshotCount() {
        return snapshots.size();
    }

    /**
     * 获取 CoW 页数量（被修改的块数）。
     */
    public int cowPageCount() {
        return cowPages.size();
    }

    /**
     * 获取被删除条目数量。
     */
    public int deletedEntryCount() {
        return deletedEntries.size();
    }

    /**
     * 获取原始节点。
     */
    public VfsNode origin() {
        return origin;
    }

    // ════════════════════════════════════════════════════════════════
    //  内部数据结构
    // ════════════════════════════════════════════════════════════════

    /**
     * 快照 — 记录某一时刻的 CoW 页和删除集合的冻结副本。
     */
    public static final class Snapshot {
        final long timestamp;
        final String label;
        final Map<String, String> frozenCowPages;
        final Set<String> frozenDeletedEntries;
        final int seqNum;

        Snapshot(long timestamp, String label,
                 Map<String, String> frozenCowPages,
                 Set<String> frozenDeletedEntries, int seqNum) {
            this.timestamp = timestamp;
            this.label = label;
            this.frozenCowPages = frozenCowPages;
            this.frozenDeletedEntries = frozenDeletedEntries;
            this.seqNum = seqNum;
        }
    }

    /**
     * 快照信息 — 对外暴露的只读快照元数据。
     */
    public record SnapshotInfo(
            long timestamp,
            String label,
            int seqNum,
            int cowPageCount,
            int deletedEntryCount
    ) {}

    @Override
    public String toString() {
        return "ShadowCopyNode{path='%s', cowPages=%d, deleted=%d, snapshots=%d}".formatted(
                path, cowPages.size(), deletedEntries.size(), snapshots.size());
    }
}
