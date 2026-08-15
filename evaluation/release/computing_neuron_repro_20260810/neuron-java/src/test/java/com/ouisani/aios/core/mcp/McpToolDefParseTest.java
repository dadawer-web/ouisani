package com.ouisani.aios.core.mcp;

import com.ouisani.aios.core.mcp.McpClientRegistry.McpToolDef;
import com.ouisani.aios.core.permission.ActionTier;
import com.ouisani.aios.core.permission.Urgency;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * McpToolDef 元数据解析测试 — 覆盖紧凑构造器默认值、显式赋值、annotations 降级链路。
 * <p>
 * syncToolsFromClient 依赖真实 McpClient（需 MCP 服务器连接），不在单测范围；
 * 这里验证 McpToolDef record 的契约行为 + ActionTier/Urgency fromString 在 MCP 场景的降级。
 */
class McpToolDefParseTest {

    @Test
    void explicit_tier_and_urgency_preserved() {
        McpToolDef def = new McpToolDef(
                "weather_forecast", "Get weather", "{}", "weather-server",
                ActionTier.AutoAllowed, Urgency.High
        );
        assertEquals(ActionTier.AutoAllowed, def.tier());
        assertEquals(Urgency.High, def.urgency());
        assertEquals("weather_forecast", def.name());
        assertEquals("weather-server", def.serverName());
    }

    @Test
    void null_tier_defaults_to_requires_permission() {
        McpToolDef def = new McpToolDef(
                "dangerous_tool", "Mutating op", "{}", "server", null, null
        );
        assertEquals(ActionTier.RequiresPermission, def.tier());
        assertEquals(Urgency.Normal, def.urgency());
    }

    @Test
    void both_null_defaults_applied() {
        McpToolDef def = new McpToolDef(
                "unknown_tool", "desc", "{}", "server", null, null
        );
        // 保守默认：未知工具默认 RequiresPermission + Normal（与 syncToolsFromClient 解析未知 annotations 一致）
        assertEquals(ActionTier.RequiresPermission, def.tier());
        assertEquals(Urgency.Normal, def.urgency());
    }

    @Test
    void annotations_unknown_tier_string_degrades() {
        // 模拟 syncToolsFromClient 解析：toolNode.annotations.tier = "weird_value"
        // ActionTier.fromString("weird_value") → RequiresPermission
        ActionTier tier = ActionTier.fromString("weird_value");
        Urgency urgency = Urgency.fromString("weird_urgency");
        McpToolDef def = new McpToolDef(
                "tool", "desc", "{}", "server", tier, urgency
        );
        assertEquals(ActionTier.RequiresPermission, def.tier());
        assertEquals(Urgency.Normal, def.urgency());
    }

    @Test
    void annotations_missing_degrades_to_defaults() {
        // 模拟 toolNode.path("annotations").path("tier").asText("") → ""
        // 空字符串经 fromString 降级
        ActionTier tier = ActionTier.fromString("");
        Urgency urgency = Urgency.fromString("");
        McpToolDef def = new McpToolDef(
                "tool", "desc", "{}", "server", tier, urgency
        );
        assertEquals(ActionTier.RequiresPermission, def.tier());
        assertEquals(Urgency.Normal, def.urgency());
    }

    @Test
    void annotations_explicit_auto_allowed_parsed() {
        // 模拟 MCP server 声明 annotations.tier = "auto_allowed"
        ActionTier tier = ActionTier.fromString("auto_allowed");
        Urgency urgency = Urgency.fromString("low");
        McpToolDef def = new McpToolDef(
                "read_only_tool", "Read-only", "{}", "server", tier, urgency
        );
        assertEquals(ActionTier.AutoAllowed, def.tier());
        assertEquals(Urgency.Low, def.urgency());
    }

    @Test
    void annotations_high_urgency_parsed() {
        Urgency urgency = Urgency.fromString("high");
        McpToolDef def = new McpToolDef(
                "urgent_tool", "Urgent", "{}", "server",
                ActionTier.RequiresPermission, urgency
        );
        assertEquals(Urgency.High, def.urgency());
    }

    @Test
    void record_equality_with_tier_urgency() {
        McpToolDef a = new McpToolDef("t", "d", "{}", "s",
                ActionTier.AutoAllowed, Urgency.Normal);
        McpToolDef b = new McpToolDef("t", "d", "{}", "s",
                ActionTier.AutoAllowed, Urgency.Normal);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
