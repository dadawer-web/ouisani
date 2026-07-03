package com.ouisani.aios.core.overnight;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OvernightTaskCard 单元测试 — 验证状态归一化、验证判定、接纳级别矩阵。
 */
class OvernightTaskCardTest {

    // ════════════════════════════════════════════════════════════════
    //  normalizeStatus 测试
    // ════════════════════════════════════════════════════════════════

    @Nested
    class NormalizeStatusTest {

        @Test
        void shouldNormalizeCompletedVariants() {
            assertEquals(OvernightTaskCard.CardStatus.COMPLETED,
                    OvernightTaskCard.normalizeStatus("done"));
            assertEquals(OvernightTaskCard.CardStatus.COMPLETED,
                    OvernightTaskCard.normalizeStatus("complete"));
            assertEquals(OvernightTaskCard.CardStatus.COMPLETED,
                    OvernightTaskCard.normalizeStatus("completed"));
            assertEquals(OvernightTaskCard.CardStatus.COMPLETED,
                    OvernightTaskCard.normalizeStatus("fixed"));
            assertEquals(OvernightTaskCard.CardStatus.COMPLETED,
                    OvernightTaskCard.normalizeStatus("validated"));
            assertEquals(OvernightTaskCard.CardStatus.COMPLETED,
                    OvernightTaskCard.normalizeStatus("merged"));
            assertEquals(OvernightTaskCard.CardStatus.COMPLETED,
                    OvernightTaskCard.normalizeStatus("pass"));
            assertEquals(OvernightTaskCard.CardStatus.COMPLETED,
                    OvernightTaskCard.normalizeStatus("success"));
            assertEquals(OvernightTaskCard.CardStatus.COMPLETED,
                    OvernightTaskCard.normalizeStatus("resolved"));
        }

        @Test
        void shouldNormalizeActiveVariants() {
            assertEquals(OvernightTaskCard.CardStatus.ACTIVE,
                    OvernightTaskCard.normalizeStatus("active"));
            assertEquals(OvernightTaskCard.CardStatus.ACTIVE,
                    OvernightTaskCard.normalizeStatus("running"));
            assertEquals(OvernightTaskCard.CardStatus.ACTIVE,
                    OvernightTaskCard.normalizeStatus("in_progress"));
            assertEquals(OvernightTaskCard.CardStatus.ACTIVE,
                    OvernightTaskCard.normalizeStatus("in-progress"));
            assertEquals(OvernightTaskCard.CardStatus.ACTIVE,
                    OvernightTaskCard.normalizeStatus("working"));
        }

        @Test
        void shouldNormalizeBlockedVariants() {
            assertEquals(OvernightTaskCard.CardStatus.BLOCKED,
                    OvernightTaskCard.normalizeStatus("blocked"));
            assertEquals(OvernightTaskCard.CardStatus.BLOCKED,
                    OvernightTaskCard.normalizeStatus("needs_user"));
            assertEquals(OvernightTaskCard.CardStatus.BLOCKED,
                    OvernightTaskCard.normalizeStatus("needs user"));
            assertEquals(OvernightTaskCard.CardStatus.BLOCKED,
                    OvernightTaskCard.normalizeStatus("waiting"));
            assertEquals(OvernightTaskCard.CardStatus.BLOCKED,
                    OvernightTaskCard.normalizeStatus("paused"));
        }

        @Test
        void shouldNormalizeDeferredVariants() {
            assertEquals(OvernightTaskCard.CardStatus.DEFERRED,
                    OvernightTaskCard.normalizeStatus("deferred"));
            assertEquals(OvernightTaskCard.CardStatus.DEFERRED,
                    OvernightTaskCard.normalizeStatus("queued"));
            assertEquals(OvernightTaskCard.CardStatus.DEFERRED,
                    OvernightTaskCard.normalizeStatus("pending"));
            assertEquals(OvernightTaskCard.CardStatus.DEFERRED,
                    OvernightTaskCard.normalizeStatus("postponed"));
        }

        @Test
        void shouldNormalizeFailedVariants() {
            assertEquals(OvernightTaskCard.CardStatus.FAILED,
                    OvernightTaskCard.normalizeStatus("failed"));
            assertEquals(OvernightTaskCard.CardStatus.FAILED,
                    OvernightTaskCard.normalizeStatus("error"));
            assertEquals(OvernightTaskCard.CardStatus.FAILED,
                    OvernightTaskCard.normalizeStatus("aborted"));
            assertEquals(OvernightTaskCard.CardStatus.FAILED,
                    OvernightTaskCard.normalizeStatus("crashed"));
        }

