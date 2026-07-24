package com.ouisani.aios.core.skill;

import java.util.List;

/**
 * 内置 MetaSkill 工厂 — 提供项目自带的 meta-skill 定义。
 * <p>
 * 当前仅 {@link #ai4sAgent()} 一个，对应
 * {@code resources/skills/ai4s-agent/SKILL.md} 中 prose-only 描述的链。
 * R2 将其提升为可执行的代码规格。
 * <p>
 * 与 {@code ScienceMcpBootstrap}（注册 MCP）模式对齐：
 * 核心层提供声明式工厂，由 user 层在启动时调用注册。
 */
public final class MetaSkills {

    private MetaSkills() {}

    /**
     * AI4S Agent — 端到端科研流水线 meta-skill。
     * <p>
     * 链：{@code research-explorer → literature-survey → experiment-suite → paper-writer}
     * <p>
     * 与 {@code skills/ai4s-agent/SKILL.md} 的 prose 描述完全对齐：
     * <ul>
     *   <li>research-explorer 是 optional（用户直接给 topic 时可跳过）</li>
     *   <li>每步骤输出到 {@code /output/ai4s-agent/<slug>/<step>/output.md}</li>
     *   <li>后续步骤可引用前一步骤的输出路径 {@code ${prev.outputPath}}</li>
     * </ul>
     */
    public static MetaSkill ai4sAgent() {
        return new MetaSkill(
                "ai4s-agent",
                "End-to-end AI4S research pipeline — meta-skill that chains "
                        + "research-explorer → literature-survey → experiment-suite → paper-writer",
                List.of(
                        new MetaSkill.SkillStep(
                                "research-explorer",
                                "direction: ${input}",
                                "research-explorer",
                                true,
                                "Explore broad direction → produce topic matrix"
                        ),
                        new MetaSkill.SkillStep(
                                "literature-survey",
                                "topic: ${input}\nprev_output_path: ${prev.outputPath}",
                                "literature-survey",
                                false,
                                "6-20pp survey with 60+ real citations"
                        ),
                        new MetaSkill.SkillStep(
                                "experiment-suite",
                                "topic: ${input}\nliterature_path: ${prev.outputPath}",
                                "experiment-suite",
                                false,
                                "Reproducible experiment package (design + code + results + figures)"
                        ),
                        new MetaSkill.SkillStep(
                                "paper-writer",
                                "topic: ${input}\nexperiment_path: ${prev.outputPath}",
                                "paper-writer",
                                false,
                                "8-14pp research paper with 200+ citations"
                        )
                ),
                "/output/ai4s-agent"
        );
    }
}
