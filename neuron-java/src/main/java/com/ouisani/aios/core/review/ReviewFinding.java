package com.ouisani.aios.core.review;

import java.util.List;

/**
 * 单条 review 发现 — 镜像 OpenScience {@code reviewer.txt} 的 {@code {claim,issue,severity,evidence}}。
 * <p>
 * Phase 6 扩展：补 {@code claim}（被核实的断言）+ {@code evidence}（provenance 源引用，
 * 如 "agent_5 wrote v2 via write at ts"），让发现自描述可追溯 —— 配合 {@code provenance_query}
 * 工具，把"数字是否可追溯"从 LLM 读文件判断变成 DAG 查询。
 * <p>
 * Phase 7 扩展：补 {@code bypassImmune} + {@code suggestedRules}，让 ReviewGate 能直接消费
 * {@link com.ouisani.aios.core.permission.PermissionDecision} 的权限拒绝信息。
 * 当 reviewer 发现 agent 在工作期间尝试了被权限引擎拒绝的操作（如 {@code rm -rf /}），
 * 把拒绝决策映射为 ReviewFinding，bypass_immune=true 标记"危险操作，allow 规则无法覆盖"，
 * suggestedRules 告诉用户"加什么规则能放行"（bypass_immune 的除外）。
 *
 * @param severity       严重等级："low" | "medium" | "high"（high 视为阻断性）
 * @param targetPath     关联的 artifact 路径（对应 {@code .aios/provenance.jsonl} 的 path），可为 null
 * @param message        发现描述
 * @param claim          被核实的断言（final answer 中的具体声明，可为空）
 * @param evidence       provenance 源引用（agent/tool/version 摘要，可为空）
 * @param bypassImmune   true = 此发现源于 bypass_immune 权限拒绝（危险操作，allow 规则无法覆盖）
 * @param suggestedRules 建议添加的放行规则清单（bypass_immune=true 时为空，因为无法通过规则放行）
 */
public record ReviewFinding(
        String severity,
        String targetPath,
        String message,
        String claim,
        String evidence,
        boolean bypassImmune,
        List<String> suggestedRules
) {

    public ReviewFinding {
        if (severity == null) severity = "low";
        if (message == null) message = "";
        if (claim == null) claim = "";
        if (evidence == null) evidence = "";
        if (suggestedRules == null) suggestedRules = List.of();
    }

    /**
     * 向后兼容构造 — claim/evidence/bypassImmune/suggestedRules 默认空。
     * <p>
     * 供 {@link ReviewGate#applyDeterministicBackstop} 等不涉及权限拒绝的调用方使用（零回归）。
     */
    public ReviewFinding(String severity, String targetPath, String message) {
        this(severity, targetPath, message, "", "", false, List.of());
    }

    /**
     * Phase 6 兼容构造 — claim/evidence 显式，bypassImmune/suggestedRules 默认空。
     */
    public ReviewFinding(String severity, String targetPath, String message,
                         String claim, String evidence) {
        this(severity, targetPath, message, claim, evidence, false, List.of());
    }

    /** high 严重级视为阻断性发现。 */
    public boolean isBlocking() {
        return "high".equalsIgnoreCase(severity);
    }

    /**
     * 从 JSONL 一行反序列化 — Phase 6/7 跨 session 磁盘回读。
     * <p>
     * 不用 Gson 反射 record（包未 {@code opens}），改用 Gson 树模型手动取字段
     * （同 {@code ReviewVerdictParser} 范式）。claim/evidence/bypassImmune/suggestedRules
     * 缺失 → 默认值（向后兼容旧 jsonl）。
     *
     * @param line JSONL 单行
     * @return 发现；解析失败/输入空 → null（best-effort，不抛）
     */
    public static ReviewFinding fromJsonLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            return fromJsonObject(com.google.gson.JsonParser.parseString(line).getAsJsonObject());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 Gson {@code JsonObject} 构造发现 — 供 {@link ReviewRecord#fromJsonLine} 解析嵌套 findings 数组复用。
     */
    static ReviewFinding fromJsonObject(com.google.gson.JsonObject o) {
        if (o == null) {
            return null;
        }
        String severity = optString(o, "severity", "low");
        String targetPath = o.has("targetPath") && o.get("targetPath").isJsonPrimitive()
                ? o.get("targetPath").getAsString() : null;
        String message = optString(o, "message", "");
        String claim = optString(o, "claim", "");
        String evidence = optString(o, "evidence", "");
        boolean bypassImmune = o.has("bypassImmune") && o.get("bypassImmune").isJsonPrimitive()
                && o.get("bypassImmune").getAsBoolean();
        List<String> suggestedRules = new java.util.ArrayList<>();
        if (o.has("suggestedRules") && o.get("suggestedRules").isJsonArray()) {
            for (com.google.gson.JsonElement el : o.getAsJsonArray("suggestedRules")) {
                if (el.isJsonPrimitive()) {
                    suggestedRules.add(el.getAsString());
                }
            }
        }
        return new ReviewFinding(severity, targetPath, message, claim, evidence, bypassImmune, suggestedRules);
    }

    private static String optString(com.google.gson.JsonObject o, String key, String def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
    }
}
