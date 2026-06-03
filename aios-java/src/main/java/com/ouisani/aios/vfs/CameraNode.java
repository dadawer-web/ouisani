package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsNode;

public non-sealed class CameraNode implements VfsNode {

    private final String path;
    private int ownerUid;
    private int permissions;

    public CameraNode(String path) {
        this(path, 0, 0444);
    }

    public CameraNode(String path, int ownerUid, int permissions) {
        this.path = path;
        this.ownerUid = ownerUid;
        this.permissions = permissions;
    }

    @Override
    public VfsNodeType nodeType() {
        return VfsNodeType.CAMERA;
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
        String json = "{\"type\":\"image_b64\", \"scene\": \"一名黑客正在盯着屏幕敲代码\", \"timestamp\": " + System.currentTimeMillis() + "}";
        log.debug("CameraNode.read: path={}, captured frame", path);
        return json;
    }

    @Override
    public boolean write(String data) {
        throw new UnsupportedOperationException("CameraNode is read-only: " + path);
    }
}
