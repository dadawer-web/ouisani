package com.ouisani.aios.user.cli;

import com.ouisani.aios.core.team.TeamManager;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 动态意图门控 — 对标 oh-my-openagent 的 IntentGate。
 * <p>
 * 在用户第一条消息中扫描模式关键词，自动注入模式特定的系统提示，
 * 并路由到对应的执行模式（单体 Agent / Team Mode / UltraWork 等）。
 * <p>
 * 对标 omo 的 keyword-detector hook：
 * <ul>
 *   <li>{@code ultrawork / ulw} → 全编排模式：并行代理、深度探索、持续执行</li>
 *   <li>{@code team} → 强制通过 Team Mode 编排</li>
 *   <li>{@code hyperplan} → 加载对抗性规划模式</li>
 *   <li>{@code quick} → 轻量级单体 Agent 快速响应</li>
 * </ul>
 * <p>
 * 与现有 IntentRouter 的关系：
 * IntentRouter 负责"意图分类"（SYSTEM_COMMAND / WORKFLOW_DEPLOY / CHAT），
 * IntentGate 负责"模式切换"（决定用单体还是团队来执行）。
 * 两者串联：IntentRouter.classify() → IntentGate.detectMode() → 执行。
 *
 * @see IntentRouter
 * @see com.ouisani.aios.core.team.TeamManager
 */
public class IntentGate {

    private static final Logger log = LoggerFactory.getLogger(IntentGate.class);
    private static final IntentGate INSTANCE = new IntentGate();

    public static IntentGate instance() {
        return INSTANCE;
    }

    // ════════════════════════════════════════════════════════════════
    //  执行模式定义
    // ════════════════════════════════════════════════════════════════

    /**
     * 执行模式 — 决定任务如何被编排和执行。
     */
    public enum ExecutionMode {
        /** 默认模式 — 单体 Agent 直接执行 */
        SOLO("solo", "Single agent direct execution"),
        /** 团队模式 — 多 Agent 组队协作 */
        TEAM("team", "Multi-agent team collaboration"),
        /** 超工模式 — 全编排：并行代理、深度探索、持续执行 */
        ULTRAWORK("ultrawork", "Full orchestration: parallel agents, deep exploration, persistent execution"),
        /** 超规划模式 — 对抗性规划，多角度审视 */
        HYPERPLAN("hyperplan", "Adversarial planning: multiple perspectives"),
        /** 快速模式 — 轻量级快速响应 */
        QUICK("quick", "Lightweight quick response");

        private final String keyword;
        private final String description;

        ExecutionMode(String keyword, String description) {
            this.keyword = keyword;
            this.description = description;
        }

        public String keyword() { return keyword; }
        public String description() { return description; }
    }

    // ════════════════════════════════════════════════════════════════
    //  关键词模式检测
    // ════════════════════════════════════════════════════════════════

    private static final Pattern ULTRAWORK_PATTERN = Pattern.compile(
            "\\b(ultrawork|ulw)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEAM_PATTERN = Pattern.compile(
            "\\b(team|团队|组队|协作|多智能体|multi.?agent)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HYPERPLAN_PATTERN = Pattern.compile(
            "\\b(hyperplan|超规划|对抗性规划)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUICK_PATTERN = Pattern.compile(
            "\\b(quick|快速|简单|轻量|fast)\\b", Pattern.CASE_INSENSITIVE);

    /** 复杂任务关键词 — 触发自动 Team Mode */
    private static final Pattern COMPLEX_TASK_PATTERN = Pattern.compile(
            "\\b(微服务|系统|架构|全栈|重构|迁移|pipeline|端到端|end.?to.?end|微前端|monorepo)\\b",
            Pattern.CASE_INSENSITIVE);

    /** 简单任务关键词 — 触发 SOLO 模式 */
    private static final Pattern SIMPLE_TASK_PATTERN = Pattern.compile(
            "\\b(查|看|显示|list|show|get|print|echo|cat|grep|status|help)\\b",
            Pattern.CASE_INSENSITIVE);

    // ════════════════════════════════════════════════════════════════
    //  模式特定的系统提示
    // ════════════════════════════════════════════════════════════════

    private static final Map<ExecutionMode, String> MODE_PROMPTS = Map.of(
            ExecutionMode.SOLO, "",
            ExecutionMode.TEAM, """
                    
                    [INTENT GATE — TEAM MODE ACTIVATED]
                    You are now operating in TEAM MODE. You must:
                    1. Use the `team` tool to create a team with specialized agents
                    2. Break the task into sub-tasks and assign them to team members
                    3. Coordinate via team_send_message for inter-agent communication
                    4. Track progress with team_task_create / team_task_update
                    5. Only approve team shutdown when ALL tasks are verified complete
                    """,
            ExecutionMode.ULTRAWORK, """
                    
                    [INTENT GATE — ULTRAWORK MODE ACTIVATED]
                    You are now in ULTRAWORK mode. You must:
                    1. Create a comprehensive plan before executing
                    2. Delegate sub-tasks to specialized agents in parallel where possible
                    3. Use deep exploration — read all relevant files before making changes
                    4. Keep executing until the task is FULLY complete — never stop early
                    5. Verify each step before moving to the next
                    6. If you encounter errors, analyze them thoroughly before retrying
                    """,
            ExecutionMode.HYPERPLAN, """
                    
                    [INTENT GATE — HYPERPLAN MODE ACTIVATED]
                    You are now in HYPERPLAN mode. You must:
                    1. First, create a detailed plan
                    2. Then, critique the plan from multiple orthogonal perspectives:
                       - Security: What could go wrong?
                       - Performance: What are the bottlenecks?
                       - Maintainability: Will this be easy to change?
                       - Completeness: What edge cases are missing?
                    3. Revise the plan based on critiques
                    4. Only execute after the plan has survived all critiques
                    """,
            ExecutionMode.QUICK, """
                    
                    [INTENT GATE — QUICK MODE ACTIVATED]
                    You are in QUICK mode. Be extremely concise:
                    1. Do the minimum necessary to complete the task
                    2. No explanations, no comments, just results
                    3. If the task is ambiguous, ask ONE clarifying question, then act
                    """
    );

