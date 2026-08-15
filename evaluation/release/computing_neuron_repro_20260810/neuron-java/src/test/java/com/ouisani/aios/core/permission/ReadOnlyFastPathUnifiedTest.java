package com.ouisani.aios.core.permission;

import com.ouisani.aios.core.tool.Tool;
import com.ouisani.aios.core.tool.ToolContext;
import com.ouisani.aios.core.tool.ToolInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * read-only fast path 统一性测试 — 反 drift 守卫。
 * <p>
 * 借鉴 AgentScope {@code permission/_engine.py::_check_read_only_fast_path} 的设计原则：
 * "A read-only invocation has no side effects, so it is auto-allowed in every PermissionMode."
 * <p>
 * 本测试验证 {@link PermissionChecker#checkReadOnlyFastPath} 在所有 6 个
 * {@link PermissionMode}（DEFAULT/PLAN/AUTO/ACCEPT_EDITS/BYPASS/DONT_ASK）下统一生效，
 * 防止任何模式再次 drift（原 DEFAULT/BYPASS 缺此步，DONT_ASK 曾缺此步导致 overnight 连
 * file_read 都用不了）。
 * <p>
 * 核心断言：只读工具在任意模式下、无显式 deny/ask 规则时，必须返回 ALLOW。
 */
class ReadOnlyFastPathUnifiedTest {

    private static final ToolContext CTX = new ToolContext("agent_readonly_fp", null, "/tmp");

    // ════════════════════════════════════════════════════════════════
    //  参数化：6 模式都自动放行 read-only 工具
    // ════════════════════════════════════════════════════════════════

    /**
     * 核心反 drift 断言：只读工具在所有 6 个模式下都自动 ALLOW。
     * <p>
     * 如果某模式返回 ASK/DENY，说明该模式 drift 了（缺 fast path 或顺序错误）。
     */
    @ParameterizedTest
    @EnumSource(PermissionMode.class)
    void readOnlyToolAutoAllowedInEveryMode(PermissionMode mode) {
        PermissionChecker pc = new PermissionChecker();
        pc.setMode(mode);

        PermissionDecision decision = pc.checkPermission(
                readOnlyTool("file_read"), jsonInput("{}"), CTX);

        assertTrue(decision.isAllowed(),
                () -> "read-only 工具在 " + mode + " 模式下应自动 ALLOW（read-only fast path），"
                      + "实际: " + decision.behavior() + " — " + decision.message());
    }

    /**
     * reason 字段统一性 — 所有模式对 read-only 工具返回的 reason 都应是
     * "read_only_fast_path"，证明走的是同一个 helper 而非各模式自己的放行逻辑。
     */
    @ParameterizedTest
    @EnumSource(PermissionMode.class)
    void readOnlyFastPathReasonIsConsistentAcrossModes(PermissionMode mode) {
        PermissionChecker pc = new PermissionChecker();
        pc.setMode(mode);

        PermissionDecision decision = pc.checkPermission(
                readOnlyTool("grep"), jsonInput("{\"pattern\":\"foo\"}"), CTX);

        assertEquals("read_only_fast_path", decision.reason(),
                () -> mode + " 模式应通过统一的 read_only_fast_path helper 放行，"
                      + "实际 reason=" + decision.reason());
    }

    // ════════════════════════════════════════════════════════════════
    //  非只读工具不受 fast path 影响（防止过度放行）
    // ════════════════════════════════════════════════════════════════

    /**
     * 非只读工具在 DEFAULT 模式下仍需 ASK — fast path 不过度放行写操作。
     */
    @Test
    void nonReadOnlyToolStillAsksInDefaultMode() {
        PermissionChecker pc = new PermissionChecker();
        pc.setMode(PermissionMode.DEFAULT);

        PermissionDecision decision = pc.checkPermission(
                writeTool("file_write"), jsonInput("{}"), CTX);

        assertTrue(decision.needsPrompt(),
                "非只读工具在 DEFAULT 下仍需 ASK，fast path 不应过度放行");
    }

    /**
     * 非只读工具在 PLAN 模式下仍 DENY — fast path 不破坏 PLAN 的只读锁定。
     */
    @Test
    void nonReadOnlyToolStillDeniedInPlanMode() {
        PermissionChecker pc = new PermissionChecker();
        pc.setMode(PermissionMode.PLAN);

        PermissionDecision decision = pc.checkPermission(
                writeTool("file_write"), jsonInput("{}"), CTX);

        assertTrue(decision.isDenied(),
                "非只读工具在 PLAN 下应 DENY，fast path 不应破坏只读锁定");
    }

    /**
     * 非只读工具在 DONT_ASK 模式下仍 DENY — fast path 不破坏无人值守安全网。
     */
    @Test
    void nonReadOnlyToolStillDeniedInDontAskMode() {
        PermissionChecker pc = new PermissionChecker();
        pc.setMode(PermissionMode.DONT_ASK);

        PermissionDecision decision = pc.checkPermission(
                writeTool("file_write"), jsonInput("{}"), CTX);

        assertTrue(decision.isDenied(),
                "非只读工具在 DONT_ASK 下应 DENY，fast path 不应破坏无人值守安全网");
    }

    // ════════════════════════════════════════════════════════════════
    //  显式 deny/ask 规则优先于 fast path（安全覆盖）
    // ════════════════════════════════════════════════════════════════

    /**
     * 显式 deny 规则覆盖 read-only fast path — 即使是只读工具，deny 规则仍绝对生效。
     * <p>
     * 验证 fast path 在 deny 规则之后调用，不会错误放行被显式禁止的只读工具。
     */
    @ParameterizedTest
    @EnumSource(PermissionMode.class)
    void denyRuleOverridesReadOnlyFastPath(PermissionMode mode) {
        PermissionChecker pc = new PermissionChecker();
        pc.setMode(mode);
        pc.addRule(new PermissionRule(
                PermissionRule.RuleSource.SESSION, PermissionBehavior.DENY,
                "file_read", null));

        PermissionDecision decision = pc.checkPermission(
                readOnlyTool("file_read"), jsonInput("{}"), CTX);

        assertTrue(decision.isDenied(),
                () -> mode + " 模式下显式 deny 规则应覆盖 read-only fast path");
    }

    /**
     * 显式 ask 规则在 DEFAULT 模式下覆盖 read-only fast path —
     * 用户显式要求确认的只读工具仍会 ASK（非 DONT_ASK 模式）。
     */
    @Test
    void askRuleOverridesReadOnlyFastPathInDefaultMode() {
        PermissionChecker pc = new PermissionChecker();
        pc.setMode(PermissionMode.DEFAULT);
        pc.addRule(new PermissionRule(
                PermissionRule.RuleSource.SESSION, PermissionBehavior.ASK,
                "file_read", null));

        PermissionDecision decision = pc.checkPermission(
                readOnlyTool("file_read"), jsonInput("{}"), CTX);

        assertTrue(decision.needsPrompt(),
                "DEFAULT 模式下显式 ask 规则应覆盖 read-only fast path");
    }

    // ════════════════════════════════════════════════════════════════
    //  DONT_ASK 不变式：fast path 不引入 ASK
    // ════════════════════════════════════════════════════════════════

    /**
     * DONT_ASK 模式下 read-only fast path 返回 ALLOW 而非 ASK —
     * 验证 fast path 不破坏 DONT_ASK 的"永不返回 ASK"不变式。
     */
    @Test
    void dontAskReadOnlyFastPathReturnsAllowNotAsk() {
        PermissionChecker pc = new PermissionChecker();
        pc.setMode(PermissionMode.DONT_ASK);

        PermissionDecision decision = pc.checkPermission(
                readOnlyTool("glob"), jsonInput("{}"), CTX);

        assertTrue(decision.isAllowed(),
                "DONT_ASK 下 read-only 应 ALLOW，不能是 ASK（破坏不变式）或 DENY（drift）");
        assertFalse(decision.needsPrompt(),
                "DONT_ASK 永不返回 ASK — read-only fast path 也不能引入 ASK");
    }

    // ════════════════════════════════════════════════════════════════
    //  helpers
    // ════════════════════════════════════════════════════════════════

    private static Tool<ToolInput> readOnlyTool(String name) {
        return new Tool<>() {
            @Override public String name() { return name; }
            @Override public String description() { return "read-only test tool"; }
            @Override public String inputSchema() { return "{}"; }
            @Override public boolean readOnly() { return true; }
            @Override public com.ouisani.aios.core.tool.ToolOutput call(
                    ToolInput input, ToolContext ctx) {
                return com.ouisani.aios.core.tool.ToolOutput.ok("ok");
            }
        };
    }

    private static Tool<ToolInput> writeTool(String name) {
        return new Tool<>() {
            @Override public String name() { return name; }
            @Override public String description() { return "write test tool"; }
            @Override public String inputSchema() { return "{}"; }
            @Override public boolean readOnly() { return false; }
            @Override public com.ouisani.aios.core.tool.ToolOutput call(
                    ToolInput input, ToolContext ctx) {
                return com.ouisani.aios.core.tool.ToolOutput.ok("ok");
            }
        };
    }

    private static ToolInput jsonInput(String json) {
        return () -> json;
    }
}
