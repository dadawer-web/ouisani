package com.ouisani.aios.core.importance;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent Importance Score 记录 — 一次工作流执行后各 role 的贡献度快照。
 * <p>
 * 借鉴 DyLAN（arXiv:2310.02170）的 Agent Importance Score：类反向传播计算每个 agent
 * 对最终产物的贡献度。与 {@link com.ouisani.aios.core.provenance.ProvenanceRecord}
 * 的持久化范式一致 — JSONL 单行追加，跨 session 累积供离线 team optimization。
 * <p>
 * <b>伪标签</b>：用 {@code node.status == SUCCESS}（客观验证通过）替代 DyLAN 的"答案命中共识"，
 * 避免"共识即正确"强化系统性错误。
 * <p>
 * 持久化到 {@link com.ouisani.aios.core.config.AiosPaths#bouldersDir()}/importance.jsonl。
 *
 * @param workflowId     工作流标识
 * @param taskType       任务类型（第一版用 workflowId 代理，follow-up 加显式字段）
 * @param ts             记录时间戳（epoch millis）
 * @param roleImportance role → 贡献度映射（值域 [0,1]，全图总和≈SUCCESS 叶子数 / 命中数）
 */
public record ImportanceRecord(
        String workflowId,
        String taskType,
        long ts,
        Map<String, Double> roleImportance
) {

    public ImportanceRecord {
        if (roleImportance == null) roleImportance = new LinkedHashMap<>();
        // 防御性拷贝，保持插入顺序便于人读
        roleImportance = new LinkedHashMap<>(roleImportance);
    }

    /** 空记录（防御性，nodes 为空时用） */
    public static ImportanceRecord empty(String workflowId, String taskType) {
        return new ImportanceRecord(workflowId, taskType, System.currentTimeMillis(), Map.of());
    }

    /**
     * 序列化为 JSONL 一行 — 仿 {@link com.ouisani.aios.core.provenance.ProvenanceRecord#toJsonLine}
     * 手写序列化，不引 Jackson 到 core 层。Map 序列化为 {@code {"role1":0.5,"role2":0.3}}。
     */
    public String toJsonLine() {
        StringBuilder sb = new StringBuilder(128);
        sb.append('{');
        sb.append("\"workflowId\":").append(escape(workflowId)).append(',');
        sb.append("\"taskType\":").append(escape(taskType)).append(',');
        sb.append("\"ts\":").append(ts).append(',');
        sb.append("\"roleImportance\":{");
        boolean first = true;
        for (Map.Entry<String, Double> e : roleImportance.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(escape(e.getKey())).append(':').append(formatDouble(e.getValue()));
        }
        sb.append("}}");
        return sb.toString();
    }

    /**
     * 从 JSONL 一行反序列化 — {@link #toJsonLine} 的对偶。
     * 用 Gson 树模型手动取字段（同 ProvenanceRecord 范式，避免反射 record 的访问异常）。
     * 解析失败/输入空 → null（best-effort，不抛）。
     */
    public static ImportanceRecord fromJsonLine(String line) {
        if (line == null || line.isBlank()) return null;
        try {
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
            String workflowId = optString(o, "workflowId");
            String taskType = optString(o, "taskType");
            long ts = o.has("ts") && o.get("ts").isJsonPrimitive() ? o.get("ts").getAsLong() : 0L;
            Map<String, Double> importance = new LinkedHashMap<>();
            if (o.has("roleImportance") && o.get("roleImportance").isJsonObject()) {
                for (Map.Entry<String, com.google.gson.JsonElement> e : o.get("roleImportance").getAsJsonObject().entrySet()) {
                    if (e.getValue().isJsonPrimitive()) {
                        importance.put(e.getKey(), e.getValue().getAsDouble());
                    }
                }
            }
            return new ImportanceRecord(workflowId, taskType, ts, importance);
        } catch (Exception e) {
            return null;
        }
    }

    private static String optString(com.google.gson.JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    /** Double 格式化 — 过滤 NaN/Infinity（JSON 不支持），保留 6 位小数 */
    private static String formatDouble(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0.0";
        // 6 位小数足够区分贡献度，避免超长浮点
        return String.format(java.util.Locale.ROOT, "%.6f", v);
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
