package com.ouisani.aios.core.skill;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.provenance.ProvenanceHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SkillChain — Meta-skill 的执行引擎。
 * <p>
 * 按顺序执行 {@link MetaSkill} 中的每个 {@link MetaSkill.SkillStep}：
 * <ol>
 *   <li>解析参数模板（${input} ${slug} ${prev.output} ${prev.outputPath} ${defaults.x} ${step.index}）</li>
 *   <li>调用 {@link SkillExecutor} 执行 specialist skill</li>
 *   <li>将输出写入 VFS（复用 {@code VfsManager.writeText}，自动触发 {@link ProvenanceHook}）</li>
 *   <li>记录 {@link StepRun}</li>
 *   <li>失败且非 optional → 中止链</li>
 * </ol>
 * <p>
 * <b>工件落 VFS，不靠聊天回复</b> — 每步骤的输出写入
 * {@code outputBasePath/slug/outputDir/output.md}，链结束时写入
 * {@code outputBasePath/slug/chain-manifest.json} 记录完整运行轨迹。
 * 与 R1 ProvenanceHook 自动集成，每条 writeText 都追加一条 provenance 记录。
 * <p>
 * <b>核心层纯净</b>：通过 {@link SkillExecutor} 函数式接口注入 LLM 调用能力，
 * 不依赖 {@code user.sdk.AiosSdk}（符合 core 不依赖 user 态的边界约束）。
 * 实际的 AiosSdk-backed executor 由 user 层提供（参考 MetaSkillTool 模式）。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * MetaSkill meta = MetaSkills.ai4sAgent();
 * SkillChainContext ctx = new SkillChainContext("agent_5", "/work", "transformer-forecasting-a1b2c3d4");
 * SkillChain.SkillExecutor executor = (agentId, skillName, args, wd) ->
 *     AiosSdk.getInstance().queryWithTools(agentId, "/" + skillName + " " + args, wd);
 * SkillChain.ChainRun run = SkillChain.run(meta, "Transformer forecasting", ctx, executor);
 * System.out.println(run.toJson());
 * }</pre>
 *
 * @see MetaSkill
 * @see SkillChainContext
 * @see ProvenanceHook
 */
public final class SkillChain {

    private static final Logger log = LoggerFactory.getLogger(SkillChain.class);

    /**
     * 异步链执行线程池 — 虚拟线程 per-task。
     * <p>
     * {@link #run} 内部有大量阻塞 I/O（VFS 写、JSONL 追加、远程执行），虚拟线程对阻塞友好；
     * 项目已用此模式（HumanResponseTool 注释 "Virtual Thread 友好"）。
     * static 进程级共享，不 shutdown（虚拟线程随 JVM 退出）。
     */
    private static final ExecutorService ASYNC_POOL = Executors.newVirtualThreadPerTaskExecutor();

    private SkillChain() {}

    /**
     * Skill 执行器 — 由调用方注入实际执行逻辑。
     * <p>
     * 通常实现为对 {@code AiosSdk.queryWithTools} 或 {@code SkillTool.call} 的委托，
     * 让 core 层无需直接依赖 user 态 SDK。
     *
     * @param agentId    调用方 Agent ID
     * @param skillName  specialist skill 名
     * @param args       解析后的参数（已替换变量）
     * @param workingDir 工作目录
     * @return skill 的输出文本（LLM 回复）；null 或空字符串视为失败
     */
    @FunctionalInterface
    public interface SkillExecutor {
        String execute(String agentId, String skillName, String args, String workingDir);
    }

