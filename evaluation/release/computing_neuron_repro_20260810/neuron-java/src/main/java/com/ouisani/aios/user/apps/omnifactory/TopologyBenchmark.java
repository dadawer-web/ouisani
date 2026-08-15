package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.tool.Port;
import com.ouisani.aios.core.tool.Tool;
import com.ouisani.aios.core.tool.ToolGraphBuilder.ToolEdge;
import com.ouisani.aios.core.tool.ToolGraphBuilder.ToolGraph;
import com.ouisani.aios.core.tool.ToolInput;
import com.ouisani.aios.core.tool.ToolRegistry;
import com.ouisani.aios.user.sdk.AiosSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 拓扑编译基准评测器 — 借鉴 TaskBench 的 Back-Instruct 方法构造评测集。
 * <p>
 * <b>核心思想</b>：传统评测是"用户指令 → 期望工具图"，但人工编写指令
 * 容易与工具图脱节。Back-Instruct 反过来：先采样合法的工具图，
 * 再让 LLM 据图反推用户指令，天然保证指令与工具图对齐。
 * <p>
 * <b>评测流程</b>：
 * <ol>
 *   <li>从 {@link ToolGraphBuilder} 获取工具依赖图（基于 I/O 契约自动构建）</li>
 *   <li>采样三种拓扑：单节点 (node) / 链式 (chain) / 有向无环图 (DAG)</li>
 *   <li>对每个采样到的拓扑，调用 LLM 反向生成用户指令</li>
 *   <li>将 (用户指令, 期望工具图) 对作为基准，评测 {@link TopologyCompiler} 的编译质量</li>
 * </ol>
 * <p>
 * <b>评测指标</b>：
 * <ul>
 *   <li><b>工具召回率</b>：编译出的拓扑中包含期望工具的比例</li>
 *   <li><b>边精确率</b>：编译出的 edges 中与期望 edges 匹配的比例</li>
 *   <li><b>拓扑结构相似度</b>：编译图与期望图的编辑距离</li>
 * </ul>
 * <p>
 * <b>用途</b>：在 TopologyCompiler 或 MoEGatingRouter 升级后运行回归测试，
 * 确保编译质量不退化。
 *
 * @see ToolGraphBuilder
 * @see TopologyCompiler
 */
public class TopologyBenchmark {

    private static final Logger log = LoggerFactory.getLogger(TopologyBenchmark.class);

    /** 采样拓扑类型 */
    public enum TopologyType {
        /** 单节点 — 最简单的工具调用 */
        NODE,
        /** 链式 — A → B → C 线性依赖 */
        CHAIN,
        /** 有向无环图 — 含分叉与汇聚 */
        DAG
    }

    /**
     * 评测样本 — 一个 (用户指令, 期望工具图) 对。
     */
    public record BenchmarkSample(
            String id,
            TopologyType topologyType,
            String userInstruction,
            List<String> expectedTools,
            List<ToolEdge> expectedEdges,
            String description
    ) {
        public BenchmarkSample {
            if (expectedTools == null) expectedTools = List.of();
            if (expectedEdges == null) expectedEdges = List.of();
        }
    }

    /**
     * 评测结果 — 单个样本的编译质量评估。
     */
    public record BenchmarkResult(
            String sampleId,
            double toolRecall,        // 期望工具被编译出的比例 [0,1]
            double edgePrecision,     // 编译边中匹配期望边的比例 [0,1]
            int expectedToolCount,
            int compiledToolCount,
            int matchedToolCount,
            int expectedEdgeCount,
            int compiledEdgeCount,
            int matchedEdgeCount,
            List<String> missingTools,
            List<String> extraTools
    ) {
    }

    private static final class Holder {
        static final TopologyBenchmark INSTANCE = new TopologyBenchmark();
    }

    public static TopologyBenchmark getInstance() {
        return Holder.INSTANCE;
    }

    private TopologyBenchmark() {
    }

