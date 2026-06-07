package com.ouisani.aios.core.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话管理器 — 对标 Claude Code 的 bridge/ 模块。
 * <p>
 * 管理 Bridge 会话的生命周期：
 * - 会话创建/恢复/归档
 * - 消息路由（入站/出站）
 * - 重连机制
 * <p>
 * OS 类比：相当于 Linux 的 session 管理 — setsid() 创建新会话组。
 */
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);
    private static final SessionManager INSTANCE = new SessionManager();

    private final Map<String, BridgeSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong sequenceCounter = new AtomicLong(0);

    private SessionManager() {}

    public static SessionManager instance() { return INSTANCE; }

    /** Bridge 会话 */
    public static class BridgeSession {
        private final String sessionId;
        private final String environmentId;
        private final long createdAt;
        private volatile BridgeState state;
        private final List<String> messageHistory = Collections.synchronizedList(new ArrayList<>());

        public BridgeSession(String sessionId, String environmentId) {
            this.sessionId = sessionId;
            this.environmentId = environmentId;
            this.createdAt = System.currentTimeMillis();
            this.state = BridgeState.READY;
        }

        public String sessionId() { return sessionId; }
        public String environmentId() { return environmentId; }
        public BridgeState state() { return state; }
        public void setState(BridgeState state) { this.state = state; }
        public List<String> history() { return Collections.unmodifiableList(messageHistory); }
        public void appendMessage(String msg) { messageHistory.add(msg); }
    }

    public enum BridgeState {
        READY, CONNECTED, RECONNECTING, FAILED
    }

    /**
     * 创建新会话。
     */
    public BridgeSession createSession(String environmentId) {
        String sessionId = "sess_" + Long.toHexString(System.currentTimeMillis()) + "_" + sequenceCounter.incrementAndGet();
        BridgeSession session = new BridgeSession(sessionId, environmentId);
        sessions.put(sessionId, session);
        log.info("[SessionManager] Created session: {} for env: {}", sessionId, environmentId);
        return session;
    }

    /**
     * 获取会话。
     */
    public Optional<BridgeSession> getSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /**
     * 恢复会话 — 重连逻辑。
     */
    public BridgeSession reconnectOrNew(String oldSessionId, String environmentId) {
        BridgeSession old = sessions.get(oldSessionId);
        if (old != null && old.state() != BridgeState.FAILED) {
            old.setState(BridgeState.RECONNECTING);
            log.info("[SessionManager] Reconnecting session: {}", oldSessionId);
            return old;
        }
        // 创建新会话
        log.info("[SessionManager] Creating new session (old {} not recoverable)", oldSessionId);
        return createSession(environmentId);
    }

    /**
     * 归档会话。
     */
    public void archiveSession(String sessionId) {
        BridgeSession session = sessions.remove(sessionId);
        if (session != null) {
            session.setState(BridgeState.FAILED);
            log.info("[SessionManager] Archived session: {} ({} messages)", sessionId, session.history().size());
        }
    }

    /**
     * 获取所有活跃会话。
     */
    public Collection<BridgeSession> activeSessions() {
        return Collections.unmodifiableCollection(sessions.values());
    }
}
