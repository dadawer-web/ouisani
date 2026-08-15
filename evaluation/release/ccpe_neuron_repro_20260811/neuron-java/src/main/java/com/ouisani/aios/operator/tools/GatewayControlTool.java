package com.ouisani.aios.operator.tools;

import com.ouisani.aios.core.tool.*;
import com.ouisani.aios.operator.gateway.GatewayConfigPolicy;
import com.ouisani.aios.operator.gateway.GatewayException;
import com.ouisani.aios.operator.gateway.GatewayToolBridge;

import java.util.*;

/**
 * Gateway 控制工具 — 对标 OpenClaw 的 GatewayTool。
 * <p>
 * 允许 Agent 查询和修改 Gateway 配置。
 * 配置变更受白名单策略约束，防止危险修改。
 */
public class GatewayControlTool implements Tool<GatewayControlTool.Input> {

    private final GatewayToolBridge bridge;

    public GatewayControlTool(GatewayToolBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public String name() { return "gateway"; }

    @Override
    public String description() {
        return "Control the OpenClaw Gateway. Actions: config.get, config.schema.lookup, config.apply, config.patch. "
                + "Use 'config.get' to read current config, 'config.apply' to replace config, "
                + "'config.patch' to merge config changes. Config changes are restricted to allowed paths only.";
    }

    @Override
    public String inputSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "action": { "type": "string", "enum": ["config.get","config.schema.lookup","config.apply","config.patch"], "description": "Action to perform" },
            "config": { "type": "object", "description": "Config object (for config.apply/config.patch)" },
            "path": { "type": "string", "description": "Config path (for config.schema.lookup)" }
          },
          "required": ["action"]
        }
        """;
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        try {
            return switch (input.action()) {
                case "config.get" -> handleConfigGet();
                case "config.schema.lookup" -> handleConfigSchemaLookup(input);
                case "config.apply" -> handleConfigApply(input);
                case "config.patch" -> handleConfigPatch(input);
                default -> ToolOutput.fail("Unknown action: " + input.action());
            };
        } catch (GatewayException e) {
            return ToolOutput.fail("Gateway error: " + e.getMessage());
        }
    }

    private ToolOutput handleConfigGet() throws GatewayException {
        String result = bridge.call("config.get", Map.of());
        return ToolOutput.ok("Current config:\n" + result);
    }

    private ToolOutput handleConfigSchemaLookup(Input input) throws GatewayException {
        Map<String, Object> params = new LinkedHashMap<>();
        if (input.path() != null) params.put("path", input.path());
        String result = bridge.call("config.schema.lookup", params);
        return ToolOutput.ok("Config schema:\n" + result);
    }

    private ToolOutput handleConfigApply(Input input) throws GatewayException {
        if (input.config() == null || input.config().isEmpty()) {
            return ToolOutput.fail("config is required for 'config.apply' action");
        }

        // 安全检查：验证配置变更是否被允许
        Set<String> changedPaths = collectChangedPaths(input.config());
        GatewayConfigPolicy.ValidationResult validation = GatewayConfigPolicy.validateChanges(changedPaths);
        if (!validation.allowed()) {
            return ToolOutput.fail("Config change denied: " + validation.reason());
        }

        String result = bridge.call("config.apply", Map.of("config", input.config()));
        return ToolOutput.ok("Config applied:\n" + result);
    }

    private ToolOutput handleConfigPatch(Input input) throws GatewayException {
        if (input.config() == null || input.config().isEmpty()) {
            return ToolOutput.fail("config is required for 'config.patch' action");
        }

        // 安全检查：验证配置变更是否被允许
        Set<String> changedPaths = collectChangedPaths(input.config());
        GatewayConfigPolicy.ValidationResult validation = GatewayConfigPolicy.validateChanges(changedPaths);
        if (!validation.allowed()) {
            return ToolOutput.fail("Config change denied: " + validation.reason());
        }

        String result = bridge.call("config.patch", Map.of("config", input.config()));
        return ToolOutput.ok("Config patched:\n" + result);
    }

    /** 收集配置对象中的所有路径 */
    private Set<String> collectChangedPaths(Map<String, Object> config) {
        Set<String> paths = new LinkedHashSet<>();
        collectPaths(config, "", paths);
        return paths;
    }

    @SuppressWarnings("unchecked")
    private void collectPaths(Map<String, Object> obj, String prefix, Set<String> paths) {
        for (Map.Entry<String, Object> e : obj.entrySet()) {
            String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            paths.add(path);
            if (e.getValue() instanceof Map<?, ?> m) {
                collectPaths((Map<String, Object>) m, path, paths);
            }
        }
    }

    @Override
    public boolean readOnly() { return false; }

    /** 工具输入 */
    public record Input(
            String action,
            Map<String, Object> config,
            String path
    ) implements ToolInput {
        @Override
        public String toJson() {
            StringBuilder sb = new StringBuilder("{\"action\":\"").append(action).append("\"");
            if (path != null) sb.append(",\"path\":\"").append(path).append("\"");
            if (config != null) sb.append(",\"config\":{}");
            sb.append("}");
            return sb.toString();
        }
    }
}
