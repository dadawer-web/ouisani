package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 宿主机源码节点 — AIOS 与宿主机文件系统的桥梁。
 * <p>
 * 将 VFS 路径映射到宿主机的真实物理文件路径，使 Agent 可以读写
 * 宿主机上的实际文件。这是 AIOS "打破第四面墙"的核心机制之一。
 *
 * <h3>OS 类比</h3>
 * 类比 Linux 的 bind mount（{@code mount --bind}）—
 * 将一个目录树挂载到另一个位置，实现文件系统的透明映射。
 */
public non-sealed class HostSourceNode implements VfsNode {

    private static final Logger log = LoggerFactory.getLogger(HostSourceNode.class);

    private final String path;
    private final String realPhysicalPath;
    private int ownerUid;
    private int permissions;
    private String ownerTenantId;

    public HostSourceNode(String vfsPath, String realPhysicalPath) {
        this(vfsPath, realPhysicalPath, 0, 0666);
    }

    public HostSourceNode(String vfsPath, String realPhysicalPath, int ownerUid, int permissions) {
        this.path = vfsPath;
        this.realPhysicalPath = realPhysicalPath;
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

    public String realPhysicalPath() {
        return realPhysicalPath;
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
        try {
            Path physical = Path.of(realPhysicalPath);
            if (!Files.exists(physical)) {
                String msg = "[HostSourceNode] Physical file not found: " + realPhysicalPath;
                log.warn(msg);
                return msg;
            }
            String content = Files.readString(physical);
            log.debug("[HostSourceNode] Read from physical path '{}': {} chars", realPhysicalPath, content.length());
            return content;
        } catch (Exception e) {
            log.error("[HostSourceNode] Read failed for '{}': {}", realPhysicalPath, e.getMessage());
            return "[HostSourceNode] Read error: " + e.getMessage();
        }
    }

    @Override
    public boolean write(String payload) {
        try {
            Path physical = Path.of(realPhysicalPath);
            // 确保父目录存在
            if (physical.getParent() != null) {
                Files.createDirectories(physical.getParent());
            }
            Files.writeString(physical, payload, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            System.out.printf("  💾 [HostSourceNode] 已写入 %d 字符至物理路径: %s%n", payload.length(), realPhysicalPath);
            log.info("[HostSourceNode] 已写入 {} 字符至物理路径 '{}'", payload.length(), realPhysicalPath);
            return true;
        } catch (Exception e) {
            log.error("[HostSourceNode] Write failed for '{}': {}", realPhysicalPath, e.getMessage());
            System.err.printf("  ❌ [HostSourceNode] Write error: %s%n", e.getMessage());
            return false;
        }
    }
}
