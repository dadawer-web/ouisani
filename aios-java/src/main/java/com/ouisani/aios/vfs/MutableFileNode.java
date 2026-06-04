package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * A mutable VFS file node that actually stores content in memory.
 * <p>
 * Unlike the immutable {@link VfsNode.FileNode} record (which discards writes
 * and always reads empty), this node persists data via an {@link AtomicReference},
 * making it suitable for agent-to-agent coordination through VFS status files,
 * PRD documents, code artifacts, and build logs.
 */
public non-sealed class MutableFileNode implements VfsNode {

    private static final Logger log = LoggerFactory.getLogger(MutableFileNode.class);

    private final String path;
    private final AtomicReference<String> content = new AtomicReference<>("");
    private int ownerUid;
    private int permissions;

    public MutableFileNode(String path) {
        this(path, 0, 0644);
    }

    public MutableFileNode(String path, int ownerUid, int permissions) {
        this.path = path;
        this.ownerUid = ownerUid;
        this.permissions = permissions;
    }

    @Override
    public VfsNodeType nodeType() {
        return VfsNodeType.FILE;
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
        return content.get();
    }

    @Override
    public boolean write(String data) {
        content.set(data != null ? data : "");
        log.trace("MutableFileNode.write: path={}, dataLen={}", path, data != null ? data.length() : 0);
        return true;
    }
}
