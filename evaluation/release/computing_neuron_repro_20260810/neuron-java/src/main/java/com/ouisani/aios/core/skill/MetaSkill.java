package com.ouisani.aios.core.skill;

import java.util.List;
import java.util.Map;

/**
 * Meta-skill 定义 — 编排多个 specialist skill 的顺序链。
 * <p>
 * 与 SKILL.md 中的 prose-only meta-skill（如 ai4s-agent/SKILL.md 用自然语言描述
 * "research-explorer → literature-survey → experiment-suite → paper-writer"）不同，
 * MetaSkill 是<b>可执行的编排规格</b>：声明步骤顺序、参数模板、输出目录约定。
 * 由 {@link SkillChain} 实际执行。
 * <p>
 * 与 blueprints/auto_medic 的 BLUEPRINT.md 模式对齐：
 * <b>声明式 frontmatter + Java 实现分离</b>。MetaSkill 是声明，SkillChain 是实现。
 * <p>
 * OS 类比：相当于 Linux 的 init 运行级脚本 — 按顺序拉起一组服务，
 * 每个服务（specialist skill）独立可用，也可被组合编排。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * MetaSkill ai4s = new MetaSkill(
 *     "ai4s-agent",
 *     "End-to-end AI4S research pipeline",
 *     List.of(
 *         new MetaSkill.SkillStep("research-explorer",
 *             "direction: ${input}", "research-explorer", true, "explore direction"),
 *         new MetaSkill.SkillStep("literature-survey",
 *             "topic: ${input}", "literature-survey", false, "survey literature"),
 *         new MetaSkill.SkillStep("experiment-suite",
 *             "topic: ${input}", "experiment-suite", false, "run experiments"),
 *         new MetaSkill.SkillStep("paper-writer",
 *             "topic: ${input} prev: ${prev.outputPath}", "paper-writer", false, "write paper")
 *     ),
 *     "/output/ai4s-agent"
 * );
 * }</pre>
 *
 * @param name           meta-skill 名（如 "ai4s-agent"）
 * @param description    人类可读描述
 * @param steps          顺序步骤（不可为空）
 * @param outputBasePath VFS 输出根（如 "/output/ai4s-agent"），为空时默认 "/output/{name}"
 * @param defaults       默认参数（可被 StepRun 的 argsTemplate 引用为 ${defaults.x}）
 * @see SkillChain
 * @see SkillChainContext
 */
public record MetaSkill(
        String name,
        String description,
        List<SkillStep> steps,
        String outputBasePath,
        Map<String, String> defaults
) {

    /**
     * 单步骤定义。
     *
     * @param skillName    要调用的 specialist skill 名（必须在 SkillLoader 中可查到）
     * @param argsTemplate 参数模板，支持变量替换：
     *                     <ul>
     *                       <li>${input} — 链输入（用户原始请求）</li>
     *                       <li>${slug} — 本次执行的 slug（用于输出路径隔离）</li>
     *                       <li>${prev.output} — 上一步的输出文本</li>
     *                       <li>${prev.outputPath} — 上一步的 VFS 输出路径</li>
     *                       <li>${step.index} — 当前步骤序号（0-based）</li>
     *                       <li>${defaults.x} — MetaSkill.defaults 中的 x</li>
     *                     </ul>
     * @param outputDir    该步骤输出子目录（如 "research-explorer"），
     *                     实际写入路径为 {@code outputBasePath/slug/outputDir/output.md}
     * @param optional     是否可跳过。true 时若执行失败则继续下一步；
     *                     false 时若失败则中止整条链
     * @param description  步骤描述（人类可读，用于 manifest 与日志）
     */
    public record SkillStep(
            String skillName,
            String argsTemplate,
            String outputDir,
            boolean optional,
            String description
    ) {
        public SkillStep {
            if (skillName == null || skillName.isBlank()) {
                throw new IllegalArgumentException("skillName required");
            }
            if (argsTemplate == null) argsTemplate = "";
            if (outputDir == null || outputDir.isBlank()) outputDir = skillName;
            if (description == null) description = "";
        }

        /** 便利构造器 — 默认 optional=false */
        public SkillStep(String skillName, String argsTemplate, String outputDir, String description) {
            this(skillName, argsTemplate, outputDir, false, description);
        }

        /** 便利构造器 — 仅 skillName，其他默认 */
        public SkillStep(String skillName) {
            this(skillName, "${input}", skillName, false, "");
        }
    }

    public MetaSkill {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name required");
        }
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("steps required (at least one)");
        }
        if (outputBasePath == null || outputBasePath.isBlank()) {
            outputBasePath = "/output/" + name;
        }
        steps = List.copyOf(steps);
        defaults = defaults == null ? Map.of() : Map.copyOf(defaults);
    }

    /** 便利构造器 — 无 defaults */
    public MetaSkill(String name, String description, List<SkillStep> steps, String outputBasePath) {
        this(name, description, steps, outputBasePath, Map.of());
    }

    /** 步骤数量 */
    public int stepCount() {
        return steps.size();
    }

    /** 按索引取步骤 */
    public SkillStep step(int i) {
        return steps.get(i);
    }
}
