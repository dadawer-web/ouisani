package com.ouisani.aios.core.review;

/**
 * Reviewer Gate 级别 — 借鉴 OpenScience {@code docs/plans/11-reviewer-agent.md} 的三级设计。
 * <p>
 * <ul>
 *   <li>{@link #OFF} — gate 完全跳过</li>
 *   <li>{@link #ANNOTATE} — 默认，非阻塞：跑 reviewer，footer 追加到答案，写 {@code .aios/review.jsonl}</li>
 *   <li>{@link #SOFT} — BLOCKING 且未达 cap 时注入 reminder 重入循环；达 cap 则带「未解决」note 返回</li>
 *   <li>{@link #HARD} — BLOCKING 且未达 cap 时重入；达 cap 仍 BLOCKING 则拒绝 finalize</li>
 * </ul>
 *
 * @see ReviewGate
 */
public enum ReviewGateLevel {
    OFF, ANNOTATE, SOFT, HARD;

    /**
     * 从字符串解析级别。{@code null}/空/未知值 → {@link #ANNOTATE}（默认，零回归）。
     */
    public static ReviewGateLevel fromString(String s) {
        if (s == null || s.isBlank()) return ANNOTATE;
        return switch (s.trim().toLowerCase()) {
            case "off" -> OFF;
            case "soft" -> SOFT;
            case "hard" -> HARD;
            case "annotate", "on", "true", "1" -> ANNOTATE;
            default -> ANNOTATE;
        };
    }
}
