package com.ouisani.aios.core.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentTool} 的 SoA 类型切换优雅降级单测。
 * <p>
 * 借鉴 self-organized-agent 框架的 ChildAgent 模式：派生树深度边界处的 agent
 * 不再硬拒绝派生（旧实现抛 DelegationException → 返回 fail → 触发自愈白烧 token），
 * 而是降级为 in-context 解决（返回 ok + 明确指令）。
 * <p>
 * 覆盖两层：
 * <ul>
 *   <li>层 A（兜底降级）：currentDepth &gt;= MAX_DEPTH 时 AgentTool.call 返回 ok 而非 fail</li>
 *   <li>层 B（主动告知）：currentDepth+1 == MAX_DEPTH 时子 prompt 被注入叶子约束</li>
 * </ul>
 */
class AgentToolSoaDegradeTest {

    @AfterEach
    void cleanupThreadLocal() {
        // 重置 static volatile 上限为默认值，防止 breadth/total 配置跨测试泄漏
        // （DelegationGuard 的上限是进程级，不重置会污染 DelegationGuardTest 等依赖默认值的测试）
        DelegationGuard.configureMaxDepth(DelegationGuard.DEFAULT_MAX_DEPTH);
        DelegationGuard.configureMaxSubagentsPerNode(DelegationGuard.DEFAULT_MAX_SUBAGENTS_PER_NODE);
        DelegationGuard.configureMaxTotalSpawns(DelegationGuard.DEFAULT_MAX_TOTAL_SPAWNS);
        DelegationGuard.resetTotalSpawns();
        // 清理 ThreadLocal（DEPTH/BREADTH/CHAIN）
        DelegationGuard.clear();
    }

    // ════════════════════════════════════════════════════════════════
    //  层 A：degradeToInContext 单元测试
    // ════════════════════════════════════════════════════════════════

    @Test
    void degradeToInContext_returnsOkNotFail() {
        AgentTool.Input input = new AgentTool.Input("分析数据并生成报告", "", false, "数据分析");
        ToolOutput out = AgentTool.degradeToInContext(input, DelegationGuard.maxDepth(), "depth");

        assertTrue(out.success(), () -> "降级应返回 ok 而非 fail: " + out.toText());
        String text = out.toText();
        assertTrue(text.contains("派生降级"), () -> "应含降级标记: " + text);
        assertTrue(text.contains("上下文内"), () -> "应含 in-context 指令: " + text);
        assertTrue(text.contains("请勿再次调用 agent"), () -> "应明确禁止再派生: " + text);
        assertTrue(text.contains("分析数据并生成报告"), () -> "应保留原子任务 prompt: " + text);
    }

    @Test
    void degradeToInContext_doesNotStartWithErrorPrefix() {
        // 旧实现 fail() 会加 "ERROR: " 前缀；降级路径绝不能有
        AgentTool.Input input = new AgentTool.Input("子任务", "", false, "");
        ToolOutput out = AgentTool.degradeToInContext(input, DelegationGuard.maxDepth(), "depth");
        assertFalse(out.toText().startsWith("ERROR:"),
                () -> "降级输出不应以 ERROR 开头（那是 fail 路径）: " + out.toText());
    }

    // ════════════════════════════════════════════════════════════════
    //  层 B：injectLeafConstraint 单元测试
    // ════════════════════════════════════════════════════════════════

    @Test
    void injectLeafConstraint_addsDirectiveAndPreservesPrompt() {
        String original = "你是一个数据分析师，请处理这批数据";
        String result = AgentTool.injectLeafConstraint(original);

        assertTrue(result.contains("叶子节点约束"), () -> "应含叶子约束标记: " + result);
        assertTrue(result.contains("禁止使用 agent"), () -> "应明确禁止 agent 工具: " + result);
        assertTrue(result.contains(original), () -> "应保留原始 prompt: " + result);
        // 约束应在原 prompt 之前（前缀注入，让 LLM 先看到约束）
        assertTrue(result.indexOf("叶子节点约束") < result.indexOf(original),
                () -> "叶子约束应作为前缀注入: " + result);
    }

    // ════════════════════════════════════════════════════════════════
    //  层 A 集成：AgentTool.call 在叶子层降级，不触 spawn
    // ════════════════════════════════════════════════════════════════

