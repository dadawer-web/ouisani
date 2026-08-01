package com.ouisani.aios.core.provenance;

/**
 * 恢复决策 provenance 记录 — 新论文（恢复通道攻击面）的审计链单元。
 * <p>
 * 与 {@link ProvenanceRecord}（文件写入追溯）平级但正交：ProvenanceRecord 记"谁用工具写了什么文件"，
 * 本记录记"哪个 agent 触发了哪层恢复策略、为什么、结果如何"。两者共同构成"这个 agent 最终为什么
 * 成功/失败"的完整可追溯链条。
 * <p>
 * <b>与论文1的边界</b>：本类是独立新增 record，<b>不修改</b> {@link ProvenanceHook} 或
 * {@link ProvenanceRecord} —— 那是论文1已描述的文件写入 provenance 逻辑，保持字节级稳定。
 * 本记录由 {@link RecoveryProvenanceRecorder} 独立存储（{@code .aios/recovery_provenance.jsonl}）。
 * <p>
 * <b>捕获的决策点</b>（经 {@link RecoveryProvenanceSubscriber} 订阅 EventBus 获得，不修改 orchestrator）：
 * <ul>
 *   <li>{@code RECOVERY_SUCCESS} / {@code RECOVERY_FAILED} —— 某层恢复策略执行结果</li>
 *   <li>{@code CIRCUIT_BREAKER_TRIGGERED} —— 固定阈值熔断触发</li>
 *   <li>{@code RECOVERY_GUARD_DENIED} / {@code BUDGET_GATE_DENIED} —— 新论文两层防御的拦截决策</li>
 * </ul>
 *
 * @param agentId      触发恢复的 agent 标识
 * @param strategyName 策略名（如 ReflectionInjection / CIRCUIT_BREAKER / RECOVERY_GUARD）
 * @param category     决策类别（RECOVERY_SUCCESS / RECOVERY_FAILED / CIRCUIT_BREAKER_TRIGGERED / ...）
 * @param success      本次决策是否成功放行重试
 * @param reason       决策原因（用于审计回溯，可能含权限/预算/策略细节）
 * @param traceId      端到端追踪标识（可能为 null）；与同 traceId 下的 cgroup/permission 决策可关联
 * @param ts           决策时间戳（epoch millis）
 */
public record RecoveryProvenanceRecord(
        String agentId,
        String strategyName,
        String category,
        boolean success,
        String reason,
        String traceId,
        long ts
) {
    /** 紧凑构造器 — null 安全（best-effort，审计记录不因 null 抛异常）。 */
    public RecoveryProvenanceRecord {
        if (agentId == null) agentId = "";
        if (strategyName == null) strategyName = "unknown";
        if (category == null) category = "UNKNOWN";
        if (reason == null) reason = "";
    }

    /**
     * 序列化为 JSONL 一行（手写，避免引入 Jackson 到 core，与 {@link ProvenanceRecord#toJsonLine} 一致）。
     *
     * @return 单行 JSON 字符串
     */
    public String toJsonLine() {
        StringBuilder sb = new StringBuilder(160);
        sb.append('{');
        sb.append("\"agentId\":").append(escape(agentId)).append(',');
        sb.append("\"strategyName\":").append(escape(strategyName)).append(',');
        sb.append("\"category\":").append(escape(category)).append(',');
        sb.append("\"success\":").append(success).append(',');
        sb.append("\"reason\":").append(escape(reason)).append(',');
        sb.append("\"traceId\":").append(traceId == null ? "null" : escape(traceId)).append(',');
        sb.append("\"ts\":").append(ts);
        sb.append('}');
        return sb.toString();
    }

    /**
     * 从 JSONL 一行反序列化（Gson 树模型，与 {@link ProvenanceRecord#fromJsonLine} 范式一致）。
     *
     * @param line JSONL 单行
     * @return 记录；解析失败/输入空 → null（best-effort，不抛）
     */
    public static RecoveryProvenanceRecord fromJsonLine(String line) {
        if (line == null || line.isBlank()) return null;
        try {
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
            return new RecoveryProvenanceRecord(
                    optString(o, "agentId"),
                    optString(o, "strategyName"),
                    optString(o, "category"),
                    o.has("success") && o.get("success").isJsonPrimitive() && o.get("success").getAsBoolean(),
                    optString(o, "reason"),
                    o.has("traceId") && !o.get("traceId").isJsonNull() ? optString(o, "traceId") : null,
                    o.has("ts") && o.get("ts").isJsonPrimitive() ? o.get("ts").getAsLong() : 0L
            );
        } catch (Exception e) {
            return null;
        }
    }

    private static String optString(com.google.gson.JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

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
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
        return out.toString();
    }
}
