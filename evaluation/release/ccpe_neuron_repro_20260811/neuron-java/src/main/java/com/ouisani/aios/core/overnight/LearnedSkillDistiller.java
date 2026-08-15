package com.ouisani.aios.core.overnight;

import com.ouisani.aios.core.config.AiosPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Learned Skill 蒸馏器 — 把 Overnight runner 跑通的 deterministic-PASS 任务蒸馏成新 SKILL.md。
 * <p>
 * 借鉴 OpenScience 的 RSITrajectory.pipeline（fire-and-forget RSI），做成<b>带验证的版本</b>：
 * 严格 deterministic-PASS 硬门（{@link NodeCompletionVerifier#verify}==PASS，不接受 LLM 回退）
 * + ACCEPT 级别才蒸馏。蒸馏出的 SKILL.md 落 {@link AiosPaths#learnedSkillsDir}（真实 FS），
 * 下次 {@code SkillLoader.loadAll} 自动拾取为 LEARNED 源（懒加载）。
 * <p>
 * opt-in：环境变量 {@code AIOS_LEARNED_SKILLS=on} 默认关。best-effort：永不抛异常，
 * 不影响 {@link OvernightResultAcceptor#acceptToMemory} 的接纳主流程（同 ReviewGate 原则）。
 *
 * @see OvernightResultAcceptor
 * @see NodeCompletionVerifier
 */
public final class LearnedSkillDistiller {

    private static final Logger log = LoggerFactory.getLogger(LearnedSkillDistiller.class);
    private static final LearnedSkillDistiller INSTANCE = new LearnedSkillDistiller();

    /** 测试覆盖入口 — env 在测试中不可改，用此 override（同 ReviewGateConfig 范式）。null=走 env。 */
    private static volatile Boolean enabledOverride = null;

    public static LearnedSkillDistiller instance() { return INSTANCE; }
    private LearnedSkillDistiller() {}

    public static void setEnabledForTesting(Boolean override) {
        enabledOverride = override;
    }

    /** 是否启用蒸馏 — env {@code AIOS_LEARNED_SKILLS} ∈ {on,1,true}（默认关）；测试可 override。 */
    public boolean isEnabled() {
        if (enabledOverride != null) return enabledOverride;
        String env = System.getenv("AIOS_LEARNED_SKILLS");
        if (env == null) return false;
        String s = env.trim().toLowerCase();
        return s.equals("on") || s.equals("1") || s.equals("true");
    }

    /**
     * 蒸馏单张任务卡片为 LEARNED skill — best-effort 永不抛。
     * <p>
     * 门控三连：{@code isEnabled()} && {@code isDeterministicPass(card)} &&
     * {@code card.acceptanceLevel()==ACCEPT}。通过则 slugify(title)+id 哈希派生技能名
     * （同卡重跑同名覆盖，幂等）→ 格式化 SKILL.md → 真实 FS 直写。
     *
     * @return true 表示已蒸馏写出 SKILL.md；false 表示门控未通过或失败
     */
    public boolean distill(OvernightTaskCard card) {
        try {
            if (!isEnabled()) return false;
            if (!isDeterministicPass(card)) return false;
            if (card.acceptanceLevel() != OvernightTaskCard.AcceptanceLevel.ACCEPT) return false;

            String name = deriveName(card);
            String skillMd = formatSkillMd(card, name);
            Path skillDir = Path.of(AiosPaths.learnedSkillsDir(), name);
            Files.createDirectories(skillDir);
            Path skillMdPath = skillDir.resolve("SKILL.md");
            Files.writeString(skillMdPath, skillMd, StandardCharsets.UTF_8);
            log.info("[LearnedSkillDistiller] 蒸馏技能: name={}, card={}", name, card.id());
            return true;
        } catch (Throwable t) {
            log.warn("[LearnedSkillDistiller] 蒸馏失败（best-effort，不影响接纳）: card={}, error={}",
                    card.id(), t.getMessage());
            return false;
        }
    }

    /**
     * 严格 deterministic-PASS 硬门 — 只认 {@link NodeCompletionVerifier} 的 PASS 判定，
     * 不接受 {@link OvernightTaskCard#isValidated()} 的 LLM 回退路径（即"带验证的版本"）。
     */
    public boolean isDeterministicPass(OvernightTaskCard card) {
        if (card.deterministicChecks() == null || card.deterministicChecks().isEmpty()) return false;
        VerificationResult result = NodeCompletionVerifier.instance().verify(card.deterministicChecks());
        return result.verdict() == VerificationResult.Verdict.PASS;
    }

    /** 派生技能名 — slugify(title) + id 哈希后缀，保证同卡幂等（重跑同名覆盖）。 */
    private static String deriveName(OvernightTaskCard card) {
        String base = slugify(card.title());
        String hash = String.format("%06x", card.id().hashCode() & 0xffffff);
        return base + "-" + hash;
    }

    private static String slugify(String s) {
        if (s == null || s.isBlank()) return "learned";
        return s.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    /** 从变更文件后缀派生 when_to_use glob（如 *.java,*.ts）。 */
    private static String deriveWhenToUse(OvernightTaskCard card) {
        if (card.after() == null || card.after().filesChanged() == null
                || card.after().filesChanged().isEmpty()) return "";
        Set<String> exts = new LinkedHashSet<>();
        for (String f : card.after().filesChanged()) {
            int dot = f.lastIndexOf('.');
            if (dot >= 0 && dot < f.length() - 1) {
                exts.add("*." + f.substring(dot + 1));
            }
        }
        return String.join(",", exts);
    }

    /** 格式化 SKILL.md — frontmatter（name/description/category:learned/when_to_use）+ 蒸馏 body。 */
    private static String formatSkillMd(OvernightTaskCard card, String name) {
        String title = card.title() != null && !card.title().isBlank() ? card.title() : "Learned Skill";
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(name).append("\n");
        sb.append("description: ").append(title).append("\n");
        sb.append("category: learned\n");
        String whenToUse = deriveWhenToUse(card);
        if (!whenToUse.isEmpty()) {
            sb.append("when_to_use: ").append(whenToUse).append("\n");
        }
        sb.append("---\n\n");

        sb.append("# ").append(title).append("\n\n");
        sb.append("> 蒸馏自 Overnight deterministic-PASS 任务 `").append(card.id()).append("`。\n\n");

        if (card.before() != null && card.before().problem() != null && !card.before().problem().isBlank()) {
            sb.append("## Problem\n").append(card.before().problem()).append("\n\n");
        }
        if (card.after() != null) {
            if (card.after().change() != null && !card.after().change().isBlank()) {
                sb.append("## Change\n").append(card.after().change()).append("\n\n");
            }
            if (card.after().filesChanged() != null && !card.after().filesChanged().isEmpty()) {
                sb.append("## Files Changed\n");
                for (String f : card.after().filesChanged()) {
                    sb.append("- ").append(f).append("\n");
                }
                sb.append("\n");
            }
        }
        if (card.validation() != null && card.validation().result() != null
                && !card.validation().result().isBlank()) {
            sb.append("## Validation\n").append(card.validation().result()).append("\n\n");
        }
        if (card.outcome() != null && !card.outcome().isBlank()) {
            sb.append("## Outcome\n").append(card.outcome()).append("\n\n");
        }
        return sb.toString();
    }
}
