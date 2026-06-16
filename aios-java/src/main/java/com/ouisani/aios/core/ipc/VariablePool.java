package com.ouisani.aios.core.ipc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AGI OS 全局/会话级共享内存池 (The IPC Shared Memory)
 * <p>
 * 借鉴 Dify 的多级命名空间设计，支持跨 Agent 数据安全读写。
 * <p>
 * OS 类比：Linux 的 shmget/shmat — 多进程通过共享内存段交换数据，
 * 而非通过管道或信号。VariablePool 提供三种作用域：
 * <ul>
 *   <li>SYSTEM — 系统级环境变量（如 OS_VERSION），全局唯一</li>
 *   <li>SESSION — 会话级全局变量，跨整个会话的上下文</li>
 *   <li>TASK — 任务级局部变量，单个 DAG 节点内的暂存</li>
 * </ul>
 *
 * @see Scope
 */
public class VariablePool {
    private static final Logger log = LoggerFactory.getLogger(VariablePool.class);

    public enum Scope {
        /** 系统级环境变量 (如 OS_VERSION) */
        SYSTEM,
        /** 会话级全局变量 (跨整个会话的上下文) */
        SESSION,
        /** 任务级局部变量 (单个 DAG 节点内的暂存) */
        TASK
    }

    // Scope -> (SessionId/TaskId -> (VariableKey -> Value))
    private final Map<Scope, Map<String, Map<String, Object>>> memory = new ConcurrentHashMap<>();

    private static final VariablePool INSTANCE = new VariablePool();

    private VariablePool() {
        for (Scope scope : Scope.values()) {
            memory.put(scope, new ConcurrentHashMap<>());
        }
    }

    public static VariablePool getInstance() { return INSTANCE; }

    public void set(Scope scope, String contextId, String key, Object value) {
        memory.get(scope).computeIfAbsent(contextId, k -> new ConcurrentHashMap<>()).put(key, value);
        log.debug("[VariablePool] [{}] WRITE: {}/{} = (type: {})", scope, contextId, key, value.getClass().getSimpleName());
    }

    public Object get(Scope scope, String contextId, String key) {
        Map<String, Object> scopedMemory = memory.get(scope).get(contextId);
        return scopedMemory != null ? scopedMemory.get(key) : null;
    }

    /**
     * 类型安全的获取方法。
     */
    @SuppressWarnings("unchecked")
    public <T> T get(Scope scope, String contextId, String key, Class<T> type) {
        Object value = get(scope, contextId, key);
        if (value == null) return null;
        if (type.isInstance(value)) return (T) value;
        log.warn("[VariablePool] Type mismatch for {}/{}: expected {}, got {}",
                contextId, key, type.getSimpleName(), value.getClass().getSimpleName());
        return null;
    }

    /**
     * 变量插值引擎：将 Prompt 中的 {{scope.key}} 替换为真实值。
     * <p>
     * 支持的占位符格式：
     * <ul>
     *   <li>{{system.os_version}} — 系统级变量</li>
     *   <li>{{session.user_name}} — 会话级变量</li>
     *   <li>{{task.output_file}} — 任务级变量</li>
     * </ul>
     *
     * @param promptTemplate 包含 {{scope.key}} 占位符的模板字符串
     * @param sessionId      当前会话 ID（用于 SESSION 作用域）
     * @param taskId         当前任务 ID（用于 TASK 作用域）
     * @return 插值后的字符串
     */
    public String interpolate(String promptTemplate, String sessionId, String taskId) {
        if (promptTemplate == null || !promptTemplate.contains("{{")) return promptTemplate;

        Pattern pattern = Pattern.compile("\\{\\{(\\w+)\\.(\\w+)}}");
        Matcher matcher = pattern.matcher(promptTemplate);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String scopeStr = matcher.group(1).toLowerCase();
            String key = matcher.group(2);

            Scope scope;
            String contextId;
            switch (scopeStr) {
                case "system" -> { scope = Scope.SYSTEM; contextId = "global"; }
                case "session" -> { scope = Scope.SESSION; contextId = sessionId; }
                case "task" -> { scope = Scope.TASK; contextId = taskId; }
                default -> {
                    matcher.appendReplacement(result, matcher.group(0));
                    continue;
                }
            }

            Object value = get(scope, contextId, key);
            String replacement = value != null ? Matcher.quoteReplacement(value.toString()) : "{{" + scopeStr + "." + key + "}}";
            matcher.appendReplacement(result, replacement);
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * 清理指定任务的所有变量，防止内存泄漏。
     */
    public void cleanupTask(String taskId) {
        memory.get(Scope.TASK).remove(taskId);
        log.debug("[VariablePool] TASK scope cleaned up for: {}", taskId);
    }

    /**
     * 清理指定会话的所有变量。
     */
    public void cleanupSession(String sessionId) {
        memory.get(Scope.SESSION).remove(sessionId);
        log.debug("[VariablePool] SESSION scope cleaned up for: {}", sessionId);
    }

    /**
     * 获取指定作用域和上下文的变量数量（供遥测监控）。
     */
    public int size(Scope scope, String contextId) {
        Map<String, Object> scopedMemory = memory.get(scope).get(contextId);
        return scopedMemory != null ? scopedMemory.size() : 0;
    }
}
