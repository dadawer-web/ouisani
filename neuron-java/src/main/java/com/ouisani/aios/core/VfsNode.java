package com.ouisani.aios.core;

import com.ouisani.aios.core.tool.Port;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * VFS 节点接口 — AIOS 虚拟文件系统中所有文件/设备/目录的统一抽象。
 * <p>
 * 类比 Linux VFS 的 inode：每个 VfsNode 代表一个虚拟文件系统对象，
 * 提供统一的 read/write/path/permissions 接口。
 * 通过 sealed interface 限制实现类型，确保只有预定义的节点类型可以存在。
 * <p>
 * 内置实现：FileNode（普通文件）、DirectoryNode（目录）、ExecutableNode（可执行文件）；
 * 外部扩展实现：SemanticNode、VectorNode、CameraNode 等特殊设备节点。
 */
public sealed interface VfsNode permits VfsNode.FileNode, VfsNode.DirectoryNode,
        VfsNode.ExecutableNode, com.ouisani.aios.vfs.PipeNode,
        com.ouisani.aios.vfs.SemanticNode, com.ouisani.aios.vfs.WebSocketNode,
        com.ouisani.aios.vfs.ProcFsNode, com.ouisani.aios.vfs.VectorNode,
        com.ouisani.aios.vfs.GraphNode, com.ouisani.aios.vfs.CameraNode,
        com.ouisani.aios.vfs.DisplayNode, com.ouisani.aios.vfs.HttpNode,
        com.ouisani.aios.vfs.WebhookNode, com.ouisani.aios.vfs.AudioNode,
        com.ouisani.aios.vfs.HostSourceNode, com.ouisani.aios.vfs.ShmNode,
        com.ouisani.aios.vfs.RegistryFsNode, com.ouisani.aios.vfs.ShadowCopyNode,
        com.ouisani.aios.vfs.GuiDomNode, com.ouisani.aios.vfs.GuiActionNode,
        com.ouisani.aios.vfs.MutableFileNode,
        com.ouisani.aios.vfs.RemoteDeviceMountNode,
        com.ouisani.aios.vfs.DesktopNotifyNode,
        com.ouisani.aios.vfs.ChromeBridgeNode,
        com.ouisani.aios.vfs.IndexNode,
        com.ouisani.aios.vfs.OverlayNode {

    Logger log = LoggerFactory.getLogger(VfsNode.class);

    /** VFS 节点类型枚举 — 类比 Linux inode 的文件类型（S_IFREG, S_IFDIR 等） */
    enum VfsNodeType {
        FILE, DIRECTORY, EXECUTABLE, DEVICE, PIPE, WASM, VECTOR,
        GRAPH, CAMERA, WEBHOOK, DISPLAY, AUDIO, SEMANTIC, REMOTE_DEVICE
    }

    VfsNodeType nodeType();

    String path();

    int ownerUid();

    void setOwnerUid(int uid);

    int permissions();

    void setPermissions(int perm);

    String read();

    boolean write(String data);

    /**
     * 节点归属租户 ID — 用于显式跨租户隔离（取代路径子串匹配）。
     * <p>
     * 默认 {@code null} 表示"未声明租户"（legacy 节点），所有权校验对 null 一律 skip
     * 以保持向后兼容。仅 {@link FileNode} / {@link DirectoryNode} / MutableFileNode /
     * HostSourceNode 覆写此方法以携带真实租户归属。
     *
     * @return 租户 ID；null 表示未声明（legacy，不参与所有权校验）
     */
    default String ownerTenantId() { return null; }

    /**
     * Create a frozen, read-only shadow copy (VSS snapshot) of this node.
     * The returned node captures the state at this instant and rejects all writes.
     * Default implementation returns a read-only wrapper; nodes with internal
     * mutable state (VectorNode, GraphNode) should override to deep-copy their data.
     *
     * @return a frozen VfsNode that is independent of the original
     */
    default VfsNode createShadowCopy() {
        final String frozenContent = this.read();
        final String frozenPath = this.path() + " [SHADOW]";
        return new com.ouisani.aios.vfs.ShadowCopyNode(frozenPath, this.nodeType(), frozenContent, this.ownerUid());
    }

    // ════════════════════════════════════════════════════════════════
    //  强类型 I/O 契约 (Type-Safe I/O Contract)
    // ════════════════════════════════════════════════════════════════

    /**
     * 声明此 VFS 节点接受的数据输入端口 — "吃进去什么"。
     * <p>
     * 默认返回空列表（无 I/O 契约声明，向后兼容）。
     * 有明确数据格式的设备节点（VectorNode / HttpNode 等）应覆写此方法，
     * 声明 write(String) 所期望的数据类型，供 GraphValidator 在部署前校验。
     *
     * @return 输入端口列表，默认为空
     */
    default List<Port> inputPorts() {
        return List.of();
    }

    /**
     * 声明此 VFS 节点产出的数据输出端口 — "吐出来什么"。
     * <p>
     * 默认返回空列表（无 I/O 契约声明，向后兼容）。
     * 有明确数据格式的设备节点应覆写此方法，
     * 声明 read() 所产出的数据类型，供下游节点类型匹配。
     *
     * @return 输出端口列表，默认为空
     */
    default List<Port> outputPorts() {
        return List.of();
    }

    /**
     * 是否声明了强类型 I/O 契约。
     *
     * @return true 如果 inputPorts 或 outputPorts 非空
     */
    default boolean hasIOContract() {
        return !inputPorts().isEmpty() || !outputPorts().isEmpty();
    }

    /**
     * 读权限检查 — 类比 Linux 的 inode_permission() + MAY_READ。
     * <p>
     * 权限模型采用 Unix 风格三位权限（owner/group/other）：
     * UID 0（root）始终通过；owner 检查 0400 位；other 检查 0004 位。
     *
     * @param callerUid 调用者 UID
     * @return true 有读权限
     */
    default boolean checkRead(int callerUid) {
        if (callerUid == 0) return true;
        if (callerUid == ownerUid()) return (permissions() & 0400) != 0;
        return (permissions() & 0004) != 0;
    }

    /**
     * 写权限检查 — 类比 Linux 的 inode_permission() + MAY_WRITE。
     * owner 检查 0200 位，other 检查 0002 位。
     */
    default boolean checkWrite(int callerUid) {
        if (callerUid == 0) return true;
        if (callerUid == ownerUid()) return (permissions() & 0200) != 0;
        return (permissions() & 0002) != 0;
    }

    /**
     * 执行权限检查 — 类比 Linux 的 inode_permission() + MAY_EXEC。
     * owner 检查 0100 位，other 检查 0001 位。
     */
    default boolean checkExecute(int callerUid) {
        if (callerUid == 0) return true;
        if (callerUid == ownerUid()) return (permissions() & 0100) != 0;
        return (permissions() & 0001) != 0;
    }

    /** 普通文件节点 — 类比 Linux 的常规文件（S_IFREG），默认权限 0644 */
    record FileNode(String path, int ownerUid, int permissions, String ownerTenantId) implements VfsNode {

        public FileNode(String path) {
            this(path, 0, 0644, null);
        }

        /** 向后兼容 3 参构造器 — ownerTenantId 默认 null（legacy）。 */
        public FileNode(String path, int ownerUid, int permissions) {
            this(path, ownerUid, permissions, null);
        }

        @Override
        public VfsNodeType nodeType() {
            return VfsNodeType.FILE;
        }

        @Override
        public void setOwnerUid(int uid) {
            throw new UnsupportedOperationException("Record is immutable; use withOwnerUid()");
        }

        @Override
        public void setPermissions(int perm) {
            throw new UnsupportedOperationException("Record is immutable; use withPermissions()");
        }

        public FileNode withOwnerUid(int uid) {
            return new FileNode(path, uid, permissions, ownerTenantId);
        }

        public FileNode withPermissions(int perm) {
            return new FileNode(path, ownerUid, perm, ownerTenantId);
        }

        /** 返回带新租户归属的副本（record 不可变，故返回新实例）。 */
        public FileNode withOwnerTenantId(String tenantId) {
            return new FileNode(path, ownerUid, permissions, tenantId);
        }

        @Override
        public String read() {
            log.trace("FileNode.read: path={}", path);
            return "";
        }

        @Override
        public boolean write(String data) {
            log.trace("FileNode.write: path={}, dataLen={}", path, data.length());
            return true;
        }
    }

    /** 目录节点 — 类比 Linux 的目录文件（S_IFDIR），默认权限 0755，不可写入 */
    non-sealed class DirectoryNode implements VfsNode {

        private final String path;
        private int ownerUid;
        private int permissions;
        private String ownerTenantId;

        public DirectoryNode(String path) {
            this(path, 0, 0755);
        }

        public DirectoryNode(String path, int ownerUid, int permissions) {
            this.path = path;
            this.ownerUid = ownerUid;
            this.permissions = permissions;
        }

        @Override
        public VfsNodeType nodeType() {
            return VfsNodeType.DIRECTORY;
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
            return listChildren().toString();
        }

        @Override
        public boolean write(String data) {
            return false;
        }

        public boolean mount(String name, VfsNode node) {
            log.info("VFS mount: {}/{} [{}]", path, name, node.nodeType());
            return true;
        }

        public boolean unmount(String name) {
            log.info("VFS unmount: {}/{}", path, name);
            return true;
        }

        public Optional<VfsNode> getChild(String name) {
            return Optional.empty();
        }

        public List<String> listChildren() {
            return List.of();
        }
    }

    /** 可执行文件节点 — 类比 Linux 的可执行文件（S_IXUSR），read 返回源码，execute 执行逻辑 */
    non-sealed class ExecutableNode implements VfsNode {

        private final String path;
        private int ownerUid;
        private int permissions;

        public ExecutableNode(String path) {
            this(path, 0, 0755);
        }

        public ExecutableNode(String path, int ownerUid, int permissions) {
            this.path = path;
            this.ownerUid = ownerUid;
            this.permissions = permissions;
        }

        @Override
        public VfsNodeType nodeType() {
            return VfsNodeType.EXECUTABLE;
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
            return "";
        }

        @Override
        public boolean write(String data) {
            return false;
        }

        public String execute(String payload) {
            log.debug("ExecutableNode.execute: path={}, payloadLen={}", path, payload.length());
            return "";
        }
    }

}
