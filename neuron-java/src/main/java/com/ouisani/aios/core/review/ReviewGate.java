package com.ouisani.aios.core.review;

import com.ouisani.aios.core.overnight.NodeCompletionVerifier;
import com.ouisani.aios.core.overnight.VerificationResult;
import com.ouisani.aios.core.overnight.VerificationSpec;
import com.ouisani.aios.core.provenance.ProvenanceHook;
import com.ouisani.aios.core.provenance.ProvenanceRecord;
import com.ouisani.aios.core.tool.ToolSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Reviewer Gate — 代码级 finalize 守门。
 * <p>
 * 借鉴 OpenScience {@code docs/plans/11-reviewer-agent.md} 的设计并真正落地：
 * 在 {@link com.ouisani.aios.core.tool.QueryEngine} 循环退出点、finalize 前跑一个 fresh、
 * 只读（PLAN 模式）、盲审的 reviewer 子 agent，用 {@link NodeCompletionVerifier} 做确定性兜底
 * 压制 LLM-as-judge 误判，结果持久化到 {@code .aios/review.jsonl}。
 * <p>
 * <b>三级配置</b>（{@link ReviewGateConfig#level()}）：
 * <ul>
 *   <li>{@code OFF} — 跳过</li>
 *   <li>{@code ANNOTATE}（默认，非阻塞）— 跑 reviewer，footer 追加到答案，写 review.jsonl</li>
 *   <li>{@code SOFT} — BLOCKING 且未达 cap 时 REENTER 注入 reminder；达 cap 带「未解决」note 返回</li>
 *   <li>{@code HARD} — BLOCKING 且未达 cap 时 REENTER；达 cap 仍 BLOCKING 拒绝 finalize</li>
 * </ul>
 * <p>
 * <b>确定性兜底</b>：对每个 artifact 路径构造 {@link VerificationSpec.FileExistsSpec}，
 * {@link NodeCompletionVerifier} 判 FAIL → 强制 BLOCKING（即使 LLM 说 CLEAN）；判 PASS + LLM INCONCLUSIVE → CLEAN。
 * 这是压制 10.2% LLM 误判的代码级闸门。
 * <p>
 * <b>Best-effort</b>：{@link #review} 永不抛出（QueryEngine 仍会再包一层 try/catch 双保险）。
 *
 * @see ReviewerRunner
 * @see ReviewVerdictParser
 * @see ReviewLedger
 */
public final class ReviewGate {

    private static final Logger log = LoggerFactory.getLogger(ReviewGate.class);

    private ReviewGate() {}

    // ════════════════════════════════════════════════════════════════
    //  上下文与结果（QueryEngine ↔ Gate 的契约）
    // ════════════════════════════════════════════════════════════════

    /** QueryEngine 传给 gate 的上下文。 */
    public record ReviewContext(
            ToolSdk sdk,
            String agentId,
            String runId,
            String workingDir,
            String finalAnswer,
            int fixCycleCount,
            boolean canReenter
    ) {}

    /** gate 返回给 QueryEngine 的指令。 */
    public record ReviewGateResult(
            Action action,        // RETURN / REENTER / SKIP
            String finalAnswer,   // RETURN 时使用（含 footer 或 refusal）
            String fixReminder,   // REENTER 时注入历史
            ReviewVerdict verdict
    ) {
        public enum Action { RETURN, REENTER, SKIP }

        public static ReviewGateResult skip(String answer) {
            return new ReviewGateResult(Action.SKIP, answer, null, null);
        }

        public static ReviewGateResult returnOriginal(String answer) {
            return new ReviewGateResult(Action.RETURN, answer, null, null);
        }
    }

    /** 确定性兜底结果（内部）。 */
    record BackstopResult(ReviewVerdict verdict, boolean deterministicForced) {}

    // ════════════════════════════════════════════════════════════════
    //  主入口
    // ════════════════════════════════════════════════════════════════

    /**
     * 执行 review gate。Best-effort：永不抛出。
     */
    public static ReviewGateResult review(ReviewContext ctx) {
        try {
            ReviewGateLevel level = ReviewGateConfig.level();
            if (level == ReviewGateLevel.OFF) {
                return ReviewGateResult.skip(ctx.finalAnswer());
            }

            // 1. 提取 artifacts（盲性：只传路径，不传父推理）
            List<ProvenanceRecord> artifacts = ProvenanceHook.listByAgent(ctx.agentId());
            if (artifacts.isEmpty()) {
                // trivial / 无产物轮 → 跳过，零回归
                return ReviewGateResult.skip(ctx.finalAnswer());
            }

            // 2. 跑 reviewer（fresh PLAN-mode QueryEngine，有界超时）
            List<String> artifactPaths = artifacts.stream()
                    .map(ProvenanceRecord::path)
                    .filter(p -> p != null && !p.isEmpty())
                    .distinct()
                    .toList();
            if (artifactPaths.isEmpty()) {
                return ReviewGateResult.skip(ctx.finalAnswer());
            }

            String prompt = buildReviewerPrompt(ctx.finalAnswer(), artifactPaths);
            String raw = ReviewerRunner.run(ctx.sdk(), ctx.agentId(), ctx.workingDir(),
                    prompt, ReviewGateConfig.timeoutMs());

            // 3. 解析 LLM verdict
            ReviewVerdict llmVerdict = ReviewVerdictParser.parse(raw);

            // 4. 确定性兜底（关键优势：压制 LLM 误判）
            BackstopResult backstop = applyDeterministicBackstop(llmVerdict, artifactPaths);
            ReviewVerdict finalVerdict = backstop.verdict();

            // 5. 持久化（best-effort）
            ReviewLedger.append(toRecord(ctx, level, finalVerdict, backstop.deterministicForced(),
                    firstPath(artifactPaths)));

            // 6. 按 level 裁决
            return decideByLevel(level, ctx, finalVerdict);
        } catch (Throwable t) {
            log.warn("[ReviewGate] 异常，降级返回原答案: {}", t.getMessage());
            return ReviewGateResult.returnOriginal(ctx.finalAnswer());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  确定性兜底 — package-private（可单测）
    // ════════════════════════════════════════════════════════════════

    /**
     * 对每个 artifact 路径跑 {@link VerificationSpec.FileExistsSpec}。
     * <ul>
     *   <li>det FAIL → 强制 BLOCKING（即使 LLM CLEAN），{@code deterministicForced=true}</li>
     *   <li>det PASS + LLM INCONCLUSIVE → CLEAN（确定性 PASS 清除 LLM 不确定）</li>
     *   <li>否则采信 LLM</li>
     * </ul>
     */
    static BackstopResult applyDeterministicBackstop(ReviewVerdict llm, List<String> artifactPaths) {
        if (artifactPaths == null || artifactPaths.isEmpty()) {
            return new BackstopResult(llm, false);
        }
        List<VerificationSpec> specs = artifactPaths.stream()
                .<VerificationSpec>map(VerificationSpec.FileExistsSpec::new)
                .toList();
        VerificationResult det = NodeCompletionVerifier.instance().verify(specs);

        if (det.isFail()) {
            // 确定性 FAIL 强制 BLOCKING，即使 LLM 说 CLEAN
            List<ReviewFinding> merged = new ArrayList<>(llm.findings());
            merged.add(new ReviewFinding("high", firstMissingPath(artifactPaths, det),
                    "Deterministic check FAILED: artifact missing — "
                            + String.join("; ", det.evidence())));
            return new BackstopResult(
                    new ReviewVerdict(ReviewVerdict.Outcome.BLOCKING, merged,
                            "deterministic backstop forced BLOCKING"),
                    true);
        }
        if (det.isPass() && llm.outcome() == ReviewVerdict.Outcome.INCONCLUSIVE) {
            return new BackstopResult(
                    new ReviewVerdict(ReviewVerdict.Outcome.CLEAN, llm.findings(),
                            "deterministic PASS + LLM inconclusive → CLEAN"),
                    false);
        }
        return new BackstopResult(llm, false);
    }

    // ════════════════════════════════════════════════════════════════
    //  按 level 裁决 — package-private（可单测）
    // ════════════════════════════════════════════════════════════════

    static ReviewGateResult decideByLevel(ReviewGateLevel level, ReviewContext ctx, ReviewVerdict v) {
        int cap = ReviewGateConfig.maxFixCycles();
        boolean canFix = ctx.canReenter() && ctx.fixCycleCount() < cap;

        switch (level) {
            case ANNOTATE -> {
                return new ReviewGateResult(ReviewGateResult.Action.RETURN,
                        ctx.finalAnswer() + formatFooter(v), null, v);
            }
            case SOFT -> {
                if (v.isBlocking() && canFix) {
                    return new ReviewGateResult(ReviewGateResult.Action.REENTER,
                            null, formatReminder(v), v);
                }
                String note = v.isBlocking()
                        ? "\n\n[Review] 未解决的阻断性发现已达修复上限（" + cap + "），请人工复核。"
                        : "";
                return new ReviewGateResult(ReviewGateResult.Action.RETURN,
                        ctx.finalAnswer() + formatFooter(v) + note, null, v);
            }
            case HARD -> {
                if (v.isBlocking() && canFix) {
                    return new ReviewGateResult(ReviewGateResult.Action.REENTER,
                            null, formatReminder(v), v);
                }
                if (v.isBlocking()) {
                    // cap 饱和仍阻断 → 拒绝 finalize
                    return new ReviewGateResult(ReviewGateResult.Action.RETURN,
                            "[Reviewer Gate] 拒绝输出：存在未解决的阻断性发现（已达 " + cap
                                    + " 次修复上限）：\n" + formatFindings(v), null, v);
                }
                return new ReviewGateResult(ReviewGateResult.Action.RETURN,
                        ctx.finalAnswer() + formatFooter(v), null, v);
            }
            default -> {
                return ReviewGateResult.returnOriginal(ctx.finalAnswer());
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Prompt / 格式化 / 记录构造（private）
    // ════════════════════════════════════════════════════════════════

    private static String buildReviewerPrompt(String finalAnswer, List<String> artifactPaths) {
        return """
                You are a blind reviewer. Evaluate ONLY the artifacts and final answer below.
                You have read-only tools (file_read / grep / glob / provenance_query). Inspect the
                artifacts to verify that claims in the final answer trace to real files.

                ## Provenance DAG Query (preferred for traceability)
                Use the `provenance_query` tool (pass an artifact `path`) to look up the provenance
                DAG: which agent wrote the artifact, with what tool, at which version, and any past
                review findings linked to it. Prefer this over file_read when verifying whether a
                number/claim in the final answer traces to a real artifact origin.

                Do NOT speculate beyond what the artifacts + provenance records show.
                You cannot write files or spawn sub-agents.

                ## Final Answer
                %s

                ## Artifacts Produced (paths only, no reasoning history)
                %s

                ## Output Contract
                Reply with a fenced ```review block containing JSON:
                ```review
                {"verdict":"CLEAN|FLAGGED|BLOCKING|INCONCLUSIVE","summary":"one-line summary","findings":[{"severity":"low|medium|high","targetPath":"...","message":"...","claim":"the assertion you verified","evidence":"provenance source, e.g. agent_5 wrote v2 via write at ts"}]}
                ```
                - CLEAN: no issues
                - FLAGGED: non-blocking issues
                - BLOCKING: must fix before finalize
                - INCONCLUSIVE: cannot determine
                - claim: the specific assertion in the final answer you verified (may be empty)
                - evidence: provenance source backing/refuting the claim (may be empty)
                """.formatted(finalAnswer, String.join("\n", artifactPaths));
    }

    private static String formatFooter(ReviewVerdict v) {
        StringBuilder sb = new StringBuilder("\n\n---\n[Review: ");
        sb.append(v.outcome()).append("] ").append(v.summary());
        if (!v.findings().isEmpty()) {
            sb.append("\n").append(formatFindings(v));
        }
        return sb.toString();
    }

    private static String formatReminder(ReviewVerdict v) {
        return "[Reviewer findings — please address and resubmit]\n"
                + "verdict: " + v.outcome() + "\n"
                + "summary: " + v.summary() + "\n"
                + formatFindings(v)
                + "\nFix the blocking findings above and produce a revised final answer.";
    }

    private static String formatFindings(ReviewVerdict v) {
        if (v.findings().isEmpty()) {
            return "(no findings)";
        }
        StringBuilder sb = new StringBuilder();
        for (ReviewFinding f : v.findings()) {
            sb.append("- [").append(f.severity()).append("] ");
            if (f.targetPath() != null && !f.targetPath().isEmpty()) {
                sb.append(f.targetPath()).append(": ");
            }
            sb.append(f.message());
            // Phase 6: 追溯信息（claim/evidence 非空时追加，保持旧输出零回归）
            if (!f.claim().isEmpty()) {
                sb.append(" | claim: ").append(f.claim());
            }
            if (!f.evidence().isEmpty()) {
                sb.append(" | evidence: ").append(f.evidence());
            }
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private static ReviewRecord toRecord(ReviewContext ctx, ReviewGateLevel level,
                                          ReviewVerdict v, boolean deterministicForced,
                                          String targetPath) {
        return new ReviewRecord(
                targetPath,
                ctx.agentId(),
                ctx.runId(),
                System.currentTimeMillis(),
                level.name().toLowerCase(),
                v.outcome().name(),
                v.summary(),
                v.findings(),
                deterministicForced
        );
    }

    private static String firstPath(List<String> paths) {
        return paths.isEmpty() ? "" : paths.get(0);
    }

    /** 从 evidence 里提取第一个缺失路径，找不到则取第一个 artifact path。 */
    private static String firstMissingPath(List<String> artifactPaths, VerificationResult det) {
        if (det.evidence() != null) {
            for (String e : det.evidence()) {
                if (e.contains("MISSING")) {
                    // evidence 形如 "/a/missing.md: MISSING ✗"
                    int colon = e.indexOf(':');
                    if (colon > 0) {
                        return e.substring(0, colon).trim();
                    }
                }
            }
        }
        return firstPath(artifactPaths);
    }
}
