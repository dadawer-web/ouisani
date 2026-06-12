package com.ouisani.aios.openclaw;

/**
 * 钩子描述符 — 描述一个生命周期钩子。
 * <p>
 * 对标 OpenClaw 的 Hook 注册。
 *
 * @param name          钩子名称（如 "before_tool_call", "after_tool_call", "session_start"）
 * @param ownerPluginId 所属插件 ID
 * @param handler       钩子处理函数
 */
public record HookDescriptor(
        String name,
        String ownerPluginId,
        HookHandler handler
) {
    public HookDescriptor {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Hook name required");
        if (handler == null) throw new IllegalArgumentException("Hook handler required");
        if (ownerPluginId == null) ownerPluginId = "";
    }
}
