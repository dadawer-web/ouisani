package com.ouisani.aios.core.overnight;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.config.AiosPaths;
import com.ouisani.aios.vfs.VectorNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

/**
 * 任务卡片接纳器 — 基于 risk + validation 决定是否将长跑结果接纳为长期记忆。
 * <p>
 * 借鉴 jcode 的结果评估思路，适配 neuron-java 的记忆子系统：
 * <ul>
 *   <li>ACCEPT（completed + validated + LOW/MEDIUM）→ 写入 /var/db/memory VectorNode，
 *       让长跑期间积累的"已验证经验"进入长期记忆，与 CognitiveDreamDaemon 衔接</li>
 *   <li>DEFER（completed + validated + HIGH / blocked）→ 记录 followup，等人工复核</li>
 *   <li>REJECT（未验证 / CRITICAL）→ 记日志，不入记忆</li>
 *   <li>ESCALATE（failed）→ 写 crashDir，触发告警</li>
 *   <li>IGNORE（active / deferred / skipped）→ 不处理</li>
 * </ul>
 * <p>
 * 接纳决策逻辑委托给 {@link OvernightTaskCard#acceptanceLevel()}。
 *
 * @see OvernightTaskCard
 * @see OvernightRunner
 */
public final class OvernightResultAcceptor {

    private static final Logger log = LoggerFactory.getLogger(OvernightResultAcceptor.class);

    /** 接纳决策结果 */
    public enum Decision {
        ACCEPTED, DEFERRED, REJECTED, ESCALATED, IGNORED
    }

    /** 单例 */
    private static final OvernightResultAcceptor INSTANCE = new OvernightResultAcceptor();

    public static OvernightResultAcceptor instance() {
        return INSTANCE;
    }

    private OvernightResultAcceptor() {}

    /**
     * 评估单张卡片并执行对应接纳动作。
     *
     * @param card 任务卡片
     * @return 接纳决策
     */
    public Decision evaluate(OvernightTaskCard card) {
        OvernightTaskCard.AcceptanceLevel level = card.acceptanceLevel();
        return switch (level) {
            case ACCEPT -> acceptToMemory(card);
            case DEFER -> defer(card);
            case REJECT -> reject(card);
            case ESCALATE -> escalate(card);
            case IGNORE -> Decision.IGNORED;
        };
    }

    /**
     * 批量评估卡片并返回摘要。
     */
    public EvaluateResult evaluateAll(List<OvernightTaskCard> cards) {
        int accepted = 0, deferred = 0, rejected = 0, escalated = 0, ignored = 0;
        for (OvernightTaskCard card : cards) {
            switch (evaluate(card)) {
                case ACCEPTED -> accepted++;
                case DEFERRED -> deferred++;
                case REJECTED -> rejected++;
                case ESCALATED -> escalated++;
                case IGNORED -> ignored++;
            }
        }
        OvernightTaskCard.Summary summary = OvernightTaskCard.summarize(cards);
        return new EvaluateResult(accepted, deferred, rejected, escalated, ignored, summary);
    }

    // ── 接纳动作 ──

    /** ACCEPT：写入 /var/db/memory VectorNode（复用 CognitiveDreamDaemon 路径） */
    private Decision acceptToMemory(OvernightTaskCard card) {
        String memoryContent = formatMemoryContent(card);
        String memoryPath = AiosPaths.memoryDbDir();

        try {
            VfsManager vfs = VfsManager.instance();
            var nodeOpt = vfs.resolve(memoryPath);
            if (nodeOpt.isPresent() && nodeOpt.get() instanceof VectorNode vecNode) {
                vecNode.write(memoryContent);
                log.info("[OvernightAcceptor] 卡片已接纳为长期记忆: id={}, title={}",
                        card.id(), card.title());
            } else {
                String fallbackPath = memoryPath + "/overnight_" + card.id() + "_"
                        + System.currentTimeMillis();
                vfs.writeText(fallbackPath, memoryContent);
                log.info("[OvernightAcceptor] 卡片已接纳（回退至文件写入）: id={}, path={}",
                        card.id(), fallbackPath);
            }
            // best-effort 蒸馏为 LEARNED skill（opt-in + deterministic-PASS 硬门，失败不影响接纳）
            try {
                LearnedSkillDistiller.instance().distill(card);
            } catch (Throwable t) {
                log.warn("[OvernightAcceptor] Learned skill 蒸馏失败（不影响接纳）: id={}, error={}",
                        card.id(), t.getMessage());
            }
            return Decision.ACCEPTED;
        } catch (Exception e) {
            log.error("[OvernightAcceptor] 接纳卡片失败: id={}, error={}",
                    card.id(), e.getMessage());
            return Decision.REJECTED;
        }
    }

