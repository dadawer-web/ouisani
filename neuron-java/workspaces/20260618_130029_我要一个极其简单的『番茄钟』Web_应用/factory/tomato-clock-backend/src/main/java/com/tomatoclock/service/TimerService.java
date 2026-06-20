package com.tomatoclock.service;

import com.tomatoclock.model.TimerSession;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class TimerService {

    private final Map<String, TimerSession> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger completedCount = new AtomicInteger(0);
    private volatile String activeSessionId = null;

    /**
     * 开始一个新的番茄钟计时
     */
    public TimerSession startTimer(String type) {
        int duration = "work".equals(type) ? 25 : 5;
        String id = UUID.randomUUID().toString().substring(0, 8);
        TimerSession session = new TimerSession(id, type, duration);
        session.setStartTime(LocalDateTime.now());
        sessions.put(id, session);
        activeSessionId = id;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", id);
        result.put("type", type);
        result.put("durationMinutes", duration);
        result.put("startTime", session.getStartTime().toString());
        result.put("message", type.equals("work")
            ? "🍅 开始25分钟工作计时！"
            : "☕ 开始5分钟休息！");

        return session;
    }

    /**
     * 停止当前计时
     */
    public Map<String, Object> stopTimer() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (activeSessionId == null || !sessions.containsKey(activeSessionId)) {
            result.put("status", "no_active_session");
            result.put("message", "当前没有活跃的计时会话");
            return result;
        }

        TimerSession session = sessions.get(activeSessionId);
        session.setEndTime(LocalDateTime.now());
        result.put("status", "stopped");
        result.put("sessionId", activeSessionId);
        result.put("type", session.getType());
        result.put("message", "计时已停止");
        activeSessionId = null;
        return result;
    }

    /**
     * 完成当前计时（计时自然结束时调用）
     */
    public Map<String, Object> completeTimer() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (activeSessionId == null || !sessions.containsKey(activeSessionId)) {
            result.put("status", "no_active_session");
            result.put("message", "当前没有活跃的计时会话");
            return result;
        }

        TimerSession session = sessions.get(activeSessionId);
        session.setEndTime(LocalDateTime.now());
        session.setCompleted(true);
        int totalCompleted = completedCount.incrementAndGet();

        result.put("status", "completed");
        result.put("sessionId", activeSessionId);
        result.put("type", session.getType());
        result.put("totalCompleted", totalCompleted);
        result.put("message", "🎉 番茄钟完成！总计完成: " + totalCompleted + " 个");
        activeSessionId = null;
        return result;
    }

    /**
     * 重置所有计时数据
     */
    public Map<String, Object> resetTimer() {
        sessions.clear();
        completedCount.set(0);
        activeSessionId = null;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "reset");
        result.put("totalCompleted", 0);
        result.put("message", "所有计时数据已重置");
        return result;
    }

    /**
     * 获取当前状态
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeSession", activeSessionId);
        result.put("totalCompleted", completedCount.get());
        result.put("status", activeSessionId != null ? "running" : "idle");
        return result;
    }
}
