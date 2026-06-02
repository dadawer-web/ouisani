package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public non-sealed class PipeNode implements VfsNode {

    private static final Logger log = LoggerFactory.getLogger(PipeNode.class);
    private static final int DEFAULT_CAPACITY = 64;

    private final String path;
    private final LinkedBlockingQueue<String> buffer;
    private final AtomicLong totalWritten = new AtomicLong(0);
    private final AtomicLong totalRead = new AtomicLong(0);
    private int ownerUid;
    private int permissions;

    public PipeNode(String path) {
        this(path, DEFAULT_CAPACITY, 0, 0644);
    }

    public PipeNode(String path, int capacity) {
        this(path, capacity, 0, 0644);
    }

    public PipeNode(String path, int capacity, int ownerUid, int permissions) {
        this.path = path;
        this.buffer = new LinkedBlockingQueue<>(capacity);
        this.ownerUid = ownerUid;
        this.permissions = permissions;
        log.info("PipeNode created: path={}, capacity={}", path, capacity);
    }

    @Override
    public VfsNodeType nodeType() {
        return VfsNodeType.PIPE;
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

    @Override
    public String read() {
        try {
            return take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("PipeNode.read interrupted: path={}", path);
            return "";
        }
    }

    @Override
    public boolean write(String data) {
        try {
            put(data);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("PipeNode.write interrupted: path={}", path);
            return false;
        }
    }

    public void put(String data) throws InterruptedException {
        log.trace("PipeNode.put: path={}, bufferSize={}, dataLen={}", path, buffer.size(), data.length());
        buffer.put(data);
        long written = totalWritten.incrementAndGet();
        if (written % 100 == 0) {
            log.debug("PipeNode progress: path={}, totalWritten={}, bufferSize={}", path, written, buffer.size());
        }
    }

    public String take() throws InterruptedException {
        String data = buffer.take();
        totalRead.incrementAndGet();
        log.trace("PipeNode.take: path={}, bufferSize={}, dataLen={}", path, buffer.size(), data.length());
        return data;
    }

    public String poll() {
        String data = buffer.poll();
        if (data != null) {
            totalRead.incrementAndGet();
        }
        return data;
    }

    public int bufferSize() {
        return buffer.size();
    }

    public int remainingCapacity() {
        return buffer.remainingCapacity();
    }

    public long totalWritten() {
        return totalWritten.get();
    }

    public long totalRead() {
        return totalRead.get();
    }

    public PipeStats stats() {
        return new PipeStats(path, buffer.size(), buffer.remainingCapacity(),
                totalWritten.get(), totalRead.get());
    }

    @Override
    public String toString() {
        return "PipeNode{path='%s', buffer=%d/%d, written=%d, read=%d}"
                .formatted(path, buffer.size(), buffer.size() + buffer.remainingCapacity(),
                        totalWritten.get(), totalRead.get());
    }

    public record PipeStats(String path, int bufferSize, int remainingCapacity,
                            long totalWritten, long totalRead) {
    }
}