    /** DEFER：记录 followup，等人工 */
    private Decision defer(OvernightTaskCard card) {
        String followup = "DEFERRED: " + card.id() + " - " + card.title()
                + " (risk=" + card.risk() + ", status=" + card.normalizedStatus() + ")";
        if (card.before() != null && card.before().problem() != null) {
            followup += " | problem: " + card.before().problem();
        }
        log.info("[OvernightAcceptor] 卡片暂缓（需人工）: {}", followup);
        return Decision.DEFERRED;
    }

    /** REJECT：记日志，不入记忆 */
    private Decision reject(OvernightTaskCard card) {
        log.warn("[OvernightAcceptor] 卡片被拒绝: id={}, title={}, risk={}, validated={}",
                card.id(), card.title(), card.risk(), card.isValidated());
        return Decision.REJECTED;
    }

    /** ESCALATE：写 crashDir，触发告警 */
    private Decision escalate(OvernightTaskCard card) {
        String crashDir = AiosPaths.crashDir();
        String crashPath = crashDir + "/overnight_failed_" + card.id() + "_"
                + System.currentTimeMillis() + ".md";
        try {
            String content = "# Overnight Task Failed\n\n"
                    + "- ID: " + card.id() + "\n"
                    + "- Title: " + card.title() + "\n"
                    + "- Risk: " + card.risk() + "\n"
                    + "- Outcome: " + (card.outcome() != null ? card.outcome() : "N/A") + "\n";
            if (card.before() != null) {
                content += "- Problem: " + card.before().problem() + "\n";
            }
            VfsManager.instance().writeText(crashPath, content);
        } catch (Exception e) {
            log.error("[OvernightAcceptor] 写 crashDir 失败: {}", e.getMessage());
        }
        log.error("[OvernightAcceptor] 卡片升级（任务失败）: id={}, title={}",
                card.id(), card.title());
        return Decision.ESCALATED;
    }

    /** 格式化卡片为长期记忆内容 */
    private String formatMemoryContent(OvernightTaskCard card) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Overnight 验证经验\n");
        sb.append("- 任务: ").append(card.title()).append("\n");
        sb.append("- 风险: ").append(card.risk()).append("\n");
        sb.append("- 来源: ").append(card.source() != null ? card.source() : "overnight").append("\n");
        if (card.before() != null && card.before().problem() != null) {
            sb.append("- 问题: ").append(card.before().problem()).append("\n");
        }
        if (card.after() != null) {
            if (card.after().change() != null) {
                sb.append("- 变更: ").append(card.after().change()).append("\n");
            }
            if (card.after().filesChanged() != null && !card.after().filesChanged().isEmpty()) {
                sb.append("- 文件: ").append(String.join(", ", card.after().filesChanged())).append("\n");
            }
        }
        if (card.validation() != null && card.validation().result() != null) {
            sb.append("- 验证: ").append(card.validation().result()).append("\n");
        }
        if (card.outcome() != null) {
            sb.append("- 结果: ").append(card.outcome()).append("\n");
        }
        sb.append("- 时间: ").append(Instant.now()).append("\n");
        return sb.toString();
    }

    /** 批量评估结果 */
    public record EvaluateResult(
            int accepted,
            int deferred,
            int rejected,
            int escalated,
            int ignored,
            OvernightTaskCard.Summary summary
    ) {
        @Override
        public String toString() {
            return "EvaluateResult{accepted=%d, deferred=%d, rejected=%d, escalated=%d, ignored=%d, %s}"
                    .formatted(accepted, deferred, rejected, escalated, ignored, summary);
        }
    }
}
