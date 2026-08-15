package com.ouisani.aios.core.compact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 输出 Token 削减器 — 借鉴 Headroom proxy/verbosity_controller.py + learn/verbosity.py。
 * <p>
 * <b>核心价值：</b>不只压输入，还压输出。LLM 每次都全力输出，即使是"读取文件"
 * 这种机械操作也会输出大段解释。三个杠杆按场景动态削减：
 * <p>
 * <b>三个杠杆：</b>
 * <ol>
 *   <li><b>Verbosity Steering（啰嗦度转向）</b> — 在 system prompt <b>尾部</b>追加
 *       "别啰嗦"指令。追加在尾部而非头部，保护 KV cache 前缀。
 *       借鉴 Headroom 的 L1-L4 四级控制。</li>
 *   <li><b>Effort Routing（努力路由）</b> — 机械续行时降 {@code output_config.effort}
 *       到 {@code low}。判断是否为"机械轮次"（读文件、grep、简单工具调用）。</li>
 *   <li><b>Thinking Budget Clamp（思考预算钳制）</b> — 机械轮次把 thinking budget
 *       压到地板。复杂推理任务保持高 thinking budget。</li>
 * </ol>
 * <p>
 * <b>OS 类比：</b>相当于 Linux 的 CPU 调度器 —
 * 后台任务用 SCHED_IDLE（低 effort），交互任务用 SCHED_FIFO（高 effort）。
 */
public class OutputTokenReducer {

    private static final Logger log = LoggerFactory.getLogger(OutputTokenReducer.class);

    /** 单例 */
    private static final OutputTokenReducer INSTANCE = new OutputTokenReducer();

    public static OutputTokenReducer instance() {
        return INSTANCE;
    }

    private OutputTokenReducer() {}

    // ════════════════════════════════════════════════════════════════
    //  Verbosity Level — 借鉴 Headroom 4 级控制
    // ════════════════════════════════════════════════════════════════

    /**
     * 啰嗦度级别 — 借鉴 Headroom VerbosityProfile.level。
     * <p>
     * 用户行为信号（interrupt_rate + fast_skip_rate）决定级别：
     * <ul>
     *   <li>{@link #L1} — 轻触：用户读答案，低 push-back，允许详细解释</li>
     *   <li>{@link #L2} — 去掉 ceremony 和 echo，中等 push-back</li>
     *   <li>{@link #L3} — 只给结论：用户很少读长答案，高 push-back</li>
     *   <li>{@link #L4} — 极简：caveman 模式（不自动应用，需手动）</li>
     * </ul>
     */
    public enum VerbosityLevel {
        /** L1: 轻触 — 用户读答案，允许详细解释 */
        L1(1, "default", """
                Respond at normal detail. Include explanations where helpful."""),

        /** L2: 去 ceremony — 中等 push-back */
        L2(2, "concise", """
                Be concise. Skip ceremony ("I'll help you with that"), restating the \
                request, and redundant explanations. Lead with the answer."""),

        /** L3: 只结论 — 用户很少读长答案 */
        L3(3, "terse", """
                Be terse. Answer in bullet points or a single sentence when possible. \
                No preamble, no restating the question, no "here's what I found" \
                filler. Only output the essential information."""),

        /** L4: 极简 — caveman 模式（不自动应用） */
        L4(4, "caveman", """
                Minimum viable output. One line unless multiple distinct facts are \
                needed. No full sentences unless explicitly asked.""");

        private final int level;
        private final String profile;
        private final String directive;

        VerbosityLevel(int level, String profile, String directive) {
            this.level = level;
            this.profile = profile;
            this.directive = directive;
        }

        public int level() { return level; }
        public String profile() { return profile; }
        public String directive() { return directive; }

