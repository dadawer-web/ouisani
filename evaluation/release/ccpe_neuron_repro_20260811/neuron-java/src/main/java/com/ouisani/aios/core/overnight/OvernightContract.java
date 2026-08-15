package com.ouisani.aios.core.overnight;

import com.ouisani.aios.core.permission.PermissionChecker;
import com.ouisani.aios.core.permission.PermissionRule;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Overnight 操作契约 — 长跑 agent 的"宪法"。
 * <p>
 * 镜像 jcode prompts.rs 的 build_coordinator_prompt 及 6 个阶段专属 prompt builder。
 * 将 jcode 的操作条款（优先已验证低风险进展 / 禁止口味驱动·大重写·支付·push·删数据 /
 * 先复现后修复 / 不等用户 / 资源感知不并行重活 / 1 coordinator + 至多 1 helper /
 * 每任务维护结构化卡片）编译为中文 prompt，注入 coordinator 的使命。
 * <p>
 * 本类是纯 prompt builder，无状态、无副作用。所有方法返回 String。
 *
 * @see OvernightPhase
 * @see OvernightManifest
 * @see OvernightRunner
 */
public final class OvernightContract {

    private OvernightContract() {}

    /** Coordinator 角色 system prompt — 每次 LLM 调用的系统提示 */
    public static final String COORDINATOR_SYSTEM_PROMPT = """
            你是 AIOS 的 Overnight Coordinator（长跑协调器）。
            用户预计在目标唤醒时间之前不在场。你拥有这次 run 的完全控制权，
            负责自主推进已验证、低风险、有界可验证的进展，并在目标时间到达时
            产出晨报。你的每一步行动都必须有据可查、风险可控。
            """;

    /**
     * 主契约 prompt — 首轮注入 coordinator 的完整操作契约。
     * <p>
     * 镜像 jcode build_coordinator_prompt，适配 neuron-java：
     * VFS 路径替代物理路径，中文表述，通用任务（不限 GH bug）。
     */
    public static String buildCoordinatorPrompt(OvernightManifest m,
                                                OvernightResourceSnapshot snapshot) {
        String cardsDir = cardsDir(m);
        String reviewPath = reviewPath(m);
        String validationDir = validationDir(m);
        String remaining = OvernightPhase.formatMinutes(
                Duration.between(Instant.now(), m.targetWakeAt()).toMinutes());

        return """
                你是 AIOS Overnight run「%s」的 Coordinator。

                用户预计在「%s」之前不在场。这是一个目标唤醒/汇报时间，不是硬停止。
                到达该时间时，run 必须处于 handoff-ready 状态，review 页面必须解释发生了什么。
                你可以在目标时间之后继续，仅为了完成一个有界的、安全的、可验证的工作块。
                默认的 post-wake 软宽限期截止于「%s」。

                使命：
                %s

                操作契约（长跑宪法）：
                - 优化已验证、低风险的进展。
                - 优先：有客观复现的 bug、failing test、静态分析发现、回归测试、
                  有界的代码质量修复、清晰的崩溃/错误输出修复。
                - 禁止：口味驱动的工作、模糊的产品决策、大范围重写、高风险迁移、
                  支付操作、发送邮件、push 到远程、删除数据、或其他外部副作用
                  （除非用户明确授权）。
                - 如果发现 bug，先复现/证明再修复。
                - 只修复重要的、有界的、可验证的问题。否则在「%s」起草一个高质量 issue。
                - 你拥有这次 run。仅在预期价值超过资源成本时才 spawn helper/swarm agent。
                  默认 1 coordinator + 至多 1 helper。只读 scout/verifier 优先于多个 editor。
                - 感知 RAM/load/battery，尤其是在编译、浏览器自动化、索引、全量测试套件期间。
                  除非资源明显健康，否则不要同时运行多个重活。
                - 不要等待用户。如果需要用户判断/凭证/口味，记录下来并切换到另一个有用任务。
                - 在目标唤醒/汇报时间之前，持续寻找有用的已验证工作，
                  除非 usage/资源使得这不合理。

                Review/日志要求：
                - 保持「%s」更新。
                - 对于每个有意义的任务，在「%s」维护一个结构化 JSON 任务卡片。
                  每张卡片必须包含清晰的 Before/After、evidence、validation、
                  files changed、risk、status、outcome。
                - 将复现/测试/命令输出放在「%s」中。
                - 当前任务标记为 active；已完成的已验证工作标记为 completed；
                  用户/口味/凭证阻塞标记为 blocked；考虑过但未执行的工作标记为 deferred 或 skipped。

                资源快照：%s

                初始步骤：
                1. 检查当前 repo/session 状态和 git status。
                2. 构建一个有优先级的可验证候选任务队列。
                3. 选择置信度最高的有界任务。
                4. 先证明/复现再修复。
                5. 验证并更新 review notes 和任务卡片。
                6. 如果提前完成，重复发现并继续。

                剩余时间约 %s。
                """.formatted(
                m.runId(),
                m.targetWakeLabel(),
                m.graceLabel(),
                m.effectiveMission(),
                cardsDir + "/issue_drafts",
                reviewPath,
                cardsDir,
                validationDir,
                snapshot != null ? snapshot.advisory() : "（未采集）",
                remaining
        );
    }

