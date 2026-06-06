package com.ouisani.aios.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

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
        com.ouisani.aios.vfs.ChromeBridgeNode {

    Logger log = LoggerFactory.getLogger(VfsNode.class);

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

    default boolean checkRead(int callerUid) {
        if (callerUid == 0) return true;
        if (callerUid == ownerUid()) return (permissions() & 0400) != 0;
        return (permissions() & 0004) != 0;
    }

    default boolean checkWrite(int callerUid) {
        if (callerUid == 0) return true;
        if (callerUid == ownerUid()) return (permissions() & 0200) != 0;
        return (permissions() & 0002) != 0;
    }

    default boolean checkExecute(int callerUid) {
        if (callerUid == 0) return true;
        if (callerUid == ownerUid()) return (permissions() & 0100) != 0;
        return (permissions() & 0001) != 0;
    }

    record FileNode(String path, int ownerUid, int permissions) implements VfsNode {

        public FileNode(String path) {
            this(path, 0, 0644);
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
            return new FileNode(path, uid, permissions);
        }

        public FileNode withPermissions(int perm) {
            return new FileNode(path, ownerUid, perm);
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

    non-sealed class DirectoryNode implements VfsNode {

        private final String path;
        private int ownerUid;
        private int permissions;

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
