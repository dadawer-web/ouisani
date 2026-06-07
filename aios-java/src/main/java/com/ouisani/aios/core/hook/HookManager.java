package com.ouisani.aios.core.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hook 管理器 — 对标 Claude Code 的 hooks.ts + AsyncHookRegistry。
 * <p>
 * 生命周期 Hook 事件：
 * - PreToolUse / PostToolUse — 工具调用前后
 * - PreCompact / PostCompact — 压缩前后
 * - SessionStart / SessionEnd — 会话生命周期
 * - Stop / StopFailure — 停止事件
 * <p>
 * OS 类比：相当于 Linux 的 notifier chain — 内核子系统注册回调，
 * 事件发生时按优先级调用。
 */
public class HookManager {

    private static final Logger log = LoggerFactory.getLogger(HookManager.class);
    private static final HookManager INSTANCE = new HookManager();

    /** Hook 事件类型 */
    public enum HookEvent {
        PRE_TOOL_USE("PreToolUse"),
        POST_TOOL_USE("PostToolUse"),
        POST_TOOL_USE_FAILURE("PostToolUseFailure"),
        PRE_COMPACT("PreCompact"),
        POST_COMPACT("PostCompact"),
        SESSION_START("SessionStart"),
        SESSION_END("SessionEnd"),
        STOP("Stop"),
        STOP_FAILURE("StopFailure"),
        SUBAGENT_START("SubagentStart"),
        SUBAGENT_STOP("SubagentStop");

        private final String name;

        HookEvent(String name) { this.name = name; }

        public String eventName() { return name; }
    }

    /** Hook 处理器 */
    @FunctionalInterface
    public interface HookHandler {
        /**
         * 处理 Hook 事件。
         *
         * @param event  事件类型
         * @param data   事件数据（工具名、参数等）
         * @return Hook 结果（null 表示继续，非 null 表示拦截/修改）
         */
        HookResult handle(HookEvent event, Map<String, Object> data);
    }

    /** Hook 结果 */
    public record HookResult(
            boolean proceed,    // 是否继续执行
            String message,     // 结果消息
            Map<String, Object> modifiedData  // 修改后的数据
    ) {
        public static HookResult ok() { return new HookResult(true, "", Map.of()); }
        public static HookResult ok(String msg) { return new HookResult(true, msg, Map.of()); }
        public static HookResult deny(String reason) { return new HookResult(false, reason, Map.of()); }
        public static HookResult modify(Map<String, Object> data) { return new HookResult(true, "", data); }
    }

    /** Hook 注册条目 */
    private record HookEntry(int priority, HookHandler handler) implements Comparable<HookEntry> {
        @Override
        public int compareTo(HookEntry o) { return Integer.compare(priority, o.priority); }
    }

    private final Map<HookEvent, List<HookEntry>> hooks = new ConcurrentHashMap<>();

    private HookManager() {}

    public static HookManager instance() { return INSTANCE; }

    /**
     * 注册 Hook 处理器。
     *
     * @param event    事件类型
     * @param handler  处理器
     * @param priority 优先级（数字越小越先执行）
     */
    public void register(HookEvent event, HookHandler handler, int priority) {
        hooks.computeIfAbsent(event, k -> new CopyOnWriteArrayList<>())
                .add(new HookEntry(priority, handler));
        hooks.get(event).sort(null); // 按优先级排序
        log.debug("[HookManager] Registered {} handler (priority: {})", event, priority);
    }

    /**
     * 注册 Hook 处理器（默认优先级 100）。
     */
    public void register(HookEvent event, HookHandler handler) {
        register(event, handler, 100);
    }

    /**
     * 触发 Hook 事件 — 按优先级执行所有处理器。
     * 如果任何处理器返回 deny，则停止执行并返回拒绝结果。
     *
     * @return 最终的 Hook 结果
     */
    public HookResult trigger(HookEvent event, Map<String, Object> data) {
        List<HookEntry> entries = hooks.get(event);
        if (entries == null || entries.isEmpty()) {
            return HookResult.ok();
        }

        Map<String, Object> currentData = new HashMap<>(data);

        for (HookEntry entry : entries) {
            try {
                HookResult result = entry.handler.handle(event, currentData);
                if (!result.proceed()) {
                    log.info("[HookManager] Hook {} denied by handler: {}", event, result.message());
                    return result;
                }
                // 合并修改的数据
                if (result.modifiedData() != null && !result.modifiedData().isEmpty()) {
                    currentData.putAll(result.modifiedData());
                }
            } catch (Exception e) {
                log.warn("[HookManager] Hook {} handler failed: {}", event, e.getMessage());
            }
        }

        return HookResult.ok();
    }

    /**
     * 触发 Hook 事件（无数据）。
     */
    public HookResult trigger(HookEvent event) {
        return trigger(event, Map.of());
    }

    /**
     * 注销指定事件的所有处理器。
     */
    public void unregisterAll(HookEvent event) {
        hooks.remove(event);
    }

    /**
     * 清除所有 Hook。
     */
    public void clearAll() {
        hooks.clear();
    }
}
