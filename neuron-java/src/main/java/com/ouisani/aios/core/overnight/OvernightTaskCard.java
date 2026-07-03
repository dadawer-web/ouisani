package com.ouisani.aios.core.overnight;

import java.util.List;

/**
 * Overnight 任务卡片 — 长跑期间每个有意义任务的结构化追踪记录。
 * <p>
 * 镜像 jcode 的 OvernightTaskCard JSON schema（lib.rs:203-233）。每张卡片
 * 记录任务的 Before（问题+证据）、After（变更+文件+证据）、Validation（命令+结果+证据）、
 * 风险等级、状态、来源等。{@link OvernightResultAcceptor} 根据 risk + validation
 * 决定是否将结果接纳为长期记忆。
 * <p>
 * 状态通过 {@link #normalizeStatus(String)} 归一化，兼容 LLM 输出的自由文本
 * （done/complete/fixed/validated/merged 均归一为 COMPLETED，以此类推）。
 *
 * @see OvernightResultAcceptor
 * @see OvernightRunner
 */
public record OvernightTaskCard(
        String id,
        String title,
        String status,
        String priority,
        String source,
        String whySelected,
        String verifiability,
        RiskLevel risk,
        String outcome,
        Before before,
        After after,
        Validation validation,
        List<String> followups,
        String updatedAt
) {

    /** 风险等级 — 驱动接纳决策 */
    public enum RiskLevel {
        LOW, MEDIUM, HIGH, CRITICAL;

        public static RiskLevel fromString(String raw) {
            if (raw == null) return MEDIUM;
            String lower = raw.trim().toLowerCase();
            return switch (lower) {
                case "low" -> LOW;
                case "medium", "moderate" -> MEDIUM;
                case "high" -> HIGH;
                case "critical", "severe", "blocker" -> CRITICAL;
                default -> MEDIUM;
            };
        }
    }

    /** 归一化后的卡片状态 — 镜像 jcode task_status_bucket() */
    public enum CardStatus {
        COMPLETED, ACTIVE, BLOCKED, DEFERRED, FAILED, SKIPPED, UNKNOWN
    }

    /** 接纳级别 — 派生自 status + risk + validated */
    public enum AcceptanceLevel {
        /** 接纳：写入长期记忆 */
        ACCEPT,
        /** 暂缓：记录 followup，等人工 */
        DEFER,
        /** 拒绝：记日志，不入记忆 */
        REJECT,
        /** 升级：写 crashDir，触发告警 */
        ESCALATE,
        /** 忽略 */
        IGNORE
    }

    /** Before 段 — 任务开始前的问题与证据 */
    public record Before(String problem, List<String> evidence) {}

    /** After 段 — 任务完成后的变更与证据 */
    public record After(String change, List<String> filesChanged, List<String> evidence) {}

    /** Validation 段 — 验证命令、结果与证据 */
    public record Validation(List<String> commands, String result, List<String> evidence) {}

    /**
     * 归一化状态 — 镜像 jcode task_status_bucket()。
     * <p>
     * 将 LLM 输出的自由文本状态归一为标准枚举：
     * <ul>
     *   <li>done/complete/fixed/validated/merged/pass/passed/success → COMPLETED</li>
     *   <li>active/running/in_progress/working → ACTIVE</li>
     *   <li>blocked/needs_user/waiting/paused → BLOCKED</li>
     *   <li>deferred/queued/pending → DEFERRED</li>
     *   <li>failed/error/aborted → FAILED</li>
     *   <li>skipped/rejected/ignored → SKIPPED</li>
     *   <li>空或未知 → UNKNOWN</li>
     * </ul>
     */
    public static CardStatus normalizeStatus(String raw) {
        if (raw == null || raw.isBlank()) return CardStatus.UNKNOWN;
        String s = raw.trim().toLowerCase().replace('-', '_').replace(' ', '_');
        return switch (s) {
            case "done", "complete", "completed", "fixed", "validated", "merged",
                 "pass", "passed", "success", "successful", "resolved", "closed" -> CardStatus.COMPLETED;
            case "active", "running", "in_progress", "working", "started", "ongoing" -> CardStatus.ACTIVE;
            case "blocked", "needs_user", "waiting", "paused", "stalled", "needs_input" -> CardStatus.BLOCKED;
            case "deferred", "queued", "pending", "postponed", "later" -> CardStatus.DEFERRED;
            case "failed", "error", "aborted", "crashed", "broken" -> CardStatus.FAILED;
            case "skipped", "rejected", "ignored", "dropped", "cancelled", "canceled" -> CardStatus.SKIPPED;
            default -> {
                if (s.contains("complet") || s.contains("done") || s.contains("fix")
                        || s.contains("valid") || s.contains("pass") || s.contains("success")) {
                    yield CardStatus.COMPLETED;
                }
                if (s.contains("block") || s.contains("need") || s.contains("wait")) {
                    yield CardStatus.BLOCKED;
                }
                if (s.contains("fail") || s.contains("error") || s.contains("abort")) {
                    yield CardStatus.FAILED;
                }
                if (s.contains("defer") || s.contains("queue") || s.contains("pend")) {
                    yield CardStatus.DEFERRED;
                }
                if (s.contains("skip") || s.contains("reject") || s.contains("cancel")) {
                    yield CardStatus.SKIPPED;
                }
                if (s.contains("activ") || s.contains("run") || s.contains("progress")) {
                    yield CardStatus.ACTIVE;
                }
                yield CardStatus.UNKNOWN;
            }
        };
    }

    /**
     * 是否已通过验证 — 镜像 jcode task_card_validated()。
     * <p>
     * 当 validation.result 包含 pass/success/ok（不区分大小写）时视为已验证。
     */
    public boolean isValidated() {
        if (validation == null || validation.result() == null || validation.result().isBlank()) {
            return false;
        }
        String r = validation.result().toLowerCase();
        return r.contains("pass") || r.contains("success") || r.contains("ok")
                || r.contains("verified") || r.contains("confirmed");
    }

    /**
     * 派生接纳级别 — 综合考虑 status + risk + validated。
     * <p>
     * 决策矩阵：
     * <ul>
     *   <li>CRITICAL 风险（任何状态）→ REJECT</li>
     *   <li>COMPLETED + validated + LOW/MEDIUM → ACCEPT</li>
     *   <li>COMPLETED + validated + HIGH → DEFER（需人工复核）</li>
     *   <li>COMPLETED + 未 validated → REJECT（证据不足）</li>
     *   <li>BLOCKED → DEFER（记录 needs_user）</li>
     *   <li>FAILED → ESCALATE（写 crashDir）</li>
     *   <li>其余 → IGNORE</li>
     * </ul>
     */
    public AcceptanceLevel acceptanceLevel() {
        if (risk == RiskLevel.CRITICAL) {
            return AcceptanceLevel.REJECT;
        }
        CardStatus s = normalizeStatus(status);
        return switch (s) {
            case COMPLETED -> switch (risk) {
                case LOW, MEDIUM -> isValidated() ? AcceptanceLevel.ACCEPT : AcceptanceLevel.REJECT;
                case HIGH -> isValidated() ? AcceptanceLevel.DEFER : AcceptanceLevel.REJECT;
                case CRITICAL -> AcceptanceLevel.REJECT;
            };
            case BLOCKED -> AcceptanceLevel.DEFER;
            case FAILED -> AcceptanceLevel.ESCALATE;
            case ACTIVE, DEFERRED, SKIPPED, UNKNOWN -> AcceptanceLevel.IGNORE;
        };
    }

    /** 归一化状态（便捷方法） */
    public CardStatus normalizedStatus() {
        return normalizeStatus(status);
    }

    /**
     * 卡片聚合摘要 — 镜像 jcode OvernightTaskCardSummary。
     */
    public record Summary(
            int total,
            int completed,
            int active,
            int blocked,
            int deferred,
            int failed,
            int skipped,
            int validated,
            int highRisk,
            int accepted
    ) {
        @Override
        public String toString() {
            return "Summary{total=%d, completed=%d, active=%d, blocked=%d, deferred=%d, "
                    .formatted(total, completed, active, blocked, deferred)
                    + "failed=%d, skipped=%d, validated=%d, highRisk=%d, accepted=%d}"
                    .formatted(failed, skipped, validated, highRisk, accepted);
        }
    }

    /**
     * 聚合一批卡片为摘要。
     */
    public static Summary summarize(List<OvernightTaskCard> cards) {
        int completed = 0, active = 0, blocked = 0, deferred = 0, failed = 0, skipped = 0;
        int validated = 0, highRisk = 0, accepted = 0;
        for (OvernightTaskCard c : cards) {
            CardStatus s = c.normalizedStatus();
            switch (s) {
                case COMPLETED -> completed++;
                case ACTIVE -> active++;
                case BLOCKED -> blocked++;
                case DEFERRED -> deferred++;
                case FAILED -> failed++;
                case SKIPPED -> skipped++;
                case UNKNOWN -> {}
            }
            if (c.isValidated()) validated++;
            if (c.risk() == RiskLevel.HIGH || c.risk() == RiskLevel.CRITICAL) highRisk++;
            if (c.acceptanceLevel() == AcceptanceLevel.ACCEPT) accepted++;
        }
        return new Summary(cards.size(), completed, active, blocked, deferred,
                failed, skipped, validated, highRisk, accepted);
    }
}
