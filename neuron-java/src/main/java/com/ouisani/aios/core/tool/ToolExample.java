package com.ouisani.aios.core.tool;

import java.util.Map;

/**
 * 工具使用示例 — 借鉴 EasyTool 的 Example schema。
 * 包含场景描述和示例参数，供 LLM 在编排工具时参考。
 */
public record ToolExample(
    String scenario,              // 自然语言场景描述
    Map<String, Object> parameters // 示例参数值
) {
    public ToolExample {
        if (scenario == null) scenario = "";
        if (parameters == null) parameters = Map.of();
    }
}
