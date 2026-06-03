package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsNode;

import java.nio.file.ReadOnlyFileSystemException;

/**
 * A frozen, read-only shadow copy of a VFS node (VSS snapshot).
 * <p>
 * Captures the state of a VFS node at a point in time. All write
 * operations are rejected. Used by the VSS shadow copy mechanism
 * to provide safe, concurrent read access to historical data.
 */
public non-sealed class ShadowCopyNode implements VfsNode {

    private final String path;
    private final String frozenContent;
    private final VfsNodeType nodeType;
    private final int ownerUid;

    public ShadowCopyNode(String path, VfsNodeType nodeType, String frozenContent, int ownerUid) {
        this.path = path;
        this.nodeType = nodeType;
        this.frozenContent = frozenContent;
        this.ownerUid = ownerUid;
    }

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
        // Immutable snapshot — no-op
    }

    @Override
    public int permissions() {
        return 0444; // Read-only for all
    }

    @Override
    public void setPermissions(int perm) {
        // Immutable snapshot — no-op
    }

    @Override
    public String read() {
        return frozenContent;
    }

    @Override
    public boolean write(String data) {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public String toString() {
        return "ShadowCopyNode{path='%s', type=%s, contentLen=%d}".formatted(path, nodeType, frozenContent.length());
    }
}