        /** 根据行为信号推荐级别 — 借鉴 Headroom recommend_level() */
        public static VerbosityLevel recommend(double interruptRate, double fastSkipRate, int humanTurnCount) {
            if (humanTurnCount < 10) {
                return L1; // 数据不足，默认 L1
            }
            double pressure = interruptRate + fastSkipRate;
            if (pressure < 0.10) return L1;
            if (pressure < 0.30) return L2;
            return L3;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Effort Level — 借鉴 Headroom output_config.effort
    // ════════════════════════════════════════════════════════════════

    /**
     * 努力级别 — 控制 LLM 推理深度。
     */
    public enum EffortLevel {
        /** 高努力：复杂推理、架构设计、调试 */
        HIGH("high", 1.0, 1.0),
        /** 中努力：普通对话、解释代码 */
        MEDIUM("medium", 0.7, 0.7),
        /** 低努力：机械操作、读文件、grep */
        LOW("low", 0.3, 0.1);

        private final String config;
        private final double thinkingMultiplier;
        private final double outputMultiplier;

        EffortLevel(String config, double thinkingMultiplier, double outputMultiplier) {
            this.config = config;
            this.thinkingMultiplier = thinkingMultiplier;
            this.outputMultiplier = outputMultiplier;
        }

        public String config() { return config; }
        public double thinkingMultiplier() { return thinkingMultiplier; }
        public double outputMultiplier() { return outputMultiplier; }
    }

    // ════════════════════════════════════════════════════════════════
    //  TurnKind — 轮次类型分类（借鉴 Headroom output_shaper.classify_turn）
    // ════════════════════════════════════════════════════════════════

    /** 轮次类型 */
    public enum TurnKind {
        /** 机械操作：读文件、grep、glob、bash 简单命令 */
        MECHANICAL,
        /** 解释说明：解释代码、回答问题 */
        EXPLANATORY,
        /** 复杂推理：架构设计、调试、算法 */
        REASONING,
        /** 创造性：写代码、重构 */
        CREATIVE,
        /** 未知 */
        UNKNOWN
    }

    // ════════════════════════════════════════════════════════════════
    //  运行时状态
    // ════════════════════════════════════════════════════════════════

    /** 当前啰嗦度级别（可被学习调整） */
    private volatile VerbosityLevel currentVerbosity = VerbosityLevel.L1;

    /** 行为信号统计 */
    private final AtomicInteger interruptCount = new AtomicInteger(0);
    private final AtomicInteger fastSkipCount = new AtomicInteger(0);
    private final AtomicInteger humanTurnCount = new AtomicInteger(0);
    private final AtomicInteger skipEligibleCount = new AtomicInteger(0);

    /** Thinking budget 钳制开关 */
    private volatile boolean thinkingBudgetClampEnabled = true;

    // ════════════════════════════════════════════════════════════════
    //  杠杆 1: Verbosity Steering — 借鉴 Headroom verbosity_steerer
    // ════════════════════════════════════════════════════════════════

    /**
     * 杠杆 1：在 system prompt 尾部追加啰嗦度指令。
     * <p>
     * <b>关键设计：</b>追加在尾部而非头部，保护 KV cache 前缀。
     * 前缀（身份 + 工具描述 + CLAUDE.md）是稳定的，追加在尾部不会破坏缓存命中。
     *
     * @param systemPrompt 原始 system prompt
     * @return 追加了啰嗦度指令的 prompt
     */
    public String applyVerbositySteering(String systemPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank()) {
            return systemPrompt;
        }

        // 追加在尾部（保护 KV cache 前缀）
        return systemPrompt + "\n\n## Output Verbosity Directive\n"
                + currentVerbosity.directive() + "\n";
    }

    /**
     * 设置啰嗦度级别。
     */
    public void setVerbosityLevel(VerbosityLevel level) {
        if (level != null && level != currentVerbosity) {
            log.info("[OutputTokenReducer] 啰嗦度调整: {} → {}", currentVerbosity.profile(), level.profile());
            this.currentVerbosity = level;
        }
    }

    /** 获取当前啰嗦度 */
    public VerbosityLevel currentVerbosity() {
        return currentVerbosity;
    }

    // ════════════════════════════════════════════════════════════════
    //  杠杆 2: Effort Routing — 借鉴 Headroom effort_router
    // ════════════════════════════════════════════════════════════════

    /**
     * 杠杆 2：根据轮次类型路由 effort 级别。
     * <p>
     * 机械轮次（读文件、grep）降 effort 到 LOW，
     * 复杂推理保持 HIGH。
     *
     * @param turnKind 轮次类型
     * @return 推荐的 effort 级别
     */
    public EffortLevel routeEffort(TurnKind turnKind) {
        return switch (turnKind) {
            case MECHANICAL -> EffortLevel.LOW;
            case EXPLANATORY -> EffortLevel.MEDIUM;
            case REASONING, CREATIVE -> EffortLevel.HIGH;
            case UNKNOWN -> EffortLevel.MEDIUM;
        };
    }

    /**
     * 分类轮次类型 — 借鉴 Headroom output_shaper.classify_turn。
     * <p>
     * 根据用户消息和最近工具调用判断当前轮次是机械操作还是复杂推理。
     *
     * @param userMessage 用户消息
     * @param lastToolUsed 最近使用的工具名（可为 null）
     * @return 轮次类型
     */
    public TurnKind classifyTurn(String userMessage, String lastToolUsed) {
        if (userMessage == null || userMessage.isBlank()) {
            return TurnKind.UNKNOWN;
        }

        String lower = userMessage.toLowerCase();

        // 机械操作：读文件、grep、glob、bash 简单命令
        if (lastToolUsed != null) {
            String toolLower = lastToolUsed.toLowerCase();
            if (toolLower.contains("file_read") || toolLower.contains("grep")
                    || toolLower.contains("glob") || toolLower.contains("bash")
                    || toolLower.contains("ls") || toolLower.contains("config")) {
                // 检查用户消息是否简短（机械续行的特征）
                if (lower.length() < 100 || lower.contains("continue") || lower.contains("next")
                        || lower.contains("继续") || lower.contains("下一步")) {
                    return TurnKind.MECHANICAL;
                }
            }
        }

        // 复杂推理：调试、架构、设计
        if (lower.contains("debug") || lower.contains("architect") || lower.contains("design")
                || lower.contains("debug") || lower.contains("why") || lower.contains("debug")
                || lower.contains("调试") || lower.contains("架构") || lower.contains("设计")
                || lower.contains("为什么") || lower.contains("分析")) {
            return TurnKind.REASONING;
        }

        // 创造性：写代码、重构
        if (lower.contains("write") || lower.contains("create") || lower.contains("implement")
                || lower.contains("refactor") || lower.contains("写") || lower.contains("实现")
                || lower.contains("重构")) {
            return TurnKind.CREATIVE;
        }

        // 解释说明
        if (lower.contains("explain") || lower.contains("what") || lower.contains("how")
                || lower.contains("解释") || lower.contains("什么") || lower.contains("如何")) {
            return TurnKind.EXPLANATORY;
        }

        return TurnKind.UNKNOWN;
    }

