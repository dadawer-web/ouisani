package com.ouisani.aios.user.apps.omnifactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.ouisani.aios.core.tool.Port;
import com.ouisani.aios.core.verification.VerificationContract;
import com.ouisani.aios.core.verification.VerificationResult;

/**
 * 工作流节点实例 — DAG 状态机中的可执行顶点。
 * <p>
 * 实现了 Dify 风格的内存隔离与状态管控。
 * 保留 record 风格的访问器方法名（instanceId()、role() 等），
 * 确保所有现有调用代码无需修改。
 *
 * @see WorkflowContext
 * @see WorkflowEngine
 */
public class WorkflowNode {

    // ── 静态定义字段（编译时确定，运行时不变） ──
    private final String instanceId;
    private final String role;
    private final String blueprintId;
    private final Map<String, String> userParams;
    private final String subscribeTopic;
    private final String publishTopic;
    private final String executor;

    // ── 运行时状态字段（DAG 状态机） ──
    private List<String> upstreamDependencies = new ArrayList<>();

    public enum Status { PENDING, RUNNING, SUCCESS, FAILED, SKIPPED, SUSPENDED }
    private volatile Status status = Status.PENDING;

    // 节点的内存输出：执行完毕后，结果存入此 Map 供下游节点读取
    private Map<String, Object> outputData = new ConcurrentHashMap<>();

    // ── Verification-aware completion contract ──
    // Explicit contracts are opt-in; a declared output schema is also promoted
    // to a deterministic completion requirement by WorkflowEngine.
    private volatile VerificationContract verificationContract;
    private volatile VerificationResult lastVerificationResult;
    private volatile Map<String, Object> verificationBaseline = Map.of();

    // ── 条件路由字段（借鉴 Langflow ConditionalRouter） ──
    // 当条件表达式求值为 false 时，此节点被标记为 SKIPPED
    // 格式： "{{upstream_node.result_type}} == 'success'" 或 "{{search_result.count}} > 0"
    private String condition;

    public String getCondition() { return condition; }
    public void setCondition(String condition) { this.condition = condition; }

    // ── Frozen 缓存字段（借鉴 Langflow Frozen Vertex） ──
    private boolean frozen = false;

    public boolean isFrozen() { return frozen; }
    public void setFrozen(boolean frozen) { this.frozen = frozen; }

    // ================= 迭代节点 (Iteration) 专属属性 =================
    // 标识这是一个特殊的控制流节点（默认为 false）
    private boolean isIteration = false;

    // 迭代节点的输入数据源（必须是一个数组，通常通过占位符如 {{fetch_node.url_list}} 引用）
    private String iteratorDataVariable;

    // 在每次循环中，当前正在处理的单条数据的局部变量别名（例如："item"）
    private String iteratorItemAlias;

    // 迭代节点内部包裹的子节点集合（子 DAG 图纸）
    private List<WorkflowNode> childNodes = new ArrayList<>();
    // =================================================================

    // ── 声明式端口字段（借鉴 Langflow Component 的 inputs/outputs） ──
    private List<Port> inputPorts = new ArrayList<>();
    private List<Port> outputPorts = new ArrayList<>();

    // ── 强类型契约字段（Type-Safe Graph Validation） ──
    // 描述该节点的输入/输出数据 Schema，供 GraphValidator 验证类型兼容性
    private SchemaDefinition inputSchema;
    private SchemaDefinition outputSchema;

    // ════════════════════════════════════════════════════════════════
    //  构造函数
    // ════════════════════════════════════════════════════════════════

    /** 完整构造函数（兼容原有 7 参数 record 调用） */
    public WorkflowNode(String instanceId, String role, String blueprintId,
                        Map<String, String> userParams, String subscribeTopic,
                        String publishTopic, String executor) {
        this.instanceId = instanceId;
        this.role = role;
        this.blueprintId = blueprintId;
        this.userParams = userParams != null ? userParams : new HashMap<>();
        this.subscribeTopic = subscribeTopic != null ? subscribeTopic : "";
        this.publishTopic = publishTopic != null ? publishTopic : "";
        this.executor = executor != null ? executor : "omni";
    }

    /** 兼容旧调用：无 executor 时默认 "omni" */
    public WorkflowNode(String instanceId, String role, String blueprintId,
                        Map<String, String> userParams, String subscribeTopic, String publishTopic) {
        this(instanceId, role, blueprintId, userParams, subscribeTopic, publishTopic, "omni");
    }

