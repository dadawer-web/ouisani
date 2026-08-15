package com.ouisani.aios.core.review;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ReviewGateLevel#fromString} 与 {@link ReviewGateConfig} 覆盖入口测试。
 * <p>
 * 核心保证：null/空/未知值 → ANNOTATE（默认零回归）；off/soft/hard 精确匹配；
 * 测试覆盖入口优先于 env。
 */
class ReviewGateLevelTest {

    @AfterEach
    void tearDown() {
        ReviewGateConfig.clearAllForTesting();
    }

    // ── ReviewGateLevel.fromString ──

    @Test
    @DisplayName("fromString 精确匹配 off/soft/hard/annotate")
    void fromString_exactValues() {
        assertEquals(ReviewGateLevel.OFF, ReviewGateLevel.fromString("off"));
        assertEquals(ReviewGateLevel.SOFT, ReviewGateLevel.fromString("soft"));
        assertEquals(ReviewGateLevel.HARD, ReviewGateLevel.fromString("hard"));
        assertEquals(ReviewGateLevel.ANNOTATE, ReviewGateLevel.fromString("annotate"));
    }

    @Test
    @DisplayName("fromString 别名 on/true/1 → ANNOTATE")
    void fromString_aliases() {
        assertEquals(ReviewGateLevel.ANNOTATE, ReviewGateLevel.fromString("on"));
        assertEquals(ReviewGateLevel.ANNOTATE, ReviewGateLevel.fromString("true"));
        assertEquals(ReviewGateLevel.ANNOTATE, ReviewGateLevel.fromString("1"));
    }

    @Test
    @DisplayName("fromString null/空/未知 → ANNOTATE（默认零回归）")
    void fromString_nullEmptyUnknown_returnsAnnotate() {
        assertEquals(ReviewGateLevel.ANNOTATE, ReviewGateLevel.fromString(null));
        assertEquals(ReviewGateLevel.ANNOTATE, ReviewGateLevel.fromString(""));
        assertEquals(ReviewGateLevel.ANNOTATE, ReviewGateLevel.fromString("   "));
        assertEquals(ReviewGateLevel.ANNOTATE, ReviewGateLevel.fromString("xyz"));
        assertEquals(ReviewGateLevel.ANNOTATE, ReviewGateLevel.fromString("maybe"));
    }

    @Test
    @DisplayName("fromString 大小写不敏感")
    void fromString_caseInsensitive() {
        assertEquals(ReviewGateLevel.OFF, ReviewGateLevel.fromString("OFF"));
        assertEquals(ReviewGateLevel.HARD, ReviewGateLevel.fromString("Hard"));
        assertEquals(ReviewGateLevel.SOFT, ReviewGateLevel.fromString("Soft"));
        assertEquals(ReviewGateLevel.ANNOTATE, ReviewGateLevel.fromString("Annotate"));
    }

    @Test
    @DisplayName("fromString 带前后空白 trim")
    void fromString_trimsWhitespace() {
        assertEquals(ReviewGateLevel.SOFT, ReviewGateLevel.fromString("  soft  "));
        assertEquals(ReviewGateLevel.OFF, ReviewGateLevel.fromString("\toff\n"));
    }

    // ── ReviewGateConfig 覆盖入口 ──

    @Test
    @DisplayName("ReviewGateConfig level override 优先于 env")
    void reviewGateConfig_overridePrecedence() {
        ReviewGateConfig.setLevelForTesting(ReviewGateLevel.SOFT);
        assertEquals(ReviewGateLevel.SOFT, ReviewGateConfig.level());

        ReviewGateConfig.setLevelForTesting(ReviewGateLevel.HARD);
        assertEquals(ReviewGateLevel.HARD, ReviewGateConfig.level());

        // 清除后回到默认（env 未设置 → fromString(null) → ANNOTATE）
        ReviewGateConfig.clearAllForTesting();
        assertEquals(ReviewGateLevel.ANNOTATE, ReviewGateConfig.level());
    }

    @Test
    @DisplayName("ReviewGateConfig maxFixCycles override")
    void reviewGateConfig_maxFixCycles_override() {
        ReviewGateConfig.setMaxFixCyclesForTesting(5);
        assertEquals(5, ReviewGateConfig.maxFixCycles());

        ReviewGateConfig.clearMaxFixCyclesForTesting();
        // 默认 2
        assertEquals(2, ReviewGateConfig.maxFixCycles());
    }

    @Test
    @DisplayName("ReviewGateConfig timeoutMs override")
    void reviewGateConfig_timeoutMs_override() {
        ReviewGateConfig.setTimeoutMsForTesting(999L);
        assertEquals(999L, ReviewGateConfig.timeoutMs());

        ReviewGateConfig.clearTimeoutMsForTesting();
        // 默认 120000
        assertEquals(120_000L, ReviewGateConfig.timeoutMs());
    }
}
