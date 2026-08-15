package com.ouisani.aios.core.permission;

import com.ouisani.aios.core.tool.Tool;
import com.ouisani.aios.core.tool.ToolContext;
import com.ouisani.aios.core.tool.ToolInput;
import com.ouisani.aios.core.tool.ToolOutput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Standing Scoped Approvals 单元测试 — 验证 target-scoped 预授权。
 * <p>
 * 借鉴 OpenWorker permissions.py:62-80 的 standing_rule_candidate：
 * <ul>
 *   <li>{tool: {allowed targets}} 精确目标绑定</li>
 *   <li>只有非 exec/destructive 工具 + 有 target 参数才有资格</li>
 *   <li>不同 target 仍需单独批准</li>
 *   <li>能覆盖 *:deny 兜底</li>
 * </ul>
 */
class StandingScopedApprovalTest {

    // ════════════════════════════════════════════════════════════════
    //  测试工具和输入
    //════════════════════════════════════════════════════════════════

    /** 模拟 send_message 工具（非只读，非 exec/destructive → 可 target-scoped） */
    private static Tool<ToolInput> sendMessageTool() {
        return new Tool<>() {
            @Override public String name() { return "send_message"; }
            @Override public String description() { return "stub"; }
            @Override public String inputSchema() { return "{}"; }
            @Override public ToolOutput call(ToolInput i, ToolContext c) { return ToolOutput.ok("ok"); }
            @Override public boolean readOnly() { return false; }
        };
    }

    /** 模拟 bash 工具（exec → 不可 target-scoped） */
    private static Tool<ToolInput> bashTool() {
        return new Tool<>() {
            @Override public String name() { return "bash"; }
            @Override public String description() { return "stub"; }
            @Override public String inputSchema() { return "{}"; }
            @Override public ToolOutput call(ToolInput i, ToolContext c) { return ToolOutput.ok("ok"); }
            @Override public boolean readOnly() { return false; }
        };
    }

    /** 简单 JSON 输入 */
    private static ToolInput input(String json) {
        return () -> json;
    }

    private static final ToolContext CTX = new ToolContext("test_agent", null, "/tmp");

    // ════════════════════════════════════════════════════════════════
    //  grantTargetApproval
    //════════════════════════════════════════════════════════════════

    @Test
    void grantTargetApproval_recordsTarget() {
        PermissionChecker checker = new PermissionChecker();
        checker.grantTargetApproval("send_message", "#general");
        assertTrue(checker.hasTargetApproval("send_message", "#general"));
    }

    @Test
    void grantTargetApproval_multipleTargets() {
        PermissionChecker checker = new PermissionChecker();
        checker.grantTargetApproval("send_message", "#general");
        checker.grantTargetApproval("send_message", "#random");
        assertTrue(checker.hasTargetApproval("send_message", "#general"));
        assertTrue(checker.hasTargetApproval("send_message", "#random"));
        assertFalse(checker.hasTargetApproval("send_message", "#private"),
                "未授权的 target 不应匹配");
    }

    @Test
    void grantTargetApproval_excludesExecDestructive_bash() {
        PermissionChecker checker = new PermissionChecker();
        checker.grantTargetApproval("bash", "rm -rf /tmp");
        assertFalse(checker.hasTargetApproval("bash", "rm -rf /tmp"),
                "bash 是 exec 工具，不应被 target-scoped 预授权");
    }

    @Test
    void grantTargetApproval_excludesExecDestructive_securityScan() {
        PermissionChecker checker = new PermissionChecker();
        checker.grantTargetApproval("security_scan", "/tmp");
        assertFalse(checker.hasTargetApproval("security_scan", "/tmp"),
                "security_scan 是 destructive 工具，不应被 target-scoped 预授权");
    }

    @Test
    void grantTargetApproval_excludesAgentAndHandoff() {
        PermissionChecker checker = new PermissionChecker();
        checker.grantTargetApproval("agent", "subagent_1");
        checker.grantTargetApproval("handoff", "operator");
        assertFalse(checker.hasTargetApproval("agent", "subagent_1"));
        assertFalse(checker.hasTargetApproval("handoff", "operator"));
    }

    @Test
    void grantTargetApproval_nullOrBlank_ignored() {
        PermissionChecker checker = new PermissionChecker();
        checker.grantTargetApproval(null, "#general");
        checker.grantTargetApproval("send_message", null);
        checker.grantTargetApproval("send_message", "");
        checker.grantTargetApproval("send_message", "  ");
        assertFalse(checker.hasTargetApproval("send_message", "#general"));
    }

    @Test
    void clearTargetApprovals_removesAll() {
        PermissionChecker checker = new PermissionChecker();
        checker.grantTargetApproval("send_message", "#general");
        checker.grantTargetApproval("web_fetch", "https://api.example.com");
        checker.clearTargetApprovals();
        assertFalse(checker.hasTargetApproval("send_message", "#general"));
        assertFalse(checker.hasTargetApproval("web_fetch", "https://api.example.com"));
    }

