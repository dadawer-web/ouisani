package com.ouisani.aios.core.observability;

/**
 * AIOS 所有可观测事件类型的枚举。
 * <p>
 * 命名约定 {@code <COMPONENT>_<OPERATION>} 或 {@code <COMPONENT>_<OPERATION>_<PHASE>}，
 * 参考 LMCache 的 {@code EventType} 但适配 AIOS 的组件划分。每个枚举值携带一个稳定的
 * 字符串 {@code code}，用于序列化与跨语言互通。
 *
 * @see ObservabilityEvent
 */
public enum EventType {
    // ── 语义缓存 ─────────────────────────────────────────────────────
    CACHE_HIT("cache.hit"),
    CACHE_MISS("cache.miss"),
    CACHE_EVICT("cache.evict"),
    CACHE_PUT("cache.put"),
    CACHE_DECAY("cache.decay"),

    // ── LLM 调用 ─────────────────────────────────────────────────────
    LLM_THINK_START("llm.think.start"),
    LLM_THINK_END("llm.think.end"),
    LLM_EMBED("llm.embed"),

    // ── 记忆后端 ─────────────────────────────────────────────────────
    MEMORY_STORE("memory.store"),
    MEMORY_RETRIEVE("memory.retrieve"),
    MEMORY_CLEAR("memory.clear"),

    // ── 工具调用 ─────────────────────────────────────────────────────
    TOOL_CALL_START("tool.call.start"),
    TOOL_CALL_END("tool.call.end"),

    // ── 任务调度 ─────────────────────────────────────────────────────
    TASK_SPAWN("task.spawn"),
    TASK_COMPLETE("task.complete"),

    // ── 崩溃恢复 ─────────────────────────────────────────────────────
    AGENT_CRASH("agent.crash"),
    AGENT_RECOVER("agent.recover"),

    // ── 系统时钟 ─────────────────────────────────────────────────────
    SYS_TICK("sys.tick"),

    // ── EventBus 自监控 ──────────────────────────────────────────────
    EVENT_BUS_DROPPED("event_bus.dropped");

    private final String code;

    EventType(String code) {
        this.code = code;
    }

    /**
     * 返回该事件类型的稳定字符串编码，用于序列化。
     *
     * @return 事件类型编码
     */
    public String code() {
        return code;
    }
}
