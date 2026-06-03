package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.network.EventBus;

public non-sealed class DisplayNode implements VfsNode {

    private final String path;
    private int ownerUid;
    private int permissions;

    public DisplayNode(String path) {
        this(path, 0, 0222);
    }

    public DisplayNode(String path, int ownerUid, int permissions) {
        this.path = path;
        this.ownerUid = ownerUid;
        this.permissions = permissions;
    }

    @Override
    public VfsNodeType nodeType() {
        return VfsNodeType.DISPLAY;
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
        throw new UnsupportedOperationException("DisplayNode is write-only: " + path);
    }

    @Override
    public boolean write(String payload) {
        EventBus.instance().broadcast("ui_render", payload);
        log.debug("DisplayNode.write: path={}, broadcasted ui_render ({} chars)", path, payload.length());
        return true;
    }
}