    /**
     * 生成评测样本集 — Back-Instruct 方法。
     * <p>
     * 从 ToolRegistry 构建工具依赖图，采样多种拓扑，
     * 对每个拓扑调用 LLM 反向生成用户指令。
     *
     * @param samplesPerType 每种拓扑类型采样的样本数
     * @return 评测样本列表
     */
    public List<BenchmarkSample> generateSamples(int samplesPerType) {
        log.info("[TopologyBenchmark] 开始生成评测样本 (每种类型 {} 个)", samplesPerType);

        ToolGraph graph = com.ouisani.aios.core.tool.ToolGraphBuilder.getInstance().build();
        if (graph.tools().isEmpty()) {
            log.warn("[TopologyBenchmark] 工具图为空，无法生成样本");
            return List.of();
        }

        List<BenchmarkSample> samples = new ArrayList<>();
        Random rng = new Random(42); // 固定种子保证可复现

        // 采样三种拓扑
        for (TopologyType type : TopologyType.values()) {
            for (int i = 0; i < samplesPerType; i++) {
                try {
                    BenchmarkSample sample = sampleAndInstruct(graph, type, rng, i);
                    if (sample != null) {
                        samples.add(sample);
                    }
                } catch (Exception e) {
                    log.warn("[TopologyBenchmark] 采样 {} #{} 失败: {}", type, i, e.getMessage());
                }
            }
        }

        log.info("[TopologyBenchmark] 评测样本生成完成: {} 个样本", samples.size());
        return samples;
    }

    /**
     * 采样一个拓扑并反向生成用户指令。
     */
    private BenchmarkSample sampleAndInstruct(ToolGraph graph, TopologyType type,
                                              Random rng, int index) {
        List<String> tools;
        List<ToolEdge> edges;

        switch (type) {
            case NODE:
                tools = sampleNode(graph, rng);
                edges = List.of();
                break;
            case CHAIN:
                var chainResult = sampleChain(graph, rng);
                tools = chainResult.first();
                edges = chainResult.second();
                break;
            case DAG:
                var dagResult = sampleDag(graph, rng);
                tools = dagResult.first();
                edges = dagResult.second();
                break;
            default:
                return null;
        }

        if (tools.isEmpty()) return null;

        // Back-Instruct: 让 LLM 据工具图反推用户指令
        String instruction = backInstruct(tools, edges, type);
        if (instruction == null || instruction.isBlank()) {
            log.warn("[TopologyBenchmark] LLM 反向生成指令为空，跳过");
            return null;
        }

        String id = type.name().toLowerCase() + "_" + index;
        String desc = String.format("%s 拓扑: %d 工具, %d 边",
                type, tools.size(), edges.size());

        log.info("[TopologyBenchmark] 生成样本 {}: {} → '{}'",
                id, tools, truncate(instruction, 60));

        return new BenchmarkSample(id, type, instruction, tools, edges, desc);
    }

    /**
     * 采样单节点拓扑 — 随机选一个工具。
     */
    private List<String> sampleNode(ToolGraph graph, Random rng) {
        List<String> tools = graph.tools();
        if (tools.isEmpty()) return List.of();
        return List.of(tools.get(rng.nextInt(tools.size())));
    }

    /**
     * 采样链式拓扑 — A → B → C（长度 2-4）。
     */
    private Pair<List<String>, List<ToolEdge>> sampleChain(ToolGraph graph, Random rng) {
        if (graph.edges().isEmpty()) {
            return new Pair<>(List.of(), List.of());
        }

        // 随机选一条起始边
        ToolEdge startEdge = graph.edges().get(rng.nextInt(graph.edges().size()));
        List<String> tools = new ArrayList<>();
        tools.add(startEdge.sourceTool());

        List<ToolEdge> edges = new ArrayList<>();
        edges.add(startEdge);

        // 链式延伸：从当前末端的 targetTool 出发，找下一条边
        String currentEnd = startEdge.targetTool();
        tools.add(currentEnd);

        int maxChainLen = 4;
        while (tools.size() < maxChainLen) {
            List<ToolEdge> outgoing = graph.outgoingEdges(currentEnd);
            if (outgoing.isEmpty()) break;

            ToolEdge next = outgoing.get(rng.nextInt(outgoing.size()));
            // 避免环（链不允许回头）
            if (tools.contains(next.targetTool())) break;

            edges.add(next);
            tools.add(next.targetTool());
            currentEnd = next.targetTool();
        }

        return new Pair<>(tools, edges);
    }

