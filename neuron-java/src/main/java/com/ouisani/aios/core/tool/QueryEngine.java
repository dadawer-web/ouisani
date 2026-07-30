package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.middleware.Middleware;
import com.ouisani.aios.core.middleware.MiddlewareRegistry;
import com.ouisani.aios.core.network.AiosEventSchema;
import com.ouisani.aios.core.network.AiosEventSchema.AiosEvent;
import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.llm.StreamCancellationHook;
import com.ouisani.aios.core.permission.PermissionChecker;
import com.ouisani.aios.core.permission.PermissionDecision;
import com.ouisani.aios.core.permission.PermissionMode;
import com.ouisani.aios.core.permission.PermissionProfile;
import com.ouisani.aios.core.permission.ToolPermissionChannel;
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

    // ── 收敛检测器（连续 2 轮同文件同 hash 则终止，避免 v1→v5 无意义重写）──
    private ConvergenceTracker convergenceTracker = new ConvergenceTracker();

    // ── 流式中断（借鉴 OpenWorker engine.py:120-148 request_interrupt）──
    /** 用户请求中断 — Stop 按钮设置此 flag */
    private volatile boolean interruptRequested = false;
    /** 当前 LLM 流的中断钩子 — requestInterrupt() 调用 hook.cancel() 打断 readLine() */
    private volatile StreamCancellationHook streamHook;

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
        this.historyCompressor = new HistoryCompressor(4000, this::generateSummary);
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
     * 持久化 partial-turn + notice 标记。借鉴 OpenWorker {@code engine.py:331-352}。
     * <p>
     * 非空 partial 先作为 assistant 消息入历史（用户已看到的文本不丢失），
     * 随后追加 display-only 的 notice 标记。notice 在 {@link HistoryCompressor#buildHistoryText()}
     * 中被剥离，provider 永远看不到；但 {@link HistoryCompressor#snapshotMessages()} 保留
     * 供前端 reload 与 {@code on_compress_context} 中间件观察。
     *
     * @param partial    已流式输出的部分响应（可为 null/blank，此时跳过 assistant 消息）
     * @param noticeText 已格式化的 notice 文本，形如 {@code [notice:error] ...} / {@code [notice:interrupted] ...}
     */
    void persistPartialTurn(String partial, String noticeText) {
        if (partial != null && !partial.isBlank()) {
            historyCompressor.addMessage("assistant", partial);
        }
        historyCompressor.addMessage(HistoryCompressor.ROLE_NOTICE, noticeText);
    }

    /** package-private 历史快照访问器 —— 供同包测试验证 partial-turn 持久化结果。 */
    List<HistoryCompressor.Message> historySnapshot() {
        return historyCompressor.snapshotMessages();
    }

    /** package-private provider-feed 访问器 —— 供同包测试验证 notice 被 buildHistoryText 剥离。 */
    String historyText() {
        return historyCompressor.buildHistoryText();
    }

    /**
     * 请求中断当前查询 — 由 Stop 按钮调用。
     * <p>
     * 借鉴 OpenWorker {@code engine.py:120-148} 的 {@code request_interrupt()}：
     * <ul>
     *   <li><b>mid-stream</b>：{@code streamHook.cancel()} 关闭 InputStream，
     *       打断阻塞中的 {@code BufferedReader.readLine()}，
     *       OpenAiAdapter 返回已收到的 partial response</li>
     *   <li><b>loop 检查点</b>：设置 {@code interruptRequested} flag，
     *       Agent Loop 下一轮检查时提前退出</li>
     * </ul>
     * <p>
     * 线程安全：volatile 字段 + 可从任意线程调用（Stop 按钮在 UI 线程）。
     */
    public void requestInterrupt() {
        interruptRequested = true;
        StreamCancellationHook hook = streamHook;
        if (hook != null) {
            log.info("[QueryEngine] 用户请求中断 — 取消 LLM 流式请求");
            hook.cancel();
        } else {
            log.info("[QueryEngine] 用户请求中断 — 无活跃 LLM 流，将在下个检查点退出");
        }
    }

    /** 查询是否被用户中断 */
    public boolean isInterruptRequested() {
        return interruptRequested;
    }

    /**
     * 触发 on_reply 洋葱中间件，包裹最终回复。best-effort：异常时返回原回复。
     * <p>
     * 用于 doQuery 的三个回复出口（output guardrail / finalize gate / max-rounds gate）。
     * leaf 不抛异常（仅返回原回复字符串），中间件 PRE/POST 异常由 registry 统一 catch；
     * 此处 try/catch 仅满足 checked exception 声明 + 终极安全网（D7 best-effort）。
     */
    private String fireOnReplyBestEffort(String original) {
        try {
            return MiddlewareRegistry.instance().fireOnReply(
                    new Middleware.ReplyContext(agentId, currentRunId, original),
                    () -> original);
        } catch (Exception e) {
            log.warn("[QueryEngine] on_reply 中间件异常，返回原回复: {}", e.getMessage());
            return original;
        }
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

        // 构建系统提示词 — on_system_prompt 串行管道（transformer）
        // 【P1 ephemeral context】systemContext 不再 bake 进 systemPrompt，改为每轮以
        // ephemeral <system-context> 块追加到最后一条 user message（send-time only，永不持久化）。
        // systemPrompt 只保留静态身份（工具/技能/格式），跨轮稳定以利 LLM provider 的 KV cache 前缀匹配。
        // 借鉴 OpenWorker engine.py:880-985 的 context_provider() + <system-context> 块。
        String systemPrompt = MiddlewareRegistry.instance().fireOnSystemPrompt(
                buildSystemPrompt(""));

        // ── 三层历史压缩（借鉴 Agent Zero bulks/topics/current） ──
        // 初始化历史压缩器，将初始用户消息加入历史
        historyCompressor.clear();
        convergenceTracker.reset();
        interruptRequested = false;  // 重置中断 flag（新 query 开始）
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
            // ── 中断检查点：每轮开始时检查用户是否按了 Stop ──
            if (interruptRequested) {
                log.info("[QueryEngine] 用户中断请求 — 在第 {} 轮开始时退出", round + 1);
                return fireOnReplyBestEffort("查询已被用户中断。");
            }
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
            // 【Partial-turn 持久化】streamingResponse 提升到 try 外，使 catch 块能访问
            // 已收到的 partial response 并持久化（借鉴 OpenWorker engine.py:331-345）。
            StringBuilder streamingResponse = new StringBuilder();
            try {
                // ── 流式渲染 + 标准化事件 ──
                // 借鉴 CopilotKit：LLM 响应逐 token 推送到前端
                AiosEventSchema.emit(AiosEventSchema.textMessageStart(agentId, currentRunId, stepNum));

                final int currentRound = round + 1;
                // fullPrompt 在循环内会被重新赋值，lambda 捕获需 effectively-final 快照
                final String currentPrompt = fullPrompt;
                // 【P1 ephemeral context】每轮重算 ephemeral 上下文（send-time only，永不持久化）。
                // 当前 = systemContext（如 RAG 结果）；预留扩展点：未来可加入当前时间/git/todo/plan-mode 提醒。
                final String currentEphemeral = buildEphemeralContext(systemContext);
                // ── on_model_call 洋葱中间件 ── LEAF = sdk.thinkStream（含 delta 流式回调）
                // on_reasoning 折叠进此 hook（D2）——代码库无独立 reasoning 阶段，
                // sdk.thinkStream 返回的 token 流混合 reasoning 与 content。
                // ── 绑定流式中断钩子（Stop 按钮能打断 readLine）──
                StreamCancellationHook hook = new StreamCancellationHook();
                streamHook = hook;
                StreamCancellationHook.bindCurrent(hook);
                Middleware.ModelCallResult mcResult;
                try {
                    mcResult = MiddlewareRegistry.instance().fireOnModelCall(
                            new Middleware.ModelCallContext(agentId, currentPrompt, currentRunId, currentRound),
                            () -> Middleware.ModelCallResult.of(sdk.thinkStream(agentId, currentPrompt, currentEphemeral, delta -> {
                                streamingResponse.append(delta);
                                // 每个 delta 都广播 TEXT_MESSAGE_CONTENT 事件
                                AiosEventSchema.emit(AiosEventSchema.textMessageContent(agentId, currentRunId, currentRound, delta));
                            }))
                    );
                } finally {
                    StreamCancellationHook.unbindCurrent();
                    streamHook = null;
                }
                llmResponse = mcResult.response();

                AiosEventSchema.emit(AiosEventSchema.textMessageEnd(agentId, currentRunId, stepNum,
                        streamingResponse.toString()));

                // ── 中断检查：LLM 流被取消时返回 partial response ──
                if (interruptRequested) {
                    log.info("[QueryEngine] LLM 流被用户中断，已收到 {} 字符 partial response",
                            llmResponse != null ? llmResponse.length() : 0);
                    // 【Partial-turn 持久化】中断路径也持久化 partial + interrupted notice。
                    // 借鉴 OpenWorker engine.py:346-352：用户已看到的文本不丢失，notice 标记 reload 存活。
                    persistPartialTurn(llmResponse,
                            "[notice:interrupted] 用户中断了本次查询。");
                    String partialNote = llmResponse != null && !llmResponse.isBlank()
                            ? "\n\n[注：以上内容为中断前已生成的部分响应]"
                            : "";
                    return fireOnReplyBestEffort("查询已被用户中断。" + partialNote);
                }

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

                // 【Partial-turn 持久化】补发 textMessageEnd，让前端文本流闭合（携带已收到的 partial）。
                // 原实现 catch 内不发射此事件，导致前端文本流永不闭合。
                AiosEventSchema.emit(AiosEventSchema.textMessageEnd(
                        agentId, currentRunId, stepNum, streamingResponse.toString()));

                // 【Notice 持久化】partial（assistant）+ error 标记（display-only notice）入历史。
                // 借鉴 OpenWorker engine.py:331-345：用户已看到的文本不丢失，notice 标记 reload 存活
                // 但 buildHistoryText() 剥离，provider 永远看不到。
                String errorNotice = "[notice:error] [System Error] " + errMsg
                        + "\n\nPlease adjust your approach and try again. If the error persists, inform the user.";

                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    // 熔断前也持久化 partial + notice（不丢失已生成内容）
                    persistPartialTurn(streamingResponse.toString(),
                            "[notice:error] [System Error] " + errMsg
                            + "\n\n查询已中止，连续 " + consecutiveErrors + " 次系统错误。");
                    log.error("[QueryEngine] {} 次连续错误 — 中止查询循环", consecutiveErrors);
                    return "查询已中止，连续 " + consecutiveErrors + " 次系统错误。最后错误: " + errMsg;
                }

                // 持久化后从历史重建 prompt（notice 被剥离，LLM 仅见 partial assistant 消息）
                persistPartialTurn(streamingResponse.toString(), errorNotice);
                fullPrompt = systemPrompt + "\n\n" + historyCompressor.buildHistoryText();
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
                    blocked = fireOnReplyBestEffort(blocked);
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
                        String finalOut = fireOnReplyBestEffort(rgResult.finalAnswer());
                        AiosEventSchema.emit(AiosEventSchema.runFinished(agentId, currentRunId, finalOut));
                        return finalOut;
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
                // 多工具调用 — 风险驱动分流（借鉴 OpenWorker engine.py:480-504）
                // read-only 工具（file_read/grep/glob）→ 并发执行
                // write/exec 工具（file_write/file_edit/bash）→ 严格串行
                // 保留原始顺序：只有连续的 read-only 工具才会并发，
                // 避免将 read→write→read 中的两个 read 并发化（第二个 read 可能依赖 write 结果）
                log.info("[QueryEngine] 正在执行 {} 个工具（第 {} 轮）— 风险驱动分流", toolCalls.size(), round + 1);
                System.out.printf("[QueryEngine] ├─ 多工具调用: %d 个（风险分流）%n", toolCalls.size());

                List<List<ToolCall>> batches = partitionByRisk(toolCalls);
                for (List<ToolCall> batch : batches) {
                    if (batch.size() == 1) {
                        // ── 串行批次：write/exec 工具同步执行 ──
                        ToolCall tc = batch.get(0);
                        log.info("[QueryEngine] 串行执行工具: {}（risk=write/exec）", tc.toolName);
                        try {
                            String result = executeTool(tc);
                            DynamicToolBridge.getInstance().markToolUsed(tc.toolName);
                            consecutiveErrors = 0;
                            toolResults.append("<tool_result name=\"").append(tc.toolName).append("\">\n");
                            toolResults.append(compactToolOutput(tc.toolName, result)).append("\n");
                            toolResults.append("</tool_result>\n\n");
                        } catch (Exception e) {
                            consecutiveErrors++;
                            String errMsg = "工具 '" + tc.toolName + "' 执行期间系统错误: " + e.getMessage();
                            toolResults.append("<tool_result name=\"").append(tc.toolName).append("\">\n");
                            toolResults.append(errMsg).append("\n");
                            toolResults.append("</tool_result>\n\n");
                            log.error("[QueryEngine] 工具 '{}' 抛出异常。({}/{})",
                                    tc.toolName, consecutiveErrors, MAX_CONSECUTIVE_ERRORS, e);
                            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                                return "查询已中止，连续 " + consecutiveErrors + " 次系统错误。最后错误: " + errMsg;
                            }
                        }
                    } else {
                        // ── 并发批次：多个 read-only 工具并行执行 ──
                        log.info("[QueryEngine] 并发执行 {} 个只读工具: {}", batch.size(),
                                batch.stream().map(tc -> tc.toolName).toList());
                        List<CompletableFuture<ToolResult>> futures = new ArrayList<>();
                        for (ToolCall tc : batch) {
                            CompletableFuture<ToolResult> future = CompletableFuture.supplyAsync(() -> {
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

                        // 等待本批所有工具完成
                        try {
                            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                                    .get(120, TimeUnit.SECONDS);
                        } catch (java.util.concurrent.TimeoutException e) {
                            log.warn("[QueryEngine] 并发只读工具批次超时（120秒）");
                        } catch (Exception e) {
                            log.warn("[QueryEngine] 并发批次被中断: {}", e.getMessage());
                        }

                        // 收集结果
                        for (int j = 0; j < futures.size(); j++) {
                            ToolCall tc = batch.get(j);
                            ToolResult tr;
                            try {
                                tr = futures.get(j).getNow(new ToolResult(tc.toolName, "Timed out", null));
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
                }
            }

            // ── 三层历史压缩（借鉴 Agent Zero bulks/topics/current） ──
            // 不再无限追加 fullPrompt，而是通过 HistoryCompressor 管理历史
            historyCompressor.addMessage("assistant", llmResponse);
            historyCompressor.addMessage("tool", toolResults.toString());

            // ── 收敛检测：连续 2 轮同文件同 hash 则终止（避免 v1→v5 无意义重写）──
            if (convergenceTracker.isConverged()) {
                String reason = convergenceTracker.convergenceReason();
                log.warn("[QueryEngine] 收敛检测触发，提前终止 Agent Loop: {}", reason);
                String convergedAnswer = "任务已收敛终止: " + reason;
                return fireOnReplyBestEffort(convergedAnswer);
            }

            // 重建 fullPrompt：系统提示 + 压缩后的历史 + 继续指令
            // ── on_compress_context 洋葱中间件 ── LEAF = buildHistoryText()
            // 中间件可观察消息快照并变换历史文本（如脱敏、注入上下文）。best-effort：异常降级原历史。
            List<HistoryCompressor.Message> msgs = historyCompressor.snapshotMessages();
            String historyText;
            try {
                historyText = MiddlewareRegistry.instance().fireOnCompressContext(
                        new Middleware.CompressContext(agentId, msgs, null),
                        () -> historyCompressor.buildHistoryText());
            } catch (Exception e) {
                log.warn("[QueryEngine] on_compress_context 中间件异常，降级为原历史: {}", e.getMessage());
                historyText = historyCompressor.buildHistoryText();
            }
            fullPrompt = systemPrompt + "\n\n" + historyText
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
        // ── on_reply 洋葱中间件 ── 包裹最终回复（max-rounds 出口）
        finalOut = fireOnReplyBestEffort(finalOut);
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
     * 构建每轮 ephemeral 系统上下文 — send-time only，由 OpenAiAdapter 追加为
     * {@code <system-context>} 块到最后一条 user message，永不持久化。
     * <p>
     * 借鉴 OpenWorker {@code engine.py} 的 {@code context_provider()} + {@code <system-context>} 块
     * （engine.py:880-985）：动态上下文不 bake 进 system prompt，而是每轮 send-time 追加到最后一条
     * user message，永不回写持久化历史。
     * <p>
     * 与 systemPrompt 的分工：
     * <ul>
     *   <li>systemPrompt 是<b>静态身份</b>（工具/技能/格式/工作目录），跨轮稳定以利 KV cache 前缀匹配</li>
     *   <li>ephemeralContext 是<b>动态环境</b>（RAG 结果/当前时间/git 状态/plan-mode 提醒），每轮可重算</li>
     * </ul>
     * 当前实现透传 {@code systemContext}（如 RAG 搜索结果）；下方预留每轮重算的易变上下文扩展点。
     *
     * @param systemContext 调用方传入的额外系统上下文（如 RAG 检索结果）
     * @return ephemeral 上下文字符串；空串表示无 ephemeral 块
     */
    private String buildEphemeralContext(String systemContext) {
        StringBuilder sb = new StringBuilder();
        if (systemContext != null && !systemContext.isBlank()) {
            sb.append(systemContext);
        }
        // ── 未来扩展点（每轮重算的易变上下文）──
        // sb.append("\nCurrent time: ").append(LocalDateTime.now().format(...));
        // sb.append("\nGit: ").append(gitSnapshot());
        // if (planModeActive) sb.append("\n[PLAN MODE: do not edit files, only propose a plan]");
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
                // ASK 决策 → 经 ToolPermissionChannel 询问人类（借鉴 OpenWorker standing scoped approvals）
                // 无前端订阅时 fallback ALLOW_ONCE（零回归，行为同原「Agent 模式下自动允许」）
                String target = PermissionChecker.extractTarget(input);
                ToolPermissionChannel.ApprovalResponse resp = ToolPermissionChannel.requestApproval(
                        agentId, tc.toolName, target, decision.message());
                switch (resp) {
                    case ALWAYS_TARGET -> permissionChecker.grantTargetApproval(tc.toolName, target);
                    case DENY -> {
                        log.warn("[QueryEngine] 工具 '{}' 被用户拒绝: {}", tc.toolName, decision.message());
                        return "权限被拒绝: " + decision.message();
                    }
                    case ALLOW_ONCE -> log.info("[QueryEngine] 工具 '{}' 获用户本次放行", tc.toolName);
                }
            }

            // ── on_acting 洋葱中间件（核心边界修复）──
            // LEAF = tool.call（纯 I/O）。PRE/POST_TOOL_USE 经 HookManagerBridgeMiddleware
            // 移入洋葱内；UpstreamMetaMiddleware 在内层记录 tool.query.<name> 元数据。
            // 权限检查（上方）+ ToolGuardrail + Handoff + telemetry（下方）全留洋葱外。
            // 这使得 next_handler 能安全 offload 到后台任务——它永远不会自行 mutate agent context
            // （修复 AgentTool.runInBackground=true 回退 GLOBAL_DEFAULT_SCOPE 的根因）。
            long startTime = System.currentTimeMillis();
            ToolOutput output = MiddlewareRegistry.instance().fireOnActing(
                    new Middleware.ActingContext(agentId, tc.toolName, input, context, Map.of()),
                    () -> tool.call(input, context)   // LEAF — 异常向上传播（D7 LEAF 不 catch）
            );
            long duration = System.currentTimeMillis() - startTime;

            // ── 遥测记录（洋葱外）── duration 含洋葱全程（PRE/POST hook + leaf），与原语义一致
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

            // ── 收敛检测：记录文件写入（best-effort，异常不阻塞工具执行）──
            recordWriteForConvergence(tc.toolName, input);

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
     * 判断工具是否可以安全并行执行。
     * <p>
     * 只读工具（file_read/grep/glob 等）不修改文件系统状态，可以并发执行；
     * 写/执行工具（file_write/file_edit/bash）必须串行以避免竞态条件。
     * <p>
     * 借鉴 OpenWorker engine.py:480-504 的 risk-based 分流：
     * {@code risk_level == "low" && requires_approval == false} → parallel-safe。
     * AIOS 用 {@link Tool#readOnly()} 作为等价判定。
     * <p>
     * fail-safe：未知工具（未注册）→ false（串行），避免对陌生工具的副作用做并发假设。
     */
    /** package-private — 供同包测试验证风险分流 */
    boolean isParallelSafe(String toolName) {
        return ToolRegistry.instance().get(toolName)
                .map(Tool::readOnly)
                .orElse(false);
    }

    /**
     * 将工具调用列表按风险分区为执行批次。
     * <p>
     * 连续的 parallel-safe 工具合并为一个并发批次；
     * 非 parallel-safe 工具各自独立成批（串行执行）。
     * <p>
     * <b>关键设计</b>：保留原始顺序，只有<b>连续的</b> read-only 工具才会并发化。
     * 避免 {@code read→write→read} 中的两个 read 被并发化
     * （第二个 read 可能依赖 write 的结果）。
     * <p>
     * 示例：
     * <pre>
     * [read_A, read_B, write_C, read_D, read_E] →
     *   batch 1: [read_A, read_B]  ← 并发
     *   batch 2: [write_C]         ← 串行
     *   batch 3: [read_D, read_E]  ← 并发
     * </pre>
     */
    /** package-private — 供同包测试验证风险分流 */
    List<List<ToolCall>> partitionByRisk(List<ToolCall> toolCalls) {
        List<List<ToolCall>> batches = new ArrayList<>();
        int i = 0;
        while (i < toolCalls.size()) {
            if (isParallelSafe(toolCalls.get(i).toolName)) {
                // 收集连续的 parallel-safe 工具
                List<ToolCall> batch = new ArrayList<>();
                while (i < toolCalls.size() && isParallelSafe(toolCalls.get(i).toolName)) {
                    batch.add(toolCalls.get(i));
                    i++;
                }
                batches.add(batch);
            } else {
                // serial 工具 — 单元素批次
                batches.add(List.of(toolCalls.get(i)));
                i++;
            }
        }
        return batches;
    }

    /**
     * 收敛检测辅助 — 记录文件写入到 ConvergenceTracker。
     * <p>
     * 对 file_write 记录完整内容 hash；对 file_edit 记录 (oldString→newString) hash。
     * 连续 2 次同路径同 hash → 收敛触发，QueryEngine 终止循环。
     * <p>
     * best-effort：异常不阻塞工具执行（收敛检测是优化，不是安全约束）。
     */
    private void recordWriteForConvergence(String toolName, ToolInput input) {
        try {
            if ("file_write".equals(toolName) && input instanceof FileWriteTool.Input fwi) {
                convergenceTracker.recordWrite(fwi.path(), fwi.content());
            } else if ("file_edit".equals(toolName) && input instanceof FileEditTool.Input fei) {
                convergenceTracker.recordWrite(fei.path(), fei.oldString() + "\n→\n" + fei.newString());
            }
        } catch (Exception e) {
            log.debug("[QueryEngine] 收敛检测记录异常（best-effort，忽略）: {}", e.getMessage());
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
