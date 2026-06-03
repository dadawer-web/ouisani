package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public non-sealed class HostSourceNode implements VfsNode {

    private static final Logger log = LoggerFactory.getLogger(HostSourceNode.class);

    private final String path;
    private final String realPhysicalPath;
    private int ownerUid;
    private int permissions;

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
            System.out.printf("  💾 [HostSourceNode] Written %d chars to physical path: %s%n", payload.length(), realPhysicalPath);
            log.info("[HostSourceNode] Written to physical path '{}': {} chars", realPhysicalPath, payload.length());
            return true;
        } catch (Exception e) {
            log.error("[HostSourceNode] Write failed for '{}': {}", realPhysicalPath, e.getMessage());
            System.err.printf("  ❌ [HostSourceNode] Write error: %s%n", e.getMessage());
            return false;
        }
    }
}