    /**
     * 采样 DAG 拓扑 — 含分叉与汇聚（3-5 节点）。
     */
    private Pair<List<String>, List<ToolEdge>> sampleDag(ToolGraph graph, Random rng) {
        if (graph.edges().isEmpty()) {
            return new Pair<>(List.of(), List.of());
        }

        // 从一个有多个出边的工具开始（分叉点）
        String forkTool = null;
        for (String tool : graph.tools()) {
            if (graph.outgoingEdges(tool).size() >= 2) {
                forkTool = tool;
                break;
            }
        }
        // 退化：随便选一个有出边的工具
        if (forkTool == null) {
            for (ToolEdge edge : graph.edges()) {
                forkTool = edge.sourceTool();
                break;
            }
        }
        if (forkTool == null) {
            return new Pair<>(List.of(), List.of());
        }

        Set<String> tools = new LinkedHashSet<>();
        tools.add(forkTool);

        List<ToolEdge> edges = new ArrayList<>();
        List<ToolEdge> outgoing = graph.outgoingEdges(forkTool);

        // 分叉：取 2 条出边
        int fanOut = Math.min(2, outgoing.size());
        List<String> midNodes = new ArrayList<>();
        for (int i = 0; i < fanOut; i++) {
            ToolEdge edge = outgoing.get(rng.nextInt(outgoing.size()));
            if (!tools.contains(edge.targetTool())) {
                edges.add(edge);
                tools.add(edge.targetTool());
                midNodes.add(edge.targetTool());
            }
        }

        // 汇聚：尝试找一个工具，其输入端口能接收多个 midNode 的输出
        for (String midNode : midNodes) {
            List<ToolEdge> midOutgoing = graph.outgoingEdges(midNode);
            for (ToolEdge edge : midOutgoing) {
                if (!tools.contains(edge.targetTool()) && tools.size() < 5) {
                    edges.add(edge);
                    tools.add(edge.targetTool());
                    break;
                }
            }
        }

        return new Pair<>(new ArrayList<>(tools), edges);
    }

