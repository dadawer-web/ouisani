package com.ouisani.aios.core.review;

/**
 * ReviewGate 配置 — 自包含静态读 env，不依赖 {@code DeclarativeConfig} 全局单例（无全局持有者）。
 * <p>
 * 环境变量：
 * <ul>
 *   <li>{@code AIOS_EXPERIMENTAL_REVIEW_GATE} — off|annotate|soft|hard（默认 annotate）</li>
 *   <li>{@code AIOS_EXPERIMENTAL_REVIEW_FIX_CYCLES} — soft/hard 修复轮次上限（默认 2）</li>
 *   <li>{@code AIOS_EXPERIMENTAL_REVIEW_TIMEOUT_MS} — reviewer 超时毫秒（默认 120000）</li>
 * </ul>
 * 测试可通过 {@code setLevelForTesting} 等覆盖入口注入（env 不可在测试中直接修改）。
 */
public final class ReviewGateConfig {

    private static volatile ReviewGateLevel levelOverride = null;
    private static volatile Integer maxFixCyclesOverride = null;
    private static volatile Long timeoutMsOverride = null;

    private ReviewGateConfig() {}

    /** 优先测试覆盖，其次 env {@code AIOS_EXPERIMENTAL_REVIEW_GATE}，默认 ANNOTATE。 */
    public static ReviewGateLevel level() {
        if (levelOverride != null) return levelOverride;
        return ReviewGateLevel.fromString(System.getenv("AIOS_EXPERIMENTAL_REVIEW_GATE"));
    }

    /** soft/hard 修复轮次上限（默认 2）。 */
    public static int maxFixCycles() {
        if (maxFixCyclesOverride != null) return maxFixCyclesOverride;
        return parseIntEnv("AIOS_EXPERIMENTAL_REVIEW_FIX_CYCLES", 2);
    }

    /** reviewer 超时毫秒（默认 120000）。 */
    public static long timeoutMs() {
        if (timeoutMsOverride != null) return timeoutMsOverride;
        return parseLongEnv("AIOS_EXPERIMENTAL_REVIEW_TIMEOUT_MS", 120_000L);
    }

    // ── 测试覆盖入口 ──

    public static void setLevelForTesting(ReviewGateLevel l) { levelOverride = l; }
    public static void clearLevelForTesting() { levelOverride = null; }

    public static void setMaxFixCyclesForTesting(Integer n) { maxFixCyclesOverride = n; }
    public static void clearMaxFixCyclesForTesting() { maxFixCyclesOverride = null; }

    public static void setTimeoutMsForTesting(Long ms) { timeoutMsOverride = ms; }
    public static void clearTimeoutMsForTesting() { timeoutMsOverride = null; }

    /** 清除所有测试覆盖（@AfterEach 调用）。 */
    public static void clearAllForTesting() {
        levelOverride = null;
        maxFixCyclesOverride = null;
        timeoutMsOverride = null;
    }

    private static int parseIntEnv(String name, int defaultVal) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return defaultVal;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private static long parseLongEnv(String name, long defaultVal) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return defaultVal;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}
