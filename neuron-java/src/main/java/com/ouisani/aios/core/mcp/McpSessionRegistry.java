package com.ouisani.aios.core.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 会话注册表 — 管理 {@code KEEP_ALIVE} 和 {@code AGENT_LOOP} 策略的会话复用。
 * <p>
 * 借鉴 Apix 的 {@code MCPToolManager}（含 {@code mcp_client_cm} 字典和 {@code cache_first} 逻辑），
 * 适配 Java 单例模式。核心能力：
 * <ul>
 *   <li><b>会话缓存</b>：按 serverName 缓存已建立的 McpClient，避免重复握手</li>
 *   <li><b>生命周期感知</b>：根据 {@link SessionLifecycle} 决定是否复用或重建</li>
 *   <li><b>优雅关闭</b>：{@link #cleanupAll} 关闭所有缓存的会话</li>
 * </ul>
 * <p>
 * <b>工作流程</b>：
 * <ol>
 *   <li>Agent 需要调用 MCP 工具时，先通过 {@link #getSession} 查找缓存的会话</li>
 *   <li>若找到且生命周期匹配，直接复用（避免重复握手）</li>
 *   <li>若找到但生命周期不匹配，关闭旧会话并返回 null（触发重建）</li>
 *   <li>若未找到，返回 null（调用方负责建立新连接并 {@link #registerSession} 注册）</li>
 * </ol>
 * <p>
 * <b>OS 类比</b>：相当于 Linux 的连接池（如 NFS 的 {@code sunrpc.conn}）—
 * 复用已建立的连接，避免重复握手开销。
 *
 * @see SessionLifecycle
 * @see McpClient
 */
public class McpSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpSessionRegistry.class);

    private static final class Holder {
        static final McpSessionRegistry INSTANCE = new McpSessionRegistry();
    }

    public static McpSessionRegistry instance() {
        return Holder.INSTANCE;
    }

    /**
     * 会话缓存项 — 记录 McpClient 及其生命周期策略。
     *
     * @param client    MCP 客户端
     * @param lifecycle 生命周期策略
     */
    private record SessionEntry(McpClient client, SessionLifecycle lifecycle) {}

    /** serverName → 会话缓存项 */
    private final ConcurrentHashMap<String, SessionEntry> sessions = new ConcurrentHashMap<>();

    private McpSessionRegistry() {}

    /**
     * 注册会话 — 将已建立的 McpClient 缓存到注册表。
     * <p>
     * 若已存在同名会话，先关闭旧会话再注册新的。
     *
     * @param serverName MCP 服务器名
     * @param client     已建立的 MCP 客户端
     * @param lifecycle  生命周期策略
     */
    public void registerSession(String serverName, McpClient client, SessionLifecycle lifecycle) {
        SessionEntry existing = sessions.put(serverName, new SessionEntry(client, lifecycle));
        if (existing != null && existing.client() != client) {
            // 关闭旧会话（避免资源泄漏）
            try {
                existing.client().disconnect();
                log.info("[McpSessionRegistry] 旧会话已关闭: server='{}'", serverName);
            } catch (Exception e) {
                log.warn("[McpSessionRegistry] 关闭旧会话失败: server='{}', error={}", serverName, e.getMessage());
            }
        }
        log.info("[McpSessionRegistry] 会话已注册: server='{}', lifecycle={}", serverName, lifecycle);
    }

    /**
     * 获取会话 — 缓存优先策略（借鉴 Apix 的 {@code cache_first}）。
     * <p>
     * <b>逻辑</b>：
     * <ul>
     *   <li>若缓存中存在该 server 的会话，且生命周期为 {@code KEEP_ALIVE}，直接复用</li>
     *   <li>若缓存中存在但生命周期为 {@code AGENT_LOOP}，复用但调用方需在循环结束时
     *       调用 {@link #releaseAgentLoopSessions} 释放</li>
     *   <li>若缓存中存在但生命周期不匹配（如旧的 KEEP_ALIVE 但现在需要 ALWAYS_CLOSE），
     *       关闭旧会话并返回 null（触发重建）</li>
     *   <li>若缓存中不存在，返回 null</li>
     * </ul>
     *
     * @param serverName       MCP 服务器名
     * @param expectedLifecycle 期望的生命周期策略
     * @return 缓存的 McpClient，无缓存或生命周期不匹配返回 null
     */
    public McpClient getSession(String serverName, SessionLifecycle expectedLifecycle) {
        SessionEntry entry = sessions.get(serverName);
        if (entry == null) {
            log.debug("[McpSessionRegistry] 无缓存会话: server='{}'", serverName);
            return null;
        }

        // ALWAYS_CLOSE 策略不缓存，每次都新建
        if (expectedLifecycle == SessionLifecycle.ALWAYS_CLOSE) {
            log.debug("[McpSessionRegistry] ALWAYS_CLOSE 策略，跳过缓存: server='{}'", serverName);
            return null;
        }

        // 生命周期匹配，复用会话
        if (entry.lifecycle() == expectedLifecycle
                || entry.lifecycle() == SessionLifecycle.KEEP_ALIVE) {
            log.info("[McpSessionRegistry] 复用缓存会话: server='{}', lifecycle={}",
                    serverName, entry.lifecycle());
            return entry.client();
        }

        // 生命周期不匹配，关闭旧会话
        log.info("[McpSessionRegistry] 生命周期变更，关闭旧会话: server='{}', old={}, new={}",
                serverName, entry.lifecycle(), expectedLifecycle);
        try {
            entry.client().disconnect();
        } catch (Exception e) {
            log.warn("[McpSessionRegistry] 关闭旧会话失败: server='{}', error={}", serverName, e.getMessage());
        }
        sessions.remove(serverName);
        return null;
    }

    /**
     * 释放指定 server 的会话 — 关闭并从缓存移除。
     *
     * @param serverName MCP 服务器名
     */
    public void releaseSession(String serverName) {
        SessionEntry entry = sessions.remove(serverName);
        if (entry != null) {
            try {
                entry.client().disconnect();
                log.info("[McpSessionRegistry] 会话已释放: server='{}'", serverName);
            } catch (Exception e) {
                log.warn("[McpSessionRegistry] 释放会话失败: server='{}', error={}", serverName, e.getMessage());
            }
        }
    }

    /**
     * 释放所有 AGENT_LOOP 策略的会话 — 在 Agent 循环结束时调用。
     * <p>
     * KEEP_ALIVE 会话不受影响，继续保活。
     */
    public void releaseAgentLoopSessions() {
        sessions.forEach((serverName, entry) -> {
            if (entry.lifecycle() == SessionLifecycle.AGENT_LOOP) {
                try {
                    entry.client().disconnect();
                    log.info("[McpSessionRegistry] AGENT_LOOP 会话已释放: server='{}'", serverName);
                } catch (Exception e) {
                    log.warn("[McpSessionRegistry] 释放 AGENT_LOOP 会话失败: server='{}', error={}",
                            serverName, e.getMessage());
                }
                sessions.remove(serverName);
            }
        });
    }

    /**
     * 关闭所有缓存的会话 — 系统关闭时调用。
     */
    public void cleanupAll() {
        sessions.forEach((serverName, entry) -> {
            try {
                entry.client().disconnect();
                log.info("[McpSessionRegistry] 会话已关闭: server='{}'", serverName);
            } catch (Exception e) {
                log.warn("[McpSessionRegistry] 关闭会话失败: server='{}', error={}", serverName, e.getMessage());
            }
        });
        sessions.clear();
        log.info("[McpSessionRegistry] 所有会话已清理");
    }

    /**
     * 获取当前缓存的会话数量。
     *
     * @return 会话数量
     */
    public int sessionCount() {
        return sessions.size();
    }

    /**
     * 判断指定 server 是否有缓存的会话。
     *
     * @param serverName MCP 服务器名
     * @return 有缓存返回 true
     */
    public boolean hasSession(String serverName) {
        return sessions.containsKey(serverName);
    }
}
