package com.ouisani.aios.core.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Tracing Span — OpenTelemetry 风格的 Span 树节点，参考 OpenAI Agents Python 的 Tracing 设计。
 * <p>
 * 一个 Span 代表一次有起止时间、可嵌套的可观测操作。Span 之间通过 parentSpanId 形成树状结构，
 * traceId 贯穿整条调用链（继承自 {@link com.ouisani.aios.core.ipc.TraceContext}）。
 * <p>
 * OS 类比：相当于 ftrace 中的单个函数追踪条目，但增加了层级关系和结构化属性。
 * <p>
 * Span 本身是可变的（startTimeNanos 在创建时设置，endTimeNanos 在结束时设置，
 * attributes/events 可在生命周期内追加），以保证 TracingManager 能够原地更新栈顶 Span。
 *
 * @see TracingManager
 * @see TracingProcessor
 */
public class TraceSpan {

    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(SerializationFeature.INDENT_OUTPUT);

    /** Span 类型枚举 — 覆盖 OpenAI Agents 的 8 种 + CUSTOM。 */
    public enum SpanType {
        AGENT,
        TASK,
        TURN,
        FUNCTION,
        GENERATION,
        RESPONSE,
        HANDOFF,
        GUARDRAIL,
        CUSTOM
    }

    /** Span 状态枚举 — 对齐 OpenTelemetry Status。 */
    public enum Status {
        UNSET,
        OK,
        ERROR
    }

    /** Span 事件 — Span 生命周期内发生的命名时间点。 */
    public static final class SpanEvent {
        private final String name;
        private final long timestampNanos;
        private final Map<String, Object> attributes;

        public SpanEvent(String name, long timestampNanos, Map<String, Object> attributes) {
            this.name = name;
            this.timestampNanos = timestampNanos;
            this.attributes = attributes != null
                    ? new LinkedHashMap<>(attributes)
                    : new LinkedHashMap<>();
        }

        public String name() { return name; }
        public long timestampNanos() { return timestampNanos; }
        public Map<String, Object> attributes() { return Collections.unmodifiableMap(attributes); }
    }

    private final String spanId;
    private final String traceId;
    private final String parentSpanId;
    private final String name;
    private final SpanType spanType;
    private final long startTimeNanos;

    private long endTimeNanos;
    private final Map<String, Object> attributes;
    private final List<SpanEvent> events;
    private Status status;

    private TraceSpan(Builder b) {
        this.spanId = b.spanId != null ? b.spanId : UUID.randomUUID().toString();
        this.traceId = b.traceId;
        this.parentSpanId = b.parentSpanId;
        this.name = b.name;
        this.spanType = b.spanType != null ? b.spanType : SpanType.CUSTOM;
        this.startTimeNanos = b.startTimeNanos != 0 ? b.startTimeNanos : System.nanoTime();
        this.endTimeNanos = b.endTimeNanos;
        this.attributes = new LinkedHashMap<>(b.attributes);
        this.events = new ArrayList<>(b.events);
        this.status = b.status != null ? b.status : Status.UNSET;
    }

    // ── 访问器 ──────────────────────────────────────────────────────

    public String spanId() { return spanId; }
    public String traceId() { return traceId; }
    public String parentSpanId() { return parentSpanId; }
    public String name() { return name; }
    public SpanType spanType() { return spanType; }
    public long startTimeNanos() { return startTimeNanos; }
    public long endTimeNanos() { return endTimeNanos; }
    public Status status() { return status; }
    public Map<String, Object> attributes() { return Collections.unmodifiableMap(attributes); }
    public List<SpanEvent> events() { return Collections.unmodifiableList(events); }

    /** 是否已结束（endTimeNanos 已设置）。 */
    public boolean isEnded() { return endTimeNanos > 0; }

    /**
     * 返回耗时（毫秒）。若 Span 尚未结束，则返回从开始到现在的耗时。
     */
    public long duration() {
        long end = endTimeNanos > 0 ? endTimeNanos : System.nanoTime();
        return Math.max(0L, (end - startTimeNanos) / 1_000_000L);
    }

