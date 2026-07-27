package com.ouisani.aios.core.overnight;

import com.ouisani.aios.core.permission.PermissionChecker;
import com.ouisani.aios.core.permission.PermissionChecker.BashToolLike;
import com.ouisani.aios.core.permission.PermissionDecision;
import com.ouisani.aios.core.permission.PermissionMode;
import com.ouisani.aios.core.permission.PermissionProfile;
import com.ouisani.aios.core.permission.PermissionRule;
import com.ouisani.aios.core.tool.BashTool;
import com.ouisani.aios.core.tool.Tool;
import com.ouisani.aios.core.tool.ToolContext;
import com.ouisani.aios.core.tool.ToolInput;
import com.ouisani.aios.core.tool.ToolOutput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Overnight 权限集成测试 — 验证 DONT_ASK 模式端到端接入 overnight runner。
 * <p>
 * 覆盖三条主线：
 * <ol>
 *   <li><b>画像结构</b>：{@link OvernightPermissionProfile#build()} 返回 DONT_ASK + deny/allow 规则</li>
 *   <li><b>applyProfile 端到端</b>：画像注入 PermissionChecker 后，只读工具放行 / 危险命令拒绝</li>
 *   <li><b>晨报聚合</b>：DENY 决策经 globalDenialSink 聚合，{@link OvernightContract#formatDenialsForMorningReport}
 *       格式化为含 suggestedRules 的晨报段落</li>
 * </ol>
 * 对齐项目记忆：overnight 硬约束（禁止 rm/git push/curl/数据删除/远程推送）收编为结构化规则。
 */
class OvernightPermissionIntegrationTest {

    private static final ToolContext CTX = new ToolContext("agent_overnight", null, "/tmp");

    private List<PermissionChecker.DenialRecord> collected;

    @BeforeEach
    void setup() {
        collected = new CopyOnWriteArrayList<>();
        PermissionChecker.setGlobalDenialSink(collected::add);
    }

    @AfterEach
    void cleanup() {
        PermissionChecker.clearGlobalDenialSink();
    }

    // ── Stub 工具 ──

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

    private static ToolInput jsonInput(String json) {
        return () -> json;
    }

    /** 用真实 BashTool（含 checkPermissionDetailed 的 safetyAsk 检测）测试危险命令。 */
    private static BashTool.Input bashInput(String command) {
        return new BashTool.Input(command);
    }

    // ════════════════════════════════════════════════════════════════
    //  主线 1：OvernightPermissionProfile 画像结构
    // ════════════════════════════════════════════════════════════════

    @Test
    void profile_build_returnsDontAskMode() {
        PermissionProfile profile = OvernightPermissionProfile.build();
        assertEquals(PermissionMode.DONT_ASK, profile.mode(),
                "overnight 画像必须是 DONT_ASK 模式");
    }

    @Test
    void profile_denyRulesContainAbsoluteProhibitions() {
        PermissionProfile profile = OvernightPermissionProfile.build();
        List<PermissionRule> denyRules = profile.denyRules();

        // 项目记忆硬约束：rm / git push / curl / wget / 数据删除 / 远程推送
        assertRuleExists(denyRules, "Bash", "rm:*", "rm 命令必须 deny");
        assertRuleExists(denyRules, "Bash", "git push:*", "git push 必须 deny");
        assertRuleExists(denyRules, "Bash", "curl:*", "curl 必须 deny");
        assertRuleExists(denyRules, "Bash", "wget:*", "wget 必须 deny");
        assertRuleExists(denyRules, "Agent", null, "spawn 子 agent 必须 deny");
    }

    @Test
    void profile_allowRulesContainReadOnlyWhitelist() {
        PermissionProfile profile = OvernightPermissionProfile.build();
        List<PermissionRule> allowRules = profile.allowRules();

        // 只读分析工具白名单
        assertRuleExists(allowRules, "file_read", null, "file_read 应在白名单");
        assertRuleExists(allowRules, "grep", null, "grep 应在白名单");
        assertRuleExists(allowRules, "glob", null, "glob 应在白名单");
        // 限定 Bash 子命令
        assertRuleExists(allowRules, "Bash", "git status", "git status 应在白名单");
        assertRuleExists(allowRules, "Bash", "mvn test:*", "mvn test 应在白名单");
    }

    @Test
    void profile_isImmutable() {
        PermissionProfile profile = OvernightPermissionProfile.build();
        // 多次 build 应返回独立实例（不可变，可共享）
        PermissionProfile p2 = OvernightPermissionProfile.build();
        assertNotSame(profile, p2, "build() 应返回新实例");
        // 内部 List 不可变
        assertThrows(UnsupportedOperationException.class, () -> profile.denyRules().add(
                new PermissionRule(PermissionRule.RuleSource.SESSION, null, "x", null)),
                "denyRules 应为不可变 List");
    }

    private void assertRuleExists(List<PermissionRule> rules, String tool, String content, String msg) {
        boolean found = rules.stream()
                .anyMatch(r -> tool.equals(r.toolName())
                        && (content == null ? r.ruleContent() == null : content.equals(r.ruleContent())));
        assertTrue(found, msg + " — 未找到 rule: " + tool + "(" + content + ")");
    }

    // ════════════════════════════════════════════════════════════════
    //  主线 2：applyProfile 端到端
    // ════════════════════════════════════════════════════════════════

    @Test
    void applyProfile_setsDontAskMode() {
        PermissionChecker pc = new PermissionChecker();
        pc.applyProfile(OvernightPermissionProfile.build());
        assertEquals(PermissionMode.DONT_ASK, pc.getMode());
    }

    @Test
    void applyProfile_readOnlyToolsAutoAllowed() {
        PermissionChecker pc = new PermissionChecker();
        pc.applyProfile(OvernightPermissionProfile.build());

        // 🚨 关键 bug 回归：只读工具在 DONT_ASK 下必须自动放行
        assertTrue(pc.checkPermission(readOnlyTool("file_read"), jsonInput("{}"), CTX).isAllowed(),
                "file_read 应通过 read-only fast path 放行");
        assertTrue(pc.checkPermission(readOnlyTool("grep"), jsonInput("{\"pattern\":\"x\"}"), CTX).isAllowed(),
                "grep 应通过 read-only fast path 放行");
    }

    @Test
    void applyProfile_dangerousCommandsDenied() {
        PermissionChecker pc = new PermissionChecker();
        pc.applyProfile(OvernightPermissionProfile.build());

        // BashTool 真实实例：rm -rf / 触发 safetyAsk → DONT_ASK 转 DENY
        Tool<BashTool.Input> bash = new BashTool();
        PermissionDecision rmRf = pc.checkPermission(bash, bashInput("rm -rf /"), CTX);
        assertTrue(rmRf.isDenied(), "rm -rf / 必须被拒绝");
        assertFalse(rmRf.needsPrompt(), "永不返回 ASK");
    }

    @Test
    void applyProfile_gitPushDeniedByRule() {
        PermissionChecker pc = new PermissionChecker();
        pc.applyProfile(OvernightPermissionProfile.build());

        Tool<BashTool.Input> bash = new BashTool();
        PermissionDecision d = pc.checkPermission(bash, bashInput("git push origin main"), CTX);
        assertTrue(d.isDenied(), "git push 必须被 deny 规则拒绝");
    }

    @Test
    void applyProfile_whitelistedBashCommandsAllowed() {
        PermissionChecker pc = new PermissionChecker();
        pc.applyProfile(OvernightPermissionProfile.build());

        Tool<BashTool.Input> bash = new BashTool();
        // git status 在白名单
        assertTrue(pc.checkPermission(bash, bashInput("git status"), CTX).isAllowed(),
                "git status 在白名单应放行");
        // ls -la 在白名单
        assertTrue(pc.checkPermission(bash, bashInput("ls -la"), CTX).isAllowed(),
                "ls:* 在白名单应放行");
    }

    @Test
    void applyProfile_nonWhitelistedWriteToolDenied() {
        PermissionChecker pc = new PermissionChecker();
        pc.applyProfile(OvernightPermissionProfile.build());

        // 未在 allow 白名单的写工具 → 默认 DENY（DONT_ASK 兜底）
        PermissionDecision d = pc.checkPermission(writeTool("custom_write"), jsonInput("{\"path\":\"/tmp/x\"}"), CTX);
        assertTrue(d.isDenied(), "非白名单写工具应 DENY");
        assertFalse(d.needsPrompt());
        assertFalse(d.suggestedRules().isEmpty(), "DENY 应附带建议规则");
    }

    // ════════════════════════════════════════════════════════════════
    //  主线 3：晨报聚合（globalDenialSink + formatDenialsForMorningReport）
    // ════════════════════════════════════════════════════════════════

    @Test
    void denials_aggregatedToGlobalSink() {
        PermissionChecker pc = new PermissionChecker();
        pc.applyProfile(OvernightPermissionProfile.build());

        Tool<BashTool.Input> bash = new BashTool();
        pc.checkPermission(bash, bashInput("git push origin main"), CTX);  // DENY
        pc.checkPermission(bash, bashInput("rm -rf /tmp/x"), CTX);         // DENY (rm:* rule)
        pc.checkPermission(writeTool("custom_write"), jsonInput("{}"), CTX); // DENY (default)

        assertEquals(3, collected.size(), "三条 DENY 应聚合到全局 sink");
    }

    @Test
    void denials_allowedNotAggregated() {
        PermissionChecker pc = new PermissionChecker();
        pc.applyProfile(OvernightPermissionProfile.build());

        Tool<BashTool.Input> bash = new BashTool();
        pc.checkPermission(bash, bashInput("git status"), CTX);  // ALLOW
        pc.checkPermission(readOnlyTool("file_read"), jsonInput("{}"), CTX); // ALLOW

        assertTrue(collected.isEmpty(), "ALLOW 决策不应聚合到 sink");
    }

    @Test
    void morningReport_formatEmptyDenialsReturnsEmpty() {
        String report = OvernightContract.formatDenialsForMorningReport(List.of());
        assertEquals("", report, "空 denials 应返回空串");
    }

    @Test
    void morningReport_formatNullDenialsReturnsEmpty() {
        String report = OvernightContract.formatDenialsForMorningReport(null);
        assertEquals("", report, "null denials 应返回空串");
    }

    @Test
    void morningReport_formatIncludesToolAndReason() {
        PermissionChecker pc = new PermissionChecker();
        pc.applyProfile(OvernightPermissionProfile.build());

        Tool<BashTool.Input> bash = new BashTool();
        pc.checkPermission(bash, bashInput("git push origin main"), CTX);

        String report = OvernightContract.formatDenialsForMorningReport(collected);
        assertFalse(report.isEmpty(), "晨报段落不应为空");
        assertTrue(report.contains("bash"), "应包含工具名 bash");
        assertTrue(report.contains("git push"), "应包含被拒命令摘要");
        assertTrue(report.contains("拒绝原因"), "应包含拒绝原因标签");
    }

    @Test
    void morningReport_formatIncludesSuggestedRules() {
        PermissionChecker pc = new PermissionChecker();
        pc.applyProfile(OvernightPermissionProfile.build());

        // 非白名单写工具 → DENY + suggestedRules
        pc.checkPermission(writeTool("file_edit"), jsonInput("{\"path\":\"src/foo.py\"}"), CTX);

        String report = OvernightContract.formatDenialsForMorningReport(collected);
        assertFalse(report.isEmpty());
        assertTrue(report.contains("建议规则"), "晨报应包含建议规则标签");
        // 建议应引用路径前缀或工具名
        boolean mentionsSuggestion = report.contains("file_edit") || report.contains("src");
        assertTrue(mentionsSuggestion, "晨报应引用建议规则内容");
    }

    @Test
    void morningReport_formatMarksBypassImmuneAsNonOverridable() {
        // safety ASK → DENY 的记录应在晨报标注"不可通过规则放行"
        // 用 sudo 命令触发 BashTool 的 SUDO_PATTERN safetyAsk（不匹配 overnight profile 的任何 deny 规则）
        PermissionChecker pc = new PermissionChecker();
        pc.applyProfile(OvernightPermissionProfile.build());

        Tool<BashTool.Input> bash = new BashTool();
        pc.checkPermission(bash, bashInput("sudo ls"), CTX);  // safetyAsk → DENY

        assertFalse(collected.isEmpty(), "sudo ls 应产生 DENY 记录");
        PermissionChecker.DenialRecord rec = collected.get(0);
        assertTrue(rec.decision().bypassImmune(), "safety ASK 转的 DENY 应保留 bypassImmune=true");
        assertEquals("dont_ask_converted_safety_ask", rec.decision().reason(),
                "reason 应标记为 safety_ask 转换");

        String report = OvernightContract.formatDenialsForMorningReport(collected);
        assertFalse(report.isEmpty());
        // bypass_immune 的 DENY 应标注"无法通过规则放行"
        assertTrue(report.contains("bypass_immune") || report.contains("无法通过规则放行"),
                "safety ASK 转的 DENY 应标注不可通过规则放行: " + report);
    }

    @Test
    void morningReport_formatIncludesCount() {
        PermissionChecker pc = new PermissionChecker();
        pc.applyProfile(OvernightPermissionProfile.build());

        Tool<BashTool.Input> bash = new BashTool();
        pc.checkPermission(bash, bashInput("git push"), CTX);
        pc.checkPermission(bash, bashInput("rm -rf /tmp"), CTX);

        String report = OvernightContract.formatDenialsForMorningReport(collected);
        assertTrue(report.contains("共 2 条"), "晨报应包含被拒操作计数: " + report);
    }
}
