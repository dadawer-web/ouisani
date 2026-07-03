package com.ouisani.aios.core.compact;

import java.util.regex.Pattern;

/**
 * 内容类型检测器 — 借鉴 Headroom transforms/content_detector.py。
 * <p>
 * 检测工具输出内容的类型，路由到专用压缩器。
 * 支持 8 种内容类型，按检测优先级排列：
 * <ol>
 *   <li>{@link #JSON_ARRAY} — JSON 数组（SmartCrusher 行级丢弃）</li>
 *   <li>{@link #GIT_DIFF} — Git 差异格式（保留文件头+变更行）</li>
 *   <li>{@link #HTML} — HTML 页面（提取文本内容）</li>
 *   <li>{@link #SEARCH_RESULTS} — grep/ripgrep 搜索结果（保留全部行）</li>
 *   <li>{@link #BUILD_OUTPUT} — 构建/测试日志（保留错误+警告）</li>
 *   <li>{@link #TABULAR} — CSV/TSV/Markdown 表格（保留表头+首尾行）</li>
 *   <li>{@link #SOURCE_CODE} — 源代码（符号重要性压缩）</li>
 *   <li>{@link #PLAIN_TEXT} — 纯文本（兜底截断）</li>
 * </ol>
 * <p>
 * <b>检测算法</b>：对每种类型用正则模式匹配，按匹配率计算置信度。
 * 第一个达到阈值的类型胜出。
 */
public class ContentTypeDetector {

    /** 内容类型枚举 — 对应 Headroom ContentType */
    public enum ContentType {
        JSON_ARRAY,      // JSON 数组（SmartCrusher）
        SOURCE_CODE,     // 源代码（Python/JS/TS/Go/Rust/Java）
        SEARCH_RESULTS,  // grep/ripgrep 输出
        BUILD_OUTPUT,    // 编译/测试/lint 日志
        GIT_DIFF,        // 统一差异格式
        HTML,            // HTML 页面
        TABULAR,         // CSV/TSV/Markdown 表格
        PLAIN_TEXT       // 纯文本（兜底）
    }

