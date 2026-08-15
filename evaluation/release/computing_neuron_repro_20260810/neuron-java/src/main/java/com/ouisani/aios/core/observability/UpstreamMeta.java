package com.ouisani.aios.core.observability;

/**
 * 上游调用元数据 — 每次 syscall/工具调用捕获的可观测性快照。
 * <p>
 * 借鉴 nuwa 项目 {@code nuwa-services/llm-gateway/src/core/routeHandler.ts}
 * 的 UpstreamMeta 中间件链元数据传递模式（res.locals.upstream 沿 Express
 * 中间件链透传 6 个标准字段）。适配 Java 生态改为 ThreadLocal + EventBus
 * 伴生事件双通道（见 {@link UpstreamMetaContext} / {@link UpstreamMetaHook}）。
 *
 * <h3>字段对照（nuwa → neuron-java）</h3>
 * <table>
 *   <tr><th>nuwa UpstreamMeta</th><th>neuron-java</th><th>说明</th></tr>
 *   <tr><td>upstream_name</td><td>upstreamName</td><td>上游标识，如 "llm.think" / "tool.web_search"</td></tr>
 *   <tr><td>upstream_duration_ms</td><td>upstreamDurationMs</td><td>墙钟耗时（毫秒）</td></tr>
 *   <tr><td>upstream_status_code</td><td>upstreamStatusCode</td><td>HTTP 风格状态码（200/403/408/409/500）</td></tr>
 *   <tr><td>upstream_cost_units</td><td>upstreamCostUnits</td><td>成本单位，v1 null；v2 "tokens:1234in/567out"</td></tr>
 *   <tr><td>upstream_bytes</td><td>upstreamBytes</td><td>响应字节长度（UTF-8 编码 response.data）</td></tr>
 *   <tr><td>error_code</td><td>errorCode</td><td>失败时的错误码（成功为 null）</td></tr>
 * </table>
 *
 * <h3>元字段（与 ProvenanceRecord 对齐）</h3>
 * <ul>
 *   <li>{@link #ts} — epoch millis 时间戳</li>
 *   <li>{@link #agentId} — Agent 标识（复用 ProvenanceHook.CURRENT_AGENT_ID）</li>
 *   <li>{@link #sessionId} — 会话标识（复用 ProvenanceHook.CURRENT_SESSION_ID）</li>
 * </ul>
 * 元字段对齐 {@code ProvenanceRecord} 使后续 {@code ProvenanceQuery.traceByAgent}
 * 与 {@code UpstreamMetaQuery.traceByAgent} 可按同一 agentId + sessionId + ts
 * 联合查询（DAG 可追溯硬约束）。
 *
 * <h3>序列化策略</h3>
 * 完全仿 {@code ProvenanceRecord.toJsonLine}：手写 StringBuilder + 私有
 * {@link #escape} 方法，避免引入 Jackson 依赖到 core 层。反序列化用 Gson
 * 树模型 {@code JsonObject} 手动取字段（同 ProvenanceRecord.fromJsonLine 范式，
 * 避免 {@code opens} 到 gson 抛 InaccessibleObjectException）。
 *
 * @see UpstreamMetaContext
 * @see UpstreamMetaHook
 * @see com.ouisani.aios.core.provenance.ProvenanceRecord
 */
public record UpstreamMeta(
        // ── 6 个标准字段（用户需求，JSON 序列化时转 snake_case 与 nuwa 对齐）──
        String upstreamName,
        long   upstreamDurationMs,
        int    upstreamStatusCode,
        String upstreamCostUnits,
        long   upstreamBytes,
        String errorCode,

        // ── 3 个元字段（与 ProvenanceRecord 对齐，便于 DAG 关联）──
        long   ts,
        String agentId,
        String sessionId
) {
    /**
     * 紧凑构造器 — 允许 upstreamName 为 null 时降级为 "unknown"（best-effort）。
     */
    public UpstreamMeta {
        if (upstreamName == null) upstreamName = "unknown";
    }

    /**
     * 序列化为 JSONL 一行（单行 JSON，无换行）。
     * <p>
     * 手写序列化避免引入 Jackson 依赖到 core 层 — 字段固定且简单。
     * 字段名 snake_case，与 nuwa UpstreamMeta 对齐。
     *
     * @return 单行 JSON 字符串
     */
    public String toJsonLine() {
        StringBuilder sb = new StringBuilder(160);
        sb.append('{');
        sb.append("\"upstream_name\":").append(escape(upstreamName)).append(',');
        sb.append("\"upstream_duration_ms\":").append(upstreamDurationMs).append(',');
        sb.append("\"upstream_status_code\":").append(upstreamStatusCode).append(',');
        sb.append("\"upstream_cost_units\":").append(upstreamCostUnits == null ? "null" : escape(upstreamCostUnits)).append(',');
        sb.append("\"upstream_bytes\":").append(upstreamBytes).append(',');
        sb.append("\"error_code\":").append(errorCode == null ? "null" : escape(errorCode)).append(',');
        sb.append("\"ts\":").append(ts).append(',');
        sb.append("\"agentId\":").append(agentId == null ? "null" : escape(agentId)).append(',');
        sb.append("\"sessionId\":").append(sessionId == null ? "null" : escape(sessionId));
        sb.append('}');
        return sb.toString();
    }

    /**
     * 从 JSONL 一行反序列化 — {@link #toJsonLine} 的对偶（跨 session 磁盘回读）。
     * <p>
     * <b>不用 Gson 反射 record</b>（包未 {@code opens} 到 gson，会抛
     * InaccessibleObjectException），改为 Gson 树模型 {@code JsonObject}
     * 手动取字段（同 {@code ProvenanceRecord.fromJsonLine} 范式）。
     *
     * @param line JSONL 单行
     * @return 记录；解析失败/输入空 → null（best-effort，不抛）
     */
    public static UpstreamMeta fromJsonLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
            return new UpstreamMeta(
                    optString(o, "upstream_name"),
                    o.has("upstream_duration_ms") && o.get("upstream_duration_ms").isJsonPrimitive() ? o.get("upstream_duration_ms").getAsLong() : 0L,
                    o.has("upstream_status_code") && o.get("upstream_status_code").isJsonPrimitive() ? o.get("upstream_status_code").getAsInt() : 0,
                    o.has("upstream_cost_units") && !o.get("upstream_cost_units").isJsonNull() ? optString(o, "upstream_cost_units") : null,
                    o.has("upstream_bytes") && o.get("upstream_bytes").isJsonPrimitive() ? o.get("upstream_bytes").getAsLong() : 0L,
                    o.has("error_code") && !o.get("error_code").isJsonNull() ? optString(o, "error_code") : null,
                    o.has("ts") && o.get("ts").isJsonPrimitive() ? o.get("ts").getAsLong() : 0L,
                    o.has("agentId") && !o.get("agentId").isJsonNull() ? optString(o, "agentId") : null,
                    o.has("sessionId") && !o.get("sessionId").isJsonNull() ? optString(o, "sessionId") : null
            );
        } catch (Exception e) {
            return null;
        }
    }

    private static String optString(com.google.gson.JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    /**
     * JSON 字符串转义 — 仅处理必要字符（" \ 换行 制表符）。
     * 与 ProvenanceRecord.escape 完全一致，保证两侧序列化结果可互读。
     */
    private static String escape(String s) {
        if (s == null) return "null";
        StringBuilder out = new StringBuilder(s.length() + 8);
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }
}