    // ── 可变操作（TracingManager 在 Span 生命周期内调用） ───────────

    /** 设置属性（覆盖已有同名 key）。 */
    public void setAttribute(String key, Object value) {
        Objects.requireNonNull(key, "attribute key");
        attributes.put(key, value);
    }

    /** 添加事件。 */
    public void addEvent(String name, Map<String, Object> eventAttributes) {
        events.add(new SpanEvent(name, System.nanoTime(), eventAttributes));
    }

    /** 添加无属性事件。 */
    public void addEvent(String name) {
        addEvent(name, null);
    }

    /** 结束 Span — 设置 endTimeNanos。重复调用安全（保留最早结束时间）。 */
    public void end() {
        if (endTimeNanos == 0) {
            endTimeNanos = System.nanoTime();
        }
    }

    /** 结束 Span 并设置状态。 */
    public void end(Status status) {
        if (status != null) this.status = status;
        end();
    }

    /** 设置状态。 */
    public void setStatus(Status status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    // ── 序列化 ──────────────────────────────────────────────────────

    /**
     * 序列化为 JSON（兼容 OpenTelemetry Span 格式）。
     * <p>
     * 字段命名对齐 OTLP：traceId / spanId / parentSpanId / name / kind /
     * startTimeUnixNano / endTimeUnixNano / attributes / events / status。
     */
    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("traceId", traceId);
        root.put("spanId", spanId);
        root.put("parentSpanId", parentSpanId);
        root.put("name", name);
        root.put("kind", spanType.name());
        root.put("startTimeUnixNano", String.valueOf(startTimeNanos));
        if (endTimeNanos > 0) {
            root.put("endTimeUnixNano", String.valueOf(endTimeNanos));
        }
        root.put("durationMs", duration());
        root.put("attributes", attributes);
        root.put("events", events.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.name());
            m.put("timeUnixNano", String.valueOf(e.timestampNanos()));
            m.put("attributes", e.attributes());
            return m;
        }).toList());
        Map<String, String> statusMap = new LinkedHashMap<>();
        statusMap.put("code", status.name());
        root.put("status", statusMap);
        try {
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            // 兜底：返回最小可用 JSON，避免抛出影响调用方
            return "{\"spanId\":\"" + spanId + "\",\"name\":\"" + name
                    + "\",\"error\":\"serialization_failed\"}";
        }
    }

    @Override
    public String toString() {
        return "TraceSpan{name='" + name + "', type=" + spanType
                + ", spanId=" + spanId + ", durationMs=" + duration()
                + ", status=" + status + "}";
    }

    // ── Builder ─────────────────────────────────────────────────────

    public static Builder builder(String name, SpanType type) {
        return new Builder(name, type);
    }

    public static final class Builder {
        private String spanId;
        private String traceId;
        private String parentSpanId;
        private final String name;
        private final SpanType spanType;
        private Long startTimeNanos;
        private long endTimeNanos;
        private final Map<String, Object> attributes = new LinkedHashMap<>();
        private final List<SpanEvent> events = new ArrayList<>();
        private Status status;

        private Builder(String name, SpanType type) {
            this.name = Objects.requireNonNull(name, "span name");
            this.spanType = type;
        }

        public Builder spanId(String spanId) { this.spanId = spanId; return this; }
        public Builder traceId(String traceId) { this.traceId = traceId; return this; }
        public Builder parentSpanId(String parentSpanId) { this.parentSpanId = parentSpanId; return this; }
        public Builder startTimeNanos(long startTimeNanos) { this.startTimeNanos = startTimeNanos; return this; }
        public Builder status(Status status) { this.status = status; return this; }

        public Builder attribute(String key, Object value) {
            this.attributes.put(key, value);
            return this;
        }

        public Builder event(String name, Map<String, Object> attrs) {
            this.events.add(new SpanEvent(name, System.nanoTime(), attrs));
            return this;
        }

        public TraceSpan build() {
            return new TraceSpan(this);
        }
    }
}