    /** running 阶段：继续推进下一个有界任务 */
    public static String buildContinuationPrompt(OvernightManifest m) {
        long remaining = Duration.between(Instant.now(), m.targetWakeAt()).toMinutes();
        if (remaining < 0) remaining = 0;
        return """
                Overnight 续航：距离目标唤醒/汇报时间还有约 %s。
                如果当前任务已完成，执行一次发现/评分扫描并选择另一个高置信度、可验证的任务。
                如果卡住了，在 review notes 和对应任务卡片 JSON 中记录原因，然后切换到更小的有界任务。
                更新 review notes 和任务卡片后继续。
                """.formatted(OvernightPhase.formatMinutes(remaining));
    }

    /** wind-down 阶段：停止开新大活，让 run 易于理解 */
    public static String buildHandoffReadyPrompt(OvernightManifest m) {
        return """
                Handoff-ready 提醒：目标唤醒/汇报时间约在 30 分钟内。
                不要放弃有用的工作，但要让 run 易于理解。
                更新 review notes 和任务卡片 JSON，记录当前任务、已完成工作、验证状态、
                变更文件、风险、跳过的工作和下一步。
                避免启动大/新的高风险变更，除非它们是隔离的且明显可验证。
                """;
    }

    /** morning report 阶段：必须发晨报 */
    public static String buildMorningReportPrompt(OvernightManifest m) {
        return """
                目标唤醒/汇报时间已到达。现在发布晨报，即使工作仍在进行中。
                更新 review notes 和任务卡片 JSON，确保 review 内容有用。
                包含：已完成工作、当前任务、before/after evidence、变更文件、
                验证状态、风险、usage/资源备注（如相关）、以及你是否计划继续。
                你可以继续，仅当下一个工作块是有界的、安全的、可验证的。
                """;
    }

    /** post-wake 阶段：宽限期，仅做有界收尾 */
    public static String buildPostWakeContinuationPrompt(OvernightManifest m) {
        return """
                Post-wake 续航：目标唤醒/汇报时间已过，晨报应该已可用。
                你可以继续，仅限有界的、安全的、可验证的、已在进行中或明显高价值的工作。
                不要启动大范围/高风险的新变更。
                保持 review notes 和任务卡片 JSON 最新，以便用户随时安全地检查或中断。
                软宽限期截止于「%s」。
                """.formatted(m.graceLabel());
    }

    /** finalizing 阶段：立即收尾 */
    public static String buildFinalWrapupPrompt(OvernightManifest m) {
        return """
                最终收尾：post-wake 软宽限期已过。停止启动新工作。
                仅完成立即的清理工作，更新 review notes、任务卡片 JSON，
                记录最终的 before/after evidence、验证状态、dirty repo 状态、
                剩余风险和下一步，然后停止。
                """;
    }

