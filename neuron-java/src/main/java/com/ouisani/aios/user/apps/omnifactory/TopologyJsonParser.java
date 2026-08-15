package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.network.GatewayJsonParser;
import com.ouisani.aios.core.tool.Port;
import com.ouisani.aios.core.verification.CorrectiveAction;
import com.ouisani.aios.core.verification.EvidenceRequirement;
import com.ouisani.aios.core.verification.EvidenceType;
import com.ouisani.aios.core.verification.GoalPredicate;
import com.ouisani.aios.core.verification.VerificationContract;
import com.ouisani.aios.core.verification.VerificationStage;
import com.ouisani.aios.user.sdk.AiosSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 拓扑 JSON 解析器 — 从 TopologyCompiler 抽取的 LLM 输出反序列化工具集。
 * <p>
 * 负责将 LLM 返回的拓扑 JSON 解析为强类型的 {@link WorkflowManifest}（含 nodes 和 edges），
 * 并提供依赖反推、JSON 修复、节点/边/端口提取等辅助能力。
 * <p>
 * 所有方法均为静态、无状态，不依赖 TopologyCompiler 实例。
 * <p>
 * OS 类比：相当于编译器后端的 Parser — 将文本 IR 解析为内存中的 AST。
 *
 * @see TopologyCompiler
 * @see WorkflowManifest
 */
final class TopologyJsonParser {

    private static final Logger log = LoggerFactory.getLogger(TopologyJsonParser.class);

    private TopologyJsonParser() {}

    // ════════════════════════════════════════════════════════════════
    //  拓扑 → WorkflowManifest 主入口
    // ════════════════════════════════════════════════════════════════

    /**
     * 将 LLM 返回的拓扑 JSON 解析为完整的 WorkflowManifest（含 nodes 和 edges）。
     * <p>
     * 解析层级：
     * <ol>
     *   <li>L1 文本清洗：去除 Markdown 代码块包裹和 &lt;think/&gt; 标签</li>
     *   <li>L2 JSON 解析：提取 nodes 数组并逐个解析为 WorkflowNode</li>
     *   <li>L4 LLM 自修复：节点解析为空时调用 E_CORE 修复 JSON 格式后重试</li>
     *   <li>edges 解析：提取 LLM 声明的 edges，并通过 userParams 占位符反推补全</li>
     * </ol>
     *
     * @param json         LLM 返回的拓扑 JSON
     * @param fallbackName workflowName 缺失时的回退名称
     * @return 包含 nodes 和 edges 的 WorkflowManifest
     */
    static WorkflowManifest parseTopologyToManifest(String json, String fallbackName) {
        // ── L1 文本清洗：去除 Markdown 代码块包裹和 <think/> 标签 ──
        String cleaned = json.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        cleaned = cleaned.replaceAll("(?s)<think.*?</think*>", "").trim();

        // 提取 workflowName
        String workflowName = extractJsonValue(cleaned, "workflowName");
        if (workflowName == null || workflowName.isBlank()) {
            workflowName = fallbackName.length() > 20
                    ? fallbackName.substring(0, 20).trim()
                    : fallbackName.trim();
        }

        // ── L2 JSON 解析：提取 nodes 数组 ──
        String nodesArray = extractNodesArray(cleaned);
        List<WorkflowNode> nodes = new ArrayList<>();
        if (nodesArray != null && !nodesArray.isBlank()) {
            List<String> rawNodes = GatewayJsonParser.splitJsonObjectsSafe(nodesArray);
            for (String obj : rawNodes) {
                WorkflowNode node = parseSingleNode(obj);
                if (node != null) {
                    nodes.add(node);
                }
            }
        }

        // ── L4 LLM 自修复：如果节点解析为空，调用 E_CORE 修复 JSON 格式 ──
        if (nodes.isEmpty() && !cleaned.isBlank()) {
            log.warn("[TopologyCompiler] 节点解析为空，尝试 E_CORE 修复 JSON 格式...");
            String repaired = repairJsonWithECore(cleaned);
            if (repaired != null && !repaired.isBlank()) {
                String repairedNodesArray = extractNodesArray(repaired);
                if (repairedNodesArray != null && !repairedNodesArray.isBlank()) {
                    List<String> rawNodes = GatewayJsonParser.splitJsonObjectsSafe(repairedNodesArray);
                    for (String obj : rawNodes) {
                        WorkflowNode node = parseSingleNode(obj);
                        if (node != null) {
                            nodes.add(node);
                        }
                    }
                    if (!nodes.isEmpty()) {
                        log.info("[TopologyCompiler] E_CORE 修复成功，解析出 {} 个节点", nodes.size());
                        cleaned = repaired; // 使用修复后的 JSON 继续解析 edges
                    }
                }
            }
        }

        // ── L2 JSON 解析：提取 edges 数组（LLM 声明的边） ──
        List<WorkflowEdge> edges = new ArrayList<>();
        String edgesArray = GatewayJsonParser.extractJsonArray(cleaned, "edges");
        if (edgesArray != null && !edgesArray.isBlank()) {
            edges = parseEdges(edgesArray);
            if (!edges.isEmpty()) {
                WorkflowEngine.getInstance().setEdges(edges);
                log.info("[TopologyCompiler] 已解析 {} 条 Edge (LLM 声明)", edges.size());
            }
        }

        // ── 依赖反推：从 userParams 的 {{nodeId.varName}} 占位符重建 edges ──
        // 借鉴 JARVIS fix_dep — 不完全信任 LLM 声明的 edges，从参数引用反推补全
        List<WorkflowEdge> rebuiltEdges = rebuildEdgesFromReferences(nodes, edges);
        if (rebuiltEdges.size() > edges.size()) {
            log.info("[TopologyCompiler] 依赖反推新增 {} 条 Edge (共 {} 条)",
                    rebuiltEdges.size() - edges.size(), rebuiltEdges.size());
            edges = rebuiltEdges;
            WorkflowEngine.getInstance().setEdges(edges);
        }

        return new WorkflowManifest(workflowName, nodes, List.of(), List.of(), "omni", edges);
    }

