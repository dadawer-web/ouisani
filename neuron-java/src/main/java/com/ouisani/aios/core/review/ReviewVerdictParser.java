package com.ouisani.aios.core.review;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ouisani.aios.core.skill.SkillOutputParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Reviewer 输出解析器 — 从 reviewer 原始响应提取结构化 {@link ReviewVerdict}。
 * <p>
 * 复用 {@link SkillOutputParser} 提取 fenced block（同层 core/skill 可依赖），
 * 契约 {@code "review:Reviewer verdict as JSON"} 优先匹配 {@code ```review} block，
 * 找不到则回退第一个 fenced block（兼容 reviewer 输出 {@code ```json}）。
 * <p>
 * 解析失败 / 无 block → {@link ReviewVerdict#inconclusive()}（best-effort，不抛出）。
 *
 * <h3>期望的 reviewer 输出契约</h3>
 * <pre>
 * ```review
 * {"verdict":"CLEAN|FLAGGED|BLOCKING|INCONCLUSIVE","summary":"...","findings":[{"severity":"low|medium|high","targetPath":"...","message":"...","claim":"...","evidence":"..."}]}
 * ```
 * </pre>
 * <p>
 * Phase 6: {@code claim}（被核实的断言）+ {@code evidence}（provenance 源引用）为可选字段，
 * 缺失时降级为空串（向后兼容旧 reviewer 输出）。
 */
public final class ReviewVerdictParser {

    private static final Logger log = LoggerFactory.getLogger(ReviewVerdictParser.class);
    private static final Gson GSON = new Gson();

    private ReviewVerdictParser() {}

    /**
     * 解析 reviewer 响应为 {@link ReviewVerdict}。永不抛出。
     */
    public static ReviewVerdict parse(String reviewerResponse) {
        if (reviewerResponse == null || reviewerResponse.isBlank()) {
            return ReviewVerdict.inconclusive();
        }

        // 优先 ```review block，回退第一个 fenced block（兼容 ```json）
        SkillOutputParser.SkillOutput out =
                SkillOutputParser.parse(reviewerResponse, "review:Reviewer verdict as JSON");
        if (!out.hasContent()) {
            out = SkillOutputParser.parse(reviewerResponse, (String) null);
        }
        if (!out.hasContent()) {
            return ReviewVerdict.inconclusive();
        }

        try {
            JsonObject obj = GSON.fromJson(out.rawContent(), JsonObject.class);
            if (obj == null) {
                return ReviewVerdict.inconclusive();
            }
            ReviewVerdict.Outcome outcome = parseOutcome(obj.get("verdict"));
            List<ReviewFinding> findings = parseFindings(
                    obj.has("findings") && obj.get("findings").isJsonArray()
                            ? obj.getAsJsonArray("findings") : null);
            String summary = obj.has("summary") && obj.get("summary").isJsonPrimitive()
                    ? obj.get("summary").getAsString() : "";
            return new ReviewVerdict(outcome, findings, summary);
        } catch (Exception e) {
            log.debug("[ReviewVerdictParser] 解析失败，降级 INCONCLUSIVE: {}", e.getMessage());
            return ReviewVerdict.inconclusive();
        }
    }

    private static ReviewVerdict.Outcome parseOutcome(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) {
            return ReviewVerdict.Outcome.INCONCLUSIVE;
        }
        try {
            return ReviewVerdict.Outcome.valueOf(e.getAsString().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ReviewVerdict.Outcome.INCONCLUSIVE;
        }
    }

    private static List<ReviewFinding> parseFindings(JsonArray arr) {
        List<ReviewFinding> list = new ArrayList<>();
        if (arr == null) {
            return list;
        }
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String severity = o.has("severity") && o.get("severity").isJsonPrimitive()
                    ? o.get("severity").getAsString() : "low";
            String targetPath = o.has("targetPath") && o.get("targetPath").isJsonPrimitive()
                    ? o.get("targetPath").getAsString() : null;
            String message = o.has("message") && o.get("message").isJsonPrimitive()
                    ? o.get("message").getAsString() : "";
            // Phase 6: claim + evidence（缺失→""，向后兼容旧 reviewer 输出）
            String claim = o.has("claim") && o.get("claim").isJsonPrimitive()
                    ? o.get("claim").getAsString() : "";
            String evidence = o.has("evidence") && o.get("evidence").isJsonPrimitive()
                    ? o.get("evidence").getAsString() : "";
            list.add(new ReviewFinding(severity, targetPath, message, claim, evidence));
        }
        return list;
    }
}
