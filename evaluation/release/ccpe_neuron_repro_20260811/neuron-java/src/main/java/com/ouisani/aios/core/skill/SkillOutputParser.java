package com.ouisani.aios.core.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Skill 输出解析器 — 从 LLM 非结构化输出中按 {@link SkillLoader.SkillDef#outputContract()}
 * 提取结构化结果。
 * <p>
 * 借鉴 ai4s-research/open-science 的 SKILL.md 输出契约设计：在 SKILL.md 末尾定义
 * {@code ```review {json} } fenced block，UI 渲染成卡片 — 让"非结构化 LLM 输出"
 * 变成"可机器解析的工件"。
 *
 * <h3>契约格式</h3>
 * {@code outputContract} 字段格式：{@code <languageTag>:<description>}，例如：
 * <ul>
 *   <li>{@code "json:Reviewer findings as JSON with findings[], severity, sections[]"}</li>
 *   <li>{@code "review:Auditor findings as fenced review block"}</li>
 *   <li>{@code "yaml:Experiment config as YAML"}</li>
 * </ul>
 * 为空或无冒号分隔时，退化为"找第一个 fenced block"。
 *
 * <h3>解析流程</h3>
 * <ol>
 *   <li>从 contract 提取 languageTag（冒号前的部分）</li>
 *   <li>从 LLM 输出文本找 {@code ```languageTag ... ```} fenced block</li>
 *   <li>提取 block 内容（trim 首尾空白）</li>
 *   <li>返回 {@link SkillOutput}（含 languageTag + rawContent + parsedJson 可选）</li>
 * </ol>
 *
 * <h3>使用示例</h3>
 * <pre>
 * SkillLoader.SkillDef skill = SkillLoader.get("integrity-auditor").orElseThrow();
 * if (skill.outputContract() != null && !skill.outputContract().isBlank()) {
 *     SkillOutput out = SkillOutputParser.parse(llmResponse, skill.outputContract());
 *     if (out.parsedJson().isPresent()) {
 *         JsonObject json = out.parsedJson().get();
 *         // 处理结构化审计结果
 *     }
 * }
 * </pre>
 *
 * @see SkillLoader.SkillDef#outputContract()
 */
public final class SkillOutputParser {

    private static final Logger log = LoggerFactory.getLogger(SkillOutputParser.class);

    /**
     * Fenced block 正则 — 匹配 {@code ```lang ... ```}（非贪婪，跨行）。
     * <p>
     * 捕获组 1 = languageTag，捕获组 2 = block 内容。
     */
    private static final Pattern FENCED_BLOCK = Pattern.compile(
            "```([a-zA-Z0-9_+-]+)?\\s*\\n(.*?)```",
            Pattern.DOTALL
    );

    private SkillOutputParser() {}

    /**
     * 从 LLM 输出文本中按 contract 提取结构化结果。
     *
     * @param llmOutput       LLM 完整输出文本
     * @param outputContract  skill 的输出契约（格式 {@code <lang>:<desc>}，可为空）
     * @return 解析结果（找不到时 rawContent 为空，parsedJson 为 empty）
     */
    public static SkillOutput parse(String llmOutput, String outputContract) {
        if (llmOutput == null || llmOutput.isEmpty()) {
            return SkillOutput.empty();
        }

        String expectedLang = extractLanguageTag(outputContract);
        Optional<String> langOpt = expectedLang == null
                ? Optional.empty()
                : Optional.of(expectedLang);

        // 找所有 fenced block，按 languageTag 过滤
        List<FencedBlock> blocks = extractFencedBlocks(llmOutput);
        if (blocks.isEmpty()) {
            log.debug("[SkillOutputParser] 无 fenced block (contract={})", outputContract);
            return SkillOutput.empty();
        }

        // 优先匹配 contract 指定的 languageTag；找不到则取第一个
        FencedBlock matched = langOpt
                .flatMap(lang -> blocks.stream().filter(b -> lang.equalsIgnoreCase(b.languageTag())).findFirst())
                .orElse(blocks.get(0));

        // 如果是 json，尝试解析
        Optional<String> parsedJson = Optional.empty();
        if ("json".equalsIgnoreCase(matched.languageTag())) {
            parsedJson = tryParseJson(matched.content());
        }

        return new SkillOutput(
                matched.languageTag(),
                matched.content(),
                parsedJson,
                matched.start(),
                matched.end()
        );
    }

    /**
     * 从 LLM 输出文本中按 {@link SkillLoader.SkillDef} 的 outputContract 提取。
     */
    public static SkillOutput parse(String llmOutput, SkillLoader.SkillDef skill) {
        if (skill == null) {
            return SkillOutput.empty();
        }
        return parse(llmOutput, skill.outputContract());
    }

    /**
     * 从 contract 字符串提取 languageTag。
     * <p>
     * 格式 {@code <lang>:<desc>} — 冒号前的部分作为 languageTag。
     * 无冒号或为空时返回 null（表示"任意 fenced block"）。
     */
    private static String extractLanguageTag(String contract) {
        if (contract == null || contract.isBlank()) {
            return null;
        }
        int colon = contract.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String lang = contract.substring(0, colon).trim().toLowerCase();
        return lang.isEmpty() ? null : lang;
    }

    /**
     * 提取所有 fenced block。
     */
    private static List<FencedBlock> extractFencedBlocks(String text) {
        List<FencedBlock> blocks = new ArrayList<>();
        Matcher m = FENCED_BLOCK.matcher(text);
        while (m.find()) {
            String lang = m.group(1);
            String content = m.group(2);
            if (content != null) {
                content = content.trim();
            }
            blocks.add(new FencedBlock(
                    lang == null ? "" : lang.toLowerCase(),
                    content == null ? "" : content,
                    m.start(),
                    m.end()
            ));
        }
        return blocks;
    }

    /**
     * 尝试 JSON 解析 — 仅做格式校验，不引入 Jackson 依赖到 core 层。
     * <p>
     * 简单校验：首尾是 {} 或 []，且括号配对。
     * 真正的 JSON 解析由上层（应用层）用 Jackson 完成。
     */
    private static Optional<String> tryParseJson(String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        String trimmed = content.trim();
        if (!isJsonLike(trimmed)) {
            return Optional.empty();
        }
        // 简单括号配对校验
        if (!bracketsBalanced(trimmed)) {
            log.debug("[SkillOutputParser] JSON 括号不配对，跳过");
            return Optional.empty();
        }
        return Optional.of(trimmed);
    }

    private static boolean isJsonLike(String s) {
        return (s.startsWith("{") && s.endsWith("}"))
                || (s.startsWith("[") && s.endsWith("]"));
    }

    private static boolean bracketsBalanced(String s) {
        int depth = 0;
        boolean inString = false;
        char prev = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && prev != '\\') {
                inString = !inString;
            }
            if (!inString) {
                if (c == '{' || c == '[') depth++;
                else if (c == '}' || c == ']') depth--;
                if (depth < 0) return false;
            }
            prev = c;
        }
        return depth == 0;
    }

    // ── 内部数据结构 ──

    private record FencedBlock(String languageTag, String content, int start, int end) {}

    /**
     * Skill 输出解析结果。
     *
     * @param languageTag fenced block 的语言标签（如 "json"、"review"）
     * @param rawContent  fenced block 的原始内容（已 trim）
     * @param parsedJson  如果 languageTag 是 json 且格式校验通过，则存在；否则 empty
     * @param start       fenced block 在原文中的起始偏移
     * @param end         fenced block 在原文中的结束偏移
     */
    public record SkillOutput(
            String languageTag,
            String rawContent,
            Optional<String> parsedJson,
            int start,
            int end
    ) {
        /** 空结果 — 无 fenced block 或解析失败 */
        public static SkillOutput empty() {
            return new SkillOutput("", "", Optional.empty(), -1, -1);
        }

        /** 是否成功提取到内容 */
        public boolean hasContent() {
            return rawContent != null && !rawContent.isEmpty();
        }
    }
}
