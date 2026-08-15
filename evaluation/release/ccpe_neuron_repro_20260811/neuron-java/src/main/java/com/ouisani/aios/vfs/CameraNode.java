package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsNode;

/**
 * 摄像头节点 — AIOS 的视觉感知设备。
 * <p>
 * 挂载在 {@code /dev/host/camera}，这是一个只读设备节点。
 * Agent 通过 VFS read 获取当前摄像头捕获的画面描述（JSON 格式）。
 *
 * <h3>OS 类比</h3>
 * 类比 Linux 的 {@code /dev/video0} —
 * 从摄像头设备读取视频帧数据。
 */
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
