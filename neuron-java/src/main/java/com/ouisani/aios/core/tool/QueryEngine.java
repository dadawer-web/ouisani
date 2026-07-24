package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.hook.HookManager;
import com.ouisani.aios.core.network.AiosEventSchema;
import com.ouisani.aios.core.network.AiosEventSchema.AiosEvent;
import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.permission.PermissionChecker;
import com.ouisani.aios.core.permission.PermissionDecision;
import com.ouisani.aios.core.permission.PermissionMode;
import com.ouisani.aios.core.permission.PermissionProfile;
import com.ouisani.aios.core.plugin.DynamicToolBridge;
import com.ouisani.aios.core.plugin.ToolDefinition;
import com.ouisani.aios.core.review.ReviewGate;
import com.ouisani.aios.core.security.Guardrail;
import com.ouisani.aios.core.security.GuardrailEngine;
import com.ouisani.aios.core.telemetry.TelemetryService;
import com.ouisani.aios.core.trace.TraceSpan;
import com.ouisani.aios.core.trace.TracingManager;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 查询引擎 — AIOS 的核心推理循环，对标 Claude Code 的 query.ts。
 * <p>
 * 实现完整的 Agent Loop：
 * 1. 用户输入 → 构建系统提示词
 * 2. 调用 LLM → 解析响应
 * 3. 检测工具调用 → 执行工具 → 将结果反馈给 LLM
 * 4. 重复直到 LLM 不再调用工具（生成纯文本回复）
 * <p>
 * OS 类比：相当于 CPU 的指令周期 — 取指(IF) → 译码(ID) → 执行(EX) → 写回(WB)，
 * 直到遇到 HALT 指令。
 */
public class QueryEngine {

    private static final Logger log = LoggerFactory.getLogger(QueryEngine.class);
    private static final Gson GSON = new Gson();

    private static final int MAX_TOOL_ROUNDS = 60;

    /**
     * 专用虚拟线程执行器 — 坚决弃用 ForkJoinPool.commonPool。
     * <p>
     * commonPool 使用平台线程，池大小 = CPU核心数-1。当工具包含阻塞式 I/O
     * （网络请求、文件读写、等待子 Agent）时，commonPool 会瞬间耗尽，
     * 导致所有并行工具调用排队死锁。
     * <p>
     * 虚拟线程执行器为每个任务分配一个虚拟线程，开销极低（~几 KB），
     * 可以轻松支持数千个并发阻塞操作。
     */
    private static final java.util.concurrent.ExecutorService VTHREAD_EXECUTOR =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    static {
        log.info("[InstructionDecoder] 增强型鲁棒字符串指针解析已激活。");
        System.out.println("  \u001B[36m[InstructionDecoder] 增强型鲁棒字符串指针解析已激活。\u001B[0m");
    }

    private final ToolSdk sdk;
    private final String agentId;
    private final String workingDir;
    private final List<Tool<? extends ToolInput>> availableTools;
    private final PermissionChecker permissionChecker;

    /** 当前运行 ID（每次 query() 调用生成一个新的） */
    private String currentRunId;

    // ── 三层历史压缩（借鉴 Agent Zero bulks/topics/current） ──
    private HistoryCompressor historyCompressor;

    /**
     * 创建查询引擎（仅使用内核全局工具）。
     *
     * @param sdk       AIOS SDK
     * @param agentId   Agent ID
     * @param workingDir 工作目录
     */
    public QueryEngine(ToolSdk sdk, String agentId, String workingDir) {
        this(sdk, agentId, workingDir, List.of());
    }

    /**
     * 创建查询引擎（内核全局工具 + 用户空间扩展工具）。
     * <p>
     * 内核工具（Bash, FileRead/Edit/Write 等）对所有 Agent 可用；
     * 扩展工具（TodoWrite, PlanMode 等）仅对具备高级认知能力的 Agent 可用。
     *
     * @param sdk           AIOS SDK
     * @param agentId       Agent ID
     * @param workingDir    工作目录
     * @param extraTools    用户空间扩展工具列表
     */
    public QueryEngine(ToolSdk sdk, String agentId, String workingDir,
                       List<Tool<? extends ToolInput>> extraTools) {
        this.sdk = sdk;
        this.agentId = agentId;
        this.workingDir = workingDir;
        this.availableTools = new ArrayList<>(ToolRegistry.instance().all());
        this.availableTools.addAll(extraTools);
        this.permissionChecker = new PermissionChecker();
        this.historyCompressor = new HistoryCompressor(8000, this::generateSummary);
    }

    /**
     * 创建查询引擎并指定权限模式 — 供 {@link com.ouisani.aios.core.review.ReviewerRunner}
     * 构造 PLAN-mode reviewer 使用（只读锁定）。
     *
     * @param mode 权限模式；null 表示不修改（沿用 DEFAULT）
     */
    public QueryEngine(ToolSdk sdk, String agentId, String workingDir,
                       List<Tool<? extends ToolInput>> extraTools, PermissionMode mode) {
        this(sdk, agentId, workingDir, extraTools);
        if (mode != null) {
            this.permissionChecker.setMode(mode);
        }
    }

    /**
     * 创建查询引擎并应用权限画像 —— 供 RoleBlueprint 驱动的角色拉起使用。
     * <p>
     * 借鉴 OpenScience：reviewer 子 agent 的 {@code *:deny + 只读工具白名单} blindness
     * 由权限层强制（而非 prompt 文字）。画像经 {@link PermissionChecker#applyProfile} 注入，
     * {@code *:deny} 走"默认拒绝 flag + allow 白名单覆盖"语义。
     *
     * @param profile 权限画像；null/empty 为 no-op（零回归）
     */
    public QueryEngine(ToolSdk sdk, String agentId, String workingDir,
                       List<Tool<? extends ToolInput>> extraTools, PermissionProfile profile) {
        this(sdk, agentId, workingDir, extraTools);
        this.permissionChecker.applyProfile(profile);
    }

