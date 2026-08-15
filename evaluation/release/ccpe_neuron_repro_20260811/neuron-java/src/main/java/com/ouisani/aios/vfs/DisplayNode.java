package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.network.EventBus;

/**
 * 显示节点 — AIOS 的简单显示输出设备。
 * <p>
 * 这是一个只写设备节点，Agent 向此节点写入的内容会通过 EventBus
 * 广播 {@code "ui_render"} 事件到所有前端。适用于简单的文本/JSON 渲染。
 *
 * <h3>OS 类比</h3>
 * 类比 Linux 的 {@code /dev/fb0}（帧缓冲区）—
 * 向显示设备写入像素数据即可在屏幕上显示。
 */
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
