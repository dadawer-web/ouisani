package com.ouisani.aios.core.compact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内容路由器 — 借鉴 Headroom transforms/content_router.py。
 * <p>
 * 检测内容类型后路由到专用压缩器，每种类型用不同算法：
 * <ul>
 *   <li>{@link JsonArrayCompressor} — JSON 数组：保留首 30% + 尾 15% + 去重</li>
 *   <li>{@link CodeCompressor} — 源代码：符号重要性 + 行预算分配</li>
 *   <li>{@link LogCompressor} — 日志：保留错误/警告 + 去重重复行</li>
 *   <li>{@link DiffCompressor} — Git Diff：保留文件头 + 变更行</li>
 *   <li>{@link HtmlContentExtractor} — HTML：提取纯文本</li>
 *   <li>{@link TabularCompressor} — 表格：保留表头 + 首/尾行</li>
 *   <li>{@link TextCompressor} — 纯文本：兜底截断</li>
 * </ul>
 * <p>
 * <b>OS 类比：</b>相当于 Linux 的 I/O 调度器 — 不同 I/O 类型用不同调度策略
 * （SSD 用 noop，机械盘用 deadline，NVMe 用 kyber）。
 */
public class ContentRouter {

    private static final Logger log = LoggerFactory.getLogger(ContentRouter.class);

    /** 压缩结果 */
    public record CompressionResult(
            String compressed,
            String strategy,           // 压缩策略名
            int originalTokens,
            int compressedTokens,
            String detectedType         // 检测到的类型
    ) {
        /** 压缩比 */
        public double ratio() {
            return originalTokens > 0 ? (double) compressedTokens / originalTokens : 1.0;
        }

        /** 是否有节省 */
        public boolean hasSavings() {
            return compressedTokens < originalTokens;
        }
    }

    // ── 各类型压缩器实例 ──
    private final JsonArrayCompressor jsonCompressor = new JsonArrayCompressor();
    private final CodeCompressor codeCompressor = new CodeCompressor();
    private final LogCompressor logCompressor = new LogCompressor();
    private final DiffCompressor diffCompressor = new DiffCompressor();
    private final HtmlContentExtractor htmlExtractor = new HtmlContentExtractor();
    private final TabularCompressor tabularCompressor = new TabularCompressor();
    private final TextCompressor textCompressor = new TextCompressor();

    /**
     * 路由并压缩 — 借鉴 Headroom ContentRouter.compress()。
     * <p>
     * 检测内容类型，路由到对应压缩器，返回压缩结果。
     */
    public CompressionResult compress(String content) {
        return compress(content, "");
    }

    /**
     * 路由并压缩（带查询上下文）。
     *
     * @param content 要压缩的内容
     * @param query   用户查询（用于相关性评分，可为空）
     */
    public CompressionResult compress(String content, String query) {
        if (content == null || content.isBlank()) {
            return new CompressionResult("", "passthrough", 0, 0, "empty");
        }

        int originalTokens = estimateTokens(content);

        // 太短的内容不压缩
        if (originalTokens < 100) {
            return new CompressionResult(content, "passthrough", originalTokens, originalTokens, "too_short");
        }

        // 检测内容类型
        ContentTypeDetector.DetectionResult detection = ContentTypeDetector.detect(content);
        String detectedType = detection.contentType().name();

        log.debug("[ContentRouter] 检测类型: {} (confidence={}, lang={}, meta={})",
                detectedType, String.format("%.2f", detection.confidence()),
                detection.language(), detection.metadata());

        // 路由到对应压缩器
        String compressed;
        String strategy;

        switch (detection.contentType()) {
            case JSON_ARRAY:
                compressed = jsonCompressor.compress(content);
                strategy = "smart_crusher";
                break;
            case SOURCE_CODE:
                compressed = codeCompressor.compress(content, detection.language());
                strategy = "code_aware";
                break;
            case BUILD_OUTPUT:
                compressed = logCompressor.compress(content);
                strategy = "log_compressor";
                break;
            case GIT_DIFF:
                compressed = diffCompressor.compress(content);
                strategy = "diff_compressor";
                break;
            case HTML:
                compressed = htmlExtractor.extract(content);
                strategy = "html_extractor";
                break;
            case TABULAR:
                compressed = tabularCompressor.compress(content);
                strategy = "tabular_compressor";
                break;
            case SEARCH_RESULTS:
                // 搜索结果通常已经是高信噪比，只做截断
                compressed = textCompressor.compress(content, 5000);
                strategy = "search_truncate";
                break;
            case PLAIN_TEXT:
            default:
                compressed = textCompressor.compress(content, 0);
                strategy = "text_truncate";
                break;
        }

        // 空输出保护 — 借鉴 Headroom 的 empty-output guard
        if (compressed == null || compressed.isBlank()) {
            log.warn("[ContentRouter] 压缩后内容为空，回退原文 (type={})", detectedType);
            return new CompressionResult(content, "fallback_original",
                    originalTokens, originalTokens, detectedType);
        }

        // 膨胀保护 — 借鉴 Headroom 的 inflation guard
        int compressedTokens = estimateTokens(compressed);
        if (compressedTokens >= originalTokens) {
            log.debug("[ContentRouter] 压缩未产生节省 ({}→{} tokens)，回退原文 (type={})",
                    originalTokens, compressedTokens, detectedType);
            return new CompressionResult(content, "passthrough",
                    originalTokens, originalTokens, detectedType);
        }

        return new CompressionResult(compressed, strategy, originalTokens, compressedTokens, detectedType);
    }

