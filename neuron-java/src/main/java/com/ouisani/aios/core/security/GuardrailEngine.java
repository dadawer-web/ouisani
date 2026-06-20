package com.ouisani.aios.core.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 护栏引擎 — 三级护栏体系的中央调度器（单例）。
 * <p>
 * 管理三类护栏的生命周期与执行：
 * <ul>
 *   <li>{@link Guardrail.InputGuardrail} — 输入护栏，<b>并行执行</b>，与 LLM 调用同时运行</li>
 *   <li>{@link Guardrail.OutputGuardrail} — 输出护栏，同步执行</li>
 *   <li>{@link Guardrail.ToolGuardrail} — 工具护栏，同步执行</li>
 * </ul>
 *
 * <h3>并行输入护栏</h3>
 * {@link #checkInput(String, String)} 返回 {@link CompletableFuture}，所有注册的输入护栏
 * 同时启动。任一护栏触发 tripwire，合并结果即触发。调用方可将此 future 与 LLM 调用
 * 并行运行——护栏先完成且触发时，可取消 LLM 调用以节省 Token。
 *
 * <h3>OS 类比</h3>
 * 相当于在 Seccomp-BPF（{@link SyscallFilter}）之上增加的应用层策略引擎：
 * Seccomp 在内核态拦截系统调用，Guardrail 在用户态验证 Agent 的输入/输出语义。
 *
 * @see Guardrail
 */
public class GuardrailEngine {

    private static final Logger log = LoggerFactory.getLogger(GuardrailEngine.class);

    private static final class Holder {
        static final GuardrailEngine INSTANCE = new GuardrailEngine();
    }

    public static GuardrailEngine instance() {
        return Holder.INSTANCE;
    }

    private final List<Guardrail.InputGuardrail> inputGuardrails = new CopyOnWriteArrayList<>();
    private final List<Guardrail.OutputGuardrail> outputGuardrails = new CopyOnWriteArrayList<>();
    private final List<Guardrail.ToolGuardrail> toolGuardrails = new CopyOnWriteArrayList<>();

    private GuardrailEngine() {}

    /**
     * 注册输入护栏。
     */
    public void registerInputGuardrail(Guardrail.InputGuardrail guardrail) {
        inputGuardrails.add(guardrail);
        log.info("[GuardrailEngine] 已注册输入护栏: {}", guardrail.getClass().getSimpleName());
    }

    /**
     * 注册输出护栏。
     */
    public void registerOutputGuardrail(Guardrail.OutputGuardrail guardrail) {
        outputGuardrails.add(guardrail);
        log.info("[GuardrailEngine] 已注册输出护栏: {}", guardrail.getClass().getSimpleName());
    }

    /**
     * 注册工具护栏。
     */
    public void registerToolGuardrail(Guardrail.ToolGuardrail guardrail) {
        toolGuardrails.add(guardrail);
        log.info("[GuardrailEngine] 已注册工具护栏: {}", guardrail.getClass().getSimpleName());
    }

    /**
     * 并行检查所有输入护栏。
     * <p>
     * 所有护栏同时执行，任一触发 tripwire 则合并结果触发。
     * 返回的 {@link CompletableFuture} 可与 LLM 调用并行运行。
     *
     * @param agentId Agent 标识
     * @param prompt  待检查的输入
     * @return 异步护栏检查结果（所有护栏完成后合并）
     */
    public CompletableFuture<Guardrail.GuardrailResult> checkInput(String agentId, String prompt) {
        if (inputGuardrails.isEmpty()) {
            return CompletableFuture.completedFuture(Guardrail.GuardrailResult.allowed());
        }

        // 并行启动所有输入护栏
        List<CompletableFuture<Guardrail.GuardrailResult>> futures = inputGuardrails.stream()
                .map(g -> {
                    try {
                        return g.check(agentId, prompt);
                    } catch (Exception e) {
                        log.warn("[GuardrailEngine] 输入护栏 '{}' 启动异常: {}",
                                g.getClass().getSimpleName(), e.getMessage());
                        return CompletableFuture.completedFuture(Guardrail.GuardrailResult.allowed());
                    }
                })
                .toList();

        // 合并所有结果：任一 tripwire 触发则合并结果触发
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    for (CompletableFuture<Guardrail.GuardrailResult> f : futures) {
                        try {
                            Guardrail.GuardrailResult r = f.getNow(Guardrail.GuardrailResult.allowed());
                            if (r.tripwireTriggered()) {
                                return r;
                            }
                        } catch (Exception e) {
                            log.warn("[GuardrailEngine] 输入护栏结果合并异常: {}", e.getMessage());
                        }
                    }
                    return Guardrail.GuardrailResult.allowed();
                });
    }

    /**
     * 同步检查所有输出护栏。
     * <p>
     * 按注册顺序依次执行，任一触发 tripwire 即立即返回该结果。
     *
     * @param agentId Agent 标识
     * @param output  待验证的输出
     * @return 护栏检查结果
     */
    public Guardrail.GuardrailResult checkOutput(String agentId, String output) {
        for (Guardrail.OutputGuardrail g : outputGuardrails) {
            try {
                Guardrail.GuardrailResult result = g.check(agentId, output);
                if (result.tripwireTriggered()) {
                    log.warn("[GuardrailEngine] 输出护栏 '{}' 触发: {}",
                            g.getClass().getSimpleName(), result.outputInfo());
                    return result;
                }
            } catch (Exception e) {
                log.warn("[GuardrailEngine] 输出护栏 '{}' 异常: {}",
                        g.getClass().getSimpleName(), e.getMessage());
            }
        }
        return Guardrail.GuardrailResult.allowed();
    }

    /**
     * 同步检查所有工具护栏。
     * <p>
     * 按注册顺序依次执行，任一触发 tripwire 即立即返回该结果。
     *
     * @param agentId  Agent 标识
     * @param toolName 工具名称
     * @param input    工具输入（参数 JSON）
     * @param output   工具输出文本
     * @return 护栏检查结果
     */
    public Guardrail.GuardrailResult checkTool(String agentId, String toolName, String input, String output) {
        for (Guardrail.ToolGuardrail g : toolGuardrails) {
            try {
                Guardrail.GuardrailResult result = g.check(agentId, toolName, input, output);
                if (result.tripwireTriggered()) {
                    log.warn("[GuardrailEngine] 工具护栏 '{}' 触发 (tool={}): {}",
                            g.getClass().getSimpleName(), toolName, result.outputInfo());
                    return result;
                }
            } catch (Exception e) {
                log.warn("[GuardrailEngine] 工具护栏 '{}' 异常: {}",
                        g.getClass().getSimpleName(), e.getMessage());
            }
        }
        return Guardrail.GuardrailResult.allowed();
    }
}
