package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.ipc.SharedMemoryManager;
import com.ouisani.aios.core.tool.DataTypes;
import com.ouisani.aios.core.tool.Port;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 共享内存 VFS 节点 — 多个 Agent 可并发读写的黑板（Blackboard）。
 * <p>
 * 通过 VFS 路径（如 {@code /dev/shm/segment_alpha}）访问，
 * 多个 Agent 可以同时读写同一共享内存段进行协调。
 * <p>
 * 写入格式：{@code key=value}（向段中写入单个键值对）<br>
 * 读取格式：返回段的完整内容（JSON 风格字符串）
 * <p>
 * 线程安全由 {@link ConcurrentHashMap} 支撑的每个段保证。
 *
 * <h3>OS 类比</h3>
 * 类比 Linux 的 POSIX 共享内存（shm_open/mmap）— 多进程通过
 * 共享内存区域进行高速数据交换，但 AIOS 使用键值对而非原始字节。
 */
public non-sealed class ShmNode implements VfsNode {

    private static final Logger log = LoggerFactory.getLogger(ShmNode.class);

    private final String path;
    private final String segmentId;
    private int ownerUid;
    private int permissions;

    public ShmNode(String path, String segmentId) {
        this(path, segmentId, 0, 0666);
    }

    public ShmNode(String path, String segmentId, int ownerUid, int permissions) {
        this.path = path;
        this.segmentId = segmentId;
        this.ownerUid = ownerUid;
        this.permissions = permissions;
        // Ensure the segment exists in the manager
        SharedMemoryManager.instance().getOrCreateSegment(segmentId);
        log.info("[SHM] ShmNode created: path={}, segmentId={}", path, segmentId);
    }

    public String segmentId() {
        return segmentId;
    }

    @Override
    public VfsNodeType nodeType() {
        return VfsNodeType.DEVICE;
    }

    // ── 强类型 I/O 契约 ──
    @Override
    public List<Port> inputPorts() {
        return List.of(new Port("entry", DataTypes.PLAIN_TEXT,
                "key=value 格式键值对（write 入口）", true));
    }

    @Override
    public List<Port> outputPorts() {
        return List.of(new Port("dump", DataTypes.JSON_DATA,
                "共享内存段全量 dump（read 出口）", true));
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
        this.ownerUid = uid;
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
     * 读取整个共享内存段。
     * 返回所有键值对，格式为 JSON 风格字符串。
     */
    @Override
    public String read() {
        String dump = SharedMemoryManager.instance().dumpSegment(segmentId);
        log.debug("[SHM] read: segment={}, resultLen={}", segmentId, dump.length());
        return dump;
    }

    /**
     * 向共享内存段写入键值对。
     * 期望格式：{@code key=value}，如果值中包含 '='，只有第一个 '=' 被视为分隔符。
     */
    @Override
    public boolean write(String data) {
        if (data == null || data.isEmpty()) {
            log.warn("[SHM] write: empty data to segment={}", segmentId);
            return false;
        }

        int eqIdx = data.indexOf('=');
        if (eqIdx <= 0) {
            log.warn("[SHM] write: invalid format (expected key=value), segment={}", segmentId);
            return false;
        }

        String key = data.substring(0, eqIdx);
        String value = data.substring(eqIdx + 1);

        SharedMemoryManager.instance().put(segmentId, key, value);
        log.debug("[SHM] write: segment={}, key={}, valueLen={}", segmentId, key, value.length());
        return true;
    }

    @Override
    public String toString() {
        return "ShmNode{path='%s', segmentId='%s'}".formatted(path, segmentId);
    }
}