    // ════════════════════════════════════════════════════════════════
    //  checkPermission — target-scoped ALLOW
    //════════════════════════════════════════════════════════════════

    @Test
    void checkPermission_defaultMode_targetScopedAllow() {
        PermissionChecker checker = new PermissionChecker();
        checker.setMode(PermissionMode.DEFAULT);
        checker.grantTargetApproval("send_message", "#general");

        // target="#general" → 应被 target-scoped 预授权放行
        Tool<ToolInput> tool = sendMessageTool();
        ToolInput input = input("{\"target\":\"#general\",\"message\":\"hello\"}");

        PermissionDecision decision = checker.checkPermission(tool, input, CTX);
        assertTrue(decision.isAllowed(), "target=#general 应被 target-scoped 预授权放行");
        assertEquals("target_scoped_allow", decision.reason());
    }

    @Test
    void checkPermission_defaultMode_differentTarget_stillAsks() {
        PermissionChecker checker = new PermissionChecker();
        checker.setMode(PermissionMode.DEFAULT);
        checker.grantTargetApproval("send_message", "#general");

        // target="#random" → 不在预授权中，DEFAULT 模式应 ASK
        Tool<ToolInput> tool = sendMessageTool();
        ToolInput input = input("{\"target\":\"#random\",\"message\":\"hello\"}");

        PermissionDecision decision = checker.checkPermission(tool, input, CTX);
        assertTrue(decision.needsPrompt(), "target=#random 不在预授权中，DEFAULT 模式应 ASK");
    }

    @Test
    void checkPermission_defaultMode_noTarget_stillAsks() {
        PermissionChecker checker = new PermissionChecker();
        checker.setMode(PermissionMode.DEFAULT);
        checker.grantTargetApproval("send_message", "#general");

        // 无 target 参数 → 无法匹配 target-scoped，DEFAULT 模式应 ASK
        Tool<ToolInput> tool = sendMessageTool();
        ToolInput input = input("{\"message\":\"hello\"}");

        PermissionDecision decision = checker.checkPermission(tool, input, CTX);
        assertTrue(decision.needsPrompt(), "无 target 参数时不应匹配 target-scoped 预授权");
    }

    @Test
    void checkPermission_dontAskMode_targetScopedAllow() {
        PermissionChecker checker = new PermissionChecker();
        checker.setMode(PermissionMode.DONT_ASK);
        checker.grantTargetApproval("send_message", "#general");

        // DONT_ASK 模式下，target-scoped 预授权应放行（不转 DENY）
        Tool<ToolInput> tool = sendMessageTool();
        ToolInput input = input("{\"target\":\"#general\",\"message\":\"hello\"}");

        PermissionDecision decision = checker.checkPermission(tool, input, CTX);
        assertTrue(decision.isAllowed(), "DONT_ASK 模式下 target-scoped 预授权应放行");
        assertEquals("target_scoped_allow", decision.reason());
    }

    @Test
    void checkPermission_dontAskMode_differentTarget_denied() {
        PermissionChecker checker = new PermissionChecker();
        checker.setMode(PermissionMode.DONT_ASK);
        checker.grantTargetApproval("send_message", "#general");

        // DONT_ASK 模式下，不同 target → DENY（不 ASK）
        Tool<ToolInput> tool = sendMessageTool();
        ToolInput input = input("{\"target\":\"#random\",\"message\":\"hello\"}");

        PermissionDecision decision = checker.checkPermission(tool, input, CTX);
        assertTrue(decision.isDenied(), "DONT_ASK 模式下未授权 target 应 DENY");
    }

    // ════════════════════════════════════════════════════════════════
    //  checkPermission — exec/destructive 排除
    //════════════════════════════════════════════════════════════════

    @Test
    void checkPermission_bash_notEligibleForTargetScoping() {
        PermissionChecker checker = new PermissionChecker();
        checker.setMode(PermissionMode.DEFAULT);
        // 尝试给 bash 授 target-scoped 预授权（应被拒绝）
        checker.grantTargetApproval("bash", "ls -la");

        Tool<ToolInput> tool = bashTool();
        ToolInput input = input("{\"command\":\"ls -la\"}");

        PermissionDecision decision = checker.checkPermission(tool, input, CTX);
        // bash 不应被 target-scoped 预授权放行 — DEFAULT 模式应 ASK
        assertTrue(decision.needsPrompt(),
                "bash 是 exec 工具，即使尝试 grantTargetApproval 也不应被 target-scoped 放行");
    }

    // ════════════════════════════════════════════════════════════════
    //  checkPermission — *:deny 覆盖
    //════════════════════════════════════════════════════════════════