    /**
     * 执行一条 meta-skill 链。
     *
     * @param meta     meta-skill 定义
     * @param input    链输入（用户原始请求，可被步骤的 ${input} 引用）
     * @param ctx      执行上下文
     * @param executor specialist skill 执行器
     * @return 链运行记录（含每步骤的状态、输出路径等）
     */
    public static ChainRun run(MetaSkill meta, String input,
                                 SkillChainContext ctx, SkillExecutor executor) {
        String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
        long startedAt = System.currentTimeMillis();
        log.info("[SkillChain] 启动: meta={}, runId={}, slug={}, steps={}",
                meta.name(), runId, ctx.slug(), meta.stepCount());

        // 设置 ProvenanceHook 上下文（让 VfsManager.writeText 自动归属到此 agent）
        ProvenanceHook.CURRENT_AGENT_ID.set(ctx.agentId());
        if (ctx.sessionId() != null && !ctx.sessionId().isEmpty()) {
            ProvenanceHook.CURRENT_SESSION_ID.set(ctx.sessionId());
        }

        List<StepRun> steps = new ArrayList<>();
        String outputPathBase = meta.outputBasePath() + "/" + ctx.slug();
        String prevOutput = "";
        String prevOutputPath = "";
        ChainStatus chainStatus = ChainStatus.COMPLETED;

        try {
            for (int i = 0; i < meta.steps().size(); i++) {
                MetaSkill.SkillStep step = meta.steps().get(i);
                long stepStarted = System.currentTimeMillis();

                // ── 参数模板解析 ──
                // defaults 中的键前缀化为 defaults.x，避免与内置变量冲突
                // （内置变量：input / slug / prev.output / prev.outputPath / step.index）
                Map<String, String> vars = new HashMap<>();
                meta.defaults().forEach((k, v) -> vars.put("defaults." + k, v));
                vars.put("input", input == null ? "" : input);
                vars.put("slug", ctx.slug());
                vars.put("prev.output", prevOutput);
                vars.put("prev.outputPath", prevOutputPath);
                vars.put("step.index", String.valueOf(i));
                String resolvedArgs = resolveTemplate(step.argsTemplate(), vars);

                // ── 调用 specialist skill ──
                String outputText;
                StepStatus status;
                String errorMessage = null;

                try {
                    outputText = executor.execute(ctx.agentId(), step.skillName(), resolvedArgs, ctx.workingDir());
                    if (outputText == null || outputText.isBlank()) {
                        status = StepStatus.FAILED;
                        errorMessage = "executor returned empty output";
                        outputText = "";
                    } else {
                        status = StepStatus.SUCCESS;
                    }
                } catch (Exception e) {
                    log.error("[SkillChain] step {} '{}' 执行异常: {}",
                            i, step.skillName(), e.getMessage());
                    outputText = "";
                    status = StepStatus.FAILED;
                    errorMessage = e.getClass().getSimpleName() + ": " + e.getMessage();
                }

                // ── 写入 VFS（成功/失败都写一份；失败时记录错误信息便于排查） ──
                String stepOutputPath = outputPathBase + "/" + step.outputDir() + "/output.md";
                try {
                    String content = status == StepStatus.SUCCESS
                            ? outputText
                            : "# Step Failed\n\nskill: " + step.skillName()
                                    + "\nerror: " + errorMessage
                                    + "\n\n## Raw Output\n\n" + outputText;
                    boolean writeOk = VfsManager.instance().writeText(stepOutputPath, content);
                    if (!writeOk) {
                        log.warn("[SkillChain] VFS writeText 返回 false: path={}", stepOutputPath);
                    }
                } catch (Exception e) {
                    // best-effort: 不中断链
                    log.warn("[SkillChain] VFS 写入失败: path={}, err={}",
                            stepOutputPath, e.getMessage());
                }

                long stepFinished = System.currentTimeMillis();
                StepRun stepRun = new StepRun(
                        step.skillName(), i, stepStarted, stepFinished,
                        resolvedArgs, outputText, stepOutputPath, status, errorMessage
                );
                steps.add(stepRun);

                log.info("[SkillChain] step {}/{} '{}' → {} ({}ms), path={}",
                        i + 1, meta.stepCount(), step.skillName(), status,
                        stepFinished - stepStarted, stepOutputPath);

                if (status == StepStatus.SUCCESS) {
                    prevOutput = outputText;
                    prevOutputPath = stepOutputPath;
                } else {
                    if (!step.optional()) {
                        log.warn("[SkillChain] 非可选步骤失败，中止链: step={}, err={}",
                                step.skillName(), errorMessage);
                        chainStatus = (i == 0) ? ChainStatus.FAILED : ChainStatus.PARTIAL;
                        break;
                    } else {
                        log.info("[SkillChain] 可选步骤失败，继续下一步: step={}",
                                step.skillName());
                    }
                }
            }
        } finally {
            // 清理 ThreadLocal（防止线程池复用导致上下文泄漏）
            ProvenanceHook.CURRENT_AGENT_ID.remove();
            ProvenanceHook.CURRENT_SESSION_ID.remove();
        }

        long finishedAt = System.currentTimeMillis();
        ChainRun run = new ChainRun(
                runId, meta.name(), startedAt, finishedAt,
                List.copyOf(steps), chainStatus, outputPathBase
        );

        // 写入 chain manifest（best-effort）
        try {
            String manifestPath = outputPathBase + "/chain-manifest.json";
            VfsManager.instance().writeText(manifestPath, run.toJson());
        } catch (Exception e) {
            log.warn("[SkillChain] manifest 写入失败: {}", e.getMessage());
        }

        // R3：持久化 RunRecord（best-effort — 永不阻断链返回）
        // 写入 per-run 目录 + append-only JSONL + 更新内存索引；
        // 与 EnvironmentSnapshotManager 的 snapshotId 通过 ctx.snapshotId() 传递
        try {
            RunRecordStore.instance().record(run, input, ctx);
        } catch (Exception e) {
            log.warn("[SkillChain] RunRecord 持久化失败: {}", e.getMessage());
        }

        log.info("[SkillChain] 完成: meta={}, runId={}, status={}, steps={}, elapsed={}ms",
                meta.name(), runId, chainStatus, steps.size(), finishedAt - startedAt);
        return run;
    }