    // ════════════════════════════════════════════════════════════════
    //  L4 降级 — E_CORE JSON 修复
    // ════════════════════════════════════════════════════════════════

    /**
     * L4 降级 — 调用 E_CORE 小模型修复格式错误的 JSON。
     * <p>
     * 借鉴 TaskBench 的 reformat_by 机制：当弱模型（或强模型）的 JSON 输出
     * 无法解析时，用另一个 LLM 调用修复格式，而非直接放弃进入下一次重试。
     * <p>
     * 成本权衡：E_CORE 修复（低成本） vs. 重新生成整个拓扑（高成本）。
     *
     * @param malformedJson 格式错误的 JSON 字符串
     * @return 修复后的 JSON 字符串，失败返回 null
     */
    static String repairJsonWithECore(String malformedJson) {
        AiosSdk sdk = AiosSdk.getInstance();
        String prompt = """
                以下文本应该是 JSON 格式的工作流拓扑定义，但可能存在格式错误（如多余文本、缺失引号、尾逗号等）。
                请修复格式问题，只返回纯 JSON，不要任何解释或 Markdown 标记。

                原始文本：
                """ + malformedJson;

        try {
            String repaired = sdk.think("json_repair", prompt);
            if (repaired == null || repaired.isBlank()) return null;
            // 清理响应
            repaired = repaired.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            repaired = repaired.replaceAll("(?s)<think.*?</think*>", "").trim();
            log.info("[TopologyCompiler] E_CORE 修复返回: {} chars", repaired.length());
            return repaired;
        } catch (Exception e) {
            log.warn("[TopologyCompiler] E_CORE JSON 修复失败: {}", e.getMessage());
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  依赖反推 — 从 userParams 占位符重建 edges
    // ════════════════════════════════════════════════════════════════

    /**
     * 依赖反推 — 从节点的 userParams 占位符重建 edges。
     * <p>
     * 借鉴 JARVIS 的 fix_dep 设计：不完全信任 LLM 声明的 edges 数组，
     * 而是从节点 userParams 中的 {{上游节点ID.变量名}} 占位符反推真实数据依赖。
     * <p>
     * 规则：
     * <ul>
     *   <li>userParams 的 key → 目标节点的 inputPort 名称</li>
     *   <li>{{nodeId.varName}} → 源节点的 outputPort 名称</li>
     *   <li>与已声明的 edges 去重（按 4 元组）</li>
     *   <li>源节点不存在时跳过（容错）</li>
     * </ul>
     *
     * @param nodes         已解析的节点列表
     * @param existingEdges LLM 声明的 edges（可能不完整或有错误）
     * @return 补全后的 edges 列表（包含原有 + 反推的）
     */
    static List<WorkflowEdge> rebuildEdgesFromReferences(List<WorkflowNode> nodes,
                                                         List<WorkflowEdge> existingEdges) {
        // 匹配 {{nodeId.varName}} 占位符
        Pattern refPattern = Pattern.compile("\\{\\{(\\w+)\\.(\\w+)\\}\\}");

        // 已有 edges 去重集合
        Set<String> existingKeys = new HashSet<>();
        for (WorkflowEdge edge : existingEdges) {
            existingKeys.add(edgeKey(edge.sourceNodeId(), edge.sourcePortName(),
                    edge.targetNodeId(), edge.targetPortName()));
        }

        List<WorkflowEdge> rebuilt = new ArrayList<>(existingEdges);

        // 构建 nodeId → node 映射（验证源节点存在）
        Map<String, WorkflowNode> nodeMap = new HashMap<>();
        for (WorkflowNode node : nodes) {
            nodeMap.put(node.instanceId(), node);
        }

        // 扫描每个节点的 userParams，查找 {{nodeId.varName}} 引用
        for (WorkflowNode targetNode : nodes) {
            Map<String, String> params = targetNode.userParams();
            if (params == null || params.isEmpty()) continue;

            for (Map.Entry<String, String> entry : params.entrySet()) {
                String inputPortName = entry.getKey();   // userParams 的 key = 输入端口名
                String paramValue = entry.getValue();
                if (paramValue == null) continue;

                Matcher matcher = refPattern.matcher(paramValue);
                while (matcher.find()) {
                    String sourceNodeId = matcher.group(1);
                    String sourcePortName = matcher.group(2);

                    // 容错：源节点不存在则跳过
                    if (!nodeMap.containsKey(sourceNodeId)) continue;

                    // 去重检查
                    String key = edgeKey(sourceNodeId, sourcePortName,
                            targetNode.instanceId(), inputPortName);
                    if (existingKeys.contains(key)) continue;

                    // 创建新 edge
                    rebuilt.add(new WorkflowEdge(sourceNodeId, sourcePortName,
                            targetNode.instanceId(), inputPortName));
                    existingKeys.add(key);
                    log.info("[TopologyCompiler] 反推 Edge: {}.{} → {}.{}",
                            sourceNodeId, sourcePortName, targetNode.instanceId(), inputPortName);
                }
            }
        }

        return rebuilt;
    }

    /** 生成 edge 的唯一标识 key（用于去重） */
    private static String edgeKey(String srcNode, String srcPort, String tgtNode, String tgtPort) {
        return srcNode + "|" + srcPort + "|" + tgtNode + "|" + tgtPort;
    }

    // ════════════════════════════════════════════════════════════════
    //  节点解析
    // ════════════════════════════════════════════════════════════════

    /**
     * 解析单个节点 JSON 对象为 WorkflowNode（含迭代节点递归解析）。
     */
    static WorkflowNode parseSingleNode(String obj) {
        String instanceId = extractJsonValue(obj, "instanceId");
        // 兼容旧格式：id → instanceId
        if (instanceId == null) instanceId = extractJsonValue(obj, "id");
        String blueprintId = extractJsonValue(obj, "blueprintId");
        String role = extractJsonValue(obj, "role");
        String executor = extractJsonValue(obj, "executor");
        String subscribeTopic = extractJsonValue(obj, "subscribeTopic");
        String publishTopic = extractJsonValue(obj, "publishTopic");

        // 解析 userParams 对象
        Map<String, String> userParams = extractUserParams(obj);

        // 解析 upstreamDependencies 数组
        List<String> upstreamDeps = extractUpstreamDependencies(obj);

        if (instanceId == null || instanceId.isBlank()) {
            return null;
        }

        WorkflowNode node = new WorkflowNode(
                instanceId.trim(),
                role != null ? role.trim() : "",
                blueprintId != null ? blueprintId.trim() : instanceId.trim(),
                userParams,
                subscribeTopic != null ? subscribeTopic.trim() : "",
                publishTopic != null ? publishTopic.trim() : "",
                executor != null ? executor.trim() : "omni"
        );

        // 注入上游依赖
        for (String dep : upstreamDeps) {
            node.addDependency(dep.trim());
        }

        // ── 条件路由字段解析 ──
        String condition = extractJsonValue(obj, "condition");
        if (condition != null && !condition.isBlank()) {
            node.setCondition(condition.trim());
        }

        // ── Frozen 字段解析 ──
        String frozenStr = extractJsonValue(obj, "frozen");
        if ("true".equalsIgnoreCase(frozenStr)) {
            node.setFrozen(true);
        }

        // ── 声明式端口解析（借鉴 Langflow Edge 端口路由） ──
        String inputPortsArray = GatewayJsonParser.extractJsonArray(obj, "inputPorts");
        if (inputPortsArray != null && !inputPortsArray.isBlank()) {
            List<Port> inputPorts = parsePorts(inputPortsArray);
            node.setInputPorts(inputPorts);
        }

        String outputPortsArray = GatewayJsonParser.extractJsonArray(obj, "outputPorts");
        if (outputPortsArray != null && !outputPortsArray.isBlank()) {
            List<Port> outputPorts = parsePorts(outputPortsArray);
            node.setOutputPorts(outputPorts);
        }

        // ── 强类型 Schema 解析（Type-Safe Graph Validation） ──
        // 解析 inputSchema / outputSchema，供 GraphValidator 验证边类型兼容性
        String inputSchemaArray = GatewayJsonParser.extractJsonArray(obj, "inputSchema");
        if (inputSchemaArray != null && !inputSchemaArray.isBlank()) {
            SchemaDefinition inputSchema = SchemaDefinition.parse(instanceId + "_input", inputSchemaArray);
            if (inputSchema != null) {
                node.setInputSchema(inputSchema);
            }
        }

        String outputSchemaArray = GatewayJsonParser.extractJsonArray(obj, "outputSchema");
        if (outputSchemaArray != null && !outputSchemaArray.isBlank()) {
            SchemaDefinition outputSchema = SchemaDefinition.parse(instanceId + "_output", outputSchemaArray);
            if (outputSchema != null) {
                node.setOutputSchema(outputSchema);
            }
        }

        // ── Verification-aware completion contract ──
        // The declarative form keeps workflow JSON portable while the Java API
        // still supports application-defined GoalPredicate implementations.
        String verificationObject = GatewayJsonParser.extractJsonObject(obj, "verification");
        if (verificationObject == null) {
            verificationObject = GatewayJsonParser.extractJsonObject(obj, "verificationContract");
        }
        VerificationContract verificationContract = parseVerificationContract(verificationObject);
        if (verificationContract != null && verificationContract.enabled()) {
            node.setVerificationContract(verificationContract);
        }

        // ── 迭代节点专属字段解析 ──
        String isIterationStr = extractJsonValue(obj, "isIteration");
        if ("true".equalsIgnoreCase(isIterationStr)) {
            node.setIteration(true);

            String iteratorDataVariable = extractJsonValue(obj, "iteratorDataVariable");
            if (iteratorDataVariable != null) {
                node.setIteratorDataVariable(iteratorDataVariable.trim());
            }

            String iteratorItemAlias = extractJsonValue(obj, "iteratorItemAlias");
            if (iteratorItemAlias != null) {
                node.setIteratorItemAlias(iteratorItemAlias.trim());
            }

            // 递归解析 childNodes 数组
            String childNodesArray = GatewayJsonParser.extractJsonArray(obj, "childNodes");
            if (childNodesArray != null && !childNodesArray.isBlank()) {
                List<String> rawChildNodes = GatewayJsonParser.splitJsonObjectsSafe(childNodesArray);
                for (String childObj : rawChildNodes) {
                    WorkflowNode childNode = parseSingleNode(childObj);
                    if (childNode != null) {
                        node.getChildNodes().add(childNode);
                    }
                }
            }

            log.info("[TopologyCompiler] Iteration node parsed: id={}, dataVar={}, itemAlias={}, childCount={}",
                    instanceId, iteratorDataVariable, iteratorItemAlias, node.getChildNodes().size());
        }

        return node;
    }

    /** Parse the portable JSON subset of a verification contract. */
    static VerificationContract parseVerificationContract(String jsonObject) {
        if (jsonObject == null || jsonObject.isBlank()) return null;

        VerificationContract.Builder builder = VerificationContract.builder();
        String stagesArray = GatewayJsonParser.extractJsonArray(jsonObject, "stages");
        String singleStage = GatewayJsonParser.extractJsonField(jsonObject, "stage");
        List<String> stageValues = stagesArray == null
                ? (singleStage == null ? List.of() : List.of(singleStage))
                : quotedValues(stagesArray);
        if (!stageValues.isEmpty()) {
            for (String raw : stageValues) {
                if (raw == null || raw.isBlank()) continue;
                try {
                    VerificationStage stage = VerificationStage.valueOf(normalizeType(raw));
                    switch (stage) {
                        case DURING -> builder.during();
                        case SKILL_END -> builder.skillEnd();
                        case FINAL -> builder.finalStage();
                    }
                } catch (IllegalArgumentException ignored) {
                    log.warn("[TopologyCompiler] Ignore unknown verification stage: {}", raw);
                }
            }
        }

        CorrectiveAction onFail = parseCorrectiveAction(
                GatewayJsonParser.extractJsonField(jsonObject, "onFail"));
        if (onFail != null) builder.onFail(onFail);
        CorrectiveAction onInconclusive = parseCorrectiveAction(
                GatewayJsonParser.extractJsonField(jsonObject, "onInconclusive"));
        if (onInconclusive != null) builder.onInconclusive(onInconclusive);

        String predicatesArray = GatewayJsonParser.extractJsonArray(jsonObject, "predicates");
        if (predicatesArray != null) {
            for (String predicateObject : GatewayJsonParser.splitJsonObjectsSafe(predicatesArray)) {
                GoalPredicate predicate = parsePredicate(predicateObject);
                if (predicate != null) builder.predicate(predicate);
            }
        }

        String evidenceArray = GatewayJsonParser.extractJsonArray(jsonObject, "evidence");
        if (evidenceArray == null) {
            evidenceArray = GatewayJsonParser.extractJsonArray(jsonObject, "evidenceRequirements");
        }
        if (evidenceArray != null) {
            for (String evidenceObject : GatewayJsonParser.splitJsonObjectsSafe(evidenceArray)) {
                EvidenceRequirement requirement = parseEvidenceRequirement(evidenceObject);
                if (requirement != null) builder.require(requirement);
            }
        }

        VerificationContract contract = builder.build();
        return contract.enabled() ? contract : null;
    }

    private static GoalPredicate parsePredicate(String jsonObject) {
        String type = normalizeType(GatewayJsonParser.extractJsonField(jsonObject, "type"));
        String key = firstNonBlank(
                GatewayJsonParser.extractJsonField(jsonObject, "key"),
                GatewayJsonParser.extractJsonField(jsonObject, "reference"),
                GatewayJsonParser.extractJsonField(jsonObject, "stepId"));
        if ("FINAL_RESPONSE_CONTAINS".equals(type)) {
            key = firstNonBlank(GatewayJsonParser.extractJsonField(jsonObject, "token"), key);
        }
        if (type == null || key == null) return null;
        return switch (type) {
            case "OUTPUT_PRESENT", "OUTPUT_KEY" -> GoalPredicate.outputPresent(key);
            case "OUTPUT_EQUALS", "OUTPUT_EQUAL" ->
                    GoalPredicate.outputEquals(key, parseScalar(GatewayJsonParser.extractJsonField(jsonObject, "value")));
            case "STATE_CHANGED", "STATE_CHANGE" -> GoalPredicate.stateChanged(key);
            case "REQUIRED_STEP", "STEP_COMPLETED" -> GoalPredicate.requiredStepCompleted(key);
            case "UPSTREAM_SUCCEEDED", "UPSTREAM_SUCCESS" -> GoalPredicate.upstreamSucceeded(key);
            case "FINAL_RESPONSE_CONTAINS" -> GoalPredicate.finalResponseContains(key);
            default -> {
                log.warn("[TopologyCompiler] Ignore unknown verification predicate: {}", type);
                yield null;
            }
        };
    }

    private static EvidenceRequirement parseEvidenceRequirement(String jsonObject) {
        String type = normalizeType(GatewayJsonParser.extractJsonField(jsonObject, "type"));
        if (type == null) return null;
        String reference = firstNonBlank(
                GatewayJsonParser.extractJsonField(jsonObject, "reference"),
                GatewayJsonParser.extractJsonField(jsonObject, "key"),
                GatewayJsonParser.extractJsonField(jsonObject, "stepId"),
                GatewayJsonParser.extractJsonField(jsonObject, "path"),
                GatewayJsonParser.extractJsonField(jsonObject, "evidenceId"));
        boolean required = parseBoolean(GatewayJsonParser.extractJsonField(jsonObject, "required"), true);
        if (!"PERMISSION_APPROVAL".equals(type) && !"PERMISSION_STILL_VALID".equals(type)
                && (reference == null || reference.isBlank())) return null;
        return switch (type) {
            case "OUTPUT_KEY" -> new EvidenceRequirement(
                    "output:" + reference, "output contains " + reference,
                    EvidenceType.OUTPUT_KEY, reference, null, required);
            case "OUTPUT_SCHEMA" -> new EvidenceRequirement(
                    "output_schema", "output matches declared schema",
                    EvidenceType.OUTPUT_SCHEMA, "output",
                    parseOutputSchema(GatewayJsonParser.extractJsonArray(jsonObject, "fields")), required);
            case "STATE_CHANGED", "STATE_CHANGE" -> new EvidenceRequirement(
                    "state_changed:" + reference, "state[" + reference + "] changed",
                    EvidenceType.STATE_CHANGE, reference, null, required);
            case "REQUIRED_STEP", "STEP_COMPLETED" -> new EvidenceRequirement(
                    "step:" + reference, "step " + reference + " completed",
                    EvidenceType.REQUIRED_STEP, reference, null, required);
            case "ARTIFACT_EXISTS", "ARTIFACT" -> new EvidenceRequirement(
                    "artifact:" + reference, "artifact exists: " + reference,
                    EvidenceType.ARTIFACT_EXISTS, reference, null, required);
            case "UPSTREAM_SUCCESS", "UPSTREAM_SUCCEEDED" -> new EvidenceRequirement(
                    "upstream:" + reference, "upstream step succeeded: " + reference,
                    EvidenceType.UPSTREAM_SUCCESS, reference, null, required);
            case "PERMISSION_APPROVAL", "PERMISSION_STILL_VALID" -> new EvidenceRequirement(
                    "permission_still_valid", "permission approval remains valid",
                    EvidenceType.PERMISSION_APPROVAL, "permission", null, required);
            case "FINAL_RESPONSE_COVERAGE", "RESPONSE_COVERAGE" -> new EvidenceRequirement(
                    "response_covered:" + reference, "final response is covered by evidence " + reference,
                    EvidenceType.FINAL_RESPONSE_COVERAGE, reference, null, required);
            default -> {
                log.warn("[TopologyCompiler] Ignore unknown verification evidence: {}", type);
                yield null;
            }
        };
    }

    private static Map<String, Class<?>> parseOutputSchema(String fieldsArray) {
        if (fieldsArray == null) return Map.of();
        Map<String, Class<?>> fields = new LinkedHashMap<>();
        for (String fieldObject : GatewayJsonParser.splitJsonObjectsSafe(fieldsArray)) {
            String name = firstNonBlank(
                    GatewayJsonParser.extractJsonField(fieldObject, "name"),
                    GatewayJsonParser.extractJsonField(fieldObject, "key"));
            if (name == null) continue;
            String type = normalizeType(GatewayJsonParser.extractJsonField(fieldObject, "type"));
            fields.put(name, switch (type == null ? "ANY" : type) {
                case "INTEGER", "INT", "FLOAT", "NUMBER" -> Number.class;
                case "BOOLEAN", "BOOL" -> Boolean.class;
                case "STRING", "TEXT", "FILE_PATH", "FILE", "PATH" -> String.class;
                default -> Object.class;
            });
        }
        return fields;
    }

    private static List<String> quotedValues(String arrayContent) {
        List<String> values = new ArrayList<>();
        if (arrayContent == null) return values;
        Matcher matcher = Pattern.compile("\\\"([^\\\"]*)\\\"").matcher(arrayContent);
        while (matcher.find()) values.add(matcher.group(1));
        return values;
    }

    private static Object parseScalar(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        try {
            if (value.matches("-?\\d+")) return Long.valueOf(value);
            if (value.matches("-?(?:\\d+\\.\\d*|\\d*\\.\\d+)(?:[eE][+-]?\\d+)?")) {
                return Double.valueOf(value);
            }
        } catch (NumberFormatException ignored) {
            // Keep the original token as a string when it is not numeric.
        }
        return value;
    }

    private static boolean parseBoolean(String raw, boolean fallback) {
        return raw == null ? fallback : Boolean.parseBoolean(raw.trim());
    }

    private static CorrectiveAction parseCorrectiveAction(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return CorrectiveAction.valueOf(normalizeType(raw));
        } catch (IllegalArgumentException ignored) {
            log.warn("[TopologyCompiler] Ignore unknown verification corrective action: {}", raw);
            return null;
        }
    }

    private static String normalizeType(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return raw.trim().replaceAll("([a-z])([A-Z])", "$1_$2")
                .toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    //  JSON 提取原语
    // ════════════════════════════════════════════════════════════════

    /**
     * 从 JSON 中提取 "nodes" 数组的内部内容。
     */
    static String extractNodesArray(String json) {
        // 先尝试 Dify 格式：{ "workflowName": "...", "nodes": [...] }
        String nodesArray = GatewayJsonParser.extractJsonArray(json, "nodes");
        if (nodesArray != null) return nodesArray;

        // 如果整个 JSON 就是一个数组 [...]
        String trimmed = json.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }

        return null;
    }

    /**
     * 从 JSON 对象中提取 upstreamDependencies 数组。
     * <p>
     * 匹配 "upstreamDependencies": ["id1", "id2"] 格式。
     */
    static List<String> extractUpstreamDependencies(String jsonObj) {
        List<String> deps = new ArrayList<>();
        String arrayContent = GatewayJsonParser.extractJsonArray(jsonObj, "upstreamDependencies");
        if (arrayContent == null || arrayContent.isBlank()) return deps;

        // 提取数组中的每个字符串值
        Pattern strPattern = Pattern.compile("\"([^\"]+)\"");
        Matcher m = strPattern.matcher(arrayContent);
        while (m.find()) {
            deps.add(m.group(1));
        }
        return deps;
    }

    /**
     * 从 JSON 对象中提取 userParams 字典。
     * <p>
     * 匹配 "userParams":{"key1":"val1","key2":"val2"} 格式。
     */
    static Map<String, String> extractUserParams(String jsonObj) {
        Map<String, String> params = new LinkedHashMap<>();

        // 先提取 userParams 对象的内容
        Pattern paramsPattern = Pattern.compile("\"userParams\"\\s*:\\s*\\{([^}]*)\\}");
        Matcher paramsMatcher = paramsPattern.matcher(jsonObj);
        if (!paramsMatcher.find()) return params;

        String paramsContent = paramsMatcher.group(1);

        // 逐个提取 key:value 对
        Pattern kvPattern = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"");
        Matcher kvMatcher = kvPattern.matcher(paramsContent);
        while (kvMatcher.find()) {
            params.put(kvMatcher.group(1), kvMatcher.group(2));
        }

        return params;
    }

