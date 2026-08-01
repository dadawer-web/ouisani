package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 可变文件节点 — 在内存中实际存储内容的 VFS 文件节点。
 * <p>
 * 与不可变的 {@link VfsNode.FileNode}（丢弃写入、始终读取空内容）不同，
 * 此节点通过 {@link AtomicReference} 持久化数据，适用于 Agent 间通过
 * VFS 状态文件进行协调、PRD 文档、代码产物和构建日志等场景。
 *
 * <h3>OS 类比</h3>
 * 类比 Linux 的 tmpfs 或 ramfs — 数据存储在内存中，
 * 读写速度快但进程退出后数据丢失。
 */
public non-sealed class MutableFileNode implements VfsNode {

    private static final Logger log = LoggerFactory.getLogger(MutableFileNode.class);

    private final String path;
    private final AtomicReference<String> content = new AtomicReference<>("");
    private int ownerUid;
    private int permissions;
    private String ownerTenantId;

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
    public String ownerTenantId() {
        return ownerTenantId;
    }

    public void setOwnerTenantId(String tenantId) {
        this.ownerTenantId = tenantId;
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
