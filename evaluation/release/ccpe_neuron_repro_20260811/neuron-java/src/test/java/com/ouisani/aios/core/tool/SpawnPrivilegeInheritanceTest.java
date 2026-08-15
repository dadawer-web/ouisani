package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.ipc.CallerContext;
import com.ouisani.aios.core.permission.PermissionChecker;
import com.ouisani.aios.core.permission.PermissionDecision;
import com.ouisani.aios.core.permission.PermissionProfile;
import com.ouisani.aios.core.permission.SpawnPrivilegeContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LIM 攻击面闭合验证 — spawn 权限非递增（Gap A）+ tenantId 跨 spawn 传播（Gap B）。
 * <p>
 * 同包以访问 package-private {@code permissionChecker()} 与 {@code inheritedTenantId()}。
 * 仅构造 QueryEngine 并调用 {@code permissionChecker().checkPermission(...)}，不触发 query()，
 * 故无需 ReviewGate/VfsManager 初始化（与 {@link QueryEngineProfileTest} 同模式）。
 * <p>
 * <b>验证目标</b>：
 * <ul>
 *   <li>Gap A：父被降权（reviewer {@code *:deny}）时，子 agent 经 {@link SpawnPrivilegeContext}
 *       继承父 profile，子 PermissionChecker 同样拒绝写工具——堵住「spawn 即升级」</li>
 *   <li>Gap B：父线程 {@link CallerContext} 设 tenantId 后，子 QueryEngine 构造期捕获该 tenantId</li>
 *   <li>零回归：父为 DEFAULT（profile null/empty）时，子保持 DEFAULT，写工具进 ASK 而非被拒</li>
 *   <li>深传播：子 {@link PermissionChecker#currentProfile()} 返回继承的 profile，可供孙 agent 继续继承</li>
 * </ul>
 */
class SpawnPrivilegeInheritanceTest {

    private static final ToolContext CTX = new ToolContext("agent_spawn", null, "/tmp");

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

    /** reviewer blindness 画像：*:deny + 只读白名单 */
    private static PermissionProfile reviewerProfile() {
        Map<String, Object> perm = new LinkedHashMap<>();
        perm.put("mode", "default");
        perm.put("deny", List.of("*"));
        perm.put("allow", List.of("file_read", "grep", "glob", "web_fetch", "web_search"));
        return PermissionProfile.fromMap(perm);
    }

    @AfterEach
    void cleanup() {
        CallerContext.clear();
        SpawnPrivilegeContext.clear();
    }

    @Test
    void gapA_childInheritsParentDeny_noEscalation() {
        // 父 agent 被 reviewer profile 降权（*:deny + 只读白名单）
        PermissionProfile parent = reviewerProfile();
        SpawnPrivilegeContext.set(parent);

        // 子 agent 经 AgentTool spawn 路径：读 SpawnPrivilegeContext.current() 注入 5 参构造器
        PermissionProfile inherited = SpawnPrivilegeContext.current();
        QueryEngine child = new QueryEngine(null, "child", "/tmp", List.of(),
                inherited == null ? PermissionProfile.empty() : inherited);
        PermissionChecker childPc = child.permissionChecker();

        // 子继承父的 *:deny → 写工具被拒（而非拿全新 DEFAULT 放行/ASK）
        PermissionDecision d = childPc.checkPermission(
                tool("file_write", false), input("{\"path\":\"/tmp/a\"}"), CTX);
        assertTrue(d.isDenied(), "子 agent 应继承父 *:deny → 写工具被拒（权限非递增）");
        assertEquals("wildcard_deny", d.reason());

        // 只读白名单工具仍放行
        assertTrue(childPc.checkPermission(tool("file_read", true), input("{}"), CTX).isAllowed());
    }

    @Test
    void gapA_defaultParent_childStaysDefault_noRegression() {
        // 父为 DEFAULT（profile null）→ SpawnPrivilegeContext.current()=null → 子传 empty() → no-op
        SpawnPrivilegeContext.set(null);
        PermissionProfile inherited = SpawnPrivilegeContext.current();
        assertNull(inherited, "set(null) 后 current() 应为 null");

        QueryEngine child = new QueryEngine(null, "child", "/tmp", List.of(),
                PermissionProfile.empty());
        PermissionChecker childPc = child.permissionChecker();

        // 子保持 DEFAULT：只读放行，写工具进 ASK（非拒绝）——零回归
        assertTrue(childPc.checkPermission(tool("file_read", true), input("{}"), CTX).isAllowed());
        PermissionDecision writeDecision = childPc.checkPermission(
                tool("file_write", false), input("{\"path\":\"/tmp/a\"}"), CTX);
        assertFalse(writeDecision.isDenied(), "DEFAULT 子 agent 写工具应进 ASK 而非被拒（零回归）");
        assertFalse(writeDecision.isAllowed(), "DEFAULT 子 agent 写工具应进 ASK 而非自动放行");
    }

    @Test
    void gapA_deepPropagation_childPublishesInheritedProfile() {
        // 父 profile → 子继承 → 子 currentProfile() 返回非 null，可供孙 agent 继续继承
        PermissionProfile parent = reviewerProfile();
        SpawnPrivilegeContext.set(parent);
        PermissionProfile inherited = SpawnPrivilegeContext.current();

        QueryEngine child = new QueryEngine(null, "child", "/tmp", List.of(),
                inherited == null ? PermissionProfile.empty() : inherited);

        PermissionProfile childEffective = child.permissionChecker().currentProfile();
        assertNotNull(childEffective, "子应发布继承的 profile 供孙 agent 继承");
        // 子的有效 profile 等价于父的（deny=*, allow=只读白名单）
        assertFalse(childEffective.denyRules().isEmpty(), "子应继承父的 deny 规则");
    }

    @Test
    void gapB_tenantIdPropagatesAcrossSpawn() {
        // 父线程注入 tenantId（模拟未来顶层注入点 / 红队手动注入）
        CallerContext.set("parent_agent", "tenantA");

        // 子 QueryEngine 构造期从 CallerContext 捕获 tenantId（InheritableThreadLocal 继承语义）
        QueryEngine child = new QueryEngine(null, "child", "/tmp", List.of());
        assertEquals("tenantA", child.inheritedTenantId(),
                "子 agent 应跨 spawn 继承父 tenantId（Gap B 管道）");
    }

    @Test
    void gapB_noCallerContext_tenantIdNull() {
        // 顶层 agent / headless：无 CallerContext → tenantId=null（legacy，所有权校验 skip）
        assertNull(CallerContext.current(), "前置：无 CallerContext");
        QueryEngine child = new QueryEngine(null, "child", "/tmp", List.of());
        assertNull(child.inheritedTenantId(), "无父注入时 tenantId 应为 null（零回归）");
    }
}