    @Test
    void call_atMaxDepth_degradesToInContext_notFail() {
        // 模拟叶子层 agent（深度=MAX_DEPTH）尝试派生子 agent
        // 旧实现：DelegationGuard.enter 抛异常 → 返回 fail
        // 新实现：层 A 在 enter 之前降级，返回 ok
        DelegationGuard.activate(new DelegationGuard.DelegationContext(
                DelegationGuard.maxDepth(), java.util.Set.of(), "leaf-agent"));

        AgentTool tool = new AgentTool();
        ToolContext ctx = new ToolContext("leaf-agent", null, null);
        ToolOutput out = tool.call(new AgentTool.Input("做子任务X", "", false, "子任务X"), ctx);

        assertTrue(out.success(), () -> "叶子层应降级为 ok 而非 fail: " + out.toText());
        assertTrue(out.toText().contains("派生降级"),
                () -> "应含降级指令: " + out.toText());
        assertTrue(out.toText().contains("请勿再次调用 agent"),
                () -> "应禁止再派生: " + out.toText());
    }

    @Test
    void call_aboveMaxDepth_alsoDegrades_notFail() {
        // 防御性：即使深度异常超过 MAX_DEPTH（理论上不应发生），也降级而非崩溃
        DelegationGuard.activate(new DelegationGuard.DelegationContext(
                DelegationGuard.maxDepth() + 2, java.util.Set.of(), "over-depth-agent"));

        AgentTool tool = new AgentTool();
        ToolContext ctx = new ToolContext("over-depth-agent", null, null);
        ToolOutput out = tool.call(new AgentTool.Input("做子任务Y", "", false, "子任务Y"), ctx);

        assertTrue(out.success(), () -> "超深也应降级为 ok: " + out.toText());
        assertTrue(out.toText().contains("派生降级"),
                () -> "应含降级指令: " + out.toText());
    }

    // ════════════════════════════════════════════════════════════════
    //  层 A 集成：广度界（maxSubagentsPerNode）耗尽时降级
    // ════════════════════════════════════════════════════════════════

    @Test
    void call_breadthExhausted_degradesWithBreadthReason() {
        // 场景：单 agent 最多派生 1 个子 agent（maxSubagentsPerNode=1）
        // 母体已派生 1 个（breadth=1），再次调用 agent 工具应降级为 in-context 而非 spawn
        // 这覆盖用户请求的"广度界"在 AgentTool 集成层的端到端降级路径
        DelegationGuard.configureMaxSubagentsPerNode(1);
        // 消耗母体的广度预算：enter 会令 BREADTH=1（不 activate，母体线程 depth 仍为 0）
        DelegationGuard.enter("mother", "child1");
        assertEquals(1, DelegationGuard.currentBreadth(),
                "前置：enter 后母体广度应为 1");

        AgentTool tool = new AgentTool();
        ToolContext ctx = new ToolContext("mother", null, null);
        ToolOutput out = tool.call(new AgentTool.Input("做子任务Z", "", false, "子任务Z"), ctx);

        assertTrue(out.success(), () -> "广度超限应降级为 ok 而非 fail: " + out.toText());
        String text = out.toText();
        assertTrue(text.contains("breadth"), () -> "降级原因应为 breadth: " + text);
        assertTrue(text.contains("派生降级"), () -> "应含降级标记: " + text);
        assertTrue(text.contains("请勿再次调用 agent"), () -> "应禁止再派生: " + text);
        assertTrue(text.contains("做子任务Z"), () -> "应保留原子任务 prompt: " + text);
    }

    // ════════════════════════════════════════════════════════════════
    //  层 A 集成：全局总数界（maxTotalSpawns）耗尽时降级
    // ════════════════════════════════════════════════════════════════

    @Test
    void call_totalSpawnsExhausted_degradesWithTotalReason() {
        // 场景：全局最多派生 1 个 agent（maxTotalSpawns=1）
        // 已派生 1 个（TOTAL_SPAWNS=1），再次调用应因全局总数超限降级
        // 注意：此时 breadth=1 但 maxSubagentsPerNode=5（默认），故 breadth 不先触发，total 触发
        DelegationGuard.configureMaxTotalSpawns(1);
        DelegationGuard.enter("mother", "child1");
        assertEquals(1, DelegationGuard.totalSpawns(),
                "前置：enter 后全局派生总数应为 1");

        AgentTool tool = new AgentTool();
        ToolContext ctx = new ToolContext("mother", null, null);
        ToolOutput out = tool.call(new AgentTool.Input("做子任务W", "", false, "子任务W"), ctx);

        assertTrue(out.success(), () -> "全局总数超限应降级为 ok 而非 fail: " + out.toText());
        String text = out.toText();
        assertTrue(text.contains("total"), () -> "降级原因应为 total: " + text);
        assertTrue(text.contains("派生降级"), () -> "应含降级标记: " + text);
        assertTrue(text.contains("做子任务W"), () -> "应保留原子任务 prompt: " + text);
    }
}
