package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.network.AiosEventSchema;
import com.ouisani.aios.core.syscall.SyscallResponse;
import com.ouisani.aios.core.tool.ToolRegistry;
import com.ouisani.aios.user.sdk.AbstractAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 子智能体 (Sub-Agent / Worker Agent) — 借鉴 Apix agent_node/sub_agent_node.py。
 * <p>
 * <b>主从智能体树模式：</b>
 * <ul>
 *   <li>OmniMotherAgent 作为 MainAgent（包工头），负责拓扑编译与任务分派</li>
 *   <li>SubAgent 作为 WorkerAgent（打工人），每个挂载单一技能，负责单个 DAG 节点执行</li>
 * </ul>
 * <p>
 * 当 TopologyCompiler 编译出 N 个节点时，内核 fork() 出 N 个 SubAgent 虚拟线程，
 * 每个 SubAgent 挂载特定单一技能，独立执行节点任务。
 * <p>
 * <b>DAG 节点状态流</b>（借鉴 Apix apix_event_pipe — 像心电图一样流式推给前端）：
 * <pre>
 * PENDING → RUNNING → (LLM_THINKING ↔ TOOL_CALLING) → SUCCESS / FAILED
 * </pre>
 * <p>
 * <b>比 OmniMotherAgent 优势：</b>
 * <ul>
 *   <li>更轻量：只挂载单一技能，上下文不膨胀</li>
 *   <li>更容错：单个 SubAgent 失败不影响其他分支（级联隔离）</li>
 *   <li>更并行：N 个 SubAgent 在虚拟线程上并行执行</li>
 * </ul>
 * <p>
 * <b>executor 字段约定：</b>
 * <ul>
 *   <li>{@code sub:tool_name} — 挂载指定工具直接执行（如 sub:web_scrape）</li>
 *   <li>{@code sub} 或 {@code subagent} — 仅走 LLM 推理，不挂载特定工具</li>
 * </ul>
 */
public class SubAgent extends AbstractAgent {

    private static final Logger log = LoggerFactory.getLogger(SubAgent.class);

    private final WorkflowNode node;
    private final WorkflowContext context;
    private final String runId;

    /** 单一技能（工具名）— null 表示走纯 LLM 推理 */
    private final String singleSkill;

    /**
     * Dify 风格构造函数：DAG 引擎按节点级调度时使用。
     *
     * @param node    要执行的 DAG 节点
     * @param context 工作流内存上下文
     */
    public SubAgent(WorkflowNode node, WorkflowContext context) {
        super("sub_" + node.instanceId(), ProcessPriority.NORMAL, 20000);
        this.node = node;
        this.context = context;
        this.runId = context.getWorkflowId();
        this.singleSkill = resolveSkill(node);
    }

    /**
     * 从 node.executor() 解析单一技能。
     * <p>
     * 格式约定：
     * - "sub:tool_name" → 挂载 tool_name 工具
     * - "sub" / "subagent" → 无特定技能，走纯 LLM 推理
     */
    private String resolveSkill(WorkflowNode node) {
        String exec = node.executor();
        if (exec == null) return null;
        if (exec.startsWith("sub:")) {
            String skill = exec.substring(4).trim();
            // 校验工具是否已注册
            if (ToolRegistry.instance().get(skill).isEmpty()) {
                log.warn("[SubAgent:{}] 技能 '{}' 未在 ToolRegistry 注册，将降级为 LLM 推理",
                        agentId, skill);
                return null;
            }
            return skill;
        }
        // "sub" / "subagent" 无特定技能
        return null;
    }

