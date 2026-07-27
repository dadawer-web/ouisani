package com.ouisani.aios.core.review;

import java.util.List;

/**
 * Review 持久化记录 — 追加到 {@code .aios/provenance.jsonl} 旁边的 {@code .aios/review.jsonl}。
 * <p>
 * 与 {@link com.ouisani.aios.core.provenance.ProvenanceRecord} 平行（不修改后者——它是 7 字段
 * 不可变 record，调用方多）。通过 {@link #targetPath()} 关联 provenance 记录。
 * <p>
 * 手写 {@link #toJsonLine()} 避免引入 Jackson 到 core 层（同 ProvenanceRecord 范式）。
 *
 * @param targetPath          关联 artifact 路径（多 artifact 时取首个或 "multi"）
 * @param agentId             被审 agent 标识
 * @param runId               QueryEngine runId
 * @param ts                  时间戳（epoch millis）
 * @param level               gate 级别（annotate/soft/hard）
 * @param outcome             裁决（CLEAN/FLAGGED/BLOCKING/INCONCLUSIVE）
 * @param summary             摘要
 * @param findings            发现列表
 * @param deterministicForced 是否由 {@link com.ouisani.aios.core.overnight.NodeCompletionVerifier} 强制
 */
public record ReviewRecord(
        String targetPath,
        String agentId,
        String runId,
        long ts,
        String level,
        String outcome,
        String summary,
        List<ReviewFinding> findings,
        boolean deterministicForced
) {
    public ReviewRecord {
        if (findings == null) findings = List.of();
        if (summary == null) summary = "";
        if (level == null) level = "annotate";
        if (outcome == null) outcome = "INCONCLUSIVE";
        if (targetPath == null) targetPath = "";
        if (agentId == null) agentId = "";
        if (runId == null) runId = "";
    }

    /**
     * 序列化为 JSONL 一行（单行 JSON，无换行）。手写转义，不引 Jackson。
     */
    public String toJsonLine() {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        sb.append("\"targetPath\":").append(escape(targetPath)).append(',');
        sb.append("\"agentId\":").append(escape(agentId)).append(',');
        sb.append("\"runId\":").append(escape(runId)).append(',');
        sb.append("\"ts\":").append(ts).append(',');
        sb.append("\"level\":").append(escape(level)).append(',');
        sb.append("\"outcome\":").append(escape(outcome)).append(',');
        sb.append("\"summary\":").append(escape(summary)).append(',');
        sb.append("\"findings\":");
        if (findings.isEmpty()) {
            sb.append("[]");
        } else {
            sb.append('[');
            for (int i = 0; i < findings.size(); i++) {
                if (i > 0) sb.append(',');
                ReviewFinding f = findings.get(i);
                sb.append("{\"severity\":").append(escape(f.severity()));
                sb.append(",\"targetPath\":").append(escape(f.targetPath() == null ? "" : f.targetPath()));
                sb.append(",\"message\":").append(escape(f.message()));
                // Phase 6: claim + evidence — 持久化可追溯断言（additive，旧 reader 缺字段不报错）
                sb.append(",\"claim\":").append(escape(f.claim()));
                sb.append(",\"evidence\":").append(escape(f.evidence()));
                // Phase 7: bypassImmune + suggestedRules — 权限拒绝信息持久化（additive）
                sb.append(",\"bypassImmune\":").append(f.bypassImmune());
                sb.append(",\"suggestedRules\":");
                if (f.suggestedRules().isEmpty()) {
                    sb.append("[]");
                } else {
                    sb.append('[');
                    for (int j = 0; j < f.suggestedRules().size(); j++) {
                        if (j > 0) sb.append(',');
                        sb.append(escape(f.suggestedRules().get(j)));
                    }
                    sb.append(']');
                }
                sb.append('}');
            }
            sb.append(']');
        }
        sb.append(",\"deterministicForced\":").append(deterministicForced);
        sb.append('}');
        return sb.toString();
    }

    /**
     * 从 JSONL 一行反序列化 — {@link #toJsonLine} 的对偶（Phase 6：跨 session 磁盘回读）。
     * <p>
     * 不用 Gson 反射 record（包未 {@code opens}），改用 Gson 树模型 {@code JsonObject} 手动取字段
     * （同 {@code ReviewVerdictParser} 范式）。findings 嵌套数组复用 {@link ReviewFinding#fromJsonObject}。
     * 旧 jsonl 行（无 claim/evidence）→ findings 的 claim/evidence 降级空串。
     *
     * @param line JSONL 单行
     * @return 记录；解析失败/输入空 → null（best-effort，不抛）
     */
    public static ReviewRecord fromJsonLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
            List<ReviewFinding> findings = new java.util.ArrayList<>();
            if (o.has("findings") && o.get("findings").isJsonArray()) {
                for (com.google.gson.JsonElement el : o.getAsJsonArray("findings")) {
                    if (el.isJsonObject()) {
                        ReviewFinding f = ReviewFinding.fromJsonObject(el.getAsJsonObject());
                        if (f != null) {
                            findings.add(f);
                        }
                    }
                }
            }
            return new ReviewRecord(
                    optString(o, "targetPath", ""),
                    optString(o, "agentId", ""),
                    optString(o, "runId", ""),
                    o.has("ts") && o.get("ts").isJsonPrimitive() ? o.get("ts").getAsLong() : 0L,
                    optString(o, "level", "annotate"),
                    optString(o, "outcome", "INCONCLUSIVE"),
                    optString(o, "summary", ""),
                    findings,
                    o.has("deterministicForced") && o.get("deterministicForced").isJsonPrimitive()
                            && o.get("deterministicForced").getAsBoolean()
            );
        } catch (Exception e) {
            return null;
        }
    }

    private static String optString(com.google.gson.JsonObject o, String key, String def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
    }

    /** JSON 字符串转义 — 同 {@link com.ouisani.aios.core.provenance.ProvenanceRecord} 模式。 */
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
