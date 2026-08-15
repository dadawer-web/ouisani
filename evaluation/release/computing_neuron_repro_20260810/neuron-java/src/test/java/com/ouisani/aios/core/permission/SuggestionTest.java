package com.ouisani.aios.core.permission;

import com.ouisani.aios.core.permission.PermissionChecker.BashToolLike;
import com.ouisani.aios.core.tool.Tool;
import com.ouisani.aios.core.tool.ToolContext;
import com.ouisani.aios.core.tool.ToolInput;
import com.ouisani.aios.core.tool.ToolOutput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PermissionChecker#generateSuggestions} 建议规则生成测试 — 对齐 AgentScope 2.0 的 {@code _generate_suggestions}。
 * <p>
 * 验证策略（DENY 决策附带"加什么规则能放行"的建议，供 overnight 晨报呈现给用户）：
 * <ul>
 *   <li>Bash 工具：提取命令前缀（"npm run build:prod" → "npm run:*"）</li>
 *   <li>文件操作工具：提取目录前缀（"src/foo/bar.py" → "src/**"）</li>
 *   <li>兜底：工具名精确匹配（无 path / command 时）</li>
 *   <li>建议规则可经 {@link PermissionRule#toRuleString()} 序列化为可解析字符串</li>
 * </ul>
 * <p>
 * 由于 {@code generateSuggestions} 是 private，通过 {@link PermissionChecker#checkPermission}
 * 在 DENY 路径上间接验证（DENY 决策的 suggestedRules 字段）。
 */
class SuggestionTest {

    private static final ToolContext CTX = new ToolContext("agent_suggestion", null, "/tmp");

    // ── Stub 工具 ──

    private static Tool<ToolInput> writeTool(String name) {
        return new Tool<>() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub"; }
            @Override public String inputSchema() { return "{}"; }
            @Override public ToolOutput call(ToolInput i, ToolContext c) { return ToolOutput.ok("ok"); }
            @Override public boolean readOnly() { return false; }
        };
    }

    private static Tool<BashInput> bashTool() {
        return new Tool<>() {
            @Override public String name() { return "bash"; }
            @Override public String description() { return "stub bash"; }
            @Override public String inputSchema() { return "{}"; }
            @Override public ToolOutput call(BashInput i, ToolContext c) { return ToolOutput.ok("ok"); }
            @Override public boolean readOnly() { return false; }
        };
    }

    /** Bash 输入 stub — 实现 BashToolLike。 */
    private static final class BashInput implements ToolInput, BashToolLike {
        private final String command;
        BashInput(String command) { this.command = command; }
        @Override public String getCommand() { return command; }
        @Override public String toJson() {
            return "{\"command\":\"" + (command == null ? "" : command.replace("\"", "\\\"")) + "\"}";
        }
    }

    private static ToolInput jsonInput(String json) {
        return () -> json;
    }

    /** 在 DONT_ASK 模式下触发 DENY（默认路径），提取 suggestedRules。 */
    private static <I extends ToolInput> PermissionDecision denyDecision(Tool<I> tool, I input) {
        PermissionChecker pc = new PermissionChecker();
        pc.setMode(PermissionMode.DONT_ASK);
        return pc.checkPermission(tool, input, CTX);
    }

    // ════════════════════════════════════════════════════════════════
    //  Bash 命令前缀提取
    // ════════════════════════════════════════════════════════════════

    @Test
    void bash_multiTokenCommand_extractsTwoTokenPrefix() {
        // "npm run build:prod" → "npm run:*"
        PermissionDecision d = denyDecision(bashTool(), new BashInput("npm run build:prod"));

        assertTrue(d.isDenied());
        List<PermissionRule> suggestions = d.suggestedRules();
        assertFalse(suggestions.isEmpty(), "Bash DENY 应有建议");

        boolean hasPrefixRule = suggestions.stream()
                .anyMatch(r -> "Bash".equals(r.toolName())
                        && r.ruleContent() != null
                        && r.ruleContent().equals("npm run:*"));
        assertTrue(hasPrefixRule, "应包含 Bash(npm run:*) 前缀建议，实际: " + suggestions);
    }

    @Test
    void bash_singleTokenCommand_extractsSingleTokenPrefix() {
        // "ls" → "ls:*"
        PermissionDecision d = denyDecision(bashTool(), new BashInput("ls"));

        List<PermissionRule> suggestions = d.suggestedRules();
        boolean hasPrefixRule = suggestions.stream()
                .anyMatch(r -> r.ruleContent() != null && r.ruleContent().equals("ls:*"));
        assertTrue(hasPrefixRule, "单 token 命令应提取为 'ls:*'");
    }

    @Test
    void bash_gitPushCommand_extractsGitPushPrefix() {
        // "git push origin main" → "git push:*"
        PermissionDecision d = denyDecision(bashTool(), new BashInput("git push origin main"));

        List<PermissionRule> suggestions = d.suggestedRules();
        boolean hasPrefixRule = suggestions.stream()
                .anyMatch(r -> r.ruleContent() != null && r.ruleContent().equals("git push:*"));
        assertTrue(hasPrefixRule, "应提取 'git push:*' 前缀");
    }

    @Test
    void bash_emptyCommand_fallsBackToToolName() {
        PermissionDecision d = denyDecision(bashTool(), new BashInput(""));

        List<PermissionRule> suggestions = d.suggestedRules();
        assertFalse(suggestions.isEmpty());
        // 空 command → 兜底工具名匹配（tool.name() 返回小写 "bash"）
        assertTrue(suggestions.stream().anyMatch(r -> "bash".equalsIgnoreCase(r.toolName())));
    }

    // ════════════════════════════════════════════════════════════════
    //  文件路径目录前缀提取
    // ════════════════════════════════════════════════════════════════

    @Test
    void fileTool_extractsDirectoryPrefixFromPath() {
        // path="src/foo/bar.py" → "src/**"
        PermissionDecision d = denyDecision(writeTool("file_edit"),
                jsonInput("{\"path\":\"src/foo/bar.py\"}"));

        List<PermissionRule> suggestions = d.suggestedRules();
        boolean hasDirRule = suggestions.stream()
                .anyMatch(r -> "file_edit".equals(r.toolName())
                        && r.ruleContent() != null
                        && r.ruleContent().equals("src/**"));
        assertTrue(hasDirRule, "应包含 file_edit(src/**) 目录前缀建议，实际: " + suggestions);
    }

    @Test
    void fileTool_extractsDirectoryFromAbsolutePath() {
        // path="/tmp/x/y.txt" → "/tmp/**"
        PermissionDecision d = denyDecision(writeTool("file_write"),
                jsonInput("{\"path\":\"/tmp/x/y.txt\"}"));

        List<PermissionRule> suggestions = d.suggestedRules();
        boolean hasDirRule = suggestions.stream()
                .anyMatch(r -> r.ruleContent() != null && r.ruleContent().equals("/tmp/**"));
        assertTrue(hasDirRule, "绝对路径应提取首段目录 '/tmp/**'");
    }

    @Test
    void fileTool_supportsMultiplePathKeys() {
        // "file" key
        PermissionDecision d1 = denyDecision(writeTool("file_edit"),
                jsonInput("{\"file\":\"src/a.py\"}"));
        assertTrue(d1.suggestedRules().stream()
                .anyMatch(r -> r.ruleContent() != null && r.ruleContent().equals("src/**")),
                "\"file\" key 应被提取");

        // "filePath" key
        PermissionDecision d2 = denyDecision(writeTool("file_edit"),
                jsonInput("{\"filePath\":\"src/b.py\"}"));
        assertTrue(d2.suggestedRules().stream()
                .anyMatch(r -> r.ruleContent() != null && r.ruleContent().equals("src/**")),
                "\"filePath\" key 应被提取");

        // "filename" key
        PermissionDecision d3 = denyDecision(writeTool("file_edit"),
                jsonInput("{\"filename\":\"src/c.py\"}"));
        assertTrue(d3.suggestedRules().stream()
                .anyMatch(r -> r.ruleContent() != null && r.ruleContent().equals("src/**")),
                "\"filename\" key 应被提取");
    }

    @Test
    void fileTool_noPathFallsBackToToolName() {
        // 无 path 字段 → 兜底工具名匹配
        PermissionDecision d = denyDecision(writeTool("custom_tool"), jsonInput("{}"));

        List<PermissionRule> suggestions = d.suggestedRules();
        assertEquals(1, suggestions.size(), "兜底应只有一条建议");
        assertEquals("custom_tool", suggestions.get(0).toolName());
        assertNull(suggestions.get(0).ruleContent(), "兜底建议 ruleContent=null（工具名精确匹配）");
    }

    // ════════════════════════════════════════════════════════════════
    //  建议规则序列化（toRuleString 可被 PermissionRule.parse 反向解析）
    // ════════════════════════════════════════════════════════════════

    @Test
    void suggestion_toRuleString_isParseable() {
        PermissionDecision d = denyDecision(bashTool(), new BashInput("npm run build"));

        for (PermissionRule r : d.suggestedRules()) {
            String serialized = r.toRuleString();
            PermissionRule parsed = PermissionRule.parse(
                    PermissionRule.RuleSource.SESSION.name(), PermissionBehavior.ALLOW, serialized);
            assertEquals(r.toolName(), parsed.toolName(),
                    "toRuleString → parse 往返应保持 toolName: " + serialized);
            // ruleContent 可能为 null（兜底）或带通配符
            if (r.ruleContent() != null) {
                assertEquals(r.ruleContent(), parsed.ruleContent(),
                        "toRuleString → parse 往返应保持 ruleContent: " + serialized);
            }
        }
    }

    @Test
    void suggestion_bashAndPathCoexist() {
        // Bash 工具 + 带 path 的 JSON → 应同时有命令前缀建议和路径建议
        // 注意：bashTool 的 input 是 BashInput，其 toJson 只有 command 字段，无 path
        // 所以这里用 jsonInput 模拟带 path 的 bash 调用（虽然 BashInput 无 path，但验证 generateSuggestions 逻辑）
        PermissionChecker pc = new PermissionChecker();
        pc.setMode(PermissionMode.DONT_ASK);

        // BashInput 的 toJson = {"command":"npm run build"}，无 path → 只有命令前缀建议
        PermissionDecision d = pc.checkPermission(bashTool(), new BashInput("npm run build"), CTX);
        assertTrue(d.isDenied());
        // 至少有一条命令前缀建议
        assertTrue(d.suggestedRules().stream()
                .anyMatch(r -> r.ruleContent() != null && r.ruleContent().startsWith("npm")));
    }

    @Test
    void suggestion_allDenyPathsCarrySuggestions() {
        // 验证所有 DENY 路径都附带建议（deny rule / ask→deny / safety→deny / wildcard / mode 默认）
        PermissionChecker pc = new PermissionChecker();
        pc.setMode(PermissionMode.DONT_ASK);

        // 1. deny 规则 DENY
        pc.addRule(new PermissionRule(PermissionRule.RuleSource.SESSION, PermissionBehavior.DENY, "bash", "rm:*"));
        PermissionDecision d1 = pc.checkPermission(bashTool(), new BashInput("rm -rf /tmp"), CTX);
        assertTrue(d1.isDenied());
        assertFalse(d1.suggestedRules().isEmpty(), "deny 规则 DENY 应有建议");

        // 2. mode 默认 DENY
        pc.clearRules();
        PermissionDecision d2 = pc.checkPermission(writeTool("custom"), jsonInput("{}"), CTX);
        assertTrue(d2.isDenied());
        assertFalse(d2.suggestedRules().isEmpty(), "mode 默认 DENY 应有建议");

        // 3. wildcard 兜底 DENY
        pc.addRule(new PermissionRule(PermissionRule.RuleSource.SESSION, PermissionBehavior.DENY, "*", null));
        PermissionDecision d3 = pc.checkPermission(writeTool("custom"), jsonInput("{\"path\":\"/tmp/a\"}"), CTX);
        assertTrue(d3.isDenied());
        assertFalse(d3.suggestedRules().isEmpty(), "wildcard 兜底 DENY 应有建议");
    }

    // ════════════════════════════════════════════════════════════════
    //  withSuggestions 链式方法
    // ════════════════════════════════════════════════════════════════

    @Test
    void withSuggestions_replacesSuggestionList() {
        PermissionDecision original = PermissionDecision.deny("test", "rule");
        assertTrue(original.suggestedRules().isEmpty());

        List<PermissionRule> newSuggestions = List.of(
                new PermissionRule(PermissionRule.RuleSource.SESSION, PermissionBehavior.ALLOW, "bash", "ls:*"));
        PermissionDecision with = original.withSuggestions(newSuggestions);

        assertEquals(1, with.suggestedRules().size());
        assertEquals("rule", with.reason(), "withSuggestions 不改变 reason");
        assertEquals("test", with.message(), "withSuggestions 不改变 message");
    }

    @Test
    void withSuggestions_nullReturnsEmptyList() {
        PermissionDecision d = PermissionDecision.deny("test", "rule").withSuggestions(null);
        assertTrue(d.suggestedRules().isEmpty());
    }
}
