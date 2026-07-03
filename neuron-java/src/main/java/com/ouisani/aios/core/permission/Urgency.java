package com.ouisani.aios.core.permission;

/**
 * 紧急度分级 — 镜像 jcode {@code safety.rs:42-48} 的 {@code Urgency} 枚举。
 * <p>
 * 三档紧急度，用于 PermissionRequest 排序与异步裁决超时策略。
 * <ul>
 *   <li>{@link #Low} — 后台任务，无超时压力</li>
 *   <li>{@link #Normal} — 默认值，标准超时</li>
 *   <li>{@link #High} — 紧急任务，缩短超时窗口</li>
 * </ul>
 * 镜像 jcode：无 {@code Default} derive，{@link #DEFAULT} 常量 = {@link #Normal}。
 */
public enum Urgency {
    Low,
    Normal,
    High;

    /** 默认紧急度 — 镜像 jcode 测试中显式使用 {@code Urgency::Normal}。 */
    public static final Urgency DEFAULT = Normal;

    /**
     * 从字符串解析 Urgency — 未知值或 null 降级到 {@link #Normal}。
     * 接受大小写不敏感匹配。
     */
    public static Urgency fromString(String s) {
        if (s == null || s.isBlank()) return Normal;
        return switch (s.toLowerCase().trim()) {
            case "low" -> Low;
            case "high" -> High;
            case "normal" -> Normal;
            default -> Normal;
        };
    }
}
