package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.permission.PermissionChecker;
import com.ouisani.aios.core.permission.PermissionDecision;
import com.ouisani.aios.core.permission.PermissionProfile;
import com.ouisani.aios.core.role.RoleBlueprint;
import com.ouisani.aios.core.role.RoleBlueprintLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link QueryEngine} 权限画像接线测试 —— 同包以访问 package-private {@code permissionChecker()}。
 * <p>
 * 验证 5-arg PermissionProfile 构造器把 RoleBlueprint 的 {@code permission:} 块注入 PermissionChecker，
 * 实现 reviewer 子 agent 的 {@code *:deny + 只读工具白名单} blindness（权限层强制）。
 * <p>
 * 仅构造 QueryEngine 并调用 {@code permissionChecker().checkPermission(...)}，不触发 query()，
 * 故无需 ReviewGate/VfsManager 初始化。
 */
class QueryEngineProfileTest {

    private static final ToolContext CTX = new ToolContext("agent_profile", null, "/tmp");

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

    @Test
    void reviewerProfile_readonlyToolsAllowed() {
        QueryEngine engine = new QueryEngine(null, "reviewer", "/tmp", List.of(), reviewerProfile());
        PermissionChecker pc = engine.permissionChecker();

        assertTrue(pc.checkPermission(tool("file_read", true), input("{}"), CTX).isAllowed());
        assertTrue(pc.checkPermission(tool("grep", true), input("{\"pattern\":\"x\"}"), CTX).isAllowed());
    }

    @Test
    void writeToolsDenied() {
        QueryEngine engine = new QueryEngine(null, "reviewer", "/tmp", List.of(), reviewerProfile());
        PermissionChecker pc = engine.permissionChecker();

        PermissionDecision d = pc.checkPermission(
                tool("file_write", false), input("{\"path\":\"/tmp/a\"}"), CTX);
        assertTrue(d.isDenied(), "写工具不在白名单 → 拒绝");
        assertEquals("wildcard_deny", d.reason(), "应走 2c' 通配符兜底");
    }

    @Test
    void loadedFromYamlViaLoader() {
        Path file = Path.of("aios_roles", "Code_Reviewer.yaml");
        assumeTrue(Files.exists(file), "aios_roles/Code_Reviewer.yaml 不可见");

        RoleBlueprint bp = RoleBlueprintLoader.loadOne(file);
        assertNotNull(bp);
        QueryEngine engine = new QueryEngine(null, "reviewer", "/tmp", List.of(), bp.permissionProfile());
        PermissionChecker pc = engine.permissionChecker();

        // 只读白名单放行
        assertTrue(pc.checkPermission(tool("file_read", true), input("{}"), CTX).isAllowed());
        // 写工具拒绝
        assertTrue(pc.checkPermission(
                tool("file_write", false), input("{\"path\":\"/tmp/a\"}"), CTX).isDenied());
    }
}