    @Test
    void checkPermission_targetScopedOverridesWildcardDeny() {
        PermissionChecker checker = new PermissionChecker();
        checker.setMode(PermissionMode.DEFAULT);
        // *:deny 规则（默认拒绝所有）
        checker.addRule(new PermissionRule(
                PermissionRule.RuleSource.SESSION, PermissionBehavior.DENY, "*", null));
        // target-scoped 预授权
        checker.grantTargetApproval("send_message", "#general");

        Tool<ToolInput> tool = sendMessageTool();
        ToolInput input = input("{\"target\":\"#general\",\"message\":\"hello\"}");

        PermissionDecision decision = checker.checkPermission(tool, input, CTX);
        assertTrue(decision.isAllowed(),
                "target-scoped 预授权应覆盖 *:deny 兜底 — 用户明确批准了这个 target");
        assertEquals("target_scoped_allow", decision.reason());
    }

    @Test
    void checkPermission_wildcardDeny_blocksUnapprovedTarget() {
        PermissionChecker checker = new PermissionChecker();
        checker.setMode(PermissionMode.DEFAULT);
        checker.addRule(new PermissionRule(
                PermissionRule.RuleSource.SESSION, PermissionBehavior.DENY, "*", null));
        checker.grantTargetApproval("send_message", "#general");

        Tool<ToolInput> tool = sendMessageTool();
        ToolInput input = input("{\"target\":\"#random\",\"message\":\"hello\"}");

        PermissionDecision decision = checker.checkPermission(tool, input, CTX);
        assertTrue(decision.isDenied(),
                "*:deny 应阻止未授权 target（#random 不在预授权中）");
    }

    // ════════════════════════════════════════════════════════════════
    //  extractTarget — 不同字段名
    //════════════════════════════════════════════════════════════════

    @Test
    void checkPermission_targetField() {
        PermissionChecker checker = new PermissionChecker();
        checker.setMode(PermissionMode.DEFAULT);
        checker.grantTargetApproval("send_message", "#general");
        PermissionDecision d = checker.checkPermission(
                sendMessageTool(), input("{\"target\":\"#general\"}"), CTX);
        assertTrue(d.isAllowed());
    }

    @Test
    void checkPermission_pathField() {
        PermissionChecker checker = new PermissionChecker();
        checker.setMode(PermissionMode.DEFAULT);
        checker.grantTargetApproval("file_write", "/app/main.py");

        Tool<ToolInput> tool = new Tool<>() {
            @Override public String name() { return "file_write"; }
            @Override public String description() { return "stub"; }
            @Override public String inputSchema() { return "{}"; }
            @Override public ToolOutput call(ToolInput i, ToolContext c) { return ToolOutput.ok("ok"); }
            @Override public boolean readOnly() { return false; }
        };
        PermissionDecision d = checker.checkPermission(
                tool, input("{\"path\":\"/app/main.py\",\"content\":\"x\"}"), CTX);
        assertTrue(d.isAllowed(), "path 字段应被 extractTarget 识别");
    }

    @Test
    void checkPermission_urlField() {
        PermissionChecker checker = new PermissionChecker();
        checker.setMode(PermissionMode.DEFAULT);
        checker.grantTargetApproval("web_fetch", "https://api.example.com");

        Tool<ToolInput> tool = new Tool<>() {
            @Override public String name() { return "web_fetch"; }
            @Override public String description() { return "stub"; }
            @Override public String inputSchema() { return "{}"; }
            @Override public ToolOutput call(ToolInput i, ToolContext c) { return ToolOutput.ok("ok"); }
            @Override public boolean readOnly() { return false; }
        };
        PermissionDecision d = checker.checkPermission(
                tool, input("{\"url\":\"https://api.example.com\"}"), CTX);
        assertTrue(d.isAllowed(), "url 字段应被 extractTarget 识别");
    }

    // ════════════════════════════════════════════════════════════════
    //  clearRules 清除 target-scoped 预授权
    //════════════════════════════════════════════════════════════════

    @Test
    void clearRules_alsoClearsTargetApprovals() {
        PermissionChecker checker = new PermissionChecker();
        checker.grantTargetApproval("send_message", "#general");
        checker.clearRules();
        assertFalse(checker.hasTargetApproval("send_message", "#general"),
                "clearRules 应同时清除 target-scoped 预授权");
    }

    // ════════════════════════════════════════════════════════════════
    //  AUTO 模式
    //════════════════════════════════════════════════════════════════

    @Test
    void checkPermission_autoMode_targetScopedAllow() {
        PermissionChecker checker = new PermissionChecker();
        checker.setMode(PermissionMode.AUTO);
        checker.grantTargetApproval("send_message", "#general");

        Tool<ToolInput> tool = sendMessageTool();
        ToolInput input = input("{\"target\":\"#general\",\"message\":\"hello\"}");

        PermissionDecision decision = checker.checkPermission(tool, input, CTX);
        assertTrue(decision.isAllowed());
        // AUTO 模式对未授权 target 也会 ALLOW（AUTO 默认放行），
        // 但 target-scoped 的 reason 应该优先
        assertEquals("target_scoped_allow", decision.reason(),
                "target-scoped 预授权应优先于 AUTO 模式的默认放行");
    }
}
