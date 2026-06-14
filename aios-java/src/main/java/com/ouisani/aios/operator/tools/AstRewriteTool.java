package com.ouisani.aios.operator.tools;

import com.ouisani.aios.core.ast.AstGrepRunner;
import com.ouisani.aios.core.tool.Tool;
import com.ouisani.aios.core.tool.ToolContext;
import com.ouisani.aios.core.tool.ToolInput;
import com.ouisani.aios.core.tool.ToolOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * AST 结构化改写工具 — 赋予大模型 AST 级别的微创手术能力。
 * <p>
 * 对标 oh-my-openagent 的 ast-grep-mcp Rewrite 功能：
 * LLM 先通过 {@link AstSearchTool} 找到目标代码结构和元变量绑定，
 * 再用此工具进行精准的 AST 级替换。
 * <p>
 * 核心优势：
 * <ul>
 *   <li>语法安全 — 替换后的代码保证语法正确（AST 级重写）</li>
 *   <li>零副作用 — 只修改匹配的 AST 节点，其余代码原样保留</li>
 *   <li>元变量复用 — 搜索时捕获的 $META_VAR 可直接用于替换模板</li>
 * </ul>
 * <p>
 * 与 HashlineEditTool 的对比：
 * <pre>
 *   HashlineEditTool — 文本级哈希切分替换，按空行分块，通用但无语法感知
 *   AstRewriteTool   — AST 级结构化替换，按语法节点定位，精准且语法安全
 * </pre>
 * <p>
 * 典型工作流：
 * <pre>
 *   1. ast_search(path, "int maxRetries = $NUM;") → 捕获 $NUM = 3
 *   2. ast_rewrite(path, "int maxRetries = $NUM;", "int maxRetries = 5;") → 精准替换
 * </pre>
 * <p>
 * 与 OmniMotherAgent 自愈机制的联动：
 * <pre>
 *   ast_rewrite → 模式不匹配 → RuntimeException
 *   → OmniMotherAgent.handleTask() catch 块捕获
 *   → 反思注入：将错误信息怼到 LLM 脸上
 *   → LLM 重新 ast_search → 获取正确模式 → 再次 ast_rewrite
 * </pre>
 *
 * @see AstSearchTool
 * @see AstGrepRunner
 */
public class AstRewriteTool implements Tool<AstRewriteTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(AstRewriteTool.class);

    @Override
    public String name() {
        return "ast_rewrite";
    }

    @Override
    public String description() {
        return "Performs surgical, AST-level code replacement. "
                + "Provide a search 'pattern' and a 'rewrite' template using the captured $META_VARs. "
                + "The pattern must match existing code exactly at the AST level. "
                + "Extremely safe — never breaks syntax. "
                + "Always use ast_search first to verify the pattern matches before rewriting.";
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
                      "description": "AST pattern to match (same syntax as ast_search). Must match existing code exactly."
                    },
                    "rewrite": {
                      "type": "string",
                      "description": "Replacement template. Use captured $META_VARs from ast_search to preserve dynamic parts. Example: pattern='int $VAR = $OLD;' rewrite='int $VAR = $NEW;'"
                    },
                    "language": {
                      "type": "string",
                      "description": "Programming language. Auto-inferred from file extension if omitted."
                    }
                  },
                  "required": ["path", "pattern", "rewrite"]
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

            // 推断语言
            String language = input.language();
            if (language == null || language.isBlank()) {
                language = AstGrepRunner.inferLanguage(filePath.toString());
            }

            // 执行 AST 级微创手术
            AstGrepRunner.replace(filePath.toString(), input.pattern(), input.rewrite(), language);

            log.info("[AstRewrite] Surgical rewrite applied to {} | pattern: {} | rewrite: {}",
                    filePath.getFileName(), input.pattern(), input.rewrite());
            return ToolOutput.ok("Success: Surgical AST rewrite applied to " + filePath.getFileName()
                    + ". Pattern: " + input.pattern() + " → Rewrite: " + input.rewrite());

        } catch (RuntimeException e) {
            // 替换失败 — 这个异常会被 OmniMotherAgent 的自愈循环捕获！
            log.warn("[AstRewrite] Rewrite failed: {}", e.getMessage());
            throw e; // 重新抛出，让自愈引擎处理
        } catch (Exception e) {
            return ToolOutput.fail("AST rewrite error: " + e.getMessage());
        }
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public String prompt() {
        return """
                IMPORTANT: Always use ast_search first to verify your pattern matches before using ast_rewrite.
                The pattern in ast_rewrite must match the existing code exactly at the AST level.
                If ast_rewrite fails, it means the pattern didn't match — re-read the file with ast_search
                using a corrected pattern, then try again.
                Use captured $META_VARs from ast_search in your rewrite template to preserve dynamic parts.
                Example workflow:
                  1. ast_search(path, "int maxRetries = $NUM;") → captures $NUM = 3
                  2. ast_rewrite(path, "int maxRetries = $NUM;", "int maxRetries = 5;") → replaces only the value
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
    public record Input(String path, String pattern, String rewrite, String language) implements ToolInput {
        @Override
        public String toJson() {
            return "{\"path\":\"" + path.replace("\"", "\\\"")
                    + "\",\"pattern\":\"" + pattern.replace("\"", "\\\"").replace("\n", "\\n")
                    + "\",\"rewrite\":\"" + rewrite.replace("\"", "\\\"").replace("\n", "\\n")
                    + "\",\"language\":\"" + (language != null ? language : "")
                    + "\"}";
        }
    }
}
