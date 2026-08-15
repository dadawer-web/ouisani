package com.ouisani.aios.core.role;

import com.ouisani.aios.core.permission.PermissionProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link RoleBlueprintLoader} 测试 —— 同包以访问 package-private {@code applyRuntime}。
 * <p>
 * 覆盖：applyRuntime 解析（空/全块/permission 子块/null/model:null/未知 mode）、
 * loadOne（真实 Code_Reviewer.yaml 集成 / primary 角色 / 缺 runtime / 畸形 YAML）、
 * loadAll（加载全部角色）。
 */
class RoleBlueprintLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void applyRuntime_emptyMap_defaults() {
        RoleBlueprint bp = RoleBlueprintLoader.applyRuntime("R", "desc", Map.of());
        assertEquals("R", bp.name());
        assertEquals("desc", bp.description());
        assertEquals(AgentMode.PRIMARY, bp.mode());
        assertFalse(bp.hidden());
        assertNull(bp.model());
        assertEquals(RoleBlueprint.DEFAULT_TEMPERATURE, bp.temperature());
        assertEquals(RoleBlueprint.DEFAULT_STEPS, bp.steps());
        assertTrue(bp.permissionProfile().denyRules().isEmpty());
        assertTrue(bp.permissionProfile().allowRules().isEmpty());
    }

    @Test
    void applyRuntime_nullRuntime_convenience() {
        RoleBlueprint bp = RoleBlueprintLoader.applyRuntime("R", "desc", null);
        assertEquals(AgentMode.PRIMARY, bp.mode());
        assertNull(bp.model());
    }

    @Test
    void applyRuntime_fullBlock_parsed() {
        Map<String, Object> rt = new LinkedHashMap<>();
        rt.put("mode", "subagent");
        rt.put("hidden", true);
        rt.put("model", "gpt-4o");
        rt.put("temperature", 0.3);
        rt.put("steps", 42);

        RoleBlueprint bp = RoleBlueprintLoader.applyRuntime("R", "d", rt);
        assertEquals(AgentMode.SUBAGENT, bp.mode());
        assertTrue(bp.hidden());
        assertEquals("gpt-4o", bp.model());
        assertEquals(0.3, bp.temperature());
        assertEquals(42, bp.steps());
    }

    @Test
    void applyRuntime_modelNull_handled() {
        Map<String, Object> rt = new LinkedHashMap<>();
        rt.put("mode", "primary");
        rt.put("model", null);   // YAML model: null → Java null

        RoleBlueprint bp = RoleBlueprintLoader.applyRuntime("R", "d", rt);
        assertEquals(AgentMode.PRIMARY, bp.mode());
        assertNull(bp.model(), "model: null 应解析为 Java null，而非 \"null\" 字符串");
    }

    @Test
    void applyRuntime_permissionBlock_parsed() {
        Map<String, Object> perm = new LinkedHashMap<>();
        perm.put("mode", "default");
        perm.put("deny", List.of("*"));
        perm.put("allow", List.of("file_read", "grep"));
        Map<String, Object> rt = new LinkedHashMap<>();
        rt.put("mode", "subagent");
        rt.put("permission", perm);

        RoleBlueprint bp = RoleBlueprintLoader.applyRuntime("R", "d", rt);
        PermissionProfile p = bp.permissionProfile();
        assertEquals(1, p.denyRules().size(), "*:deny 一条");
        assertEquals("*", p.denyRules().get(0).toolName());
        assertEquals(2, p.allowRules().size(), "allow 白名单两条");
        assertEquals("file_read", p.allowRules().get(0).toolName());
        assertEquals("grep", p.allowRules().get(1).toolName());
    }

    @Test
    void applyRuntime_unknownMode_fallsBackToPrimary() {
        Map<String, Object> rt = Map.of("mode", "bogus_mode");
        RoleBlueprint bp = RoleBlueprintLoader.applyRuntime("R", "d", rt);
        assertEquals(AgentMode.PRIMARY, bp.mode(), "未知 mode → PRIMARY");
    }

    @Test
    void loadOne_realCodeReviewerYaml_integratesBlindnessProfile() {
        Path file = Path.of("aios_roles", "Code_Reviewer.yaml");
        assumeTrue(Files.exists(file), "aios_roles/Code_Reviewer.yaml 不可见（CWD 相关）");

        RoleBlueprint bp = RoleBlueprintLoader.loadOne(file);
        assertNotNull(bp);
        assertEquals("Code_Reviewer", bp.name());
        assertEquals(AgentMode.SUBAGENT, bp.mode(), "Code_Reviewer 应为 subagent");
        assertEquals(0.2, bp.temperature());
        assertEquals(30, bp.steps());
        // blindness 画像：*:deny + 只读白名单
        assertEquals(1, bp.permissionProfile().denyRules().size());
        assertEquals("*", bp.permissionProfile().denyRules().get(0).toolName());
        assertFalse(bp.permissionProfile().allowRules().isEmpty(), "应有只读白名单");
    }

    @Test
    void loadOne_primaryRole() {
        Path file = Path.of("aios_roles", "Python_Coder.yaml");
        assumeTrue(Files.exists(file));
        RoleBlueprint bp = RoleBlueprintLoader.loadOne(file);
        assertNotNull(bp);
        assertEquals(AgentMode.PRIMARY, bp.mode());
    }

    @Test
    void loadAll_loadsAllFourRoles() {
        Path dir = Path.of("aios_roles");
        assumeTrue(Files.isDirectory(dir));
        Map<String, RoleBlueprint> all = RoleBlueprintLoader.loadAll(dir);
        assertTrue(all.size() >= 4, "应至少加载 4 个角色，实际 " + all.size());
        assertNotNull(all.get("Code_Reviewer"));
        assertNotNull(all.get("Python_Coder"));
    }

    @Test
    void loadOne_malformedYaml_returnsNull() throws Exception {
        Path bad = tempDir.resolve("Bad.yaml");
        Files.writeString(bad, "profile: {name: \"Bad\"\n  : [unbalanced"); // 畸形 YAML
        RoleBlueprint bp = RoleBlueprintLoader.loadOne(bad);
        assertNull(bp, "畸形 YAML 应 best-effort 返回 null，不抛异常");
    }

    @Test
    void loadOne_missingRuntime_defaults() throws Exception {
        Path f = tempDir.resolve("Plain.yaml");
        Files.writeString(f, "profile:\n  name: \"Plain\"\n  description: \"no runtime\"\n");
        RoleBlueprint bp = RoleBlueprintLoader.loadOne(f);
        assertNotNull(bp);
        assertEquals("Plain", bp.name());
        assertEquals(AgentMode.PRIMARY, bp.mode(), "缺 runtime → PRIMARY 默认");
    }
}