    /** 精简构造函数（Dify 风格，仅指定核心字段） */
    public WorkflowNode(String instanceId, String role, String executor) {
        this(instanceId, role, instanceId, new HashMap<>(), "", "", executor);
    }

    // ════════════════════════════════════════════════════════════════
    //  Record 风格访问器（保持与原有 record 完全兼容的方法签名）
    // ════════════════════════════════════════════════════════════════

    public String instanceId() { return instanceId; }
    public String role() { return role; }
    public String blueprintId() { return blueprintId; }
    public Map<String, String> userParams() { return userParams; }
    public String subscribeTopic() { return subscribeTopic; }
    public String publishTopic() { return publishTopic; }
    public String executor() { return executor; }

    // ════════════════════════════════════════════════════════════════
    //  运行时状态访问器（DAG 状态机专用）
    // ════════════════════════════════════════════════════════════════

    public List<String> getUpstreamDependencies() { return upstreamDependencies; }
    public void addDependency(String upstreamId) { this.upstreamDependencies.add(upstreamId); }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public Map<String, Object> getOutputData() { return outputData; }
    public void putOutput(String key, Object value) { this.outputData.put(key, value); }

    /** Declarative business/evidence contract evaluated before SUCCESS. */
    public VerificationContract verificationContract() { return verificationContract; }
    public void setVerificationContract(VerificationContract contract) { this.verificationContract = contract; }

    /** Last stage result, retained for the run console and recovery diagnostics. */
    public VerificationResult lastVerificationResult() { return lastVerificationResult; }
    public void setLastVerificationResult(VerificationResult result) { this.lastVerificationResult = result; }

    /** Capture pre-execution state so state-change predicates have a real baseline. */
    public void captureVerificationBaseline() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : outputData.entrySet()) {
            snapshot.put(entry.getKey(), snapshotValue(entry.getValue()));
        }
        this.verificationBaseline = Collections.unmodifiableMap(snapshot);
    }

    public Map<String, Object> verificationBaseline() { return verificationBaseline; }

    private static Object snapshotValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(entry.getKey(), snapshotValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) copy.add(snapshotValue(item));
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Set<?> set) {
            return Collections.unmodifiableSet(new java.util.LinkedHashSet<>(
                    set.stream().map(WorkflowNode::snapshotValue).toList()));
        }
        if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> copy = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                copy.add(snapshotValue(java.lang.reflect.Array.get(value, i)));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    // ════════════════════════════════════════════════════════════════
    //  迭代节点访问器
    // ════════════════════════════════════════════════════════════════

    public boolean isIteration() { return isIteration; }
    public void setIteration(boolean iteration) { isIteration = iteration; }

    public String getIteratorDataVariable() { return iteratorDataVariable; }
    public void setIteratorDataVariable(String iteratorDataVariable) { this.iteratorDataVariable = iteratorDataVariable; }

    public String getIteratorItemAlias() { return iteratorItemAlias; }
    public void setIteratorItemAlias(String iteratorItemAlias) { this.iteratorItemAlias = iteratorItemAlias; }

    public List<WorkflowNode> getChildNodes() { return childNodes; }
    public void setChildNodes(List<WorkflowNode> childNodes) { this.childNodes = childNodes; }

    // ════════════════════════════════════════════════════════════════
    //  声明式端口访问器（借鉴 Langflow Edge 端口路由）
    // ════════════════════════════════════════════════════════════════

    public List<Port> inputPorts() { return inputPorts; }
    public void setInputPorts(List<Port> ports) { this.inputPorts = ports != null ? ports : new ArrayList<>(); }

    public List<Port> outputPorts() { return outputPorts; }
    public void setOutputPorts(List<Port> ports) { this.outputPorts = ports != null ? ports : new ArrayList<>(); }

    /** 根据端口名获取输出端口 */
    public Port getOutputPort(String name) {
        return outputPorts.stream().filter(p -> p.name().equals(name)).findFirst().orElse(null);
    }

    /** 根据端口名获取输入端口 */
    public Port getInputPort(String name) {
        return inputPorts.stream().filter(p -> p.name().equals(name)).findFirst().orElse(null);
    }

    // ── 强类型契约访问器 ──
    public SchemaDefinition inputSchema() { return inputSchema; }
    public void setInputSchema(SchemaDefinition schema) { this.inputSchema = schema; }

    public SchemaDefinition outputSchema() { return outputSchema; }
    public void setOutputSchema(SchemaDefinition schema) { this.outputSchema = schema; }

    @Override
    public String toString() {
        return "WorkflowNode{" + instanceId + ", role=" + role + ", executor=" + executor + ", status=" + status + "}";
    }
}
