package com.ouisani.aios.core.ast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 抽象语法树查询与修改引擎（基于 ast-grep / sg）。
 * <p>
 * 对标 oh-my-openagent 的 ast-grep-mcp：
 * 通过调用宿主机的 {@code sg} 命令行工具，实现 AST 级别的代码搜索与替换。
 * <p>
 * 与 HashlineCore 的对比：
 * <pre>
 *   HashlineCore — 文本级哈希切分，按空行分块，通用但粗糙
 *   AstGrepRunner — AST 级模式匹配，按语法结构定位，精准且语义感知
 * </pre>
 * <p>
 * ast-grep 模式语法（元变量）：
 * <pre>
 *   $NAME    — 匹配任意单个 AST 节点（变量名、表达式等）
 *   $$$BODY  — 匹配零或多个 AST 节点（方法体、参数列表等）
 *   $$$ARGS  — 同上，语义别名
 * </pre>
 * <p>
 * 使用示例：
 * <pre>
 *   // 查找所有 public void 方法
 *   search("Foo.java", "public void $METHOD_NAME() { $$$ }")
 *
 *   // 将 maxRetries 的值从变量改为常量 5
 *   replace("Foo.java", "int maxRetries = $NUM;", "int maxRetries = 5;")
 * </pre>
 *
 * @see <a href="https://ast-grep.github.io">ast-grep 官方文档</a>
 */
public class AstGrepRunner {

