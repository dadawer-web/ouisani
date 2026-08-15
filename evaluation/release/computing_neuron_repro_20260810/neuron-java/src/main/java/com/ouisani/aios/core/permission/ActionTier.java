package com.ouisani.aios.core.permission;

/**
 * 工具行为分级 — 镜像 jcode {@code safety.rs:35-40} 的 {@code ActionTier} 枚举。
 * <p>
 * 两态分级，简洁优于 {@link PermissionMode} 的 6 模式：
 * <ul>
 *   <li>{@link #AutoAllowed} — 白名单工具（read-only/grep/glob 等），免询问自动放行</li>
 *   <li>{@link #RequiresPermission} — 其余工具，须询问或异步裁决</li>
 * </ul>
 * serde 风格 snake_case（对齐 jcode）：{@code "auto_allowed"} / {@code "requires_permission"}。
 *
 * @see PermissionChecker#classify(String)
 */
public enum ActionTier {
    AutoAllowed,
    RequiresPermission;

    /**
     * 从字符串解析 ActionTier — 镜像 jcode serde {@code rename_all = "snake_case"}。
     * <p>
     * 接受 snake_case（{@code "auto_allowed"}）、enum name（{@code "AutoAllowed"}），
     * 未知值或 null 静默降级到 {@link #RequiresPermission}（保守默认）。
     */
    public static ActionTier fromString(String s) {
        if (s == null || s.isBlank()) return RequiresPermission;
        return switch (s.toLowerCase().trim()) {
            case "auto_allowed", "autoallowed", "auto" -> AutoAllowed;
            case "requires_permission", "requirespermission", "requires" -> RequiresPermission;
            default -> RequiresPermission;
        };
    }
}