    // ════════════════════════════════════════════════════════════════
    //  杠杆 3: Thinking Budget Clamp — 借鉴 Headroom thinking_budget
    // ════════════════════════════════════════════════════════════════

    /**
     * 杠杆 3：钳制 thinking budget。
     * <p>
     * 机械轮次把 thinking budget 压到地板（0.1x），
     * 复杂推理保持满 thinking budget。
     *
     * @param effort 努力级别
     * @param defaultBudget 默认 thinking budget（token 数）
     * @return 钳制后的 thinking budget
     */
    public int clampThinkingBudget(EffortLevel effort, int defaultBudget) {
        if (!thinkingBudgetClampEnabled) {
            return defaultBudget;
        }

        int clamped = (int) (defaultBudget * effort.thinkingMultiplier());
        log.debug("[OutputTokenReducer] Thinking budget clamp: {} → {} (effort={})",
                defaultBudget, clamped, effort.config());
        return clamped;
    }

    /**
     * 启用/禁用 thinking budget 钳制。
     */
    public void setThinkingBudgetClampEnabled(boolean enabled) {
        this.thinkingBudgetClampEnabled = enabled;
    }

    // ════════════════════════════════════════════════════════════════
    //  行为信号收集 — 借鉴 Headroom verbosity.py 的信号提取
    // ════════════════════════════════════════════════════════════════

    /**
     * 记录用户中断 — 借鉴 Headroom _INTERRUPT_MARKER 检测。
     * <p>
     * 用户中断长输出是"输出太多"的强信号。
     */
    public void recordInterrupt() {
        interruptCount.incrementAndGet();
        humanTurnCount.incrementAndGet();
        tryAutoAdjust();
    }

    /**
     * 记录快速跳过 — 借鉴 Headroom fast-skip 检测。
     * <p>
     * 用户在回答还没读完时就回复了（回复时间 < 阅读时间的 50%）。
     */
    public void recordFastSkip() {
        fastSkipCount.incrementAndGet();
        skipEligibleCount.incrementAndGet();
        humanTurnCount.incrementAndGet();
        tryAutoAdjust();
    }

    /**
     * 记录正常人类轮次。
     */
    public void recordHumanTurn() {
        humanTurnCount.incrementAndGet();
        skipEligibleCount.incrementAndGet();
    }

    /**
     * 尝试自动调整啰嗦度级别 — 借鉴 Headroom recommend_level。
     * <p>
     * 当收集到足够信号（≥10 个人类轮次）时自动调整。
     */
    private void tryAutoAdjust() {
        int turns = humanTurnCount.get();
        if (turns < 10) return;
        if (turns % 10 != 0) return; // 每 10 轮检查一次

        double ir = (double) interruptCount.get() / Math.max(1, humanTurnCount.get());
        double fsr = (double) fastSkipCount.get() / Math.max(1, skipEligibleCount.get());

        VerbosityLevel recommended = VerbosityLevel.recommend(ir, fsr, turns);
        if (recommended != currentVerbosity) {
            log.info("[OutputTokenReducer] 自动调整啰嗦度: {} → {} (interrupt_rate={}, fast_skip_rate={})",
                    currentVerbosity.profile(), recommended.profile(),
                    String.format("%.2f", ir), String.format("%.2f", fsr));
            setVerbosityLevel(recommended);
        }
    }

    /**
     * 获取行为信号统计。
     */
    public VerbositySignals getSignals() {
        return new VerbositySignals(
                interruptCount.get(),
                fastSkipCount.get(),
                humanTurnCount.get(),
                skipEligibleCount.get()
        );
    }

    /** 行为信号 — 借鉴 Headroom VerbositySignals */
    public record VerbositySignals(
            int interrupts,
            int fastSkips,
            int humanTurns,
            int skipEligible
    ) {
        public double interruptRate() {
            return humanTurns > 0 ? (double) interrupts / humanTurns : 0.0;
        }

        public double fastSkipRate() {
            return skipEligible > 0 ? (double) fastSkips / skipEligible : 0.0;
        }

        public double pressure() {
            return interruptRate() + fastSkipRate();
        }
    }
}
