package com.ouisani.aios.operator.tools;

import com.ouisani.aios.core.ast.AstGrepRunner;
import com.ouisani.aios.core.tool.Tool;
import com.ouisani.aios.core.tool.ToolContext;
import com.ouisani.aios.core.tool.ToolInput;
import com.ouisani.aios.core.tool.ToolOutput;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * AST 结构化搜索工具 — 赋予大模型 AST 级别的代码理解能力。
 * <p>
 * 对标 oh-my-openagent 的 ast-grep-mcp Search 功能：
 * LLM 通过此工具使用 ast-grep 语法模式在文件中进行结构化搜索，
 * 而非简单的文本正则匹配。
 * <p>
 * 核心优势：
 * <ul>
 *   <li>语义感知 — 按语法结构匹配，不受格式/空格/注释干扰</li>
 *   <li>元变量捕获 — $NAME 匹配单个节点，$$$BODY 匹配多个节点</li>
 *   <li>精准定位 — 返回匹配代码块及其位置信息</li>
 * </ul>
 * <p>
 * 与 HashlineReadTool 的对比：
 * <pre>
 *   HashlineReadTool — 文本级哈希切分，按空行分块，通用但粗糙
 *   AstSearchTool    — AST 级模式匹配，按语法结构定位，精准且语义感知
 * </pre>
 * <p>
 * 典型工作流：
 * <pre>
 *   1. ast_search(path, pattern) → 找到目标代码结构
 *   2. LLM 分析匹配结果和元变量绑定
 *   3. ast_rewrite(path, pattern, rewrite) → 精准替换
 * </pre>
 *
 * @see AstRewriteTool
 * @see AstGrepRunner
 */
public class AstSearchTool implements Tool<AstSearchTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(AstSearchTool.class);

    @Override
    public String name() {
        return "ast_search";
    }

    @Override
    public String description() {
        return "Searches a file using structural AST patterns (ast-grep syntax). "
                + "Use $META_VAR to match specific tokens (e.g. $METHOD_NAME, $TYPE), "
                + "and $$$ to match multiple statements (e.g. $$$BODY, $$$ARGS). "
                + "Returns matched code blocks with their positions and captured meta-variables. "
                + "This is far more precise than text-based grep for code search.";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "path": {
                      "type": "string",
                      "description": "Path to the source code file"
                    },
                    "pattern": {
                      "type": "string",
                      "description": "AST pattern to search for (ast-grep syntax). Examples: 'public void $METHOD_NAME() { $$$ }', 'int $VAR = $VALUE;', 'class $CLASS_NAME { $$$ }'"
                    },
                    "language": {
                      "type": "string",
                      "description": "Programming language (java, python, javascript, typescript, rust, go, etc.). Auto-inferred from file extension if omitted."
                    }
                  },
                  "required": ["path", "pattern"]
                }""";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        try {
            Path filePath = resolvePath(input.path(), context.workingDir());
            if (!Files.exists(filePath)) {
                return ToolOutput.fail("File not found: " + filePath);
            }

            // 检查 ast-grep 是否可用
            if (!AstGrepRunner.isAvailable()) {
                return ToolOutput.fail("ast-grep (sg) is not installed. Install with: npm i -g @ast-grep/cli");
            }

            // 推断语言：优先使用显式指定，否则自动推断
            String language = input.language();
            if (language == null || language.isBlank()) {
                language = AstGrepRunner.inferLanguage(filePath.toString());
            }

            List<JsonNode> results = AstGrepRunner.search(filePath.toString(), input.pattern(), language);
            if (results.isEmpty()) {
                log.info("[AstSearch] No matches found in {} for pattern: {}", filePath.getFileName(), input.pattern());
                return ToolOutput.ok("No structural matches found for pattern: " + input.pattern());
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(results.size()).append(" AST match(es) in ")
                    .append(filePath.getFileName()).append(":\n\n");

            for (int i = 0; i < results.size(); i++) {
                JsonNode match = results.get(i);
                sb.append("--- Match ").append(i + 1).append(" ---\n");

                // 提取匹配文本
                if (match.has("text")) {
                    sb.append("Code:\n").append(match.get("text").asText()).append("\n");
                }
                // 提取行号范围
                if (match.has("range")) {
                    JsonNode range = match.get("range");
                    sb.append("Range: line ").append(range.path("startLine").asText())
                            .append(" - ").append(range.path("endLine").asText()).append("\n");
                }
                // 提取元变量绑定 — 供 LLM 在 ast_rewrite 中复用
                if (match.has("metaVariables")) {
                    JsonNode meta = match.get("metaVariables");
                    sb.append("Captured Variables:\n");
                    meta.fieldNames().forEachRemaining(name ->
                            sb.append("  $").append(name).append(" = ").append(meta.get(name).asText()).append("\n"));
                }
                sb.append("\n");
            }

            sb.append("TIP: Use ast_rewrite with the same pattern and captured $META_VARs to perform surgical replacement.");

            log.info("[AstSearch] Found {} matches in {} for pattern: {}", results.size(), filePath.getFileName(), input.pattern());
            return ToolOutput.ok(sb.toString());

        } catch (RuntimeException e) {
            log.warn("[AstSearch] Search failed: {}", e.getMessage());
            throw e; // 让自愈引擎处理
        } catch (Exception e) {
            return ToolOutput.fail("AST search error: " + e.getMessage());
        }
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public String prompt() {
        return """
                Use ast_search for structural code search instead of text-based grep when you need to:
                - Find specific code patterns (method declarations, class definitions, assignments)
                - Match code by its syntactic structure, not just text
                - Capture specific parts of matched code using $META_VAR syntax
                Common patterns:
                  $NAME — matches a single AST node (variable, expression, type, etc.)
                  $$$BODY — matches zero or more AST nodes (method body, argument list, etc.)
                Examples:
                  ast_search(path, "public void $METHOD() { $$$ }") — find all public void methods
                  ast_search(path, "class $CLASS { $$$ }") — find all class declarations
                  ast_search(path, "int $VAR = $VALUE;") — find all int variable declarations
                """;
    }

    private Path resolvePath(String path, String workingDir) {
        Path p = Paths.get(path);
        if (p.isAbsolute()) return p;
        return Paths.get(workingDir).resolve(p).normalize();
    }

    /**
     * 工具输入参数
     */
    public record Input(String path, String pattern, String language) implements ToolInput {
        @Override
        public String toJson() {
            return "{\"path\":\"" + path.replace("\"", "\\\"")
                    + "\",\"pattern\":\"" + pattern.replace("\"", "\\\"").replace("\n", "\\n")
                    + "\",\"language\":\"" + (language != null ? language : "")
                    + "\"}";
        }
    }
}