    private static final Logger log = LoggerFactory.getLogger(AstGrepRunner.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** ast-grep 命令名（全局安装时为 sg） */
    private static final String SG_COMMAND = "sg";

    /** 命令执行超时（秒） */
    private static final int TIMEOUT_SECONDS = 30;

    /** 缓存 sg 是否可用，避免每次都检测 */
    private static Boolean sgAvailable = null;

    /**
     * 检测宿主机是否安装了 ast-grep。
     *
     * @return true 如果 sg 命令可用
     */
    public static synchronized boolean isAvailable() {
        if (sgAvailable != null) return sgAvailable;
        try {
            ProcessBuilder pb = new ProcessBuilder(SG_COMMAND, "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean exited = process.waitFor(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            if (exited && process.exitValue() == 0) {
                sgAvailable = true;
                log.info("[AST Grep] ast-grep 已检测且可用。");
            } else {
                sgAvailable = false;
                log.warn("[AST Grep] sg 命令已找到但返回非零退出码。");
            }
        } catch (Exception e) {
            sgAvailable = false;
            log.warn("[AST Grep] sg 命令未找到。AST 级操作已禁用。安装: npm i -g @ast-grep/cli");
        }
        return sgAvailable;
    }

    /**
     * 结构化查询：在文件中寻找匹配特定 AST 模式的代码。
     *
     * @param filePath 目标文件路径
     * @param pattern  ast-grep 模式（例如: "public void $METHOD_NAME() { $$$ }"）
     * @return 匹配结果的 JSON 数组，每个元素包含 range/text/metadata
     */
    public static List<JsonNode> search(String filePath, String pattern) {
        return search(filePath, pattern, null);
    }

    /**
     * 结构化查询（指定语言）：在文件中寻找匹配特定 AST 模式的代码。
     *
     * @param filePath 目标文件路径
     * @param pattern  ast-grep 模式
     * @param language 目标语言（java/python/javascript 等），null 则自动推断
     * @return 匹配结果的 JSON 数组
     */
    public static List<JsonNode> search(String filePath, String pattern, String language) {
        if (!isAvailable()) {
            throw new RuntimeException("ast-grep (sg) not installed. Cannot perform AST search.");
        }

        List<JsonNode> results = new ArrayList<>();
        try {
            List<String> command = buildSearchCommand(filePath, pattern, language);
            log.debug("[AST Grep] Executing: {}", String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = new BufferedReader(new InputStreamReader(process.getInputStream()))
                    .lines().collect(Collectors.joining("\n"));

            int exitCode = process.waitFor();
            if (exitCode == 0 && !output.isBlank()) {
                JsonNode rootNode = mapper.readTree(output);
                if (rootNode.isArray()) {
                    rootNode.forEach(results::add);
                } else if (rootNode.isObject()) {
                    // 单个结果也包装成列表
                    results.add(rootNode);
                }
                log.info("[AST Grep] Search found {} matches in {}", results.size(), filePath);
            } else {
                log.warn("[AST Grep] 搜索返回退出码 {}。输出: {}", exitCode,
                        output.length() > 200 ? output.substring(0, 200) + "..." : output);
            }
        } catch (Exception e) {
            log.error("[AST Grep] 搜索执行失败。", e);
            throw new RuntimeException("AST search failed: " + e.getMessage());
        }
        return results;
    }

    /**
     * 结构化替换：使用 AST 模式进行极度安全的微创手术。
     * <p>
     * ast-grep 会先解析文件为 AST，匹配 pattern，再用 rewrite 替换匹配的节点，
     * 最后重新生成代码。这保证了替换后的代码语法正确。
     *
     * @param filePath 目标文件路径
     * @param pattern  要寻找的 AST 模式（例如: "int maxRetries = $NUM;"）
     * @param rewrite  替换后的模式（例如: "int maxRetries = 5;"）
     */
    public static void replace(String filePath, String pattern, String rewrite) {
        replace(filePath, pattern, rewrite, null);
    }

    /**
     * 结构化替换（指定语言）。
     *
     * @param filePath 目标文件路径
     * @param pattern  要寻找的 AST 模式
     * @param rewrite  替换后的模式
     * @param language 目标语言，null 则自动推断
     */
    public static void replace(String filePath, String pattern, String rewrite, String language) {
        if (!isAvailable()) {
            throw new RuntimeException("ast-grep (sg) not installed. Cannot perform AST replace.");
        }

        try {
            List<String> command = buildReplaceCommand(filePath, pattern, rewrite, language);
            log.debug("[AST Grep] Executing: {}", String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = new BufferedReader(new InputStreamReader(process.getInputStream()))
                    .lines().collect(Collectors.joining("\n"));

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log.info("[AST Grep] Surgical rewrite applied successfully to: {}", filePath);
            } else {
                log.error("[AST Grep] 重写失败 (Code {})。输出: {}", exitCode, output);
                throw new RuntimeException("AST rewrite failed: " + output);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[AST Grep] 重写执行失败。", e);
            throw new RuntimeException("AST rewrite failed: " + e.getMessage());
        }
    }

    /**
     * 搜索并返回格式化的文本结果 — 供 LLM 直接阅读。
     *
     * @param filePath 目标文件路径
     * @param pattern  ast-grep 模式
     * @return 格式化的搜索结果文本
     */
    public static String searchFormatted(String filePath, String pattern) {
        return searchFormatted(filePath, pattern, null);
    }

    /**
     * 搜索并返回格式化的文本结果（指定语言）。
     *
     * @param filePath 目标文件路径
     * @param pattern  ast-grep 模式
     * @param language 目标语言
     * @return 格式化的搜索结果文本
     */
    public static String searchFormatted(String filePath, String pattern, String language) {
        List<JsonNode> results = search(filePath, pattern, language);
        if (results.isEmpty()) {
            return "No AST matches found for pattern: " + pattern;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(results.size()).append(" AST match(es) in ").append(filePath).append(":\n\n");

        for (int i = 0; i < results.size(); i++) {
            JsonNode match = results.get(i);
            sb.append("--- Match ").append(i + 1).append(" ---\n");

            // 提取匹配文本
            if (match.has("text")) {
                sb.append("Text: ").append(match.get("text").asText()).append("\n");
            }
            // 提取行号范围
            if (match.has("range")) {
                JsonNode range = match.get("range");
                sb.append("Range: line ").append(range.path("startLine").asText())
                        .append(" - ").append(range.path("endLine").asText()).append("\n");
            }
            // 提取元变量绑定
            if (match.has("metaVariables")) {
                JsonNode meta = match.get("metaVariables");
                meta.fieldNames().forEachRemaining(name ->
                        sb.append("  $").append(name).append(" = ").append(meta.get(name).asText()).append("\n"));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 根据文件扩展名推断语言。
     */
    public static String inferLanguage(String filePath) {
        if (filePath == null) return null;
        String name = filePath.toLowerCase();
        if (name.endsWith(".java")) return "java";
        if (name.endsWith(".py")) return "python";
        if (name.endsWith(".js") || name.endsWith(".mjs")) return "javascript";
        if (name.endsWith(".ts") || name.endsWith(".tsx")) return "typescript";
        if (name.endsWith(".rs")) return "rust";
        if (name.endsWith(".go")) return "go";
        if (name.endsWith(".c") || name.endsWith(".h")) return "c";
        if (name.endsWith(".cpp") || name.endsWith(".cc") || name.endsWith(".hpp")) return "cpp";
        if (name.endsWith(".kt")) return "kotlin";
        if (name.endsWith(".scala")) return "scala";
        if (name.endsWith(".rb")) return "ruby";
        if (name.endsWith(".swift")) return "swift";
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    //  内部命令构建方法
    // ════════════════════════════════════════════════════════════════

    private static List<String> buildSearchCommand(String filePath, String pattern, String language) {
        List<String> cmd = new ArrayList<>();
        cmd.add(SG_COMMAND);
        cmd.add("run");
        cmd.add("-p");
        cmd.add(pattern);
        if (language != null) {
            cmd.add("-l");
            cmd.add(language);
        }
        cmd.add("--json");
        cmd.add(filePath);
        return cmd;
    }

    private static List<String> buildReplaceCommand(String filePath, String pattern, String rewrite, String language) {
        List<String> cmd = new ArrayList<>();
        cmd.add(SG_COMMAND);
        cmd.add("run");
        cmd.add("-p");
        cmd.add(pattern);
        cmd.add("-r");
        cmd.add(rewrite);
        if (language != null) {
            cmd.add("-l");
            cmd.add(language);
        }
        cmd.add("--update-all");
        cmd.add(filePath);
        return cmd;
    }
}
