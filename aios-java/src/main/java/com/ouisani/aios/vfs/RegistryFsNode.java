package com.ouisani.aios.vfs;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.config.SemanticRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry VFS Node — exposes the global Semantic Registry through the VFS
 * at {@code /proc/registry}.
 * <p>
 * Read behavior:
 * <ul>
 *   <li>If the node's path corresponds to a specific registry key (e.g.
 *       {@code /proc/registry/HKEY_LOCAL_AIOS/System/DefaultLlm}), returns that value.</li>
 *   <li>If the path is a prefix with no exact match, dumps all keys under that prefix.</li>
 *   <li>If the path is {@code /proc/registry}, dumps the entire registry.</li>
 * </ul>
 * <p>
 * Write behavior: only REALTIME-priority agents may write.
 * Write format: {@code key=value} to set, or {@code key=} to delete.
 */
public non-sealed class RegistryFsNode implements VfsNode {

    private static final Logger log = LoggerFactory.getLogger(RegistryFsNode.class);

    /** The VFS path prefix that maps to this registry node. */
    private static final String REGISTRY_VFS_PREFIX = "/proc/registry";

    private final String path;
    private int ownerUid;
    private int permissions;

    public RegistryFsNode(String path) {
        this(path, 0, 0644);
    }

    public RegistryFsNode(String path, int ownerUid, int permissions) {
        this.path = path;
        this.ownerUid = ownerUid;
        this.permissions = permissions;
    }

    @Override
    public VfsNodeType nodeType() {
        return VfsNodeType.DEVICE;
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

    /**
     * Extract the registry key from the VFS path.
     * E.g. "/proc/registry/HKEY_LOCAL_AIOS/System/DefaultLlm" → "HKEY_LOCAL_AIOS/System/DefaultLlm"
     */
    private String extractRegistryKey() {
        if (path.equals(REGISTRY_VFS_PREFIX) || path.equals(REGISTRY_VFS_PREFIX + "/")) {
            return "";
        }
        String key = path.substring(REGISTRY_VFS_PREFIX.length());
        if (key.startsWith("/")) {
            key = key.substring(1);
        }
        return key;
    }

    @Override
    public String read() {
        String registryKey = extractRegistryKey();
        SemanticRegistry reg = SemanticRegistry.instance();

        if (registryKey.isEmpty()) {
            // Dump entire registry
            String dump = reg.dumpAll();
            log.debug("[RegistryFsNode] read: full dump ({} chars)", dump.length());
            return dump;
        }

        // Try exact key match first
        String value = reg.getValue(registryKey);
        if (value != null) {
            log.debug("[RegistryFsNode] read: key='{}' → '{}'", registryKey, value);
            return value;
        }

        // No exact match: dump subtree with this prefix
        String subTree = reg.dumpSubTree(registryKey + "/");
        if (subTree.isEmpty()) {
            log.debug("[RegistryFsNode] read: key='{}' not found", registryKey);
            return "(not found)";
        }

        log.debug("[RegistryFsNode] read: prefix='{}' → subtree ({} chars)", registryKey, subTree.length());
        return subTree;
    }

    @Override
    public boolean write(String data) {
        // Only REALTIME agents may modify the registry via VFS
        AgentTask currentTask = TaskScheduler.CURRENT_TASK.get();
        if (currentTask == null || currentTask.processPriority() != ProcessPriority.REALTIME) {
            log.warn("[RegistryFsNode] WRITE DENIED: only REALTIME agents may modify the registry "
                    + "(caller={})", currentTask != null ? "pid=" + currentTask.pid() : "unknown");
            return false;
        }

        if (data == null || data.isEmpty()) {
            return false;
        }

        // Format: key=value
        int eqIdx = data.indexOf('=');
        if (eqIdx < 0) {
            log.warn("[RegistryFsNode] Invalid write format (expected key=value): {}", data);
            return false;
        }

        String key = data.substring(0, eqIdx);
        String value = data.substring(eqIdx + 1);

        if (value.isEmpty()) {
            // Empty value means delete
            SemanticRegistry.instance().removeKey(key);
            log.info("[RegistryFsNode] DELETE: key='{}' by Agent#{}", key, currentTask.pid());
        } else {
            SemanticRegistry.instance().setValue(key, value);
            log.info("[RegistryFsNode] SET: key='{}' value='{}' by Agent#{}", key, value, currentTask.pid());
        }

        return true;
    }

    @Override
    public String toString() {
        return "RegistryFsNode{path='%s'}".formatted(path);
    }
}