    private static int estimateTokens(String text) {
        return (int) (text.length() * 4.0 / 3.0);
    }

    // ════════════════════════════════════════════════════════════════
    //  JSON 数组压缩器 — 借鉴 Headroom SmartCrusher
    // ════════════════════════════════════════════════════════════════

    /**
     * JSON 数组压缩器 — 借鉴 Headroom SmartCrusher。
     * <p>
     * 算法参数（对应 SmartCrusherConfig）：
     * <ul>
     *   <li>{@code first_fraction=0.3} — 保留数组前 30%</li>
     *   <li>{@code last_fraction=0.15} — 保留数组后 15%</li>
     *   <li>{@code max_items_after_crush=15} — 压缩后最多 15 项</li>
     *   <li>{@code dedup_identical_items=true} — 去重相同项</li>
     *   <li>{@code preserve_change_points=true} — 保留变化点</li>
     * </ul>
     */
    public static class JsonArrayCompressor {

        private static final double FIRST_FRACTION = 0.3;
        private static final double LAST_FRACTION = 0.15;
        private static final int MAX_ITEMS = 15;
        private static final int MIN_ITEMS_TO_CRUSH = 5;

        public String compress(String content) {
            // 简化版 JSON 数组解析：按顶层 { } 分割
            List<String> items = splitJsonArray(content);
            if (items.size() < MIN_ITEMS_TO_CRUSH) {
                return content; // 太少不值得压缩
            }

            // 去重
            List<String> deduped = deduplicate(items);

            int total = deduped.size();
            if (total <= MAX_ITEMS) {
                return rebuildArray(deduped);
            }

            // 保留首 30% + 尾 15%
            int firstCount = Math.max(1, (int) (total * FIRST_FRACTION));
            int lastCount = Math.max(1, (int) (total * LAST_FRACTION));

            // 确保不超过 MAX_ITEMS
            if (firstCount + lastCount > MAX_ITEMS) {
                firstCount = (int) (MAX_ITEMS * FIRST_FRACTION / (FIRST_FRACTION + LAST_FRACTION));
                lastCount = MAX_ITEMS - firstCount;
            }

            List<String> result = new ArrayList<>();
            result.addAll(deduped.subList(0, firstCount));
            int omitted = total - firstCount - lastCount;
            if (omitted > 0) {
                result.add(String.format("  // [%d items omitted]", omitted));
            }
            result.addAll(deduped.subList(total - lastCount, total));

            return rebuildArray(result);
        }

        /** 简化版 JSON 数组分割 — 按顶层花括号匹配 */
        private List<String> splitJsonArray(String content) {
            List<String> items = new ArrayList<>();
            String trimmed = content.strip();
            // 去掉外层 []
            if (trimmed.startsWith("[")) trimmed = trimmed.substring(1);
            if (trimmed.endsWith("]")) trimmed = trimmed.substring(0, trimmed.length() - 1);

            int depth = 0;
            int start = -1;
            for (int i = 0; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                if (c == '{') {
                    if (depth == 0) start = i;
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0 && start >= 0) {
                        items.add(trimmed.substring(start, i + 1).strip());
                        start = -1;
                    }
                }
            }