    @Override
    protected void onStart() {
        log.info("[SubAgent:{}] 启动 — 节点={}, 技能={}", agentId, node.instanceId(),
                singleSkill != null ? singleSkill : "(LLM推理)");

        // ── 发出 PENDING → RUNNING 状态流事件 ──
        AiosEventSchema.emit(AiosEventSchema.dagNodePending(
                agentId, runId, node.instanceId(), node.role(), "sub-agent"));
        AiosEventSchema.emit(AiosEventSchema.dagNodeRunning(
                agentId, runId, node.instanceId(), node.role()));
        node.setStatus(WorkflowNode.Status.RUNNING);

        try {
            executeNodeTask();
            // Returning from the skill/LLM only means execution returned.
            // WorkflowEngine owns the SUCCESS transition after its
            // verification contract has passed.
            log.info("[SubAgent:{}] 节点 {} 执行返回，等待 WorkflowEngine 验证",
                    agentId, node.instanceId());
        } catch (Exception e) {
            // ── 失败 ──
            node.setStatus(WorkflowNode.Status.FAILED);
            AiosEventSchema.emit(AiosEventSchema.dagNodeFailed(
                    agentId, runId, node.instanceId(), node.role(), e.getMessage()));
            log.error("[SubAgent:{}] 节点 {} 执行失败: {}", agentId, node.instanceId(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 执行节点任务 — 根据是否有单一技能选择执行路径。
     */
    private void executeNodeTask() {
        // 1. 解析上游输入参数
        Map<String, Object> inputs = resolveUpstreamInputs();

        if (singleSkill != null) {
            // 2a. 有单一技能：直接调用工具
            executeSingleSkill(inputs);
        } else {
            // 2b. 无特定技能：走 LLM 推理
            executeLlmReasoning(inputs);
        }
    }

    /**
     * 直接调用单一技能工具 — 最短路径，不经过 LLM。
     */
    private void executeSingleSkill(Map<String, Object> inputs) {
        AiosEventSchema.emit(AiosEventSchema.dagNodeToolCalling(
                agentId, runId, node.instanceId(), node.role(), singleSkill));

        log.info("[SubAgent:{}] 调用工具: {} args={}", agentId, singleSkill, inputs.keySet());
        SyscallResponse resp = sdk.callTool(agentId, singleSkill, inputs);

        if (!resp.success()) {
            node.putOutput("_tool_success", false);
            node.putOutput("_tool_result_state", resp.resultState() == null
                    ? "UNKNOWN" : resp.resultState().name());
            if (resp.errorMessage() != null) node.putOutput("_tool_error", resp.errorMessage());
            // Give a DURING contract a chance to turn a failed tool result
            // into an explicit OBSERVE/ASK_USER/ABORT decision. The ordinary
            // tool exception remains the fallback for legacy nodes.
            WorkflowEngine.instance().observeToolResult(runId, node, context, singleSkill, resp);
            throw new RuntimeException("工具 " + singleSkill + " 调用失败: " + resp.errorMessage());
        }

        // 提交输出到工作流内存总线
        Map<String, Object> output = new HashMap<>();
        output.put("result", resp.data());
        output.put("tool", singleSkill);
        output.put("engine", "sub-agent");
        node.putOutput("result", resp.data());
        context.commitNodeOutput(node.instanceId(), output);
        WorkflowEngine.instance().observeToolResult(runId, node, context, singleSkill, resp);
    }

    /**
     * 纯 LLM 推理路径 — 无特定工具，用 LLM 思考节点任务。
     */
    private void executeLlmReasoning(Map<String, Object> inputs) {
        AiosEventSchema.emit(AiosEventSchema.dagNodeLlmThinking(
                agentId, runId, node.instanceId(), node.role(), node.role()));

        // 构建提示词：节点角色 + 上游输入
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是工作流节点 '").append(node.role()).append("' 的执行者。\n");
        prompt.append("节点 ID: ").append(node.instanceId()).append("\n");
        if (!inputs.isEmpty()) {
            prompt.append("上游输入:\n");
            inputs.forEach((k, v) -> prompt.append("  - ").append(k).append(": ")
                    .append(v != null ? v.toString().substring(0, Math.min(v.toString().length(), 200)) : "null")
                    .append("\n"));
        }
        // 注入用户参数
        if (!node.userParams().isEmpty()) {
            prompt.append("参数:\n");
            node.userParams().forEach((k, v) -> {
                Object resolved = context.resolveValue(v);
                prompt.append("  - ").append(k).append(": ").append(resolved).append("\n");
            });
        }
        prompt.append("\n请完成任务并给出结果。");

        String carryover = context == null ? "" : context.renderCarryoverState();
        if (!carryover.isBlank()) prompt.append("\n\n").append(carryover);
        String response = sdk.think(agentId, prompt.toString());
        if (response == null || response.isBlank() || response.startsWith("[SDK Error]")) {
            throw new RuntimeException("LLM 推理失败: " + response);
        }

        // 提交输出到工作流内存总线
        Map<String, Object> output = new HashMap<>();
        output.put("result", response);
        output.put("engine", "sub-agent-llm");
        node.putOutput("result", response);
        context.commitNodeOutput(node.instanceId(), output);
    }

    /**
     * 解析上游节点的输入 — 从 WorkflowContext 内存总线读取。
     */
    private Map<String, Object> resolveUpstreamInputs() {
        Map<String, Object> inputs = new HashMap<>();
        for (String depId : node.getUpstreamDependencies()) {
            Map<String, Object> upstreamOutput = context.getNodeOutput(depId);
            if (upstreamOutput != null && !upstreamOutput.isEmpty()) {
                inputs.putAll(upstreamOutput);
            }
        }
        // 解析用户参数中的 {{nodeId.variable}} 引用
        Map<String, Object> resolvedParams = new HashMap<>();
        node.userParams().forEach((k, v) -> {
            Object resolved = context.resolveValue(v);
            if (resolved != null) {
                resolvedParams.put(k, resolved);
            }
        });
        inputs.putAll(resolvedParams);
        return inputs;
    }

    @Override
    protected void onMessage(String msg) {
        log.debug("[SubAgent:{}] 收到消息: {}", agentId,
                msg != null ? msg.substring(0, Math.min(msg.length(), 80)) : "null");
    }
}