        @Test
        void shouldNormalizeSkippedVariants() {
            assertEquals(OvernightTaskCard.CardStatus.SKIPPED,
                    OvernightTaskCard.normalizeStatus("skipped"));
            assertEquals(OvernightTaskCard.CardStatus.SKIPPED,
                    OvernightTaskCard.normalizeStatus("rejected"));
            assertEquals(OvernightTaskCard.CardStatus.SKIPPED,
                    OvernightTaskCard.normalizeStatus("ignored"));
            assertEquals(OvernightTaskCard.CardStatus.SKIPPED,
                    OvernightTaskCard.normalizeStatus("cancelled"));
            assertEquals(OvernightTaskCard.CardStatus.SKIPPED,
                    OvernightTaskCard.normalizeStatus("canceled"));
        }

        @Test
        void shouldHandleCaseInsensitive() {
            assertEquals(OvernightTaskCard.CardStatus.COMPLETED,
                    OvernightTaskCard.normalizeStatus("DONE"));
            assertEquals(OvernightTaskCard.CardStatus.COMPLETED,
                    OvernightTaskCard.normalizeStatus("Complete"));
            assertEquals(OvernightTaskCard.CardStatus.ACTIVE,
                    OvernightTaskCard.normalizeStatus("RUNNING"));
        }

        @Test
        void shouldReturnUnknownForNullOrEmpty() {
            assertEquals(OvernightTaskCard.CardStatus.UNKNOWN,
                    OvernightTaskCard.normalizeStatus(null));
            assertEquals(OvernightTaskCard.CardStatus.UNKNOWN,
                    OvernightTaskCard.normalizeStatus(""));
            assertEquals(OvernightTaskCard.CardStatus.UNKNOWN,
                    OvernightTaskCard.normalizeStatus("   "));
        }

        @Test
        void shouldReturnUnknownForUnrecognized() {
            assertEquals(OvernightTaskCard.CardStatus.UNKNOWN,
                    OvernightTaskCard.normalizeStatus("xyzzy"));
            assertEquals(OvernightTaskCard.CardStatus.UNKNOWN,
                    OvernightTaskCard.normalizeStatus("frobnicate"));
        }