            // 如果没找到 { } 分割，按逗号分割（标量数组）
            if (items.isEmpty()) {
                String[] parts = trimmed.split(",\\s*");
                for (String p : parts) {
                    String s = p.strip();
                    if (!s.isEmpty()) items.add(s);
                }
            }

            return items;
        }

        /** 去重 — 借鉴 SmartCrusher dedup_identical_items */
        private List<String> deduplicate(List<String> items) {
            List<String> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int dupCount = 0;
            for (String item : items) {
                if (seen.add(item)) {
                    result.add(item);
                } else {
                    dupCount++;
                }
            }
            if (dupCount > 0) {
                // 在末尾标注去重数
                result.add(String.format("  // [%d duplicate items removed]", dupCount));
            }
            return result;
        }

        private String rebuildArray(List<String> items) {
            return "[\n" + String.join(",\n", items) + "\n]";
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  代码压缩器 — 借鉴 Headroom CodeAwareCompressor
    // ════════════════════════════════════════════════════════════════

    /**
     * 代码压缩器 — 借鉴 Headroom CodeAwareCompressor（AST-based）。
     * <p>
     * 简化版算法（不依赖 tree-sitter）：
     * <ol>
     *   <li>识别函数/类/方法定义行（符号定义）</li>
     *   <li>统计每个符号的引用次数（符号重要性）</li>
     *   <li>按重要性分配行预算 — 高引用符号保留更多行</li>
     *   <li>低重要性函数体用 {@code // [N lines omitted]} 替换</li>
     * </ol>
     */
    public static class CodeCompressor {

        private static final double TARGET_COMPRESSION_RATE = 0.3; // 保留 30%

        private static final Pattern FUNC_DEF_PATTERN = Pattern.compile(
                "^\\s*(def |func |function |public |private |protected |fn |async func |async def ).+",
                Pattern.CASE_INSENSITIVE);

        private static final Pattern CLASS_DEF_PATTERN = Pattern.compile(
                "^\\s*(class |interface |enum |struct |impl |trait |object ).+",
                Pattern.CASE_INSENSITIVE);

        private static final Pattern IMPORT_PATTERN = Pattern.compile(
                "^\\s*(import |from |use |package |include |require|#include ).+",
                Pattern.CASE_INSENSITIVE);

        public String compress(String content, String language) {
            String[] lines = content.split("\n");
            if (lines.length < 20) return content; // 太短不压缩

            // 1. 识别结构定义行
            List<int[]> definitions = new ArrayList<>(); // [startLine, endLine]
            for (int i = 0; i < lines.length; i++) {
                if (FUNC_DEF_PATTERN.matcher(lines[i]).find()
                        || CLASS_DEF_PATTERN.matcher(lines[i]).find()) {
                    definitions.add(new int[]{i, findFunctionEnd(lines, i, language)});
                }
            }

            if (definitions.isEmpty()) {
                // 无结构定义，按行截断
                return truncateLines(lines, (int) (lines.length * TARGET_COMPRESSION_RATE));
            }

            // 2. 计算符号重要性（引用次数）
            Map<Integer, Integer> importance = new LinkedHashMap<>();
            for (int[] def : definitions) {
                String defLine = lines[def[0]];
                String symbolName = extractSymbolName(defLine);
                int refs = countReferences(lines, symbolName, def[0]);
                importance.put(def[0], refs);
            }

            // 3. 分配行预算
            int totalBudget = (int) (lines.length * TARGET_COMPRESSION_RATE);
            int fixedLines = countFixedLines(lines, definitions);
            int bodyBudget = Math.max(5, totalBudget - fixedLines);

            // 4. 按重要性比例分配预算
            int totalImportance = importance.values().stream().mapToInt(Integer::intValue).sum();
            if (totalImportance == 0) totalImportance = 1;

            StringBuilder result = new StringBuilder();
            int currentLine = 0;

            for (int[] def : definitions) {
                int defStart = def[0];
                int defEnd = def[1];

                // 输出定义前的非结构代码（imports, 注释等）
                while (currentLine < defStart) {
                    if (IMPORT_PATTERN.matcher(lines[currentLine]).find()
                            || lines[currentLine].strip().startsWith("//")
                            || lines[currentLine].strip().startsWith("#")
                            || lines[currentLine].strip().startsWith("/*")
                            || lines[currentLine].strip().startsWith("*")) {
                        result.append(lines[currentLine]).append("\n");
                    }
                    currentLine++;
                }

                // 计算此函数的行预算
                int funcSize = defEnd - defStart + 1;
                double share = (double) importance.get(defStart) / totalImportance;
                int funcBudget = Math.max(3, (int) (bodyBudget * share));

                if (funcSize <= funcBudget) {
                    // 整个函数都能保留
                    for (int i = defStart; i <= defEnd && i < lines.length; i++) {
                        result.append(lines[i]).append("\n");
                    }
                } else {
                    // 保留签名 + 前几行 + 标注省略
                    result.append(lines[defStart]).append("\n"); // 签名行
                    int remaining = funcBudget - 1;
                    for (int i = defStart + 1; i <= defEnd && remaining > 0 && i < lines.length; i++) {
                        result.append(lines[i]).append("\n");
                        remaining--;
                    }
                    int omitted = funcSize - funcBudget;
                    if (omitted > 0) {
                        result.append("    // [").append(omitted)
                                .append(" lines omitted]\n");
                    }
                }

                currentLine = defEnd + 1;
            }

            // 输出尾部非结构代码
            while (currentLine < lines.length) {
                String line = lines[currentLine].strip();
                if (!line.isEmpty() && (line.startsWith("if __name__")
                        || line.startsWith("module.exports")
                        || line.startsWith("export "))) {
                    result.append(lines[currentLine]).append("\n");
                }
                currentLine++;
            }

            return result.toString().trim();
        }

        /** 找到函数体结束行 */
        private int findFunctionEnd(String[] lines, int startLine, String language) {
            // 简化版：找到下一个同级缩进的定义或文件末尾
            String startIndent = getIndent(lines[startLine]);
            for (int i = startLine + 1; i < lines.length; i++) {
                String line = lines[i];
                if (!line.isBlank()) {
                    String indent = getIndent(line);
                    // 缩进回到相同或更少级别 = 函数结束
                    if (indent.length() <= startIndent.length()
                            && (FUNC_DEF_PATTERN.matcher(line).find()
                            || CLASS_DEF_PATTERN.matcher(line).find())) {
                        return i - 1;
                    }
                }
            }
            return lines.length - 1;
        }

        private String getIndent(String line) {
            int i = 0;
            while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
            return line.substring(0, i);
        }

        private String extractSymbolName(String defLine) {
            Matcher m = Pattern.compile("\\b(def|func|function|fn|class|interface|enum|struct|impl)\\s+(\\w+)").matcher(defLine);
            if (m.find()) return m.group(2);
            return "";
        }

        private int countReferences(String[] lines, String symbol, int defLine) {
            if (symbol.isEmpty()) return 0;
            int count = 0;
            for (int i = 0; i < lines.length; i++) {
                if (i == defLine) continue;
                if (lines[i].contains(symbol)) count++;
            }
            return count;
        }

        private int countFixedLines(String[] lines, List<int[]> definitions) {
            int count = 0;
            Set<Integer> defLines = new HashSet<>();
            for (int[] def : definitions) {
                for (int i = def[0]; i <= def[1]; i++) defLines.add(i);
            }
            for (int i = 0; i < lines.length; i++) {
                if (!defLines.contains(i)
                        && (IMPORT_PATTERN.matcher(lines[i]).find()
                        || lines[i].strip().startsWith("//")
                        || lines[i].strip().startsWith("#")
                        || lines[i].isBlank())) {
                    count++;
                }
            }
            return count;
        }

        private String truncateLines(String[] lines, int maxLines) {
            if (lines.length <= maxLines) return String.join("\n", lines);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < maxLines; i++) sb.append(lines[i]).append("\n");
            sb.append("// [").append(lines.length - maxLines).append(" lines omitted]");
            return sb.toString();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  日志压缩器 — 借鉴 Headroom LogCompressor
    // ════════════════════════════════════════════════════════════════

    /**
     * 日志压缩器 — 保留错误/警告 + 去重重复行。
     * <p>
     * 算法：
     * <ol>
     *   <li>保留所有 ERROR/WARN/FAIL 行</li>
     *   <li>保留 Traceback/堆栈跟踪</li>
     *   <li>INFO/DEBUG 行去重（相同内容只保留最后一次）</li>
     *   <li>连续相同日志合并为 [N similar lines]</li>
     * </ol>
     */
    public static class LogCompressor {

        private static final Pattern ERROR_PATTERN = Pattern.compile(
                "\\b(ERROR|FAIL|FAILED|FATAL|CRITICAL|Traceback|Exception|Error:)\\b",
                Pattern.CASE_INSENSITIVE);
        private static final Pattern WARN_PATTERN = Pattern.compile(
                "\\b(WARN|WARNING)\\b", Pattern.CASE_INSENSITIVE);

        public String compress(String content) {
            String[] lines = content.split("\n");
            List<String> result = new ArrayList<>();
            String prevLine = null;
            int dupCount = 0;

            for (String line : lines) {
                boolean isError = ERROR_PATTERN.matcher(line).find();
                boolean isWarn = WARN_PATTERN.matcher(line).find();

                if (isError || isWarn) {
                    // 错误/警告行：刷新去重计数后直接保留
                    if (dupCount > 0) {
                        result.add(String.format("  ... [%d similar lines omitted] ...", dupCount));
                        dupCount = 0;
                    }
                    result.add(line);
                    prevLine = line;
                } else if (line.isBlank()) {
                    if (dupCount > 0) {
                        result.add(String.format("  ... [%d similar lines omitted] ...", dupCount));
                        dupCount = 0;
                    }
                    result.add(line);
                    prevLine = null;
                } else {
                    // 普通行：检测是否与前一行相同
                    if (line.equals(prevLine)) {
                        dupCount++;
                    } else {
                        if (dupCount > 0) {
                            result.add(String.format("  ... [%d similar lines omitted] ...", dupCount));
                            dupCount = 0;
                        }
                        result.add(line);
                        prevLine = line;
                    }
                }
            }

            if (dupCount > 0) {
                result.add(String.format("  ... [%d similar lines omitted] ...", dupCount));
            }

            return String.join("\n", result);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Diff 压缩器 — 保留文件头 + 变更行
    // ════════════════════════════════════════════════════════════════

    /**
     * Git Diff 压缩器 — 保留文件头和变更行，省略上下文行。
     */
    public static class DiffCompressor {

        private static final Pattern HUNK_HEADER = Pattern.compile("^@@.*@@");
        private static final Pattern FILE_HEADER = Pattern.compile("^(diff --git|--- |\\+\\+\\+ )");
        private static final Pattern ADDED_LINE = Pattern.compile("^\\+[^+]");
        private static final Pattern REMOVED_LINE = Pattern.compile("^-[^-]");

        public String compress(String content) {
            String[] lines = content.split("\n");
            List<String> result = new ArrayList<>();
            int contextOmitted = 0;

            for (String line : lines) {
                if (FILE_HEADER.matcher(line).find()
                        || HUNK_HEADER.matcher(line).find()
                        || ADDED_LINE.matcher(line).find()
                        || REMOVED_LINE.matcher(line).find()) {
                    // 保留文件头、hunk 头、变更行
                    if (contextOmitted > 0) {
                        result.add("  // [" + contextOmitted + " context lines omitted]");
                        contextOmitted = 0;
                    }
                    result.add(line);
                } else if (line.startsWith(" ")) {
                    // 上下文行：省略
                    contextOmitted++;
                } else {
                    if (contextOmitted > 0) {
                        result.add("  // [" + contextOmitted + " context lines omitted]");
                        contextOmitted = 0;
                    }
                    result.add(line);
                }
            }

            if (contextOmitted > 0) {
                result.add("  // [" + contextOmitted + " context lines omitted]");
            }

            return String.join("\n", result);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  HTML 内容提取器 — 剥离标签提取纯文本
    // ════════════════════════════════════════════════════════════════

    /**
     * HTML 内容提取器 — 剥离 script/style/nav 标签，提取纯文本。
     * <p>
     * 借鉴 Headroom 的 HtmlExtractor — HTML 需要的是内容提取而非压缩。
     */
    public static class HtmlContentExtractor {

        private static final Pattern SCRIPT_TAG = Pattern.compile(
                "<script[^>]*>.*?</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        private static final Pattern STYLE_TAG = Pattern.compile(
                "<style[^>]*>.*?</style>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        private static final Pattern NAV_TAG = Pattern.compile(
                "<nav[^>]*>.*?</nav>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        private static final Pattern TAG_STRIP = Pattern.compile("<[^>]+>");
        private static final Pattern MULTI_SPACE = Pattern.compile(" {2,}");
        private static final Pattern MULTI_NEWLINE = Pattern.compile("\n{3,}");

        public String extract(String content) {
            String result = content;
            // 移除 script/style/nav
            result = SCRIPT_TAG.matcher(result).replaceAll("");
            result = STYLE_TAG.matcher(result).replaceAll("");
            result = NAV_TAG.matcher(result).replaceAll("");
            // 块级元素后加换行
            result = result.replaceAll("(?i)</(div|p|br|h[1-6]|li|tr|table)>", "\n");
            result = result.replaceAll("(?i)<br\\s*/?>", "\n");
            // 剥离所有标签
            result = TAG_STRIP.matcher(result).replaceAll("");
            // 清理 HTML 实体
            result = result.replace("&nbsp;", " ").replace("&lt;", "<")
                    .replace("&gt;", ">").replace("&amp;", "&")
                    .replace("&quot;", "\"").replace("&#39;", "'");
            // 压缩多余空白
            result = MULTI_SPACE.matcher(result).replaceAll(" ");
            result = MULTI_NEWLINE.matcher(result).replaceAll("\n\n");
            return result.strip();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  表格压缩器 — 保留表头 + 首/尾行
    // ════════════════════════════════════════════════════════════════

    /**
     * 表格压缩器 — 保留表头 + 首 N 行 + 尾 M 行。
     * <p>
     * 借鉴 Headroom TabularCompressor — CSV/TSV 的列式压缩。
     */
    public static class TabularCompressor {

        private static final int KEEP_FIRST = 5;
        private static final int KEEP_LAST = 3;
        private static final int MIN_ROWS_TO_COMPRESS = 10;

        public String compress(String content) {
            String[] lines = content.split("\n");
            if (lines.length < MIN_ROWS_TO_COMPRESS) return content;

            List<String> result = new ArrayList<>();
            // 保留表头
            result.add(lines[0]);
            if (lines.length > 1) result.add(lines[1]); // 分隔行（Markdown 表格）

            // 保留前 N 行数据
            int dataStart = (lines[1] != null && isSeparator(lines[1])) ? 2 : 1;
            int dataEnd = lines.length;

            for (int i = dataStart; i < Math.min(dataStart + KEEP_FIRST, dataEnd); i++) {
                result.add(lines[i]);
            }

            int omitted = dataEnd - dataStart - KEEP_FIRST - KEEP_LAST;
            if (omitted > 0) {
                result.add("  ... [" + omitted + " rows omitted] ...");
            }

            // 保留后 M 行
            int tailStart = Math.max(dataStart + KEEP_FIRST, dataEnd - KEEP_LAST);
            for (int i = tailStart; i < dataEnd; i++) {
                result.add(lines[i]);
            }

            return String.join("\n", result);
        }

        private boolean isSeparator(String line) {
            return line.strip().matches("^[|:?\\-\\s]+$");
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  纯文本压缩器 — 兜底截断
    // ════════════════════════════════════════════════════════════════

    /**
     * 纯文本压缩器 — 按段落截断的兜底压缩器。
     */
    public static class TextCompressor {

        public String compress(String content, int maxLength) {
            if (maxLength > 0 && content.length() > maxLength) {
                return content.substring(0, maxLength)
                        + "\n... [truncated at " + maxLength + " chars]";
            }

            // 按段落分割，保留首尾段
            String[] paragraphs = content.split("\n\n");
            if (paragraphs.length <= 3) return content;

            StringBuilder sb = new StringBuilder();
            sb.append(paragraphs[0]).append("\n\n");

            int omitted = paragraphs.length - 2;
            sb.append("... [").append(omitted).append(" paragraphs omitted] ...\n\n");

            sb.append(paragraphs[paragraphs.length - 1]);
            return sb.toString();
        }
    }
}
