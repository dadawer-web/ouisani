package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.lsp.LspManager;
import com.ouisani.aios.core.lsp.LspManager.LspLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * LSP 工具 — 代码智能操作，对标 Claude Code 的 LSPTool。
 * <p>
 * 提供基于 LSP 协议的代码智能功能：
 * - goToDefinition：跳转到定义
 * - findReferences：查找所有引用
 * - hover：悬浮提示（简化实现，返回指定行内容）
 * - documentSymbol：文档符号大纲（简化实现，返回文件结构概览）
 * <p>
 * OS 类比：相当于 IDE 的代码导航服务 — 通过语言服务器协议获取语义信息。
 */
public class LspTool implements Tool<LspTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(LspTool.class);

    /**
     * 工具输入参数。
     *
     * @param operation 操作类型：goToDefinition / findReferences / hover / documentSymbol
     * @param filePath  目标文件路径
     * @param line      行号（从 0 开始）
     * @param character 列号（从 0 开始）
     */
    public record Input(
            String operation,
            String filePath,
            int line,
            int character
    ) implements ToolInput {
        public Input {
            if (operation == null || operation.isBlank()) {
                throw new IllegalArgumentException("operation 不能为空");
            }
            if (filePath == null || filePath.isBlank()) {
                throw new IllegalArgumentException("filePath 不能为空");
            }
            if (line < 0) line = 0;
            if (character < 0) character = 0;
        }

        @Override
        public String toJson() {
            return "{\"operation\":\"" + operation
                    + "\",\"filePath\":\"" + filePath.replace("\"", "\\\"")
                    + "\",\"line\":" + line
                    + ",\"character\":" + character + "}";
        }
    }

    @Override
    public String name() {
        return "lsp";
    }

    @Override
    public String description() {
        return "LSP 代码智能操作";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"operation\":{\"type\":\"string\",\"enum\":[\"goToDefinition\",\"findReferences\",\"hover\",\"documentSymbol\"],\"description\":\"LSP 操作类型\"},"
                + "\"filePath\":{\"type\":\"string\",\"description\":\"目标文件路径\"},"
                + "\"line\":{\"type\":\"integer\",\"description\":\"行号（从 0 开始）\"},"
                + "\"character\":{\"type\":\"integer\",\"description\":\"列号（从 0 开始）\"}"
                + "},\"required\":[\"operation\",\"filePath\"]}";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        log.info("[LspTool] 执行操作: operation={}, filePath={}:{}:{}", input.operation(), input.filePath(), input.line(), input.character());

        return switch (input.operation()) {
            case "goToDefinition"  -> handleGoToDefinition(input);
            case "findReferences"  -> handleFindReferences(input);
            case "hover"           -> handleHover(input);
            case "documentSymbol"  -> handleDocumentSymbol(input);
            default                -> ToolOutput.fail("不支持的操作类型: " + input.operation()
                    + "，支持的操作: goToDefinition, findReferences, hover, documentSymbol");
        };
    }

    /**
     * 跳转到定义 — 委托 LspManager 执行。
     */
    private ToolOutput handleGoToDefinition(Input input) {
        LspLocation location = LspManager.instance().goToDefinition(input.filePath(), input.line(), input.character());
        if (location == null) {
            return ToolOutput.ok("未找到定义位置。可能原因：LSP 服务器未运行或该位置无定义。");
        }
        return ToolOutput.ok("定义位置: " + location.filePath() + ":" + location.line() + ":" + location.col());
    }

    /**
     * 查找所有引用 — 委托 LspManager 执行。
     */
    private ToolOutput handleFindReferences(Input input) {
        List<LspLocation> references = LspManager.instance().findReferences(input.filePath(), input.line(), input.character());
        if (references.isEmpty()) {
            return ToolOutput.ok("未找到引用。可能原因：LSP 服务器未运行或该位置无引用。");
        }
        String result = references.stream()
                .map(loc -> "  " + loc.filePath() + ":" + loc.line() + ":" + loc.col())
                .collect(Collectors.joining("\n"));
        return ToolOutput.ok("找到 " + references.size() + " 个引用:\n" + result);
    }

    /**
     * 悬浮提示 — 简化实现，返回指定行的文件内容。
     */
    private ToolOutput handleHover(Input input) {
        try {
            Path path = Path.of(input.filePath());
            if (!path.toFile().exists()) {
                return ToolOutput.fail("文件不存在: " + input.filePath());
            }

            // 读取指定行内容
            try (BufferedReader reader = new BufferedReader(new FileReader(path.toFile()))) {
                String line;
                int currentLine = 0;
                while ((line = reader.readLine()) != null) {
                    if (currentLine == input.line()) {
                        return ToolOutput.ok("第 " + input.line() + " 行内容:\n" + line);
                    }
                    currentLine++;
                }
            }
            return ToolOutput.fail("行号超出范围: " + input.line());
        } catch (IOException e) {
            log.error("[LspTool] hover 操作读取文件失败: {}", e.getMessage());
            return ToolOutput.fail("读取文件失败: " + e.getMessage());
        }
    }

    /**
     * 文档符号大纲 — 简化实现，返回文件结构概览。
     * 提取类定义、方法定义、函数定义等关键行。
     */
    private ToolOutput handleDocumentSymbol(Input input) {
        try {
            Path path = Path.of(input.filePath());
            if (!path.toFile().exists()) {
                return ToolOutput.fail("文件不存在: " + input.filePath());
            }

            StringBuilder outline = new StringBuilder();
            outline.append("文件大纲: ").append(input.filePath()).append("\n");

            try (BufferedReader reader = new BufferedReader(new FileReader(path.toFile()))) {
                String line;
                int lineNum = 0;
                int symbolCount = 0;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    // 匹配常见的代码结构定义
                    if (isSymbolLine(trimmed)) {
                        outline.append(String.format("  %4d | %s%n", lineNum, trimmed));
                        symbolCount++;
                    }
                    lineNum++;
                }
                if (symbolCount == 0) {
                    outline.append("  (未检测到符号定义)");
                } else {
                    outline.insert(outline.indexOf("\n") + 1, "共 " + symbolCount + " 个符号\n");
                }
            }
            return ToolOutput.ok(outline.toString());
        } catch (IOException e) {
            log.error("[LspTool] documentSymbol 操作读取文件失败: {}", e.getMessage());
            return ToolOutput.fail("读取文件失败: " + e.getMessage());
        }
    }

    /**
     * 判断一行代码是否为符号定义行（类、方法、函数等）。
     * 简化的模式匹配，覆盖 Java/Python/TypeScript/Go/Rust 常见定义。
     */
    private boolean isSymbolLine(String trimmed) {
        if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("/*")) {
            return false;
        }
        // Java/Kotlin: class, interface, enum, method
        if (trimmed.startsWith("public ") || trimmed.startsWith("private ") || trimmed.startsWith("protected ")
                || trimmed.startsWith("class ") || trimmed.startsWith("interface ") || trimmed.startsWith("enum ")) {
            return true;
        }
        // Python: def, class, async def
        if (trimmed.startsWith("def ") || trimmed.startsWith("class ") || trimmed.startsWith("async def ")) {
            return true;
        }
        // TypeScript/JavaScript: function, class, interface, type, export
        if (trimmed.startsWith("function ") || trimmed.startsWith("export function ")
                || trimmed.startsWith("export class ") || trimmed.startsWith("export interface ")
                || trimmed.startsWith("export type ") || trimmed.startsWith("export default ")) {
            return true;
        }
        // Go: func, type, interface, struct
        if (trimmed.startsWith("func ") || trimmed.startsWith("type ") && (trimmed.contains("struct") || trimmed.contains("interface"))) {
            return true;
        }
        // Rust: fn, struct, enum, impl, trait, pub fn, pub struct
        if (trimmed.startsWith("fn ") || trimmed.startsWith("pub fn ") || trimmed.startsWith("struct ")
                || trimmed.startsWith("pub struct ") || trimmed.startsWith("enum ")
                || trimmed.startsWith("impl ") || trimmed.startsWith("trait ")) {
            return true;
        }
        return false;
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public String prompt() {
        return "使用 LSP 工具进行代码智能操作。goToDefinition 和 findReferences 需要对应语言的 LSP 服务器已启动。"
                + "hover 和 documentSymbol 为简化实现，直接读取文件内容。"
                + "行号和列号均从 0 开始。";
    }
}
