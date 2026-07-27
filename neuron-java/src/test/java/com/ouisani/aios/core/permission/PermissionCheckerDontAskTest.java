package com.ouisani.aios.core.permission;

import com.ouisani.aios.core.permission.PermissionChecker.BashToolLike;
import com.ouisani.aios.core.tool.Tool;
import com.ouisani.aios.core.tool.ToolContext;
import com.ouisani.aios.core.tool.ToolInput;
import com.ouisani.aios.core.tool.ToolOutput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PermissionMode#DONT_ASK} 模式专项测试 — 借鉴 AgentScope 2.0 的 DONT_ASK 契约。
 * <p>
 * 验证三大不变式（对齐 AgentScope {@code _check_dont_ask}）：
 * <ol>
 *   <li><b>永不返回 ASK</b>：所有 ASK 路径（ask 规则 / 默认 / safety ASK）转 DENY</li>
 *   <li><b>read-only fast path 生效</b>：🚨 修复关键 bug 的回归测试 — 只读工具在 DONT_ASK 下自动 ALLOW</li>
 *   <li><b>safety ASK 转 DENY（非静默放行）</b>：危险操作（rm -rf /）即使在 overnight 也拒绝</li>
 * </ol>
 * 同时验证 suggestedRules 保留、deny 优先于 allow、wildcard 兜底等行为。
 */
class PermissionCheckerDontAskTest {

    private static final ToolContext CTX = new ToolContext("agent_dont_ask", null, "/tmp");

    @AfterEach
    void cleanup() {
        // 隔离测试：每个用例后清掉全局 denial sink，避免跨用例污染
        PermissionChecker.clearGlobalDenialSink();
    }

    // ── Stub 工具构造 ──