    /** package-private 访问器 —— 供同包测试验证画像注入后的权限决策。 */
    PermissionChecker permissionChecker() {
        return permissionChecker;
    }

    /**
     * 执行一次完整的查询循环。
     *
     * @param userMessage 用户输入
     * @return 最终的文本回复
     */
    public String query(String userMessage) {
        return query(userMessage, "");
    }

    /**
     * 执行一次完整的查询循环，带额外系统上下文。
     *
     * @param userMessage      用户输入
     * @param systemContext    额外的系统上下文（如 RAG 搜索结果）
     * @return 最终的文本回复
     */
    public String query(String userMessage, String systemContext) {
        // ── Tracing Span：TASK 级，覆盖整个 query 生命周期 ──
        TraceSpan taskSpan = TracingManager.instance().startSpan("agent.query", TraceSpan.SpanType.TASK);
        if (taskSpan != null) {
            taskSpan.setAttribute("agent_id", agentId);
            taskSpan.setAttribute("user_message_length", userMessage != null ? userMessage.length() : 0);
        }
        try {
            return doQuery(userMessage, systemContext, taskSpan);
        } finally {
            if (taskSpan != null) {
                TracingManager.instance().endSpan(taskSpan.spanId());
            }
        }
    }

    /**
     * query 方法的实际实现 — 由 {@link #query(String, String)} 包装在 TASK span 中调用。
     * <p>
     * 此方法假定调用方已创建 TASK span 并负责其生命周期。
     */
    private String doQuery(String userMessage, String systemContext, TraceSpan taskSpan) {
        // ── 标准化事件协议：RUN_STARTED ──
        currentRunId = UUID.randomUUID().toString();
        AiosEventSchema.emit(AiosEventSchema.runStarted(agentId, currentRunId, userMessage));

        // 将 run_id 关联到 TASK span，便于 Span 树与 AiosEvent 交叉查询
        if (taskSpan != null) {
            taskSpan.setAttribute("run_id", currentRunId);
        }

        log.info("[QueryEngine] 正在启动查询循环，agent={}，消息长度={}", agentId, userMessage.length());

        // ── DynamicToolBridge: 根据用户消息自动挂载相关工具 ──
        List<ToolDefinition> autoMounted = DynamicToolBridge.getInstance()
                .autoMountByQuery(agentId, userMessage);
        if (!autoMounted.isEmpty()) {
            log.info("[QueryEngine] DynamicToolBridge 自动挂载了 {} 个工具，agent='{}': {}",
                    autoMounted.size(), agentId,
                    autoMounted.stream().map(ToolDefinition::name).toList());
        }

        // 构建系统提示词
        String systemPrompt = buildSystemPrompt(systemContext);

        // ── 三层历史压缩（借鉴 Agent Zero bulks/topics/current） ──
        // 初始化历史压缩器，将初始用户消息加入历史
        historyCompressor.clear();
        historyCompressor.addMessage("user", userMessage);

        // 构建完整 prompt（包含工具描述）— 通过历史压缩器管理
        String fullPrompt = systemPrompt + "\n\n" + historyCompressor.buildHistoryText();

        // Agent Loop — 最多 MAX_TOOL_ROUNDS 轮
        int consecutiveErrors = 0;
        final int MAX_CONSECUTIVE_ERRORS = 3;
        // 【动刀4】参数级错误熔断器 — 防止 LLM 陷入语法错误死循环
        int syntaxErrorCount = 0;
        final int MAX_SYNTAX_ERRORS = 2; // 参数级错误最多重试 2 次，超过则熔断
        String lastLlmResponse = null;
        int reviewFixCycles = 0; // ReviewGate soft/hard 修复轮次计数

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            log.info("[QueryEngine] 第 {}/{} 轮", round + 1, MAX_TOOL_ROUNDS);

            // ── Tracing Span：TURN 级，覆盖单轮 LLM+工具循环 ──
            TraceSpan turnSpan = TracingManager.instance().startSpan(
                    "agent.turn." + (round + 1), TraceSpan.SpanType.TURN);
            if (turnSpan != null) {
                turnSpan.setAttribute("round", round + 1);
            }
            try {
                // ── 标准化事件协议：STEP_STARTED ──
                int stepNum = round + 1;
                AiosEventSchema.emit(AiosEventSchema.stepStarted(agentId, currentRunId, stepNum));

            // ── InputGuardrail: 并行启动输入护栏检查（不阻塞 LLM 调用） ──
            // 护栏与 LLM 调用同时运行；护栏触发则取消/丢弃模型响应，节省 Token
            CompletableFuture<Guardrail.GuardrailResult> inputGuardrailFuture =
                    GuardrailEngine.instance().checkInput(agentId, fullPrompt);

            // 调用 LLM（含异常防御）
            String llmResponse;
            // ── Tracing Span：GENERATION 级，覆盖单次 LLM 调用 ──
            TraceSpan genSpan = TracingManager.instance().startSpan("llm.generate", TraceSpan.SpanType.GENERATION);
            if (genSpan != null) {
                genSpan.setAttribute("prompt_length", fullPrompt.length());
                genSpan.setAttribute("agent_id", agentId);
            }
            try {
                // ── 流式渲染 + 标准化事件 ──
                // 借鉴 CopilotKit：LLM 响应逐 token 推送到前端
                StringBuilder streamingResponse = new StringBuilder();
                AiosEventSchema.emit(AiosEventSchema.textMessageStart(agentId, currentRunId, stepNum));

                final int currentRound = round + 1;
                llmResponse = sdk.thinkStream(agentId, fullPrompt, delta -> {
                    streamingResponse.append(delta);
                    // 每个 delta 都广播 TEXT_MESSAGE_CONTENT 事件
                    AiosEventSchema.emit(AiosEventSchema.textMessageContent(agentId, currentRunId, currentRound, delta));
                });

                AiosEventSchema.emit(AiosEventSchema.textMessageEnd(agentId, currentRunId, stepNum,
                        streamingResponse.toString()));
                consecutiveErrors = 0;
                lastLlmResponse = llmResponse;
                if (genSpan != null) {
                    genSpan.setAttribute("response_length", llmResponse != null ? llmResponse.length() : 0);
                    genSpan.setAttribute("model", "default");
                    genSpan.setStatus(TraceSpan.Status.OK);
                }
            } catch (Exception e) {
                if (genSpan != null) {
                    genSpan.setAttribute("error", e.getMessage());
                    genSpan.setStatus(TraceSpan.Status.ERROR);
                }
                consecutiveErrors++;
                String errMsg = "LLM 推理期间系统错误: " + e.getMessage();
                log.error("\u001B[31m[QueryEngine] 捕获异常。将错误反馈到 LLM 上下文。({}/{})\u001B[0m",
                        consecutiveErrors, MAX_CONSECUTIVE_ERRORS, e);

                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    log.error("[QueryEngine] {} 次连续错误 — 中止查询循环", consecutiveErrors);
                    return "查询已中止，连续 " + consecutiveErrors + " 次系统错误。最后错误: " + errMsg;
                }

                // 将错误注入对话历史，让 LLM 自主判断是否换方式重试
                fullPrompt = fullPrompt + "\n\n[System Error] " + errMsg
                        + "\n\nPlease adjust your approach and try again. If the error persists, inform the user.";
                continue;
            } finally {
                if (genSpan != null) {
                    TracingManager.instance().endSpan(genSpan.spanId());
                }
            }

            // ── InputGuardrail: 检查结果，tripwire 触发则丢弃 LLM 响应 ──
            // 护栏与 LLM 并行运行，此处等待护栏完成；触发则取消当前轮次
            Guardrail.GuardrailResult inputGuardrailResult;
            try {
                inputGuardrailResult = inputGuardrailFuture.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                inputGuardrailFuture.cancel(true);
                inputGuardrailResult = Guardrail.GuardrailResult.allowed();
                log.warn("[QueryEngine] InputGuardrail 检查超时或异常，放行: {}", e.getMessage());
            }
            if (inputGuardrailResult.tripwireTriggered()) {
                log.warn("[QueryEngine] InputGuardrail 触发，丢弃 LLM 响应: {}",
                        inputGuardrailResult.outputInfo());
                String blocked = "输入被护栏拦截: " + inputGuardrailResult.outputInfo();
                AiosEventSchema.emit(AiosEventSchema.runFinished(agentId, currentRunId, blocked));
                return blocked;
            }

            // 检测工具调用
            if (registeredToolNames == null) {
                registeredToolNames = new HashSet<>();
                for (Tool<? extends ToolInput> tool : availableTools) {
                    registeredToolNames.add(tool.name());
                }
            }
            List<ToolCall> toolCalls = ToolCallParser.parseToolCalls(llmResponse, registeredToolNames);

            if (toolCalls.isEmpty()) {
                // 没有工具调用，返回纯文本回复
                log.info("[QueryEngine] 未检测到工具调用，返回最终响应");
                // ── OutputGuardrail: 最终输出前验证 ──
                Guardrail.GuardrailResult outputGuardrailResult =
                        GuardrailEngine.instance().checkOutput(agentId, llmResponse);
                if (outputGuardrailResult.tripwireTriggered()) {
                    log.warn("[QueryEngine] OutputGuardrail 触发: {}",
                            outputGuardrailResult.outputInfo());
                    String blocked = "输出被护栏拦截: " + outputGuardrailResult.outputInfo();
                    AiosEventSchema.emit(AiosEventSchema.runFinished(agentId, currentRunId, blocked));
                    return blocked;
                }
                // ── ReviewGate: finalize 守门（盲审 reviewer + 确定性兜底）──
                ReviewGate.ReviewGateResult rgResult;
                try {
                    rgResult = ReviewGate.review(new ReviewGate.ReviewContext(
                            sdk, agentId, currentRunId, workingDir, llmResponse,
                            reviewFixCycles, /*canReenter*/ true));
                } catch (Throwable t) {
                    log.warn("[QueryEngine] ReviewGate 异常，跳过 gate: {}", t.getMessage());
                    rgResult = ReviewGate.ReviewGateResult.returnOriginal(llmResponse);
                }
                switch (rgResult.action()) {
                    case SKIP, RETURN -> {
                        AiosEventSchema.emit(AiosEventSchema.runFinished(agentId, currentRunId, rgResult.finalAnswer()));
                        return rgResult.finalAnswer();
                    }
                    case REENTER -> {
                        // soft/hard：将 review 报告作为 user 提醒注入历史，继续循环
                        historyCompressor.addMessage("assistant", llmResponse);
                        historyCompressor.addMessage("user", rgResult.fixReminder());
                        fullPrompt = systemPrompt + "\n\n" + historyCompressor.buildHistoryText()
                                + "\n\nPlease address the reviewer findings above.";
                        reviewFixCycles++;
                        continue;
                    }
                }
            }

            // 执行工具调用（并行执行 — 多个工具同时运行，大幅提升吞吐）
            StringBuilder toolResults = new StringBuilder();
            if (toolCalls.size() == 1) {
                // 单工具调用 — 直接执行，无需并行开销
                ToolCall tc = toolCalls.get(0);
                log.info("[QueryEngine] 正在执行工具: {}（第 {} 轮）", tc.toolName, round + 1);
                System.out.printf("[QueryEngine] ├─ 工具调用: %s%n", tc.toolName);

                String result;
                try {
                    // ── 标准化事件协议：TOOL_CALL_STARTED ──
                    AiosEventSchema.emit(AiosEventSchema.toolCallStarted(agentId, currentRunId, round + 1, tc.toolName, tc.paramsJson));
                    result = executeTool(tc);
                    consecutiveErrors = 0;
                    syntaxErrorCount = 0; // 成功执行，重置语法错误计数
                    DynamicToolBridge.getInstance().markToolUsed(tc.toolName);
                } catch (Exception e) {
                    consecutiveErrors++;
                    result = "工具 '" + tc.toolName + "' 执行期间系统错误: " + e.getMessage();
                    log.error("[QueryEngine] 工具 '{}' 抛出异常。({}/{})",
                            tc.toolName, consecutiveErrors, MAX_CONSECUTIVE_ERRORS, e);

                    // 【动刀4】参数级错误熔断器 — 检测语法/参数级错误
                    String errMsg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                    boolean isSyntaxError = errMsg.contains("path required") || errMsg.contains("path is null")
                            || errMsg.contains("missing") || errMsg.contains("required")
                            || errMsg.contains("empty command") || errMsg.contains("invalid")
                            || e instanceof IllegalArgumentException;
                    if (isSyntaxError) {
                        syntaxErrorCount++;
                        log.warn("[QueryEngine] 参数级错误 ({}/{})，工具: {}，错误: {}",
                                syntaxErrorCount, MAX_SYNTAX_ERRORS, tc.toolName, e.getMessage());
                        if (syntaxErrorCount > MAX_SYNTAX_ERRORS) {
                            log.error("[QueryEngine] 参数级错误熔断器触发 — LLM 陷入语法错误死循环，终止执行！");
                            return "查询已中止：LLM 连续 " + syntaxErrorCount + " 次参数级错误（如缺少必填参数），"
                                    + "已触发熔断器终止执行。最后错误: " + result;
                        }
                    }

                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        log.error("[QueryEngine] {} 次连续错误 — 中止查询循环", consecutiveErrors);
                        return "查询已中止，连续 " + consecutiveErrors + " 次系统错误。最后错误: " + result;
                    }
                }

                toolResults.append("<tool_result name=\"").append(tc.toolName).append("\">\n");
                String compactedResult = compactToolOutput(tc.toolName, result);
                toolResults.append(compactedResult).append("\n");
                toolResults.append("</tool_result>\n\n");
                // ── 标准化事件协议：TOOL_CALL_COMPLETED ──
                AiosEventSchema.emit(AiosEventSchema.toolCallCompleted(agentId, currentRunId, round + 1, tc.toolName, result, true));
            } else {
                // 多工具调用 — 并行执行（CompletableFuture + 虚拟线程）
                log.info("[QueryEngine] 正在并行执行 {} 个工具（第 {} 轮）", toolCalls.size(), round + 1);
                System.out.printf("[QueryEngine] ├─ 并行工具调用: %d%n", toolCalls.size());

                java.util.List<java.util.concurrent.CompletableFuture<ToolResult>> futures = new java.util.ArrayList<>();
                for (ToolCall tc : toolCalls) {
                    // 【动刀1】强制挂载虚拟线程池，弃用 ForkJoinPool.commonPool
                    java.util.concurrent.CompletableFuture<ToolResult> future = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try {
                            String result = executeTool(tc);
                            DynamicToolBridge.getInstance().markToolUsed(tc.toolName);
                            return new ToolResult(tc.toolName, result, null);
                        } catch (Exception e) {
                            return new ToolResult(tc.toolName, null, e);
                        }
                    }, VTHREAD_EXECUTOR);
                    futures.add(future);
                }

                // 等待所有工具完成
                try {
                    java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                            .get(120, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    log.warn("[QueryEngine] 部分并行工具调用超时（120秒）");
                } catch (Exception e) {
                    log.warn("[QueryEngine] 并行工具执行被中断: {}", e.getMessage());
                }

                // 收集结果
                for (int i = 0; i < futures.size(); i++) {
                    ToolCall tc = toolCalls.get(i);
                    ToolResult tr;
                    try {
                        tr = futures.get(i).getNow(new ToolResult(tc.toolName, "Timed out", null));
                    } catch (Exception e) {
                        tr = new ToolResult(tc.toolName, null, e);
                    }

                    if (tr.error != null) {
                        consecutiveErrors++;
                        String errMsg = "工具 '" + tr.toolName + "' 执行期间系统错误: " + tr.error.getMessage();
                        toolResults.append("<tool_result name=\"").append(tr.toolName).append("\">\n");
                        toolResults.append(errMsg).append("\n");
                        toolResults.append("</tool_result>\n\n");

                        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                            log.error("[QueryEngine] {} 次连续错误 — 中止查询循环", consecutiveErrors);
                            return "查询已中止，连续 " + consecutiveErrors + " 次系统错误。";
                        }
                    } else {
                        consecutiveErrors = 0;
                        toolResults.append("<tool_result name=\"").append(tr.toolName).append("\">\n");
                        String compactedResult = compactToolOutput(tr.toolName, tr.result);
                        toolResults.append(compactedResult).append("\n");
                        toolResults.append("</tool_result>\n\n");
                    }
                }
            }

            // ── 三层历史压缩（借鉴 Agent Zero bulks/topics/current） ──
            // 不再无限追加 fullPrompt，而是通过 HistoryCompressor 管理历史
            historyCompressor.addMessage("assistant", llmResponse);
            historyCompressor.addMessage("tool", toolResults.toString());

            // 重建 fullPrompt：系统提示 + 压缩后的历史 + 继续指令
            fullPrompt = systemPrompt + "\n\n" + historyCompressor.buildHistoryText()
                    + "\n\nPlease continue based on the tool results above.";

            // ── 标准化事件协议：STEP_FINISHED ──
            AiosEventSchema.emit(AiosEventSchema.stepFinished(agentId, currentRunId, stepNum));
            } finally {
                // ── Tracing Span：TURN 结束（覆盖 continue/return/正常退出所有路径） ──
                if (turnSpan != null) {
                    TracingManager.instance().endSpan(turnSpan.spanId());
                }
            }
        }

        log.warn("[QueryEngine] 已达到最大工具轮次 ({})", MAX_TOOL_ROUNDS);
        String maxAnswer = "查询已达到最大工具执行轮次 (" + MAX_TOOL_ROUNDS + ")。";
        // ── ReviewGate: max-rounds 出口（canReenter=false → 永不 REENTER，降级 annotate/拒绝）──
        ReviewGate.ReviewGateResult rgResult;
        try {
            rgResult = ReviewGate.review(new ReviewGate.ReviewContext(
                    sdk, agentId, currentRunId, workingDir, maxAnswer,
                    Integer.MAX_VALUE, /*canReenter*/ false));
        } catch (Throwable t) {
            log.warn("[QueryEngine] ReviewGate 异常（max-rounds 出口），跳过 gate: {}", t.getMessage());
            rgResult = ReviewGate.ReviewGateResult.returnOriginal(maxAnswer);
        }
        String finalOut = rgResult.finalAnswer() != null ? rgResult.finalAnswer() : maxAnswer;
        // ── 标准化事件协议：RUN_FINISHED ──
        AiosEventSchema.emit(AiosEventSchema.runFinished(agentId, currentRunId, finalOut));
        return finalOut;
    }

    /**
     * 带扩展点的查询 — 借鉴 Agent Zero 的 @extensible 机制。
     * <p>
     * 包装 {@link #query(String, String)}，支持 before/after 钩子。
     * <ul>
     *   <li>before 钩子可短路：返回非 null 时直接作为最终结果</li>
     *   <li>after 钩子可修改返回值</li>
     * </ul>
     * 不替换原始 query 方法，仅提供带扩展点的入口。
     *
     * @param userMessage   用户输入
     * @param systemContext 额外的系统上下文
     * @return 最终的文本回复（经过 after 钩子处理）
     */
    @com.ouisani.aios.core.plugin.Extensible("query")
    public String queryWithExtensions(String userMessage, String systemContext) {
        // before 钩子
        Map<String, Object> hookArgs = new HashMap<>();
        hookArgs.put("userMessage", userMessage);
        hookArgs.put("systemContext", systemContext);
        Object shortCircuit = com.ouisani.aios.core.plugin.ExtensibleHookRegistry.before("query", this, hookArgs);
        if (shortCircuit != null) {
            String result = (String) shortCircuit;
            return (String) com.ouisani.aios.core.plugin.ExtensibleHookRegistry.after("query", this, result, hookArgs);
        }

        // 执行原始逻辑
        String result = query(userMessage, systemContext);

        // after 钩子
        return (String) com.ouisani.aios.core.plugin.ExtensibleHookRegistry.after("query", this, result, hookArgs);
    }

    /**
     * 使用 LLM 生成对话摘要 — 借鉴 Agent Zero 的 utility model 摘要。
     */
    private String generateSummary(List<HistoryCompressor.Message> messages, String hint) {
        try {
            StringBuilder input = new StringBuilder();
            for (HistoryCompressor.Message m : messages) {
                input.append("[").append(m.role()).append("]: ").append(m.content()).append("\n");
            }
            String prompt = hint + "\n\n" + input + "\n\nSummary (max 200 chars):";
            String summary = sdk.think(agentId, prompt);
            // 截断摘要，防止 LLM 返回过长
            return summary.length() > 300 ? summary.substring(0, 300) : summary;
        } catch (Exception e) {
            log.debug("[QueryEngine] 摘要生成失败，使用截断回退: {}", e.getMessage());
            // 回退：简单截断第一条消息
            if (messages.isEmpty()) return "";
            String content = messages.get(0).content();
            return content.length() > 200 ? content.substring(0, 200) : content;
        }
    }

    /**
     * 构建系统提示词 — 包含工具描述、行为准则和使用指南。
     * <p>
     * 集成 DynamicToolBridge：在静态工具列表之后，
     * 追加动态挂载的工具 Schema，实现按需工具注入。
     * <p>
     * 集成 SkillLoader：将已激活技能的行为准则注入系统提示词，
     * 实现 Karpathy Guidelines 等行为协议的动态插拔。
     */
    private String buildSystemPrompt(String extraContext) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are an AIOS agent with access to the following tools:\n\n");

        // 工具列表
        for (Tool<? extends ToolInput> tool : availableTools) {
            sb.append("### ").append(tool.name()).append("\n");
            sb.append(tool.description()).append("\n");
            sb.append("Input: ").append(tool.inputSchema()).append("\n");
            String p = tool.prompt();
            if (p != null && !p.isBlank()) {
                sb.append("Notes: ").append(p).append("\n");
            }
            sb.append("\n");
        }

        // ── DynamicToolBridge: 注入动态挂载的工具 Schema ──
        String dynamicToolsDesc = DynamicToolBridge.getInstance().getMountedToolsDescription(agentId);
        if (dynamicToolsDesc != null && !dynamicToolsDesc.isBlank()) {
            sb.append(dynamicToolsDesc).append("\n");
        }

        // ── SkillLoader: 注入已激活技能的行为准则 ──
        String activeSkillsPrompt = com.ouisani.aios.core.skill.SkillLoader.formatActiveSkillsAsPrompt();
        if (activeSkillsPrompt != null && !activeSkillsPrompt.isBlank()) {
            sb.append(activeSkillsPrompt).append("\n");
        }

        // 工具调用格式说明
        sb.append("## Tool Call Format\n");
        sb.append("To call a tool, use XML tags with the tool name and JSON parameters:\n");
        sb.append("<tool_name>{\"param\":\"value\"}</tool_name>\n");
        sb.append("You may call multiple tools in one response. After receiving tool results, continue your reasoning.\n\n");

        // 额外上下文
        if (extraContext != null && !extraContext.isBlank()) {
            sb.append("## Additional Context\n");
            sb.append(extraContext).append("\n\n");
        }

        sb.append("## Working Directory\n");
        sb.append(workingDir != null ? workingDir : System.getProperty("user.dir")).append("\n");

        return sb.toString();
    }

    /**
     * 合法的工具名称集合 — 用于过滤 LLM 响应中的误匹配 XML 标签。
     * <p>
     * LLM 在描述工具格式时可能输出 &lt;tool_name&gt;{...}&lt;/tool_name&gt; 等示例标签，
     * 这些不应被解析为真正的工具调用。只有与已注册工具名匹配的标签才是合法调用。
     */
    private Set<String> registeredToolNames;

    /**
     * 执行单个工具调用（含权限检查）。
     * <p>
     * 查找顺序：先从当前引擎的 availableTools（含扩展工具）查找，
     * 再回退到全局 ToolRegistry 查找。
     */
    @SuppressWarnings("unchecked")
    private String executeTool(ToolCall tc) {
        // ── Tracing Span：FUNCTION 级，覆盖单次工具调用 ──
        TraceSpan toolSpan = TracingManager.instance().startSpan("tool." + tc.toolName, TraceSpan.SpanType.FUNCTION);
        long toolStartMs = System.currentTimeMillis();
        if (toolSpan != null) {
            toolSpan.setAttribute("tool_name", tc.toolName);
            toolSpan.setAttribute("success", true);
        }
        try {
        // 优先从 availableTools 查找（包含用户空间扩展工具）
        Optional<Tool<ToolInput>> opt = availableTools.stream()
                .filter(t -> t.name().equals(tc.toolName))
                .map(t -> (Tool<ToolInput>) t)
                .findFirst();

        // 回退到全局 ToolRegistry
        if (opt.isEmpty()) {
            opt = ToolRegistry.instance().get(tc.toolName);
        }

        if (opt.isEmpty()) {
            String msg = "未知工具: " + tc.toolName + "。可用工具: "
                    + availableTools.stream().map(Tool::name).reduce((a, b) -> a + ", " + b).orElse("无");
            log.warn("[QueryEngine] {}", msg);
            if (toolSpan != null) {
                toolSpan.setAttribute("success", false);
                toolSpan.setAttribute("error", "unknown_tool");
                toolSpan.setStatus(TraceSpan.Status.ERROR);
            }
            return msg;
        }

        Tool<ToolInput> tool = opt.get();
        ToolContext context = new ToolContext(agentId, sdk, workingDir);

        try {
            ToolInput input = parseInput(tc.paramsJson(), tool);

            // ── 权限检查 ──
            PermissionDecision decision = permissionChecker.checkPermission(tool, input, context);
            if (decision.isDenied()) {
                log.warn("[QueryEngine] 工具 '{}' 被拒绝: {}", tc.toolName, decision.message());
                return "权限被拒绝: " + decision.message();
            }
            if (decision.needsPrompt()) {
                log.info("[QueryEngine] 工具 '{}' 需要确认（Agent 模式下自动允许）", tc.toolName);
            }

            // ── PreToolUse Hook ──
            Map<String, Object> hookData = new HashMap<>();
            hookData.put("tool_name", tc.toolName);
            hookData.put("input", input.toJson());
            HookManager.HookResult preResult = HookManager.instance().trigger(
                    HookManager.HookEvent.PRE_TOOL_USE, hookData);
            if (!preResult.proceed()) {
                return "Blocked by PreToolUse hook: " + preResult.message();
            }

            // ── 执行工具 ──
            long startTime = System.currentTimeMillis();
            ToolOutput output = tool.call(input, context);
            long duration = System.currentTimeMillis() - startTime;

            // ── PostToolUse Hook ──
            Map<String, Object> postData = new HashMap<>();
            postData.put("tool_name", tc.toolName);
            postData.put("success", output.success());
            postData.put("duration_ms", duration);
            HookManager.instance().trigger(HookManager.HookEvent.POST_TOOL_USE, postData);

            // ── 遥测记录 ──
            TelemetryService.instance().recordToolUsage(tc.toolName, duration);

            log.info("[QueryEngine] 工具 '{}' 完成：success={} ({}ms)", tc.toolName, output.success(), duration);

            // ── ToolGuardrail: 工具执行后检查输入/输出 ──
            String outputText = output.toText();
            Guardrail.GuardrailResult toolGuardrailResult = GuardrailEngine.instance().checkTool(
                    agentId, tc.toolName, input.toJson(), outputText);
            if (toolGuardrailResult.tripwireTriggered()) {
                log.warn("[QueryEngine] ToolGuardrail 触发 (tool={}): {}",
                        tc.toolName, toolGuardrailResult.outputInfo());
                if (toolGuardrailResult.action() == Guardrail.GuardrailAction.RAISE_EXCEPTION) {
                    return "工具输出被护栏拦截: " + toolGuardrailResult.outputInfo();
                }
                // REJECT_CONTENT: 替换为拦截信息，阻止敏感内容流入对话历史
                return "[ToolGuardrail 拦截] " + toolGuardrailResult.outputInfo();
            }

            // ── Handoff 专用处理：创建 HANDOFF Span 并广播事件到前端 ──
            // Handoff 不终止当前 Agent 的执行循环，仅触发目标 Agent 异步执行
            if (HandoffTool.TOOL_NAME.equals(tc.toolName) && input instanceof HandoffInput handoffInput) {
                handleHandoffExecution(handoffInput, output, toolSpan);
            }
            return outputText;
        } catch (Exception e) {
            if (toolSpan != null) {
                toolSpan.setAttribute("success", false);
                toolSpan.setAttribute("error", e.getMessage());
                toolSpan.setStatus(TraceSpan.Status.ERROR);
            }
            log.error("[QueryEngine] 工具 '{}' 执行失败: {}", tc.toolName, e.getMessage());
            return "工具执行错误: " + e.getMessage();
        }
        } finally {
            if (toolSpan != null) {
                toolSpan.setAttribute("duration_ms", System.currentTimeMillis() - toolStartMs);
                TracingManager.instance().endSpan(toolSpan.spanId());
            }
        }
    }

    /**
     * 处理 Handoff 工具执行后的专用逻辑。
     * <p>
     * Handoff 不终止当前 Agent 的执行循环，仅：
     * <ol>
     *   <li>创建 HANDOFF 类型的 Tracing Span（记录 source/target/reason/context）</li>
     *   <li>广播 handoff 事件到前端（通过 AiosEventSchema STATE_SNAPSHOT）</li>
     * </ol>
     * 实际的目标 Agent 派发已在 {@link HandoffTool#call} 中完成。
     *
     * @param handoffInput Handoff 输入参数
     * @param output       工具执行输出
     * @param parentSpan   父 Span（FUNCTION 级），HANDOFF Span 作为其子 Span
     */
    private void handleHandoffExecution(HandoffInput handoffInput, ToolOutput output, TraceSpan parentSpan) {
        // ── 创建 HANDOFF 类型的 Tracing Span ──
        TraceSpan handoffSpan = TracingManager.instance().startSpan(
                "agent.handoff", TraceSpan.SpanType.HANDOFF);
        if (handoffSpan != null) {
            handoffSpan.setAttribute("source_agent", agentId);
            handoffSpan.setAttribute("target_agent", handoffInput.getTargetAgent());
            handoffSpan.setAttribute("reason", handoffInput.getReason());
            handoffSpan.setAttribute("context_summary_length",
                    handoffInput.getContextSummary() != null ? handoffInput.getContextSummary().length() : 0);
            handoffSpan.setAttribute("success", output.success());
            if (!output.success()) {
                handoffSpan.setStatus(TraceSpan.Status.ERROR);
            } else {
                handoffSpan.setStatus(TraceSpan.Status.OK);
            }
            TracingManager.instance().endSpan(handoffSpan.spanId());
            log.info("[QueryEngine] Handoff Span 已创建: {} -> {} (success={})",
                    agentId, handoffInput.getTargetAgent(), output.success());
        }

        // ── 广播 handoff 事件到前端（通过 AiosEventSchema STATE_SNAPSHOT） ──
        try {
            Map<String, Object> handoffState = new HashMap<>();
            handoffState.put("type", "handoff");
            handoffState.put("source_agent", agentId);
            handoffState.put("target_agent", handoffInput.getTargetAgent());
            handoffState.put("reason", handoffInput.getReason());
            handoffState.put("context_summary", handoffInput.getContextSummary());
            handoffState.put("success", output.success());
            handoffState.put("result", output.toText());
            AiosEventSchema.emit(AiosEventSchema.stateSnapshot(
                    agentId, currentRunId, 0, handoffState));
        } catch (Exception e) {
            log.debug("[QueryEngine] Handoff 前端事件广播失败: {}", e.getMessage());
        }
    }

    /**
     * 简单的 JSON 参数解析 — 根据工具类型构造对应的 Input record。
     * <p>
     * 这是一个简化的实现，生产环境应使用 Jackson/Gson 进行完整解析。
     */
    private ToolInput parseInput(String paramsJson, Tool<ToolInput> tool) {
        String toolName = tool.name();

        return switch (toolName) {
            case "bash" -> new BashTool.Input(extractJsonString(paramsJson, "command"),
                    extractJsonInt(paramsJson, "timeoutSeconds", 120));
            case "file_read" -> new FileReadTool.Input(extractJsonString(paramsJson, "path"),
                    extractJsonInt(paramsJson, "offset", 0),
                    extractJsonInt(paramsJson, "limit", 2000));
            case "file_edit" -> new FileEditTool.Input(extractJsonString(paramsJson, "path"),
                    extractJsonString(paramsJson, "old_string"),
                    extractJsonString(paramsJson, "new_string"));
            case "file_write" -> new FileWriteTool.Input(extractJsonString(paramsJson, "path"),
                    extractJsonString(paramsJson, "content"));
            case "grep" -> new GrepTool.Input(extractJsonString(paramsJson, "pattern"),
                    extractJsonString(paramsJson, "path", "."),
                    extractJsonString(paramsJson, "glob", ""),
                    extractJsonInt(paramsJson, "contextLines", 0));
            case "glob" -> new GlobTool.Input(extractJsonString(paramsJson, "pattern"),
                    extractJsonString(paramsJson, "path", "."));
            case "web_fetch" -> new WebFetchTool.Input(extractJsonString(paramsJson, "url"),
                    extractJsonString(paramsJson, "prompt", ""));
            case "agent" -> new AgentTool.Input(
                    extractJsonString(paramsJson, "prompt"),
                    extractJsonString(paramsJson, "subagent_type", ""),
                    extractJsonBool(paramsJson, "run_in_background", false),
                    extractJsonString(paramsJson, "description", ""));
            case "todo_write" -> new com.ouisani.aios.user.apps.omnifactory.tools.TodoWriteTool.Input(List.of()); // 简化：完整解析需 Jackson
            // ask_user_question 已删除 — 阻塞式人类 I/O 违反异步 IPC 原则
            // 智能体如需与人类交互，必须通过 send_message 发送 type:user_prompt 到 EventBus UI 频道
            case "plan_mode" -> new com.ouisani.aios.user.apps.omnifactory.tools.PlanModeTool.Input(
                    extractJsonString(paramsJson, "action", "enter"),
                    extractJsonString(paramsJson, "plan", ""));
            case "human_response" -> GSON.fromJson(paramsJson, HumanResponseTool.HumanResponseInput.class);
            case "frontend_tool" -> GSON.fromJson(paramsJson, FrontendTool.FrontendToolInput.class);
            case "transfer_to_agent" -> new HandoffInput(
                    extractJsonString(paramsJson, "target_agent"),
                    extractJsonString(paramsJson, "reason"),
                    extractJsonString(paramsJson, "context_summary"));
            default -> throw new IllegalArgumentException("Unknown tool for input parsing: " + toolName);
        };
    }

    // ── 简易 JSON 提取工具（纯 indexOf 线性扫描，无正则，绝不 StackOverflow） ──

    private static String extractJsonString(String json, String key) {
        return extractJsonString(json, key, "");
    }

    private static String extractJsonString(String json, String key, String defaultVal) {
        String searchKey = "\"" + key + "\"";
        int keyIdx = json.indexOf(searchKey);
        if (keyIdx < 0) return defaultVal;

        // 找到冒号后的值
        int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
        if (colonIdx < 0) return defaultVal;

        // 跳过空白
        int valStart = colonIdx + 1;
        while (valStart < json.length() && json.charAt(valStart) == ' ') valStart++;

        if (valStart >= json.length() || json.charAt(valStart) != '"') return defaultVal;

        // 提取引号内的字符串值，处理转义
        StringBuilder sb = new StringBuilder();
        int i = valStart + 1;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\') {
                i++;
                if (i < json.length()) {
                    char next = json.charAt(i);
                    if (next == '"') sb.append('"');
                    else if (next == 'n') sb.append('\n');
                    else if (next == 't') sb.append('\t');
                    else if (next == '\\') sb.append('\\');
                    else sb.append(next);
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
            i++;
        }
        return sb.toString();
    }

    private static int extractJsonInt(String json, String key, int defaultVal) {
        String searchKey = "\"" + key + "\"";
        int keyIdx = json.indexOf(searchKey);
        if (keyIdx < 0) return defaultVal;

        int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
        if (colonIdx < 0) return defaultVal;

        int valStart = colonIdx + 1;
        while (valStart < json.length() && json.charAt(valStart) == ' ') valStart++;

        int valEnd = valStart;
        while (valEnd < json.length() && (Character.isDigit(json.charAt(valEnd)) || json.charAt(valEnd) == '-')) {
            valEnd++;
        }

        if (valEnd == valStart) return defaultVal;
        try {
            return Integer.parseInt(json.substring(valStart, valEnd));
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private static boolean extractJsonBool(String json, String key, boolean defaultVal) {
        String searchKey = "\"" + key + "\"";
        int keyIdx = json.indexOf(searchKey);
        if (keyIdx < 0) return defaultVal;

        int colonIdx = json.indexOf(':', keyIdx + searchKey.length());
        if (colonIdx < 0) return defaultVal;

        int valStart = colonIdx + 1;
        while (valStart < json.length() && json.charAt(valStart) == ' ') valStart++;

        if (valStart + 4 <= json.length() && json.substring(valStart, valStart + 4).equals("true")) return true;
        if (valStart + 5 <= json.length() && json.substring(valStart, valStart + 5).equals("false")) return false;

        return defaultVal;
    }

    /**
     * 工具调用记录。
     */
    record ToolCall(String toolName, String paramsJson) {}

    /**
     * 并行工具执行结果记录。
     */
    private record ToolResult(String toolName, String result, Exception error) {}

    /**
     * 上下文瘦身：防止工具输出过长导致 Prompt 膨胀 → API 超时。
     */
    private String compactToolOutput(String toolName, String result) {
        if (result == null) return "";
        if (result.length() > 1000) {
            String compacted = result.substring(0, 1000)
                    + "\n... [AIOS Kernel: 截断过多重复上下文以防止脑死亡, 原始长度="
                    + result.length() + "] ...";
            log.info("[QueryEngine] 上下文已压缩：工具 '{}' {} → {} 字符",
                    toolName, result.length(), compacted.length());
            return compacted;
        }
        return result;
    }
}