    /** 检测结果 */
    public record DetectionResult(
            ContentType contentType,
            double confidence,    // 0.0 - 1.0
            String language,      // 仅 SOURCE_CODE：检测到的编程语言
            String metadata       // 类型特定元数据
    ) {
        public DetectionResult(ContentType type, double confidence) {
            this(type, confidence, null, "");
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  检测模式 — 借鉴 Headroom content_detector.py 的正则表
    // ════════════════════════════════════════════════════════════════

    // 搜索结果格式：file:line:content
    private static final Pattern SEARCH_RESULT_PATTERN =
            Pattern.compile("^\\S+:\\d+:.*");

    // Git Diff 头部
    private static final Pattern DIFF_HEADER_PATTERN = Pattern.compile(
            "^(diff --git|diff --combined |diff --cc |--- a/\\|" +
            "@@\\s+-\\d+,\\d+\\s+\\+\\d+,\\d+\\s+@@|@@@+\\s)");

    // Diff 变更行
    private static final Pattern DIFF_CHANGE_PATTERN =
            Pattern.compile("^[+-][^+-]");

    // HTML 检测
    private static final Pattern HTML_DOCTYPE =
            Pattern.compile("^\\s*<!doctype\\s+html", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAG =
            Pattern.compile("<html[\\s>]", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_STRUCTURAL = Pattern.compile(
            "<(div|span|script|style|link|meta|nav|header|footer|aside|article|section|main)[\\s>]",
            Pattern.CASE_INSENSITIVE);

    // 日志模式
    private static final Pattern[] LOG_PATTERNS = {
            Pattern.compile("\\b(ERROR|FAIL|FAILED|FATAL|CRITICAL)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(WARN|WARNING)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(INFO|DEBUG|TRACE)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*\\d{4}-\\d{2}-\\d{2}"),           // 时间戳
            Pattern.compile("^\\s*\\[\\d{2}:\\d{2}:\\d{2}\\]"),     // 时间格式
            Pattern.compile("^={3,}|^-{3,}"),                        // 分隔线
            Pattern.compile("^\\s*(PASSED|FAILED|SKIPPED)"),        // 测试结果
            Pattern.compile("^(npm ERR!|yarn error|cargo error)"),  // 构建工具
            Pattern.compile("Traceback \\(most recent call last\\)"), // Python traceback
            Pattern.compile("^\\w*(Error|Exception):"),              // 异常
            Pattern.compile("^\\s*at\\s+[\\w.$]+\\(")               // 堆栈跟踪
    };

    // 代码模式 — 按语言
    private static final Pattern[] PYTHON_PATTERNS = {
            Pattern.compile("^\\s*(def|class|import|from|async def)\\s+\\w+"),
            Pattern.compile("^\\s*@\\w+"),                          // 装饰器
            Pattern.compile("^\\s*\"\"\""),                         // docstring
            Pattern.compile("^\\s*if __name__\\s*==")
    };
    private static final Pattern[] JS_PATTERNS = {
            Pattern.compile("^\\s*(function|const|let|var|class|import|export)\\s+"),
            Pattern.compile("^\\s*(async\\s+function|=>\\s*\\{)"),
            Pattern.compile("^\\s*module\\.exports")
    };
    private static final Pattern[] TS_PATTERNS = {
            Pattern.compile("^\\s*(interface|type|enum|namespace)\\s+\\w+"),
            Pattern.compile(":\\s*(string|number|boolean|any|void)\\b")
    };
    private static final Pattern[] GO_PATTERNS = {
            Pattern.compile("^\\s*(func|type|package|import)\\s+"),
            Pattern.compile("^\\s*func\\s+\\([^)]+\\)\\s+\\w+")    // 方法
    };
    private static final Pattern[] RUST_PATTERNS = {
            Pattern.compile("^\\s*(fn|struct|enum|impl|mod|use|pub)\\s+"),
            Pattern.compile("^\\s*#\\[")                            // 属性
    };
    private static final Pattern[] JAVA_PATTERNS = {
            Pattern.compile("^\\s*(public|private|protected)\\s+(class|interface|enum)"),
            Pattern.compile("^\\s*@\\w+"),                          // 注解
            Pattern.compile("^\\s*package\\s+[\\w.]+;")
    };

    // ════════════════════════════════════════════════════════════════
    //  主检测方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 检测内容类型 — 借鉴 Headroom detect_content_type()。
     * <p>
     * 按优先级依次检测，第一个达到置信度阈值的类型胜出。
     */
    public static DetectionResult detect(String content) {
        if (content == null || content.isBlank()) {
            return new DetectionResult(ContentType.PLAIN_TEXT, 0.0);
        }

        String trimmed = content.strip();

        // 1. JSON 数组（最高优先级）
        DetectionResult jsonResult = tryDetectJson(trimmed);
        if (jsonResult != null) return jsonResult;

        // 2. Git Diff
        DetectionResult diffResult = tryDetectDiff(content);
        if (diffResult != null && diffResult.confidence() >= 0.7) return diffResult;

        // 3. HTML
        DetectionResult htmlResult = tryDetectHtml(trimmed);
        if (htmlResult != null && htmlResult.confidence() >= 0.7) return htmlResult;

        // 4. 搜索结果
        DetectionResult searchResult = tryDetectSearch(content);
        if (searchResult != null && searchResult.confidence() >= 0.6) return searchResult;

        // 5. 构建/日志输出
        DetectionResult logResult = tryDetectLog(content);
        if (logResult != null && logResult.confidence() >= 0.5) return logResult;

        // 6. 表格数据
        DetectionResult tabularResult = tryDetectTabular(content);
        if (tabularResult != null && tabularResult.confidence() >= 0.6) return tabularResult;

        // 7. 源代码
        DetectionResult codeResult = tryDetectCode(content);
        if (codeResult != null && codeResult.confidence() >= 0.5) return codeResult;

        // 8. 兜底纯文本
        return new DetectionResult(ContentType.PLAIN_TEXT, 0.5);
    }

    // ════════════════════════════════════════════════════════════════
    //  各类型检测方法
    // ════════════════════════════════════════════════════════════════

    /** JSON 数组检测 */
    private static DetectionResult tryDetectJson(String content) {
        if (!content.startsWith("[")) return null;
        try {
            // 简化版：检查是否以 ] 结尾且包含 { 或 "
            if (content.endsWith("]") && (content.contains("{") || content.contains("\""))) {
                int itemCount = countOccurrences(content, "\"", 0);
                boolean isDictArray = content.contains("{");
                double confidence = isDictArray ? 1.0 : 0.8;
                return new DetectionResult(ContentType.JSON_ARRAY, confidence, null,
                        isDictArray ? "dict_array" : "scalar_array");
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Git Diff 检测 */
    private static DetectionResult tryDetectDiff(String content) {
        String[] lines = content.split("\n", 500);
        int headerMatches = 0;
        int changeMatches = 0;

        for (String line : lines) {
            if (DIFF_HEADER_PATTERN.matcher(line).find()) headerMatches++;
            if (DIFF_CHANGE_PATTERN.matcher(line).find()) changeMatches++;
        }

        if (headerMatches == 0) return null;
        double confidence = Math.min(1.0, 0.5 + headerMatches * 0.2 + changeMatches * 0.05);
        return new DetectionResult(ContentType.GIT_DIFF, confidence, null,
                "headers=" + headerMatches + ",changes=" + changeMatches);
    }

    /** HTML 检测 */
    private static DetectionResult tryDetectHtml(String content) {
        String sample = content.length() > 3000 ? content.substring(0, 3000) : content;
        boolean hasDoctype = HTML_DOCTYPE.matcher(sample).find();
        boolean hasHtmlTag = HTML_TAG.matcher(sample).find();

        int structuralMatches = 0;
        java.util.regex.Matcher m = HTML_STRUCTURAL.matcher(sample);
        while (m.find()) structuralMatches++;

        if (!hasDoctype && !hasHtmlTag && structuralMatches < 3) return null;

        double confidence = 0;
        if (hasDoctype) confidence += 0.5;
        if (hasHtmlTag) confidence += 0.3;
        confidence += Math.min(0.3, structuralMatches * 0.03);
        confidence = Math.min(1.0, confidence);

        if (confidence < 0.5) return null;
        return new DetectionResult(ContentType.HTML, confidence, null,
                "doctype=" + hasDoctype + ",structural=" + structuralMatches);
    }

    /** 搜索结果检测 */
    private static DetectionResult tryDetectSearch(String content) {
        String[] lines = content.split("\n", 100);
        int matching = 0;
        int nonEmpty = 0;

        for (String line : lines) {
            if (line.isBlank()) continue;
            nonEmpty++;
            if (SEARCH_RESULT_PATTERN.matcher(line).find()) matching++;
        }

        if (matching == 0 || nonEmpty == 0) return null;
        double ratio = (double) matching / nonEmpty;
        if (ratio < 0.3) return null;

        double confidence = Math.min(1.0, 0.4 + ratio * 0.6);
        return new DetectionResult(ContentType.SEARCH_RESULTS, confidence, null,
                "matching=" + matching + "/" + nonEmpty);
    }

    /** 构建/日志检测 */
    private static DetectionResult tryDetectLog(String content) {
        String[] lines = content.split("\n", 200);
        int patternMatches = 0;
        int errorMatches = 0;
        int nonEmpty = 0;

        for (String line : lines) {
            if (line.isBlank()) continue;
            nonEmpty++;
            for (int i = 0; i < LOG_PATTERNS.length; i++) {
                if (LOG_PATTERNS[i].matcher(line).find()) {
                    patternMatches++;
                    if (i < 2) errorMatches++;
                    break;
                }
            }
        }

        if (patternMatches == 0 || nonEmpty == 0) return null;
        double ratio = (double) patternMatches / nonEmpty;
        if (ratio < 0.1) return null;

        double confidence = Math.min(1.0, 0.3 + ratio * 0.5 + errorMatches * 0.05);
        return new DetectionResult(ContentType.BUILD_OUTPUT, confidence, null,
                "patterns=" + patternMatches + ",errors=" + errorMatches);
    }

    /** 表格数据检测（Markdown 表格 + CSV/TSV） */
    private static DetectionResult tryDetectTabular(String content) {
        String[] allLines = content.split("\n");
        // 取前 50 个非空行
        String[] lines = new String[Math.min(50, allLines.length)];
        int idx = 0;
        for (String line : allLines) {
            if (!line.isBlank() && idx < lines.length) {
                lines[idx++] = line;
            }
        }
        if (idx < 3) return null;

        // Markdown 表格：header | separator
        for (int i = 0; i < idx - 1; i++) {
            if (lines[i].contains("|") && isMdSeparator(lines[i + 1])) {
                int cols = lines[i].split("\\|").length;
                if (cols >= 2) {
                    return new DetectionResult(ContentType.TABULAR, 0.95, null,
                            "format=markdown,columns=" + cols);
                }
            }
        }

        // CSV/TSV：检测分隔符一致性
        String[] sample = java.util.Arrays.copyOf(lines, Math.min(idx, 20));
        for (String delim : new String[]{",", "\t", ";", "|"}) {
            int[] counts = new int[sample.length];
            for (int i = 0; i < sample.length; i++) {
                counts[i] = countOccurrences(sample[i], delim);
            }
            if (counts[0] == 0) continue;

            // 找最常见的列数
            java.util.Map<Integer, Integer> freq = new java.util.HashMap<>();
            for (int c : counts) freq.merge(c, 1, Integer::sum);
            var mostCommon = freq.entrySet().stream()
                    .max(java.util.Map.Entry.comparingByValue()).orElse(null);
            if (mostCommon == null || mostCommon.getKey() == 0) continue;

            double consistency = (double) mostCommon.getValue() / sample.length;
            int ncols = mostCommon.getKey() + 1;
            if (ncols < 2 || consistency < 0.7) continue;

            double confidence = Math.min(0.95, 0.5 + consistency * 0.3 + Math.min(ncols, 5) * 0.03);
            return new DetectionResult(ContentType.TABULAR, confidence, null,
                    "format=csv,delimiter=" + delim.replace("\t", "\\t") + ",columns=" + ncols);
        }

        return null;
    }

    /** 源代码检测 */
    private static DetectionResult tryDetectCode(String content) {
        String[] lines = content.split("\n", 100);
        java.util.Map<String, Integer> scores = new java.util.HashMap<>();

        for (String line : lines) {
            checkLanguagePatterns(line, "python", PYTHON_PATTERNS, scores);
            checkLanguagePatterns(line, "javascript", JS_PATTERNS, scores);
            checkLanguagePatterns(line, "typescript", TS_PATTERNS, scores);
            checkLanguagePatterns(line, "go", GO_PATTERNS, scores);
            checkLanguagePatterns(line, "rust", RUST_PATTERNS, scores);
            checkLanguagePatterns(line, "java", JAVA_PATTERNS, scores);
        }

        if (scores.isEmpty()) return null;
        var best = scores.entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue()).orElse(null);
        if (best == null || best.getValue() < 3) return null;

        int nonEmpty = (int) java.util.Arrays.stream(lines).filter(l -> !l.isBlank()).count();
        double ratio = (double) best.getValue() / Math.max(nonEmpty, 1);
        double confidence = Math.min(1.0, 0.4 + ratio * 0.4 + best.getValue() * 0.02);

        return new DetectionResult(ContentType.SOURCE_CODE, confidence, best.getKey(),
                "matches=" + best.getValue());
    }

    // ════════════════════════════════════════════════════════════════
    //  辅助方法
    // ════════════════════════════════════════════════════════════════

    private static void checkLanguagePatterns(String line, String lang,
                                                Pattern[] patterns,
                                                java.util.Map<String, Integer> scores) {
        for (Pattern p : patterns) {
            if (p.matcher(line).find()) {
                scores.merge(lang, 1, Integer::sum);
                break;
            }
        }
    }

    private static boolean isMdSeparator(String row) {
        String[] cells = row.strip().split("\\|");
        if (cells.length < 2) return false;
        for (String c : cells) {
            String trimmed = c.strip();
            if (trimmed.isEmpty()) continue;
            if (!trimmed.matches("^:?-{2,}:?$")) return false;
        }
        return true;
    }

    private static int countOccurrences(String text, String substr, int fromIndex) {
        int count = 0;
        int idx = fromIndex;
        while ((idx = text.indexOf(substr, idx)) != -1) {
            count++;
            idx += substr.length();
        }
        return count;
    }

    private static int countOccurrences(String text, String delim) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(delim, idx)) != -1) {
            count++;
            idx += delim.length();
        }
        return count;
    }
}