    /** 取消提示 */
    public static String buildCancellationPrompt() {
        return "收到取消请求。立即收尾当前工作块，更新任务卡片为 blocked 或 deferred，然后停止。";
    }

    /**
     * 阶段分发器 — 根据当前阶段选择对应 prompt。
     *
     * @param phase    当前阶段（由 OvernightPhase.compute 计算）
     * @param m        运行清单
     * @param snapshot 资源快照（仅 running 首轮需要，后续可为 null）
     * @return 对应阶段的 prompt；终态返回空串
     */
    public static String promptForPhase(OvernightPhase phase, OvernightManifest m,
                                        OvernightResourceSnapshot snapshot) {
        return switch (phase) {
            case RUNNING -> buildContinuationPrompt(m);
            case WIND_DOWN -> buildHandoffReadyPrompt(m);
            case MORNING_REPORT -> buildMorningReportPrompt(m);
            case POST_WAKE -> buildPostWakeContinuationPrompt(m);
            case FINALIZING -> buildFinalWrapupPrompt(m);
            case CANCELLING -> buildCancellationPrompt();
            case COMPLETED, FAILED -> "";
        };
    }

    /**
     * 把聚合的 DENY 决策格式化为晨报段落 — 供 coordinator 在 MORNING_REPORT 阶段注入 prompt。
     * <p>
     * 借鉴 AgentScope 2.0 的 suggested_rules：每条 DENY 附带"加什么规则能放行"的建议，
     * 让用户晨起后能快速决定是否放宽权限。bypass_immune 的 DENY（危险操作）标注"不可通过规则放行"。
     *
     * @param denials 聚合的 DENY 记录（由 PermissionChecker.globalDenialSink 收集）
     * @return 格式化的段落；空输入返回空串
     */
    public static String formatDenialsForMorningReport(List<PermissionChecker.DenialRecord> denials) {
        if (denials == null || denials.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("## Overnight 被拒操作清单（共 ").append(denials.size()).append(" 条）\n\n");
        int idx = 1;
        for (PermissionChecker.DenialRecord r : denials) {
            sb.append(idx++).append(". [").append(r.toolName()).append("] ")
              .append(truncate(r.inputDigest(), 80))
              .append(" — 拒绝原因：").append(r.decision().message())
              .append(" (").append(r.decision().reason()).append(")\n");

            List<PermissionRule> suggestions = r.decision().suggestedRules();
            if (r.decision().bypassImmune()) {
                sb.append("   建议规则：无（bypass_immune，无法通过规则放行 — 危险操作）\n");
            } else if (suggestions.isEmpty()) {
                sb.append("   建议规则：无\n");
            } else {
                sb.append("   建议规则（如需放行可添加）:\n");
                for (PermissionRule s : suggestions) {
                    sb.append("     - ").append(s.toRuleString()).append("\n");
                }
            }
            sb.append("\n");
        }
        sb.append("请在晨报中总结这些被拒操作，并说明哪些（如有）是误拒、哪些是合理拒绝、")
          .append("用户可以添加哪些规则以提升下次 overnight 的覆盖度。");
        return sb.toString();
    }

    /** 截断字符串到指定长度，超长加省略号。 */
    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    // ── VFS 路径辅助 ──

    /** 任务卡片目录 */
    static String cardsDir(OvernightManifest m) {
        return m.vfsRunDir() != null ? m.vfsRunDir() + "/cards" : "/var/run/overnight/" + m.runId() + "/cards";
    }

    /** review notes 路径 */
    static String reviewPath(OvernightManifest m) {
        return m.vfsRunDir() != null ? m.vfsRunDir() + "/review.md" : "/var/run/overnight/" + m.runId() + "/review.md";
    }

    /** validation 输出目录 */
    static String validationDir(OvernightManifest m) {
        return m.vfsRunDir() != null ? m.vfsRunDir() + "/validation" : "/var/run/overnight/" + m.runId() + "/validation";
    }
}