    // ════════════════════════════════════════════════════════════════
    //  核心检测逻辑
    // ════════════════════════════════════════════════════════════════

    /**
     * 检测用户输入的执行模式。
     * <p>
     * 优先级：显式关键词 > 任务复杂度推断 > 默认 SOLO。
     *
     * @param userInput 用户自然语言输入
     * @return 门控结果（包含执行模式 + 模式提示）
     */
    public GateResult detectMode(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return new GateResult(ExecutionMode.SOLO, "", "Empty input");
        }

        // ── 1. 显式关键词检测（最高优先级） ──
        if (ULTRAWORK_PATTERN.matcher(userInput).find()) {
            log.info("[IntentGate] ULTRAWORK keyword detected");
            SemanticEtw.getInstance().logEvent("INTENT_GATE", "ULTRAWORK", "input=" + truncate(userInput, 80));
            return buildResult(ExecutionMode.ULTRAWORK, "ultrawork keyword");
        }

        if (HYPERPLAN_PATTERN.matcher(userInput).find()) {
            log.info("[IntentGate] HYPERPLAN keyword detected");
            SemanticEtw.getInstance().logEvent("INTENT_GATE", "HYPERPLAN", "input=" + truncate(userInput, 80));
            return buildResult(ExecutionMode.HYPERPLAN, "hyperplan keyword");
        }

        if (TEAM_PATTERN.matcher(userInput).find()) {
            log.info("[IntentGate] TEAM keyword detected");
            SemanticEtw.getInstance().logEvent("INTENT_GATE", "TEAM", "input=" + truncate(userInput, 80));
            return buildResult(ExecutionMode.TEAM, "team keyword");
        }

        if (QUICK_PATTERN.matcher(userInput).find()) {
            log.info("[IntentGate] QUICK keyword detected");
            return buildResult(ExecutionMode.QUICK, "quick keyword");
        }

        // ── 2. 任务复杂度推断 ──
        if (COMPLEX_TASK_PATTERN.matcher(userInput).find()) {
            log.info("[IntentGate] Complex task detected → auto-routing to TEAM mode");
            SemanticEtw.getInstance().logEvent("INTENT_GATE", "AUTO_TEAM", "input=" + truncate(userInput, 80));
            return buildResult(ExecutionMode.TEAM, "complex task auto-detection");
        }

        if (SIMPLE_TASK_PATTERN.matcher(userInput).find()) {
            return buildResult(ExecutionMode.QUICK, "simple task auto-detection");
        }

        // ── 3. 默认 SOLO ──
        return buildResult(ExecutionMode.SOLO, "default");
    }

    /**
     * 获取模式特定的系统提示注入。
     */
    public String getModePrompt(ExecutionMode mode) {
        return MODE_PROMPTS.getOrDefault(mode, "");
    }

    /**
     * 判断是否需要自动创建团队。
     */
    public boolean shouldAutoCreateTeam(ExecutionMode mode) {
        return mode == ExecutionMode.TEAM || mode == ExecutionMode.ULTRAWORK;
    }

    /**
     * 为 TEAM/ULTRAWORK 模式自动生成团队规格。
     */
    public TeamManager.TeamSpec generateTeamSpec(String userInput, String leadAgentId, ExecutionMode mode) {
        List<String> memberIds = new ArrayList<>();

        if (mode == ExecutionMode.ULTRAWORK) {
            // UltraWork 模式：拉起完整团队
            memberIds.add("hephaestus");   // 深度自主工作者
            memberIds.add("prometheus");   // 战略规划师
            memberIds.add("oracle");       // 架构/调试专家
        } else {
            // Team 模式：根据任务推断需要的角色
            if (userInput.toLowerCase().contains("前端") || userInput.toLowerCase().contains("frontend")) {
                memberIds.add("frontend-dev");
            }
            if (userInput.toLowerCase().contains("后端") || userInput.toLowerCase().contains("backend")) {
                memberIds.add("backend-dev");
            }
            if (userInput.toLowerCase().contains("测试") || userInput.toLowerCase().contains("test")) {
                memberIds.add("qa-engineer");
            }
            // 至少一个通用成员
            if (memberIds.isEmpty()) {
                memberIds.add("worker-1");
                memberIds.add("worker-2");
            }
        }

        String teamName = "team_" + System.currentTimeMillis();
        return new TeamManager.TeamSpec(teamName, leadAgentId, memberIds, userInput);
    }

    // ── 内部方法 ──

    private GateResult buildResult(ExecutionMode mode, String trigger) {
        String prompt = getModePrompt(mode);
        return new GateResult(mode, prompt, trigger);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "?";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // ════════════════════════════════════════════════════════════════
    //  结果数据类
    // ════════════════════════════════════════════════════════════════

    /**
     * 门控结果 — 包含执行模式、模式提示和触发原因。
     *
     * @param mode     检测到的执行模式
     * @param prompt   模式特定的系统提示注入
     * @param trigger  触发原因
     */
    public record GateResult(
            ExecutionMode mode,
            String prompt,
            String trigger
    ) {}
}