    private static Tool<ToolInput> readOnlyTool(String name) {
        return new Tool<>() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub"; }
            @Override public String inputSchema() { return "{}"; }
            @Override public ToolOutput call(ToolInput i, ToolContext c) { return ToolOutput.ok("ok"); }
            @Override public boolean readOnly() { return true; }
        };
    }

    private static Tool<ToolInput> writeTool(String name) {
        return new Tool<>() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub"; }
            @Override public String inputSchema() { return "{}"; }
            @Override public ToolOutput call(ToolInput i, ToolContext c) { return ToolOutput.ok("ok"); }
            @Override public boolean readOnly() { return false; }
        };
    }

    /** Bash stub — 输入实现 BashToolLike，使 shell 规则匹配路径生效。 */
    private static Tool<BashInput> bashTool() {
        return new Tool<>() {
            @Override public String name() { return "bash"; }
            @Override public String description() { return "stub bash"; }
            @Override public String inputSchema() { return "{}"; }
            @Override public ToolOutput call(BashInput i, ToolContext c) { return ToolOutput.ok("ok"); }
            @Override public boolean readOnly() { return false; }
        };
    }

    /** 危险 Bash stub — checkPermissionDetailed 返回 safetyAsk（模拟 BashTool 检测到 rm -rf /）。 */
    private static Tool<BashInput> dangerousBashTool() {
        return new Tool<>() {
            @Override public String name() { return "bash"; }
            @Override public String description() { return "dangerous bash stub"; }
            @Override public String inputSchema() { return "{}"; }
            @Override public ToolOutput call(BashInput i, ToolContext c) { return ToolOutput.ok("ok"); }
            @Override public boolean readOnly() { return false; }
            @Override public SafetyCheckResult checkPermissionDetailed(BashInput i, ToolContext c) {
                if (i.getCommand() != null && i.getCommand().contains("rm -rf /")) {
                    return SafetyCheckResult.safetyAsk("Dangerous: recursive force-delete of root");
                }
                return Tool.super.checkPermissionDetailed(i, c);
            }
        };
    }

    /** Bash 输入 stub — 实现 BashToolLike + ToolInput。 */
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

    private static PermissionRule allow(String tool, String content) {
        return new PermissionRule(PermissionRule.RuleSource.SESSION, PermissionBehavior.ALLOW, tool, content);
    }

    private static PermissionRule deny(String tool, String content) {
        return new PermissionRule(PermissionRule.RuleSource.SESSION, PermissionBehavior.DENY, tool, content);
    }

    private static PermissionRule ask(String tool, String content) {
        return new PermissionRule(PermissionRule.RuleSource.SESSION, PermissionBehavior.ASK, tool, content);
    }

    // ════════════════════════════════════════════════════════════════
    //  不变式 1：永不返回 ASK
    // ════════════════════════════════════════════════════════════════

    @Test
    void dontAsk_neverReturnsAskForWriteToolWithoutRules() {
        PermissionChecker pc = new PermissionChecker();
        pc.setMode(PermissionMode.DONT_ASK);

        PermissionDecision d = pc.checkPermission(writeTool("file_write"), jsonInput("{\"path\":\"/tmp/a\"}"), CTX);
        assertTrue(d.isDenied(), "DONT_ASK 下无规则的写工具应 DENY");
        assertFalse(d.needsPrompt(), "DONT_ASK 不变式：永不返回 ASK");
    }

    @Test
    void dontAsk_neverReturnsAskForAskRule() {
        PermissionChecker pc = new PermissionChecker();
        pc.setMode(PermissionMode.DONT_ASK);
        pc.addRule(ask("bash", null));

        PermissionDecision d = pc.checkPermission(bashTool(), new BashInput("ls"), CTX);
        assertTrue(d.isDenied(), "ask 规则在 DONT_ASK 下转 DENY");
        assertFalse(d.needsPrompt(), "DONT_ASK 不变式：ask 规则不返回 ASK");
        assertEquals("dont_ask_converted_ask", d.reason(), "reason 标记为 ask→deny 转换");
    }

    @Test
    void dontAsk_neverReturnsAskForDefaultPath() {
        // 无任何规则 + 非只读 + 无 safety → 默认路径在 DONT_ASK 下应 DENY（非 ASK）
        PermissionChecker pc = new PermissionChecker();
        pc.setMode(PermissionMode.DONT_ASK);

        PermissionDecision d = pc.checkPermission(writeTool("custom_tool"), jsonInput("{}"), CTX);
        assertTrue(d.isDenied());
        assertEquals("mode", d.reason(), "默认路径 DENY 的 reason=mode");
    }

    // ════════════════════════════════════════════════════════════════
    //  不变式 2：read-only fast path 生效（🚨 关键 bug 回归测试）
    // ════════════════════════════════════════════════════════════════

    @Test
    void dontAsk_readOnlyToolAutoAllowed() {
        // 🚨 这是修复的核心 bug：原 DONT_ASK 缺 read-only fast path，导致 file_read 被拒绝
        PermissionChecker pc = new PermissionChecker();
        pc.setMode(PermissionMode.DONT_ASK);

        assertTrue(pc.checkPermission(readOnlyTool("file_read"), jsonInput("{}"), CTX).isAllowed(),
                "file_read 在 DONT_ASK 下应自动 ALLOW（read-only fast path）");
        assertTrue(pc.checkPermission(readOnlyTool("grep"), jsonInput("{\"pattern\":\"foo\"}"), CTX).isAllowed(),
                "grep 在 DONT_ASK 下应自动 ALLOW");
        assertTrue(pc.checkPermission(readOnlyTool("glob"), jsonInput("{}"), CTX).isAllowed(),
                "glob 在 DONT_ASK 下应自动 ALLOW");
    }

    @Test
    void dontAsk_readOnlyFastPathSurvivesWildcardDeny() {
        // *:deny + 只读工具：read-only fast path 应在通配符兜底之前放行
        PermissionChecker pc = new PermissionChecker();
        pc.setMode(PermissionMode.DONT_ASK);
        pc.addRule(deny("*", null));

        assertTrue(pc.checkPermission(readOnlyTool("file_read"), jsonInput("{}"), CTX).isAllowed(),
                "*:deny 下只读工具仍应 ALLOW（read-only fast path 早于兜底）");
    }

    // ════════════════════════════════════════════════════════════════
    //  不变式 3：safety ASK 转 DENY（不静默放行危险操作）
    // ════════════════════════════════════════════════════════════════

    @Test
    void dontAsk_safetyAskConvertedToDeny() {
        PermissionChecker pc = new PermissionChecker();
        pc.setMode(PermissionMode.DONT_ASK);

        BashInput rmRfRoot = new BashInput("rm -rf /");
        PermissionDecision d = pc.checkPermission(dangerousBashTool(), rmRfRoot, CTX);

        assertTrue(d.isDenied(), "safety ASK 在 DONT_ASK 下转 DENY（不静默放行 rm -rf /）");
        assertFalse(d.needsPrompt(), "永不返回 ASK");
        assertEquals("dont_ask_converted_safety_ask", d.reason(),
                "reason 标记为 safety_ask→deny 转换，与普通 ask 区分");
    }

    @Test
    void dontAsk_safetyAskDenialHasSuggestions() {
        PermissionChecker pc = new PermissionChecker();
        pc.setMode(PermissionMode.DONT_ASK);

        BashInput rmRfRoot = new BashInput("rm -rf /");
        PermissionDecision d = pc.checkPermission(dangerousBashTool(), rmRfRoot, CTX);

        assertTrue(d.isDenied());
        assertFalse(d.suggestedRules().isEmpty(), "DENY 应附带 suggestedRules 供晨报呈现");
    }

    @Test
    void dontAsk_allowRuleDoesNotOverrideSafetyAsk() {
        // 即便配了 allow bash，safety ASK 仍应在 allow 之前转 DENY
        PermissionChecker pc = new PermissionChecker();
        pc.setMode(PermissionMode.DONT_ASK);
        pc.addRule(allow("bash", null));

        BashInput rmRfRoot = new BashInput("rm -rf /");
        PermissionDecision d = pc.checkPermission(dangerousBashTool(), rmRfRoot, CTX);

        assertTrue(d.isDenied(), "allow 规则不能覆盖 safety ASK（bypass_immune 语义）");
    }

    // ════════════════════════════════════════════════════════════════
    //  deny 规则优先 + allow 白名单
    // ════════════════════════════════════════════════════════════════

    @Test
    void dontAsk_denyRuleTakesPrecedenceOverAllow() {
        PermissionChecker pc = new PermissionChecker();
        pc.setMode(PermissionMode.DONT_ASK);
        pc.addRule(deny("bash", "rm:*"));
        pc.addRule(allow("bash", null));

        PermissionDecision d = pc.checkPermission(bashTool(), new BashInput("rm -rf /tmp/x"), CTX);
        assertTrue(d.isDenied(), "deny 规则优先于 allow");
        assertEquals("rule", d.reason());
    }

    @Test
    void dontAsk_shellRuleDenyMatchesCommandPrefix() {
        PermissionChecker pc = new PermissionChecker();
        pc.setMode(PermissionMode.DONT_ASK);
        pc.addRule(deny("Bash", "git push:*"));

        PermissionDecision d = pc.checkPermission(bashTool(), new BashInput("git push origin main"), CTX);
        assertTrue(d.isDenied(), "shell 规则 git push:* 应匹配 'git push origin main'");
    }

    @Test
    void dontAsk_allowRuleWhitelistsSpecificCommand() {
        PermissionChecker pc = new PermissionChecker();
        pc.setMode(PermissionMode.DONT_ASK);
        pc.addRule(allow("Bash", "ls:*"));

        // ls 子命令 → ALLOW（allow 规则匹配）
        assertTrue(pc.checkPermission(bashTool(), new BashInput("ls -la"), CTX).isAllowed(),
                "allow ls:* 应放行 'ls -la'");
        // 非 ls 命令 → DENY（默认兜底）
        assertTrue(pc.checkPermission(bashTool(), new BashInput("rm -rf /tmp"), CTX).isDenied(),
                "非白名单命令应 DENY");
    }

    @Test
    void dontAsk_wildcardDenyFallback() {
        PermissionChecker pc = new PermissionChecker();
        pc.setMode(PermissionMode.DONT_ASK);
        pc.addRule(deny("*", null));
        // 只读工具仍 ALLOW（fast path 早于兜底）
        assertTrue(pc.checkPermission(readOnlyTool("file_read"), jsonInput("{}"), CTX).isAllowed());
        // 写工具 → wildcard 兜底 DENY
        PermissionDecision d = pc.checkPermission(writeTool("file_write"), jsonInput("{\"path\":\"/tmp/a\"}"), CTX);
        assertTrue(d.isDenied());
        assertEquals("wildcard_deny", d.reason());
    }

    // ════════════════════════════════════════════════════════════════
    //  suggestedRules 保留
    // ════════════════════════════════════════════════════════════════

    @Test
    void dontAsk_denialCarriesSuggestedRules() {
        PermissionChecker pc = new PermissionChecker();
        pc.setMode(PermissionMode.DONT_ASK);

        // 写工具无规则 → 默认 DENY，应附带建议
        PermissionDecision d = pc.checkPermission(writeTool("file_edit"), jsonInput("{\"path\":\"src/foo.py\"}"), CTX);
        assertTrue(d.isDenied());
        assertFalse(d.suggestedRules().isEmpty(), "DENY 应附带 suggestedRules");
        // 建议应包含工具名或路径前缀
        boolean mentionsToolOrPath = d.suggestedRules().stream()
                .anyMatch(r -> "file_edit".equalsIgnoreCase(r.toolName())
                        || (r.ruleContent() != null && r.ruleContent().contains("src")));
        assertTrue(mentionsToolOrPath, "建议应引用工具名或路径前缀");
    }

    @Test
    void dontAsk_bashDenialHasCommandPrefixSuggestion() {
        PermissionChecker pc = new PermissionChecker();
        pc.setMode(PermissionMode.DONT_ASK);

        BashInput cmd = new BashInput("npm run build:prod");
        PermissionDecision d = pc.checkPermission(bashTool(), cmd, CTX);
        assertTrue(d.isDenied());
        assertFalse(d.suggestedRules().isEmpty());
        // 建议应包含 "npm run:*" 前缀模式
        boolean hasPrefixSuggestion = d.suggestedRules().stream()
                .anyMatch(r -> r.ruleContent() != null && r.ruleContent().startsWith("npm run"));
        assertTrue(hasPrefixSuggestion, "Bash DENY 建议应包含命令前缀模式");
    }

    // ════════════════════════════════════════════════════════════════
    //  全局 denial sink（供 overnight 晨报聚合）
    // ════════════════════════════════════════════════════════════════

    @Test
    void dontAsk_denialsForwardedToGlobalSink() {
        java.util.List<PermissionChecker.DenialRecord> collected = new java.util.concurrent.CopyOnWriteArrayList<>();
        Consumer<PermissionChecker.DenialRecord> sink = collected::add;
        PermissionChecker.setGlobalDenialSink(sink);

        PermissionChecker pc = new PermissionChecker();
        pc.setMode(PermissionMode.DONT_ASK);

        pc.checkPermission(writeTool("file_write"), jsonInput("{\"path\":\"/tmp/a\"}"), CTX);
        pc.checkPermission(bashTool(), new BashInput("rm -rf /tmp"), CTX);

        assertEquals(2, collected.size(), "两条 DENY 应转发到全局 sink");
        assertEquals("file_write", collected.get(0).toolName());
        assertEquals("bash", collected.get(1).toolName());
    }

    @Test
    void dontAsk_allowedDecisionsNotForwardedToSink() {
        java.util.List<PermissionChecker.DenialRecord> collected = new java.util.concurrent.CopyOnWriteArrayList<>();
        PermissionChecker.setGlobalDenialSink(collected::add);

        PermissionChecker pc = new PermissionChecker();
        pc.setMode(PermissionMode.DONT_ASK);
        pc.addRule(allow("Bash", "ls:*"));

        pc.checkPermission(readOnlyTool("file_read"), jsonInput("{}"), CTX);   // ALLOW
        pc.checkPermission(bashTool(), new BashInput("ls -la"), CTX);           // ALLOW

        assertTrue(collected.isEmpty(), "ALLOW 决策不应转发到 denial sink");
    }

    @Test
    void dontAsk_peekRecentDenialsHasRecord() {
        PermissionChecker pc = new PermissionChecker();
        pc.setMode(PermissionMode.DONT_ASK);

        pc.checkPermission(writeTool("file_write"), jsonInput("{\"path\":\"/tmp/a\"}"), CTX);

        List<PermissionChecker.DenialRecord> recent = pc.peekRecentDenials();
        assertEquals(1, recent.size());
        assertEquals("file_write", recent.get(0).toolName());
        assertFalse(recent.get(0).decision().suggestedRules().isEmpty(),
                "recent denial 应保留 suggestedRules");
    }
}
