package com.ouisani.aios.core.permission;

import com.ouisani.aios.core.tool.Tool;
import com.ouisani.aios.core.tool.ToolContext;
import com.ouisani.aios.core.tool.ToolInput;
import com.ouisani.aios.core.tool.ToolOutput;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PermissionChecker} 通配符 {@code *:deny} + allow 白名单语义测试。
 * <p>
 * 验证 reviewer 子 agent 的 blindness 画像：{@code *:deny} 走"默认拒绝 flag + allow 覆盖"，
 * 非通配符 deny 保持绝对性（零回归），BYPASS/PLAN 上游返回不受通配符兜底影响。
 */
class PermissionCheckerWildcardTest {

    private static final ToolContext CTX = new ToolContext("agent_test", null, "/tmp");

    private static Tool<ToolInput> tool(String name, boolean readOnly) {
        return new Tool<>() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub"; }
            @Override public String inputSchema() { return "{}"; }
            @Override public ToolOutput call(ToolInput i, ToolContext c) { return ToolOutput.ok("ok"); }
            @Override public boolean readOnly() { return readOnly; }
        };
    }

    private static ToolInput input(String json) {
        return () -> json;
    }

    @Test
    void wildcardDeny_blocksAllWritesButReadOnlyFastPathSurvives() {
        PermissionChecker pc = new PermissionChecker();
        pc.addRule(PermissionRule.parse("POLICY_SETTINGS", PermissionBehavior.DENY, "*"));

        // 只读工具仍 ALLOW — read-only fast path 在 *:deny 兜底之前放行
        // （与 DONT_ASK 模式 dontAsk_readOnlyFastPathSurvivesWildcardDeny 行为一致，统一不 drift）
        // 原理：read-only 无副作用，*:deny 的语义是"默认拒绝写操作"，不应阻止无害的读操作
        assertTrue(pc.checkPermission(tool("file_read", true), input("{}"), CTX).isAllowed(),
                "*:deny 下只读工具应被 fast path 放行（无副作用）");
        // 非只读工具被 *:deny 兜底拒绝
        assertTrue(pc.checkPermission(tool("file_write", false), input("{\"path\":\"/tmp/a\"}"), CTX).isDenied(),
                "*:deny 下非只读工具应被兜底拒绝");
    }

    @Test
    void overriddenByExplicitAllow() {
        PermissionChecker pc = new PermissionChecker();
        pc.addRule(PermissionRule.parse("POLICY_SETTINGS", PermissionBehavior.DENY, "*"));
        pc.addRule(PermissionRule.parse("POLICY_SETTINGS", PermissionBehavior.ALLOW, "file_read"));

        // 白名单只读工具放行
        assertTrue(pc.checkPermission(tool("file_read", true), input("{}"), CTX).isAllowed());
        // 非白名单仍拒绝
        assertTrue(pc.checkPermission(tool("file_write", false), input("{\"path\":\"/tmp/a\"}"), CTX).isDenied());
    }

    @Test
    void writeToolBlockedEvenIfRegistered() {
        PermissionChecker pc = new PermissionChecker();
        pc.addRule(PermissionRule.parse("POLICY_SETTINGS", PermissionBehavior.DENY, "*"));
        pc.addRule(PermissionRule.parse("POLICY_SETTINGS", PermissionBehavior.ALLOW, "file_read"));

        PermissionDecision d = pc.checkPermission(
                tool("file_write", false), input("{\"path\":\"/tmp/a\"}"), CTX);
        assertTrue(d.isDenied());
        assertEquals("wildcard_deny", d.reason(), "应走 2c' 通配符兜底");
    }

    @Test
    void nonWildcardDeny_stillAbsolute() {
        PermissionChecker pc = new PermissionChecker();
        // 非通配符 deny 立即拒绝，即使有 allow [*] 通配符
        pc.addRule(PermissionRule.parse("POLICY_SETTINGS", PermissionBehavior.DENY, "bash"));
        pc.addRule(PermissionRule.parse("POLICY_SETTINGS", PermissionBehavior.ALLOW, "*"));

        PermissionDecision d = pc.checkPermission(tool("bash", false), input("{\"cmd\":\"ls\"}"), CTX);
        assertTrue(d.isDenied());
        assertEquals("rule", d.reason(), "非通配符 deny 在 1a 立即拒绝，未到兜底");
    }

    @Test
    void bypassedUnderBypassMode() {
        PermissionChecker pc = new PermissionChecker();
        pc.setMode(PermissionMode.BYPASS);
        pc.addRule(PermissionRule.parse("POLICY_SETTINGS", PermissionBehavior.DENY, "*"));

        // BYPASS 上帝模式覆盖 *:deny（2a 在 2c' 之前返回）
        assertTrue(pc.checkPermission(
                tool("file_write", false), input("{\"path\":\"/tmp/a\"}"), CTX).isAllowed());
    }

    @Test
    void planModeReadonlyAllowedBeforeFallback() {
        PermissionChecker pc = new PermissionChecker();
        pc.setMode(PermissionMode.PLAN);
        pc.addRule(PermissionRule.parse("POLICY_SETTINGS", PermissionBehavior.DENY, "*"));

        // PLAN 模式只读工具在 1d 放行（早于 2c' 通配符兜底）
        assertTrue(pc.checkPermission(tool("file_read", true), input("{}"), CTX).isAllowed());
        // PLAN 模式写工具在 1e 拒绝（早于 2c'）
        assertTrue(pc.checkPermission(
                tool("file_write", false), input("{\"path\":\"/tmp/a\"}"), CTX).isDenied());
    }

    @Test
    void applyProfile_setsModeAndRules() {
        Map<String, Object> perm = new LinkedHashMap<>();
        perm.put("mode", "default");
        perm.put("deny", List.of("*"));
        perm.put("allow", List.of("file_read"));
        PermissionProfile profile = PermissionProfile.fromMap(perm);

        PermissionChecker pc = new PermissionChecker();
        pc.applyProfile(profile);

        assertEquals(PermissionMode.DEFAULT, pc.getMode());
        assertTrue(pc.checkPermission(tool("file_read", true), input("{}"), CTX).isAllowed());
        assertTrue(pc.checkPermission(
                tool("file_write", false), input("{\"path\":\"/tmp/a\"}"), CTX).isDenied());
    }

    @Test
    void applyProfile_nullIsNoop() {
        PermissionChecker pc = new PermissionChecker();
        pc.applyProfile(null);

        assertEquals(PermissionMode.DEFAULT, pc.getMode());
        // 无规则 + 只读工具 → read-only fast path 自动 ALLOW（无副作用，无需用户确认）
        // 借鉴 AgentScope _check_read_only_fast_path：read-only 在所有模式自动放行
        assertTrue(pc.checkPermission(tool("file_read", true), input("{}"), CTX).isAllowed(),
                "read-only 工具在 DEFAULT 无规则时应自动 ALLOW（read-only fast path）");
        // 无规则 + 非只读工具 → 仍走默认 ASK（用户确认）
        assertTrue(pc.checkPermission(tool("file_write", false), input("{}"), CTX).needsPrompt(),
                "非只读工具在 DEFAULT 无规则时仍需 ASK");
    }
}