    /**
     * Back-Instruct — 让 LLM 据工具图反推用户指令。
     * <p>
     * 借鉴 TaskBench 的核心创新：先有工具图，再生成指令，
     * 天然保证指令与工具图对齐。
     */
    private String backInstruct(List<String> tools, List<ToolEdge> edges, TopologyType type) {
        AiosSdk sdk = AiosSdk.getInstance();

        // 构建工具描述上下文
        StringBuilder toolCtx = new StringBuilder();
        ToolRegistry registry = ToolRegistry.instance();
        for (String toolName : tools) {
            Optional<Tool<ToolInput>> opt = registry.get(toolName);
            if (opt.isEmpty()) continue;
            Tool<?> tool = opt.get();
            toolCtx.append("- ").append(tool.name()).append(": ").append(tool.description()).append("\n");
        }

        // 构建边描述
        StringBuilder edgeCtx = new StringBuilder();
        if (!edges.isEmpty()) {
            edgeCtx.append("工具间的数据流（A → B 表示 A 的输出作为 B 的输入）：\n");
            for (ToolEdge edge : edges) {
                edgeCtx.append("  ").append(edge.sourceTool()).append(" → ").append(edge.targetTool()).append("\n");
            }
        }

        String prompt = """
                你是一个工作流逆向工程师。下面给你一个工具调用拓扑图，请反推一个用户可能会提出的自然语言指令，
                使得 TopologyCompiler 在收到该指令时，会编译出与下面拓扑图一致的工具调用结构。

                拓扑类型: %s

                可用工具:
                %s
                %s
                要求：
                1. 指令必须自然、像真实用户会说的话
                2. 指令必须明确需要使用上述所有工具
                3. 指令必须隐含上述工具间的数据流依赖
                4. 只返回一句用户指令，不要任何解释或 Markdown 标记

                用户指令：
                """.formatted(type, toolCtx, edgeCtx);

        try {
            String response = sdk.think("topology_benchmark", prompt);
            if (response == null) return null;
            // 清理 Markdown 和 think 标签
            response = response.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            response = response.replaceAll("(?s)<think.*?</think*>", "").trim();
            // 取第一行作为指令（LLM 可能会多嘴）
            int newlineIdx = response.indexOf('\n');
            if (newlineIdx > 0 && response.length() < 200) {
                // 短响应直接返回
                return response;
            }
            return response.lines().findFirst().orElse(response);
        } catch (Exception e) {
            log.warn("[TopologyBenchmark] Back-Instruct LLM 调用失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 运行评测 — 对每个样本调用 TopologyCompiler 编译并评估质量。
     * <p>
     * 注意：此方法会实际调用 LLM（P_CORE），成本较高。
     * 建议在 CI 或手动回归测试时运行。
     *
     * @param samples 评测样本列表
     * @return 评测结果列表
     */
    public List<BenchmarkResult> evaluate(List<BenchmarkSample> samples) {
        List<BenchmarkResult> results = new ArrayList<>();
        for (BenchmarkSample sample : samples) {
            try {
                BenchmarkResult result = evaluateSample(sample);
                results.add(result);
                log.info("[TopologyBenchmark] 样本 {} 评测完成: toolRecall={}, edgePrecision={}",
                        sample.id(),
                        String.format("%.2f", result.toolRecall()),
                        String.format("%.2f", result.edgePrecision()));
            } catch (Exception e) {
                log.error("[TopologyBenchmark] 样本 {} 评测失败: {}", sample.id(), e.getMessage());
            }
        }
        return results;
    }

    /**
     * 评估单个样本 — 调用 TopologyCompiler 编译并比对。
     */
    private BenchmarkResult evaluateSample(BenchmarkSample sample) {
        // 调用 TopologyCompiler 编译
        String topologyJson = TopologyCompiler.compileTopology(
                sample.userInstruction(), List.of(), List.of());

        // 解析编译结果中的工具名（从 blueprintId 或 instanceId 提取）
        Set<String> compiledTools = extractToolNamesFromTopology(topologyJson);
        Set<String> compiledEdges = extractEdgeKeysFromTopology(topologyJson);

        // 计算工具召回率
        Set<String> expectedTools = new HashSet<>(sample.expectedTools());
        int matchedTools = 0;
        for (String expected : expectedTools) {
            if (compiledTools.contains(expected)) {
                matchedTools++;
            }
        }
        double toolRecall = expectedTools.isEmpty() ? 1.0 : (double) matchedTools / expectedTools.size();

        // 计算边精确率
        Set<String> expectedEdgeKeys = new HashSet<>();
        for (ToolEdge edge : sample.expectedEdges()) {
            expectedEdgeKeys.add(edge.sourceTool() + "|" + edge.targetTool());
        }
        int matchedEdges = 0;
        for (String compiledEdge : compiledEdges) {
            String[] parts = compiledEdge.split("\\|");
            if (parts.length >= 2) {
                String key = parts[0] + "|" + parts[1];
                if (expectedEdgeKeys.contains(key)) {
                    matchedEdges++;
                }
            }
        }
        double edgePrecision = compiledEdges.isEmpty() ? 1.0
                : (double) matchedEdges / compiledEdges.size();

        // 缺失/多余工具
        List<String> missingTools = new ArrayList<>();
        for (String expected : expectedTools) {
            if (!compiledTools.contains(expected)) {
                missingTools.add(expected);
            }
        }
        List<String> extraTools = new ArrayList<>();
        for (String compiled : compiledTools) {
            if (!expectedTools.contains(compiled)) {
                extraTools.add(compiled);
            }
        }

        return new BenchmarkResult(
                sample.id(),
                toolRecall,
                edgePrecision,
                expectedTools.size(),
                compiledTools.size(),
                matchedTools,
                sample.expectedEdges().size(),
                compiledEdges.size(),
                matchedEdges,
                missingTools,
                extraTools
        );
    }

    /**
     * 从拓扑 JSON 中提取工具名（从 blueprintId 字段）。
     */
    private Set<String> extractToolNamesFromTopology(String json) {
        Set<String> tools = new HashSet<>();
        if (json == null || json.isBlank()) return tools;
        // 简单正则提取 "blueprintId": "xxx"
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\"blueprintId\"\\s*:\\s*\"([^\"]+)\"");
        java.util.regex.Matcher matcher = pattern.matcher(json);
        while (matcher.find()) {
            tools.add(matcher.group(1));
        }
        // 也提取 instanceId 作为兜底
        java.util.regex.Pattern idPattern = java.util.regex.Pattern.compile(
                "\"instanceId\"\\s*:\\s*\"([^\"]+)\"");
        java.util.regex.Matcher idMatcher = idPattern.matcher(json);
        while (idMatcher.find()) {
            tools.add(idMatcher.group(1));
        }
        return tools;
    }

    /**
     * 从拓扑 JSON 中提取边的 key（sourceNodeId|targetNodeId）。
     */
    private Set<String> extractEdgeKeysFromTopology(String json) {
        Set<String> edges = new HashSet<>();
        if (json == null || json.isBlank()) return edges;
        // 提取 edges 数组中的 source/target
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\\{[^}]*\"sourceNodeId\"\\s*:\\s*\"([^\"]+)\"[^}]*\"targetNodeId\"\\s*:\\s*\"([^\"]+)\"[^}]*\\}");
        java.util.regex.Matcher matcher = pattern.matcher(json);
        while (matcher.find()) {
            edges.add(matcher.group(1) + "|" + matcher.group(2));
        }
        // 兼容 upstreamDependencies 字段
        java.util.regex.Pattern depPattern = java.util.regex.Pattern.compile(
                "\"instanceId\"\\s*:\\s*\"([^\"]+)\"[^}]*\"upstreamDependencies\"\\s*:\\s*\\[([^\\]]*)\\]");
        java.util.regex.Matcher depMatcher = depPattern.matcher(json);
        while (depMatcher.find()) {
            String target = depMatcher.group(1);
            String deps = depMatcher.group(2);
            // 提取 deps 中的引号字符串
            java.util.regex.Pattern strPattern = java.util.regex.Pattern.compile("\"([^\"]+)\"");
            java.util.regex.Matcher strMatcher = strPattern.matcher(deps);
            while (strMatcher.find()) {
                edges.add(strMatcher.group(1) + "|" + target);
            }
        }
        return edges;
    }

    /**
     * 打印评测报告摘要。
     */
    public String summarize(List<BenchmarkResult> results) {
        if (results == null || results.isEmpty()) {
            return "（无评测结果）";
        }
        double avgToolRecall = results.stream().mapToDouble(BenchmarkResult::toolRecall).average().orElse(0);
        double avgEdgePrecision = results.stream().mapToDouble(BenchmarkResult::edgePrecision).average().orElse(0);

        StringBuilder sb = new StringBuilder();
        sb.append("════════ TopologyBenchmark 评测报告 ════════\n");
        sb.append(String.format("样本数: %d\n", results.size()));
        sb.append(String.format("平均工具召回率: %.2f%%\n", avgToolRecall * 100));
        sb.append(String.format("平均边精确率: %.2f%%\n", avgEdgePrecision * 100));
        sb.append("\n详细结果:\n");
        for (BenchmarkResult r : results) {
            sb.append(String.format("  [%s] toolRecall=%.2f (%d/%d), edgePrecision=%.2f (%d/%d)",
                    r.sampleId(),
                    r.toolRecall(), r.matchedToolCount(), r.expectedToolCount(),
                    r.edgePrecision(), r.matchedEdgeCount(), r.expectedEdgeCount()));
            if (!r.missingTools().isEmpty()) {
                sb.append(" missing=").append(r.missingTools());
            }
            if (!r.extraTools().isEmpty()) {
                sb.append(" extra=").append(r.extraTools());
            }
            sb.append("\n");
        }
        sb.append("═══════════════════════════════════════════");
        return sb.toString();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    /** 简易 Pair 工具类（避免引入额外依赖） */
    private record Pair<A, B>(A first, B second) {
    }
}
