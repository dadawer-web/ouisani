package com.ouisani.aios.core.recovery;

import com.ouisani.aios.core.permission.PermissionChecker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RecoveryReauthorizationGate} 单元测试 — Phase 4 defense #4（统一重新授权关卡）全分支契约。
 * <p>
 * 核心断言：编排器层的"副作用重授权"关卡在 opt-in 开启时正确拦截越权角色替换，关闭时零行为
 * （保论文1 字节稳定）。本关卡是 Layer 2 纵深防御 —— 即便策略内 Layer 1（{@link RoleReplacementValidator}）
 * 被关或未来新增策略漏加内嵌校验，本关卡兜底拦截。
 * <p>
 * <b>测试隔离</b>：通过系统属性 {@code aios.recovery.reauthGate} 切换开关，{@link RecoveryReauthorizationGate#isEnabled()}
 * 动态读取当前值，每个测试前 {@code @BeforeEach} 重置为关态（生产默认）。
 */
class RecoveryReauthorizationGateTest {

    private final PermissionChecker pc = new PermissionChecker();
    private static final String CURRENT_ROLE = "Code_Reviewer"; // 只读 reviewer

    @BeforeEach
    void cleanup() {
        PermissionChecker.clearGlobalDenialSink();
        // 默认恢复关态（与生产默认一致），各测试按需开启
        System.clearProperty("aios.recovery.reauthGate");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("aios.recovery.reauthGate");
        PermissionChecker.clearGlobalDenialSink();
    }

    /** 开启关卡（供 enabled 分支测试）。 */
    private static void enableGate() {
        System.setProperty("aios.recovery.reauthGate", "true");
    }

    private static RecoveryContext contextWith(String currentRole, String suggestedRole) {
        RecoveryContext ctx = new RecoveryContext("agent_reauth_test",
                new RuntimeException("test"), 1, "test error");
        if (currentRole != null) ctx.withMetadata(TopologyMutationStrategy.META_CURRENT_ROLE, currentRole);
        if (suggestedRole != null) ctx.withMetadata(RecoveryReauthorizationGate.META_SUGGESTED_ROLE, suggestedRole);
        return ctx;
    }

    // ════════════════════════════════════════════════════════════════
    //  关卡关闭（默认） — 普通结果保字节稳定；副作用结果强制校验
    //════════════════════════════════════════════════════════════════

    @Test
    void gate_disabled_skips_non_reauth_results() {
        // 关卡关闭 + 普通结果（requiresReauthorization=false）→ skip 放行（保字节稳定）
        RecoveryResult result = RecoveryResult.ok("normal recovery");
        RecoveryContext ctx = contextWith(CURRENT_ROLE, "admin");
        RecoveryReauthorizationGate.ReauthResult r = RecoveryReauthorizationGate.check(result, ctx, pc);
        assertTrue(r.allowed(), "普通结果应放行");
        assertEquals("SKIP", r.category(), "应返回 SKIP 类别");
    }

    @Test
    void gate_disabled_still_blocks_reauth_escalation() {
        // 关卡关闭 + 副作用结果（requiresReauthorization=true）+ 越权 → 强制校验拦截
        // 重构后契约：防越权是硬约束，不受 opt-in 开关控制
        RecoveryResult result = RecoveryResult.okRequiringReauthorization("mutation", null);
        RecoveryContext ctx = contextWith(CURRENT_ROLE, "admin");
        RecoveryReauthorizationGate.ReauthResult r = RecoveryReauthorizationGate.check(result, ctx, pc);
        assertFalse(r.allowed(), "副作用结果强制校验：越权角色应被拦截，即便 gate 关闭");
        assertEquals("UNKNOWN_ROLE", r.category(), "admin 不在白名单 → UNKNOWN_ROLE");
    }

    @Test
    void gate_disabled_does_not_block_non_reauth() {
        // 关卡关闭 + 普通结果 → shouldBlock 返回 false（保字节稳定）
        RecoveryResult result = RecoveryResult.ok("normal recovery");
        RecoveryContext ctx = contextWith(CURRENT_ROLE, "admin");
        assertFalse(RecoveryReauthorizationGate.shouldBlock(result, ctx, pc),
                "关卡关闭 + 普通结果不应拦截");
    }

    // ════════════════════════════════════════════════════════════════
    //  关卡开启 — 副作用结果必须重授权
    //════════════════════════════════════════════════════════════════

    @Test
    void gate_enabled_skips_non_reauth_results() {
        enableGate();
        // 普通结果（requiresReauthorization=false）应 skip，即便关卡开启
        RecoveryResult result = RecoveryResult.ok("normal recovery");
        RecoveryContext ctx = contextWith(CURRENT_ROLE, "admin");
        RecoveryReauthorizationGate.ReauthResult r = RecoveryReauthorizationGate.check(result, ctx, pc);
        assertTrue(r.allowed(), "非副作用结果应放行");
        assertEquals("SKIP", r.category());
    }

    @Test
    void gate_enabled_blocks_unknown_role() {
        enableGate();
        // suggested_role=admin 不在白名单 → 拒绝（category 透传 validator 的 UNKNOWN_ROLE）
        RecoveryResult result = RecoveryResult.okRequiringReauthorization("mutation to admin", null);
        RecoveryContext ctx = contextWith(CURRENT_ROLE, "admin");
        RecoveryReauthorizationGate.ReauthResult r = RecoveryReauthorizationGate.check(result, ctx, pc);
        assertFalse(r.allowed(), "未知角色 admin 应被关卡拒绝");
        assertEquals("UNKNOWN_ROLE", r.category(),
                "category 应透传 validator 的 UNKNOWN_ROLE");
    }

    @Test
    void gate_enabled_blocks_privilege_escalation() {
        enableGate();
        // Code_Reviewer(只读) → System_Architect(默认权限) = 越权（category=PRIVILEGE_ESCALATION）
        RecoveryResult result = RecoveryResult.okRequiringReauthorization("mutation to architect", null);
        RecoveryContext ctx = contextWith(CURRENT_ROLE, "System_Architect");
        RecoveryReauthorizationGate.ReauthResult r = RecoveryReauthorizationGate.check(result, ctx, pc);
        assertFalse(r.allowed(), "越权替换（Code_Reviewer → System_Architect）应被关卡拒绝");
        assertEquals("PRIVILEGE_ESCALATION", r.category(),
                "category 应透传 validator 的 PRIVILEGE_ESCALATION");
    }

    @Test
    void gate_enabled_allows_downgrade() {
        enableGate();
        // Python_Coder(默认) → Code_Reviewer(只读) = 降级，应放行
        RecoveryResult result = RecoveryResult.okRequiringReauthorization("downgrade to reviewer", null);
        RecoveryContext ctx = contextWith("Python_Coder", "Code_Reviewer");
        RecoveryReauthorizationGate.ReauthResult r = RecoveryReauthorizationGate.check(result, ctx, pc);
        assertTrue(r.allowed(), "降级（Python_Coder → Code_Reviewer）应放行");
        assertEquals("ALLOWED", r.category());
    }

    @Test
    void gate_enabled_allows_lateral_move() {
        enableGate();
        // Python_Coder → Security_Auditor（横向，等权）应放行
        RecoveryResult result = RecoveryResult.okRequiringReauthorization("lateral to auditor", null);
        RecoveryContext ctx = contextWith("Python_Coder", "Security_Auditor");
        RecoveryReauthorizationGate.ReauthResult r = RecoveryReauthorizationGate.check(result, ctx, pc);
        assertTrue(r.allowed(), "横向移动（等权角色）应放行");
        assertEquals("ALLOWED", r.category());
    }

    @Test
    void gate_enabled_allows_when_no_suggested_role() {
        enableGate();
        // 声明了 reauth 但 metadata 无 suggestedRole → 保守放行（Layer 1 兜底）
        RecoveryResult result = RecoveryResult.okRequiringReauthorization("mutation", null);
        RecoveryContext ctx = contextWith(CURRENT_ROLE, null); // 无 suggestedRole
        RecoveryReauthorizationGate.ReauthResult r = RecoveryReauthorizationGate.check(result, ctx, pc);
        assertTrue(r.allowed(), "无 suggestedRole 应放行（Layer 1 已兜底）");
        assertEquals("ALLOWED", r.category());
    }

    @Test
    void gate_enabled_allows_when_no_current_role() {
        enableGate();
        // 无 currentRole → 仅做存在性白名单校验。已知角色放行
        RecoveryResult result = RecoveryResult.okRequiringReauthorization("mutation", null);
        RecoveryContext ctx = contextWith(null, "Code_Reviewer"); // 无 currentRole
        RecoveryReauthorizationGate.ReauthResult r = RecoveryReauthorizationGate.check(result, ctx, pc);
        assertTrue(r.allowed(), "无 currentRole + 已知角色应放行（仅白名单校验）");
        assertEquals("ALLOWED", r.category());
    }

    @Test
    void gate_enabled_blocks_unknown_role_without_current_role() {
        enableGate();
        // 无 currentRole + 未知角色 admin → 仍应被存在性白名单拦截
        RecoveryResult result = RecoveryResult.okRequiringReauthorization("mutation to admin", null);
        RecoveryContext ctx = contextWith(null, "admin");
        RecoveryReauthorizationGate.ReauthResult r = RecoveryReauthorizationGate.check(result, ctx, pc);
        assertFalse(r.allowed(), "无 currentRole + 未知角色 admin 仍应被白名单拒绝");
        assertEquals("UNKNOWN_ROLE", r.category());
    }

    @Test
    void null_result_skips() {
        enableGate();
        RecoveryContext ctx = contextWith(CURRENT_ROLE, "admin");
        RecoveryReauthorizationGate.ReauthResult r = RecoveryReauthorizationGate.check(null, ctx, pc);
        assertTrue(r.allowed(), "null result 应 skip 放行");
        assertEquals("SKIP", r.category());
    }
}