        @Test
        void shouldHandleFuzzyMatching() {
            assertEquals(OvernightTaskCard.CardStatus.COMPLETED,
                    OvernightTaskCard.normalizeStatus("completion"));
            assertEquals(OvernightTaskCard.CardStatus.FAILED,
                    OvernightTaskCard.normalizeStatus("failure"));
            assertEquals(OvernightTaskCard.CardStatus.BLOCKED,
                    OvernightTaskCard.normalizeStatus("blocking"));
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  isValidated 测试
    // ════════════════════════════════════════════════════════════════

    @Nested
    class IsValidatedTest {

        @Test
        void shouldReturnTrueWhenResultContainsPass() {
            OvernightTaskCard card = buildCard("completed", "LOW", "all tests pass");
            assertTrue(card.isValidated());
        }

        @Test
        void shouldReturnTrueWhenResultContainsSuccess() {
            OvernightTaskCard card = buildCard("completed", "LOW", "build success");
            assertTrue(card.isValidated());
        }

        @Test
        void shouldReturnTrueWhenResultContainsOk() {
            OvernightTaskCard card = buildCard("completed", "LOW", "ok");
            assertTrue(card.isValidated());
        }

        @Test
        void shouldReturnTrueWhenResultContainsVerified() {
            OvernightTaskCard card = buildCard("completed", "LOW", "verified by CI");
            assertTrue(card.isValidated());
        }

        @Test
        void shouldReturnTrueWhenResultContainsConfirmed() {
            OvernightTaskCard card = buildCard("completed", "LOW", "confirmed working");
            assertTrue(card.isValidated());
        }

        @Test
        void shouldReturnFalseWhenResultIsEmpty() {
            OvernightTaskCard card = buildCard("completed", "LOW", "");
            assertFalse(card.isValidated());
        }

        @Test
        void shouldReturnFalseWhenResultIsNull() {
            OvernightTaskCard card = buildCard("completed", "LOW", null);
            assertFalse(card.isValidated());
        }

        @Test
        void shouldReturnFalseWhenValidationIsNull() {
            OvernightTaskCard card = new OvernightTaskCard(
                    "id", "title", "completed", "high", "src", "why", "verifiable",
                    OvernightTaskCard.RiskLevel.LOW, "outcome",
                    null, null, null, null, "2026-01-01T00:00:00Z");
            assertFalse(card.isValidated());
        }

        @Test
        void shouldBeCaseInsensitive() {
            OvernightTaskCard card = buildCard("completed", "LOW", "PASS");
            assertTrue(card.isValidated());

            OvernightTaskCard card2 = buildCard("completed", "LOW", "SUCCESS");
            assertTrue(card2.isValidated());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  acceptanceLevel 矩阵测试
    // ════════════════════════════════════════════════════════════════

    @Nested
    class AcceptanceLevelTest {

        @Test
        void completedValidatedLow_shouldAccept() {
            OvernightTaskCard card = buildCard("completed", "LOW", "tests pass");
            assertEquals(OvernightTaskCard.AcceptanceLevel.ACCEPT, card.acceptanceLevel());
        }

        @Test
        void completedValidatedMedium_shouldAccept() {
            OvernightTaskCard card = buildCard("completed", "MEDIUM", "tests pass");
            assertEquals(OvernightTaskCard.AcceptanceLevel.ACCEPT, card.acceptanceLevel());
        }

        @Test
        void completedValidatedHigh_shouldDefer() {
            OvernightTaskCard card = buildCard("completed", "HIGH", "tests pass");
            assertEquals(OvernightTaskCard.AcceptanceLevel.DEFER, card.acceptanceLevel());
        }

        @Test
        void completedUnvalidatedLow_shouldReject() {
            OvernightTaskCard card = buildCard("completed", "LOW", "");
            assertEquals(OvernightTaskCard.AcceptanceLevel.REJECT, card.acceptanceLevel());
        }

        @Test
        void completedUnvalidatedHigh_shouldReject() {
            OvernightTaskCard card = buildCard("completed", "HIGH", "");
            assertEquals(OvernightTaskCard.AcceptanceLevel.REJECT, card.acceptanceLevel());
        }

        @Test
        void criticalRisk_shouldAlwaysReject() {
            assertEquals(OvernightTaskCard.AcceptanceLevel.REJECT,
                    buildCard("completed", "CRITICAL", "tests pass").acceptanceLevel());
            assertEquals(OvernightTaskCard.AcceptanceLevel.REJECT,
                    buildCard("failed", "CRITICAL", "").acceptanceLevel());
            assertEquals(OvernightTaskCard.AcceptanceLevel.REJECT,
                    buildCard("active", "CRITICAL", "pass").acceptanceLevel());
        }

        @Test
        void blocked_shouldDefer() {
            OvernightTaskCard card = buildCard("blocked", "LOW", "");
            assertEquals(OvernightTaskCard.AcceptanceLevel.DEFER, card.acceptanceLevel());

            OvernightTaskCard card2 = buildCard("needs_user", "HIGH", "");
            assertEquals(OvernightTaskCard.AcceptanceLevel.DEFER, card2.acceptanceLevel());
        }

        @Test
        void failed_shouldEscalate() {
            OvernightTaskCard card = buildCard("failed", "LOW", "");
            assertEquals(OvernightTaskCard.AcceptanceLevel.ESCALATE, card.acceptanceLevel());

            OvernightTaskCard card2 = buildCard("error", "MEDIUM", "");
            assertEquals(OvernightTaskCard.AcceptanceLevel.ESCALATE, card2.acceptanceLevel());
        }

        @Test
        void active_shouldIgnore() {
            OvernightTaskCard card = buildCard("active", "LOW", "");
            assertEquals(OvernightTaskCard.AcceptanceLevel.IGNORE, card.acceptanceLevel());
        }

        @Test
        void deferred_shouldIgnore() {
            OvernightTaskCard card = buildCard("deferred", "LOW", "");
            assertEquals(OvernightTaskCard.AcceptanceLevel.IGNORE, card.acceptanceLevel());
        }

        @Test
        void skipped_shouldIgnore() {
            OvernightTaskCard card = buildCard("skipped", "LOW", "");
            assertEquals(OvernightTaskCard.AcceptanceLevel.IGNORE, card.acceptanceLevel());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  summarize 测试
    // ════════════════════════════════════════════════════════════════

    @Nested
    class SummarizeTest {

        @Test
        void shouldSummarizeCorrectly() {
            List<OvernightTaskCard> cards = List.of(
                    buildCard("completed", "LOW", "pass"),        // completed, validated, accepted
                    buildCard("completed", "MEDIUM", "pass"),      // completed, validated, accepted
                    buildCard("completed", "HIGH", "pass"),        // completed, validated, deferred
                    buildCard("completed", "LOW", ""),             // completed, unvalidated, rejected
                    buildCard("active", "LOW", ""),               // active
                    buildCard("blocked", "MEDIUM", ""),            // blocked, deferred
                    buildCard("failed", "LOW", ""),                // failed, escalated
                    buildCard("skipped", "LOW", ""),               // skipped
                    buildCard("deferred", "HIGH", "pass"),         // deferred (highRisk)
                    buildCard("completed", "CRITICAL", "pass")     // completed, critical, rejected
            );

            OvernightTaskCard.Summary summary = OvernightTaskCard.summarize(cards);

            assertEquals(10, summary.total());
            assertEquals(5, summary.completed());   // 5 cards with completed-status
            assertEquals(1, summary.active());
            assertEquals(1, summary.blocked());
            assertEquals(1, summary.deferred());
            assertEquals(1, summary.failed());
            assertEquals(1, summary.skipped());
            assertEquals(5, summary.validated());     // 5 cards with pass in validation
            assertEquals(3, summary.highRisk());      // HIGH(2) + CRITICAL(1)
            assertEquals(2, summary.accepted());       // 2 ACCEPT (completed+validated+LOW/MEDIUM)
        }

        @Test
        void shouldHandleEmptyList() {
            OvernightTaskCard.Summary summary = OvernightTaskCard.summarize(List.of());
            assertEquals(0, summary.total());
            assertEquals(0, summary.completed());
            assertEquals(0, summary.accepted());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  RiskLevel.fromString 测试
    // ════════════════════════════════════════════════════════════════

    @Test
    void riskLevelFromString_shouldParseKnownValues() {
        assertEquals(OvernightTaskCard.RiskLevel.LOW,
                OvernightTaskCard.RiskLevel.fromString("low"));
        assertEquals(OvernightTaskCard.RiskLevel.MEDIUM,
                OvernightTaskCard.RiskLevel.fromString("medium"));
        assertEquals(OvernightTaskCard.RiskLevel.MEDIUM,
                OvernightTaskCard.RiskLevel.fromString("moderate"));
        assertEquals(OvernightTaskCard.RiskLevel.HIGH,
                OvernightTaskCard.RiskLevel.fromString("high"));
        assertEquals(OvernightTaskCard.RiskLevel.CRITICAL,
                OvernightTaskCard.RiskLevel.fromString("critical"));
        assertEquals(OvernightTaskCard.RiskLevel.CRITICAL,
                OvernightTaskCard.RiskLevel.fromString("blocker"));
    }

    @Test
    void riskLevelFromString_shouldDefaultToMediumForUnknown() {
        assertEquals(OvernightTaskCard.RiskLevel.MEDIUM,
                OvernightTaskCard.RiskLevel.fromString("unknown"));
        assertEquals(OvernightTaskCard.RiskLevel.MEDIUM,
                OvernightTaskCard.RiskLevel.fromString(null));
    }

    // ════════════════════════════════════════════════════════════════
    //  辅助方法
    // ════════════════════════════════════════════════════════════════

    /** 构建测试用卡片 */
    private static OvernightTaskCard buildCard(String status, String risk, String validationResult) {
        OvernightTaskCard.RiskLevel riskLevel = OvernightTaskCard.RiskLevel.fromString(risk);
        OvernightTaskCard.Validation validation = validationResult != null
                ? new OvernightTaskCard.Validation(null, validationResult, null)
                : null;
        return new OvernightTaskCard(
                "card-" + status + "-" + risk,
                "Test task: " + status + " / " + risk,
                status,
                "high",
                "test",
                "verifiable",
                "verifiable",
                riskLevel,
                "outcome for " + status,
                new OvernightTaskCard.Before("problem description", null),
                new OvernightTaskCard.After("change description", null, null),
                validation,
                List.of("followup1"),
                "2026-01-01T00:00:00Z"
        );
    }
}
