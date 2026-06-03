package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.ipc.SharedMemoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared Memory VFS Node — a blackboard that multiple agents can read/write
 * concurrently through a VFS path like {@code /dev/shm/segment_alpha}.
 * <p>
 * Write format: {@code key=value} (writes a single key-value pair into the segment).<br>
 * Read format: returns the full segment contents as a JSON-like string.
 * <p>
 * Thread safety is guaranteed by {@link ConcurrentHashMap} backing each segment.
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
     * Read the entire shared memory segment.
     * Returns all key-value pairs as a JSON-like string.
     */
    @Override
    public String read() {
        String dump = SharedMemoryManager.instance().dumpSegment(segmentId);
        log.debug("[SHM] read: segment={}, resultLen={}", segmentId, dump.length());
        return dump;
    }

    /**
     * Write a key-value pair into the shared memory segment.
     * Expected format: {@code key=value}
     * If the value contains '=', only the first '=' is treated as the delimiter.
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
