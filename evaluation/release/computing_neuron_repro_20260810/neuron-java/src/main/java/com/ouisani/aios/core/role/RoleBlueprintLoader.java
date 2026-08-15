package com.ouisani.aios.core.role;

import com.ouisani.aios.core.permission.PermissionProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色蓝图加载器 — 双读 {@code aios_roles/*.yaml}：原文→prompt（向后兼容 AiosAppManager）
 * + 结构化字段→{@link RoleBlueprint}。
 * <p>
 * 原本 {@code aios_roles/*.yaml} 是 prompt 素材（AiosAppManager {@code Files.readString} 拼接），
 * 全工程无结构化 YAML 解析。本加载器补结构化侧：用 SnakeYAML（{@link SafeConstructor} 防任意对象实例化）
 * 解析 {@code profile:} 与 {@code runtime:} 块，产出 RoleBlueprint。
 * <p>
 * <b>注意</b>：本加载器不替换 AiosAppManager 的 prompt 拼接 —— 两者并存，零回归。
 *
 * <h3>支持的 YAML 结构</h3>
 * <pre>
 * profile:
 *   name: "Code_Reviewer"
 *   description: "..."
 * runtime:                       # 可选，缺失则全默认
 *   mode: subagent
 *   hidden: false
 *   model: null                  # null → 不覆盖
 *   temperature: 0.2
 *   steps: 30
 *   permission:
 *     mode: default
 *     deny: ["*"]
 *     allow: [file_read, grep, glob, web_fetch, web_search]
 * </pre>
 */
public final class RoleBlueprintLoader {

    private static final Logger log = LoggerFactory.getLogger(RoleBlueprintLoader.class);

    private RoleBlueprintLoader() {}

    /**
     * 加载目录下所有 {@code *.yaml} 为 RoleBlueprint（best-effort，单文件失败不影响其余）。
     *
     * @param dir aios_roles 目录
     * @return 角色名 → 蓝图（保序）；目录不存在则空
     */
    public static Map<String, RoleBlueprint> loadAll(Path dir) {
        Map<String, RoleBlueprint> out = new LinkedHashMap<>();
        if (dir == null || !Files.isDirectory(dir)) return out;
        try (var stream = Files.list(dir)) {
            stream.filter(f -> f.toString().endsWith(".yaml"))
                    .forEach(f -> {
                        try {
                            RoleBlueprint bp = loadOne(f);
                            if (bp != null) out.put(bp.name(), bp);
                        } catch (Throwable t) {
                            log.warn("[RoleBlueprintLoader] 加载失败 {}: {}", f, t.getMessage());
                        }
                    });
        } catch (Throwable t) {
            log.warn("[RoleBlueprintLoader] 列目录失败 {}: {}", dir, t.getMessage());
        }
        return out;
    }

    /**
     * 加载单个 yaml 文件为 RoleBlueprint。
     *
     * @return 蓝图；解析异常返回 null
     */
    public static RoleBlueprint loadOne(Path file) {
        try {
            String content = Files.readString(file);
            Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(content);
            if (root == null) root = Map.of();
            String name = deriveName(file, root);
            return buildBlueprint(name, root);
        } catch (Throwable t) {
            log.warn("[RoleBlueprintLoader] 加载失败 {}: {}", file, t.getMessage());
            return null;
        }
    }

    private static RoleBlueprint buildBlueprint(String name, Map<String, Object> root) {
        Map<String, Object> profile = asMap(root.get("profile"));
        String description = asNullableString(profile.get("description"));
        Map<String, Object> runtime = asMap(root.get("runtime"));
        return applyRuntime(name, description, runtime);
    }

    /**
     * 把 {@code runtime:} 块应用到 RoleBlueprint —— package-private 供测试直接验证解析逻辑。
     *
     * @param name        角色名
     * @param description 描述
     * @param runtime     runtime 子树；null/空 → 全默认便捷构造
     * @return 蓝图
     */
    static RoleBlueprint applyRuntime(String name, String description, Map<String, Object> runtime) {
        if (runtime == null || runtime.isEmpty()) {
            return new RoleBlueprint(name, description, null);
        }
        AgentMode mode = AgentMode.fromString(asNullableString(runtime.get("mode")));
        boolean hidden = asBoolean(runtime.get("hidden"), false);
        String model = asNullableString(runtime.get("model"));
        double temperature = asDouble(runtime.get("temperature"), RoleBlueprint.DEFAULT_TEMPERATURE);
        int steps = asInt(runtime.get("steps"), RoleBlueprint.DEFAULT_STEPS);
        PermissionProfile perm = PermissionProfile.fromMap(asMap(runtime.get("permission")));
        return new RoleBlueprint(name, description, null, mode, hidden, model, temperature, steps, perm);
    }

    private static String deriveName(Path file, Map<String, Object> root) {
        Map<String, Object> profile = asMap(root.get("profile"));
        Object nameVal = profile.get("name");
        if (nameVal instanceof String s && !s.isBlank()) return s.trim();
        String fn = file.getFileName().toString();
        return fn.substring(0, fn.length() - ".yaml".length());
    }

    // ── YAML 值类型助手（SnakeYAML 把 list/map/scalar 装载为 Java 原生类型）──

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (var e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return Map.of();
    }

    /** YAML null → Java null；非 String → toString */
    private static String asNullableString(Object o) {
        if (o == null) return null;
        if (o instanceof String s) return s;
        return o.toString();
    }

    private static boolean asBoolean(Object o, boolean def) {
        if (o instanceof Boolean b) return b;
        if (o instanceof String s && !s.isBlank()) return Boolean.parseBoolean(s.trim());
        return def;
    }

    private static double asDouble(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s && !s.isBlank()) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private static int asInt(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    // 保留 List 引用以备后续扩展（如 expertise 列表解析）
    @SuppressWarnings("unused")
    private static List<Object> asList(Object o) {
        if (o instanceof List<?> l) return List.copyOf(l);
        return List.of();
    }
}
