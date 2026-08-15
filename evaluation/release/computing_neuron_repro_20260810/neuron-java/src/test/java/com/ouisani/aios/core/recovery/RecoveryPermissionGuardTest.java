package com.ouisani.aios.core.recovery;

import com.ouisani.aios.core.permission.PermissionBehavior;
import com.ouisani.aios.core.permission.PermissionChecker;
import com.ouisani.aios.core.permission.PermissionRule;
import com.ouisani.aios.core.tool.Tool;
import com.ouisani.aios.core.tool.ToolContext;
import com.ouisani.aios.core.tool.ToolInput;
import com.ouisani.aios.core.tool.ToolOutput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RecoveryPermissionGuard} 单元测试 — 验证"恢复重试前权限重校验"的分支契约。
 * <p>
 * 核心断言：恢复通道不再假设"失败=无害，重试=安全"。每次重试用原始失败的工具调用
 * 重新走 {@link PermissionChecker}；DENY 或异步路径无法处理的 ASK 一律拒绝重试。
 */
class RecoveryPermissionGuardTest {

    private static final ToolContext CTX = new ToolContext("agent_guard_test", null, "/tmp");

    @AfterEach
    void cleanup() {
        PermissionChecker.clearGlobalDenialSink();
    }

    // ── Stub 工具（模式取自 PermissionCheckerDontAskTest） ──

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

    /** checkPermissionDetailed 抛异常的写工具 — 模拟校验器自身故障。 */
    private static Tool<ToolInput> throwingTool(String name) {
        return new Tool<>() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub";
            }
            @Override public String inputSchema() { return "{}"; }
            @Override public ToolOutput call(ToolInput i, ToolContext c) { return ToolOutput.ok("ok"); }
            @Override public boolean readOnly() { return false; }
            @Override public com.ouisani.aios.core.permission.SafetyCheckResult checkPermissionDetailed(
                    ToolInput i, ToolContext c) {
                throw new RuntimeException("simulated checker failure");
            }
        };
    }

    private static ToolInput jsonInput(String json) {
        return () -> json;
    }

    private static PermissionRule rule(PermissionBehavior b, String tool) {
        return new PermissionRule(PermissionRule.RuleSource.SESSION, b, tool, null);
    }

    // ── 分支契约测试 ──

    @Test
    void null_checker_allows_in_legacy_mode() {
        // 未接入权限子系统时维持原行为 —— 守卫不阻塞恢复路径
        RecoveryPermissionGuard.GuardResult r = RecoveryPermissionGuard.instance()
                .recheck(null, readOnlyTool("file_read"), jsonInput("{}"), CTX);
        assertTrue(r.allowed(), "null checker 应放行（legacy 模式）");
        assertNull(r.decision());
    }

    @Test
    void null_tool_or_input_allows_non_tool_failure() {
        PermissionChecker pc = new PermissionChecker();
        var guard = RecoveryPermissionGuard.instance();
        assertTrue(guard.recheck(pc, null, jsonInput("{}"), CTX).allowed(),
                "无原始工具调用（非工具失败）应放行");
        assertTrue(guard.recheck(pc, readOnlyTool("file_read"), null, CTX).allowed(),
                "input 为 null 应放行");
    }

    @Test
    void allow_decision_passes_through() {
        // 只读工具 → read-only fast path → ALLOW
        PermissionChecker pc = new PermissionChecker();
        Tool<ToolInput> tool = readOnlyTool("file_read");
        RecoveryPermissionGuard.GuardResult r = RecoveryPermissionGuard.instance()
                .recheck(pc, tool, jsonInput("{}"), CTX);
        assertTrue(r.allowed(), "ALLOW 决策应放行重试");
        assertNotNull(r.decision());
        assertTrue(r.decision().isAllowed());
    }

    @Test
    void deny_decision_blocks_retry() {
        // 显式 deny 规则 → DENY → 守卫拒绝重试
        PermissionChecker pc = new PermissionChecker();
        pc.addRule(rule(PermissionBehavior.DENY, "file_write"));
        Tool<ToolInput> tool = writeTool("file_write");
        RecoveryPermissionGuard.GuardResult r = RecoveryPermissionGuard.instance()
                .recheck(pc, tool, jsonInput("{}"), CTX);
        assertFalse(r.allowed(), "DENY 决策必须拒绝重试");
        assertTrue(r.reason().contains("DENIED"), "原因应标明 DENIED");
        assertNotNull(r.decision());
        assertTrue(r.decision().isDenied());
    }

    @Test
    void ask_decision_blocks_retry_in_async_path() {
        // ASK 在异步恢复路径无法同步询问用户 → 必须拒绝并升级人类介入
        PermissionChecker pc = new PermissionChecker();
        pc.addRule(rule(PermissionBehavior.ASK, "custom_tool"));
        Tool<ToolInput> tool = writeTool("custom_tool");
        RecoveryPermissionGuard.GuardResult r = RecoveryPermissionGuard.instance()
                .recheck(pc, tool, jsonInput("{}"), CTX);
        assertFalse(r.allowed(), "ASK 在异步恢复路径必须拒绝重试");
        assertTrue(r.reason().contains("ASK"), "原因应标明 ASK");
        assertNotNull(r.decision());
        assertTrue(r.decision().needsPrompt());
    }

    @Test
    void checker_throws_conservatively_denies() {
        // 校验器自身异常 → 保守拒绝，绝不在恢复通道静默放行
        PermissionChecker pc = new PermissionChecker();
        Tool<ToolInput> tool = throwingTool("file_write");
        RecoveryPermissionGuard.GuardResult r = RecoveryPermissionGuard.instance()
                .recheck(pc, tool, jsonInput("{}"), CTX);
        assertFalse(r.allowed(), "校验器异常时必须保守拒绝");
        assertTrue(r.reason().contains("conservative deny"), "原因应标明保守拒绝");
        assertNull(r.decision(), "异常路径无决策对象");
    }
}
