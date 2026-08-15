package com.ouisani.aios.core.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 工具依赖图构建器 — 基于 I/O 契约自动推导工具间可能的依赖关系。
 * <p>
 * 借鉴 JARVIS / TaskBench 的工具图采样机制：在构造评测集或为 LLM 提供
 * 工具编排上下文时，需要知道哪些工具的输出可以喂给哪些工具的输入。
 * <p>
 * <b>构建规则</b>：对每一对工具 (A, B)，若 A 的任一输出端口类型
 * 与 B 的任一输入端口类型 {@link DataTypes#isCompatible(String, String)} 兼容，
 * 则在图中添加一条有向边 A → B（表示 A 的输出可作为 B 的输入）。
 * <p>
 * <b>用途</b>：
 * <ul>
 *   <li>为 {@code TopologyBenchmark} 提供工具拓扑采样基础（Back-Instruct 方法）</li>
 *   <li>为 TopologyCompiler 的 LLM 提示词注入"可衔接工具对"上下文</li>
 *   <li>可视化工具生态的依赖网络（诊断工具覆盖盲区）</li>
 * </ul>
 * <p>
 * <b>OS 类比</b>：相当于 Linux 内核的 sys_call_dependency_map —
 * 哪些系统调用的输出可作为另一些系统调用的输入。
 *
 * @see Tool#inputPorts()
 * @see Tool#outputPorts()
 * @see DataTypes#isCompatible(String, String)
 */
public class ToolGraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(ToolGraphBuilder.class);

    /**
     * 工具图中的有向边 — 表示 source 工具的输出可衔接 target 工具的输入。
     * <p>
     * 一条 {@code ToolEdge} 携带具体的端口对信息，便于 LLM 理解
     * "A 的哪个输出端口 → B 的哪个输入端口"是合法的衔接。
     */
    public record ToolEdge(
            String sourceTool,
            String sourcePort,
            String targetTool,
            String targetPort,
            String sourceType,
            String targetType
    ) {
        @Override
        public String toString() {
            return sourceTool + "." + sourcePort + "(" + sourceType + ") → "
                    + targetTool + "." + targetPort + "(" + targetType + ")";
        }
    }

    /**
     * 工具依赖图 — 包含节点（工具名）和边（可衔接的工具对）。
     */
    public record ToolGraph(
            List<String> tools,
            List<ToolEdge> edges
    ) {
        public ToolGraph {
            if (tools == null) tools = List.of();
            if (edges == null) edges = List.of();
        }

        /**
         * 获取指定工具的所有下游工具（即此工具的输出可作为其输入的工具）。
         */
        public List<String> downstreamOf(String toolName) {
            Set<String> result = new LinkedHashSet<>();
            for (ToolEdge edge : edges) {
                if (edge.sourceTool().equals(toolName)) {
                    result.add(edge.targetTool());
                }
            }
            return new ArrayList<>(result);
        }

        /**
         * 获取指定工具的所有上游工具（即其输出可作为此工具输入的工具）。
         */
        public List<String> upstreamOf(String toolName) {
            Set<String> result = new LinkedHashSet<>();
            for (ToolEdge edge : edges) {
                if (edge.targetTool().equals(toolName)) {
                    result.add(edge.sourceTool());
                }
            }
            return new ArrayList<>(result);
        }

        /**
         * 获取从指定工具出发的所有出边。
         */
        public List<ToolEdge> outgoingEdges(String toolName) {
            List<ToolEdge> result = new ArrayList<>();
            for (ToolEdge edge : edges) {
                if (edge.sourceTool().equals(toolName)) {
                    result.add(edge);
                }
            }
            return result;
        }

        /**
         * 获取指向指定工具的所有入边。
         */
        public List<ToolEdge> incomingEdges(String toolName) {
            List<ToolEdge> result = new ArrayList<>();
            for (ToolEdge edge : edges) {
                if (edge.targetTool().equals(toolName)) {
                    result.add(edge);
                }
            }
            return result;
        }
    }

    private static final class Holder {
        static final ToolGraphBuilder INSTANCE = new ToolGraphBuilder();
    }

    public static ToolGraphBuilder getInstance() {
        return Holder.INSTANCE;
    }

    private ToolGraphBuilder() {
    }

    /**
     * 从 ToolRegistry 构建工具依赖图。
     * <p>
     * 遍历所有已注册工具，对每一对 (A, B) 检查 I/O 端口类型兼容性，
     * 若兼容则添加有向边 A → B。
     *
     * @return 工具依赖图
     */
    public ToolGraph build() {
        return build(ToolRegistry.instance().all());
    }

    /**
     * 从指定的工具集合构建工具依赖图。
     * <p>
     * 允许传入子集，用于构建特定领域或特定 SOP 的工具子图。
     *
     * @param tools 工具集合
     * @return 工具依赖图
     */
    public ToolGraph build(Collection<Tool<? extends ToolInput>> tools) {
        if (tools == null || tools.isEmpty()) {
            log.warn("[ToolGraphBuilder] 工具集合为空，返回空图");
            return new ToolGraph(List.of(), List.of());
        }

        // 转为列表以便索引访问
        List<Tool<? extends ToolInput>> toolList = new ArrayList<>(tools);
        List<String> toolNames = new ArrayList<>();
        for (Tool<? extends ToolInput> tool : toolList) {
            toolNames.add(tool.name());
        }

        List<ToolEdge> edges = new ArrayList<>();

        // 对每一对工具 (A, B) 检查 I/O 端口兼容性
        for (Tool<? extends ToolInput> sourceTool : toolList) {
            List<Port> sourceOutputs = sourceTool.outputPorts();
            if (sourceOutputs == null || sourceOutputs.isEmpty()) continue;

            for (Tool<? extends ToolInput> targetTool : toolList) {
                // 自环跳过 — 工具不衔接自身（避免噪声边）
                if (sourceTool.name().equals(targetTool.name())) continue;

                List<Port> targetInputs = targetTool.inputPorts();
                if (targetInputs == null || targetInputs.isEmpty()) continue;

                // 检查每一对 (sourceOutput, targetInput) 的类型兼容性
                for (Port sourcePort : sourceOutputs) {
                    for (Port targetPort : targetInputs) {
                        if (DataTypes.isCompatible(sourcePort.type(), targetPort.type())) {
                            edges.add(new ToolEdge(
                                    sourceTool.name(), sourcePort.name(),
                                    targetTool.name(), targetPort.name(),
                                    sourcePort.type(), targetPort.type()
                            ));
                        }
                    }
                }
            }
        }

        log.info("[ToolGraphBuilder] 工具图构建完成: {} 个工具, {} 条依赖边",
                toolNames.size(), edges.size());
        return new ToolGraph(toolNames, edges);
    }

    /**
     * 构建工具图的可读字符串表示 — 供 LLM 提示词注入或日志输出。
     * <p>
     * 格式示例：
     * <pre>
     * 工具依赖图（A → B 表示 A 的输出可作为 B 的输入）：
     *   web_fetch.content(WebPageContent) → html_to_markdown.html(HtmlText)
     *   file_read.content(FileContent) → grep.pattern(PlainText)
     *   ...
     * </pre>
     *
     * @param graph 工具依赖图
     * @return 可读字符串
     */
    public String toReadableString(ToolGraph graph) {
        if (graph == null || graph.edges().isEmpty()) {
            return "（工具依赖图为空）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("工具依赖图（A → B 表示 A 的输出可作为 B 的输入）：\n");
        for (ToolEdge edge : graph.edges()) {
            sb.append("  ").append(edge).append("\n");
        }
        return sb.toString();
    }
}
