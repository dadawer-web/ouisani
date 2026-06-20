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
 * 对象管理器 — AIOS 的句柄式安全访问控制。
 * <p>
 * Agent 不能直接通过路径访问 VFS 节点，必须先通过 {@link #openHandle} 获取
 * 整数句柄，然后用句柄进行后续操作。这实现了：
 * <ul>
 *   <li>访问控制在句柄创建时执行（而非每次读写）</li>
 *   <li>句柄撤销 / 权能回收</li>
 *   <li>审计日志：记录谁打开了什么</li>
 * </ul>
 *
 * <h3>OS 类比: Windows Object Manager</h3>
 * Windows 内核的 Object Manager 将所有内核对象（文件、事件、互斥体等）
 * 统一用 Handle 管理，进程通过 Handle 而非指针访问对象。
 * AIOS 的 ObjectManager 采用相同模型：Agent 通过整数 Handle 访问 VFS 节点，
 * 而非直接操作路径。Handle 的分配、校验、回收由 ObjectManager 统一管控。
 *
 * @see SecurityToken
 * @see InvalidHandleException
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
     * 为指定 Agent 打开一个 VFS 路径的句柄。
     * <p>
     * 安全检查流程：
     * <ol>
     *   <li>解析 VFS 路径，若不存在则抛出 IllegalArgumentException</li>
     *   <li>校验有效令牌的 SE_HANDLE_OPEN 权能</li>
     *   <li>若路径包含 "secret"，还需校验 SE_SECRET_ACCESS 权能</li>
     * </ol>
     *
     * @param agentId 请求访问的 Agent 标识
     * @param vfsPath 要打开的 VFS 路径
     * @return 整数句柄，用于后续操作
     * @throws SecurityException       如果 Agent 缺少权限
     * @throws IllegalArgumentException 如果路径不存在
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
     * 通过句柄获取关联的 VfsNode。
     *
     * @param handle 由 {@link #openHandle} 返回的句柄
     * @return VfsNode
     * @throws InvalidHandleException 如果句柄无效或已关闭
     */
    public VfsNode getNodeByHandle(int handle) {
        VfsNode node = handleTable.get(handle);
        if (node == null) {
            throw new InvalidHandleException(handle);
        }
        return node;
    }

    /**
     * 关闭句柄，释放映射。
     *
     * @param handle 要关闭的句柄
     * @return true 如果句柄成功关闭，false 如果句柄已无效
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

    /** 检查句柄是否有效 */
    public boolean isValidHandle(int handle) {
        return handleTable.containsKey(handle);
    }

    /** 获取句柄的元数据 */
    public HandleInfo getHandleInfo(int handle) {
        return handleInfo.get(handle);
    }

    /** 获取所有活跃句柄（只读视图） */
    public Map<Integer, VfsNode> activeHandles() {
        return Collections.unmodifiableMap(handleTable);
    }

    /**
     * 获取所有活跃句柄的元数据（只读视图）。
     * 供 CrashAnalyzer 收集句柄快照以生成核心转储。
     */
    public Map<Integer, HandleInfo> activeHandleInfo() {
        return Collections.unmodifiableMap(handleInfo);
    }

    /** 获取活跃句柄数量 */
    public int activeHandleCount() {
        return handleTable.size();
    }

    /** 关闭指定 Agent 的所有句柄 */
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
            log.info("[Object Manager] 已为 Agent '{}' 关闭 {} 个句柄", agentId, closed);
        }
        return closed;
    }

    /** 句柄元数据：记录哪个 Agent 打开了哪个 VFS 路径 */
    public record HandleInfo(String agentId, String vfsPath, long openedAt) {}
}