    /**
     * 从 JSON 对象中提取指定 key 的字符串值。
     */
    static String extractJsonValue(String jsonObj, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*?)\"");
        Matcher m = p.matcher(jsonObj);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    //  端口与边解析
    // ════════════════════════════════════════════════════════════════

    /**
     * 解析端口数组 JSON 为 Port 列表。
     * <p>
     * 匹配格式：[{"name": "data_in", "dataType": "JsonData", "description": "...", "required": true}, ...]
     * 也兼容 "type" 字段名（与 "dataType" 等效）。
     */
    static List<Port> parsePorts(String portsArray) {
        List<Port> ports = new ArrayList<>();
        List<String> rawPortObjs = GatewayJsonParser.splitJsonObjectsSafe(portsArray);
        for (String portObj : rawPortObjs) {
            String name = extractJsonValue(portObj, "name");
            // 兼容 "dataType" 和 "type" 两种字段名
            String dataType = extractJsonValue(portObj, "dataType");
            if (dataType == null) dataType = extractJsonValue(portObj, "type");
            String description = extractJsonValue(portObj, "description");
            String requiredStr = extractJsonValue(portObj, "required");
            boolean required = requiredStr == null || "true".equalsIgnoreCase(requiredStr.trim());

            if (name != null && !name.isBlank()) {
                ports.add(new Port(
                        name.trim(),
                        dataType != null ? dataType.trim() : "any",
                        description != null ? description.trim() : "",
                        required
                ));
            }
        }
        return ports;
    }

    /**
     * 解析边数组 JSON 为 WorkflowEdge 列表。
     * <p>
     * 匹配格式：[{"sourceNodeId": "a", "sourcePortName": "result", "targetNodeId": "b", "targetPortName": "data_in"}, ...]
     */
    static List<WorkflowEdge> parseEdges(String edgesArray) {
        List<WorkflowEdge> edges = new ArrayList<>();
        List<String> rawEdgeObjs = GatewayJsonParser.splitJsonObjectsSafe(edgesArray);
        for (String edgeObj : rawEdgeObjs) {
            String sourceNodeId = extractJsonValue(edgeObj, "sourceNodeId");
            String sourcePortName = extractJsonValue(edgeObj, "sourcePortName");
            String targetNodeId = extractJsonValue(edgeObj, "targetNodeId");
            String targetPortName = extractJsonValue(edgeObj, "targetPortName");
            if (sourceNodeId != null && targetNodeId != null
                    && sourcePortName != null && targetPortName != null) {
                edges.add(new WorkflowEdge(
                        sourceNodeId.trim(), sourcePortName.trim(),
                        targetNodeId.trim(), targetPortName.trim()));
            }
        }
        return edges;
    }
}