    /**
     * 异步执行一条 meta-skill 链 — 返回 {@link CompletableFuture}，不阻塞调用线程。
     * <p>
     * 内部用 {@link #ASYNC_POOL}（虚拟线程）跑同步 {@link #run}，语义与 run() 完全一致
     * （包括 VFS 写入、chain-manifest 持久化、RunRecord 记录、ProvenanceHook 上下文设置与清理）。
     * <p>
     * <b>Additive</b>：同步 {@link #run} 完全不动；{@code RunRecordStore.reproduce} 仍调 sync run()，无影响。
     * 调用方可用 {@code future.get(timeout, unit)} 阻塞取回，或用 {@code thenAccept} 回调式消费。
     * <p>
     * <b>异常语义</b>：{@link #run} 内部已 catch executor 异常并转为 {@link StepStatus#FAILED}，
     * 故 future 正常 complete（不 exceptionally）— 即便某步失败，{@link ChainRun#status()} 反映 FAILED/PARTIAL。
     *
     * @param meta     meta-skill 定义
     * @param input    链输入（用户原始请求）
     * @param ctx      执行上下文
     * @param executor specialist skill 执行器
     * @return 异步完成的 ChainRun future
     */
    public static CompletableFuture<ChainRun> runAsync(MetaSkill meta, String input,
                                                        SkillChainContext ctx, SkillExecutor executor) {
        return CompletableFuture.supplyAsync(() -> run(meta, input, ctx, executor), ASYNC_POOL);
    }

