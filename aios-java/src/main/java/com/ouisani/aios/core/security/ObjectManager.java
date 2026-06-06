package com.ouisani.aios.core.security;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.VfsNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Windows-style Object Manager with handle-based security for AIOS.
 * <p>
 * Agents cannot access VFS nodes directly by path. They must first
 * {@link #openHandle} to obtain an integer handle, then use that handle
 * for all subsequent operations. This enables:
 * <ul>
 *   <li>Access control at handle-creation time (not every read/write)</li>
 *   <li>Handle revocation / capability revocation</li>
 *   <li>Audit logging of who opened what</li>
 * </ul>
 */
public final class ObjectManager {

    private static final Logger log = LoggerFactory.getLogger(ObjectManager.class);

    private static final class Holder {
        static final ObjectManager INSTANCE = new ObjectManager();
    }

    public static ObjectManager instance() {
        return Holder.INSTANCE;
    }

    private final AtomicInteger handleAllocator = new AtomicInteger(0x100);
    private final ConcurrentHashMap<Integer, VfsNode> handleTable = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, HandleInfo> handleInfo = new ConcurrentHashMap<>();

    private ObjectManager() {}

    /**
     * Open a handle to a VFS path for the given agent.
     * <p>
     * Security checks:
     * <ul>
     *   <li>If the path contains "secret" and the agent is not REALTIME, throws {@link SecurityException}.</li>
     * </ul>
     *
     * @param agentId the agent requesting access
     * @param vfsPath the VFS path to open
     * @return an integer handle for subsequent operations
     * @throws SecurityException       if the agent lacks permission
     * @throws IllegalArgumentException if the path does not resolve
     */
    public int openHandle(String agentId, String vfsPath) {
        // Resolve VFS node
        var nodeOpt = VfsManager.instance().resolve(vfsPath);
        if (nodeOpt.isEmpty()) {
            log.warn("[Object Manager] Agent '{}' attempted to open non-existent path: '{}'", agentId, vfsPath);
            throw new IllegalArgumentException("VFS path not found: " + vfsPath);
        }

        // Security check using effective token (impersonation > primary)
        SecurityToken effectiveToken = SecurityToken.getEffective();

        // Check SE_HANDLE_OPEN privilege
        if (effectiveToken == null || !effectiveToken.hasCapability(SecurityToken.SE_HANDLE_OPEN)) {
            log.warn("[Object Manager] ACCESS DENIED: Agent '{}' lacks SE_HANDLE_OPEN privilege "
                    + "(effectiveToken={})", agentId, effectiveToken != null ? effectiveToken.ownerId() : "null");
            throw new SecurityException(
                    "Access denied: agent '" + agentId + "' lacks SE_HANDLE_OPEN privilege");
        }

        // Check "secret" paths: require SE_SECRET_ACCESS privilege
        if (vfsPath.toLowerCase().contains("secret")) {
            if (!effectiveToken.hasCapability(SecurityToken.SE_SECRET_ACCESS)) {
                log.warn("[Object Manager] ACCESS DENIED: Agent '{}' attempted to open protected path '{}' "
                        + "(effectiveToken='{}', requires SE_SECRET_ACCESS)",
                        agentId, vfsPath, effectiveToken.ownerId());
                throw new SecurityException(
                        "Access denied: path '" + vfsPath + "' requires SE_SECRET_ACCESS privilege");
            }
        }

        // Allocate handle
        int handle = handleAllocator.incrementAndGet();
        handleTable.put(handle, nodeOpt.get());
        handleInfo.put(handle, new HandleInfo(agentId, vfsPath, System.currentTimeMillis()));

        log.info("[Object Manager] Granted Handle 0x{} for path '{}' to Agent '{}' (tokenOwner='{}')",
                Integer.toHexString(handle).toUpperCase(), vfsPath, agentId, effectiveToken.ownerId());

        return handle;
    }

    /**
     * Retrieve the VfsNode associated with a handle.
     *
     * @param handle the handle returned by {@link #openHandle}
     * @return the VfsNode
     * @throws InvalidHandleException if the handle is not valid or has been closed
     */
    public VfsNode getNodeByHandle(int handle) {
        VfsNode node = handleTable.get(handle);
        if (node == null) {
            throw new InvalidHandleException(handle);
        }
        return node;
    }

    /**
     * Close a handle, releasing the mapping.
     *
     * @param handle the handle to close
     * @return true if the handle was closed, false if it was already invalid
     */
    public boolean closeHandle(int handle) {
        VfsNode removed = handleTable.remove(handle);
        HandleInfo info = handleInfo.remove(handle);
        if (removed != null) {
            log.info("[Object Manager] Closed Handle 0x{} (path='{}', agent='{}')",
                    Integer.toHexString(handle).toUpperCase(),
                    info != null ? info.vfsPath : "?",
                    info != null ? info.agentId : "?");
            return true;
        }
        return false;
    }

    /**
     * Check if a handle is currently valid.
     */
    public boolean isValidHandle(int handle) {
        return handleTable.containsKey(handle);
    }

    /**
     * Get metadata about a handle.
     */
    public HandleInfo getHandleInfo(int handle) {
        return handleInfo.get(handle);
    }

    /**
     * Get all currently active handles (read-only view).
     */
    public Map<Integer, VfsNode> activeHandles() {
        return Collections.unmodifiableMap(handleTable);
    }

    /**
     * Get all currently active handle info entries (read-only view).
     * Used by CrashAnalyzer to collect handle snapshots for core dumps.
     */
    public Map<Integer, HandleInfo> activeHandleInfo() {
        return Collections.unmodifiableMap(handleInfo);
    }

    /**
     * Get the number of active handles.
     */
    public int activeHandleCount() {
        return handleTable.size();
    }

    /**
     * Close all handles for a specific agent.
     */
    public int closeAllHandlesForAgent(String agentId) {
        int closed = 0;
        for (Map.Entry<Integer, HandleInfo> entry : handleInfo.entrySet()) {
            if (agentId.equals(entry.getValue().agentId)) {
                handleTable.remove(entry.getKey());
                handleInfo.remove(entry.getKey());
                closed++;
            }
        }
        if (closed > 0) {
            log.info("[Object Manager] Closed {} handles for Agent '{}'", closed, agentId);
        }
        return closed;
    }

    /**
     * Metadata about an open handle.
     */
    public record HandleInfo(String agentId, String vfsPath, long openedAt) {}
}
