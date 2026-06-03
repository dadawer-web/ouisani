package com.ouisani.aios.core.ipc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared Memory Segment Manager (SHM IPC) for AIOS.
 * <p>
 * Manages named shared memory segments that multiple agents can read/write
 * concurrently through VFS paths like {@code /dev/shm/<segmentId>}.
 * Each segment is a key-value map ({@code Map<String, String>}), enabling
 * structured zero-copy cross-agent communication.
 */
public final class SharedMemoryManager {

    private static final Logger log = LoggerFactory.getLogger(SharedMemoryManager.class);

    private static final class Holder {
        static final SharedMemoryManager INSTANCE = new SharedMemoryManager();
    }

    public static SharedMemoryManager instance() {
        return Holder.INSTANCE;
    }

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> shmSegments = new ConcurrentHashMap<>();

    private SharedMemoryManager() {}

    /**
     * Create a new shared memory segment, or get the existing one.
     *
     * @param segmentId the segment identifier
     * @return the key-value map for this segment
     */
    public ConcurrentHashMap<String, String> getOrCreateSegment(String segmentId) {
        return shmSegments.computeIfAbsent(segmentId, id -> {
            log.info("[SHM] Segment created: {}", id);
            return new ConcurrentHashMap<>();
        });
    }

    /**
     * Get a segment if it exists.
     *
     * @param segmentId the segment identifier
     * @return the key-value map, or null if not found
     */
    public ConcurrentHashMap<String, String> getSegment(String segmentId) {
        return shmSegments.get(segmentId);
    }

    /**
     * Destroy a shared memory segment.
     *
     * @param segmentId the segment identifier
     * @return true if the segment was removed
     */
    public boolean destroySegment(String segmentId) {
        ConcurrentHashMap<String, String> removed = shmSegments.remove(segmentId);
        if (removed != null) {
            log.info("[SHM] Segment destroyed: {} (had {} keys)", segmentId, removed.size());
            return true;
        }
        return false;
    }

    /**
     * List all segment IDs.
     */
    public Set<String> listSegments() {
        return Collections.unmodifiableSet(shmSegments.keySet());
    }

    /**
     * Get the number of active segments.
     */
    public int segmentCount() {
        return shmSegments.size();
    }

    /**
     * Write a key-value pair into a segment (auto-creates the segment if needed).
     *
     * @param segmentId the segment identifier
     * @param key       the key
     * @param value     the value
     */
    public void put(String segmentId, String key, String value) {
        getOrCreateSegment(segmentId).put(key, value);
        log.debug("[SHM] put: segment={}, key={}, valueLen={}", segmentId, key, value.length());
    }

    /**
     * Read a value from a segment.
     *
     * @param segmentId the segment identifier
     * @param key       the key
     * @return the value, or null if not found
     */
    public String get(String segmentId, String key) {
        Map<String, String> segment = shmSegments.get(segmentId);
        if (segment == null) return null;
        return segment.get(key);
    }

    /**
     * Dump the entire contents of a segment as a JSON-like string.
     *
     * @param segmentId the segment identifier
     * @return the full key-value contents, or empty string if segment not found
     */
    public String dumpSegment(String segmentId) {
        Map<String, String> segment = shmSegments.get(segmentId);
        if (segment == null) return "";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : segment.entrySet()) {
            if (!first) sb.append(", ");
            sb.append("\"").append(entry.getKey()).append("\": \"")
                    .append(entry.getValue().length() > 200
                            ? entry.getValue().substring(0, 200) + "..."
                            : entry.getValue())
                    .append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }
}