    /**
     * 简单的 ${var} 模板解析 — 不支持嵌套，不支持默认值。
     * 未识别的 ${...} 保持原样（便于调试）。
     */
    static String resolveTemplate(String template, Map<String, String> vars) {
        if (template == null || template.isEmpty()) return "";
        String result = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            String val = e.getValue() == null ? "" : e.getValue();
            result = result.replace("${" + e.getKey() + "}", val);
        }
        return result;
    }

    // ════════════════════════════════════════════════════════════════
    //  运行记录类型
    // ════════════════════════════════════════════════════════════════

    /** 链整体状态 */
    public enum ChainStatus {
        /** 全部步骤成功 */
        COMPLETED,
        /** 部分步骤成功后非可选步骤失败（已产出部分工件） */
        PARTIAL,
        /** 第一步即失败（无任何工件） */
        FAILED,
        /** 被外部中断（预留，当前未使用） */
        ABORTED
    }

    /** 单步骤状态 */
    public enum StepStatus {
        /** 被跳过（预留：可由条件步骤触发） */
        SKIPPED,
        /** 成功 */
        SUCCESS,
        /** 失败 */
        FAILED
    }

    /**
     * 链运行记录 — 一次 SkillChain.run 的完整结果。
     * <p>
     * 同时持久化为 {@code chain-manifest.json}，便于事后审计与重放。
     */
    public record ChainRun(
            String runId,
            String metaSkillName,
            long startedAt,
            long finishedAt,
            List<StepRun> steps,
            ChainStatus status,
            String outputBasePath
    ) {
        /** 总耗时（毫秒） */
        public long elapsedMs() {
            return finishedAt - startedAt;
        }

        /** 成功步骤数 */
        public long successCount() {
            return steps.stream().filter(s -> s.status() == StepStatus.SUCCESS).count();
        }

        /** 失败步骤数 */
        public long failureCount() {
            return steps.stream().filter(s -> s.status() == StepStatus.FAILED).count();
        }

        /** 序列化为 JSON（手写，core 层无 Jackson） */
        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"runId\":\"").append(escape(runId)).append("\",");
            sb.append("\"metaSkillName\":\"").append(escape(metaSkillName)).append("\",");
            sb.append("\"startedAt\":").append(startedAt).append(",");
            sb.append("\"finishedAt\":").append(finishedAt).append(",");
            sb.append("\"elapsedMs\":").append(elapsedMs()).append(",");
            sb.append("\"status\":\"").append(status).append("\",");
            sb.append("\"successCount\":").append(successCount()).append(",");
            sb.append("\"failureCount\":").append(failureCount()).append(",");
            sb.append("\"outputBasePath\":\"").append(escape(outputBasePath)).append("\",");
            sb.append("\"steps\":[");
            for (int i = 0; i < steps.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(steps.get(i).toJson());
            }
            sb.append("]}");
            return sb.toString();
        }
    }

    /** 单步骤运行记录 */
    public record StepRun(
            String skillName,
            int index,
            long startedAt,
            long finishedAt,
            String inputArgs,
            String outputText,
            String outputVfsPath,
            StepStatus status,
            String errorMessage
    ) {
        /** 步骤耗时（毫秒） */
        public long elapsedMs() {
            return finishedAt - startedAt;
        }

        /** 序列化为 JSON */
        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"skillName\":\"").append(escape(skillName)).append("\",");
            sb.append("\"index\":").append(index).append(",");
            sb.append("\"startedAt\":").append(startedAt).append(",");
            sb.append("\"finishedAt\":").append(finishedAt).append(",");
            sb.append("\"elapsedMs\":").append(elapsedMs()).append(",");
            sb.append("\"status\":\"").append(status).append("\",");
            sb.append("\"outputVfsPath\":\"").append(escape(outputVfsPath)).append("\",");
            sb.append("\"inputArgs\":\"").append(escape(truncate(inputArgs, 500))).append("\",");
            sb.append("\"outputText\":\"").append(escape(truncate(outputText, 500))).append("\",");
            sb.append("\"errorMessage\":\"").append(errorMessage == null ? "" : escape(errorMessage)).append("\"");
            sb.append("}");
            return sb.toString();
        }
    }

    // ── 内部工具 ──

    static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }

    static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
