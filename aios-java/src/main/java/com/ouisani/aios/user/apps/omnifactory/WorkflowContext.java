package com.ouisani.aios.user.apps.omnifactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作流全局内存上下文 (Memory Context Bus)。
 * <p>
 * 维护整个 DAG 生命周期内的变量池，实现 Dify 风格的节点间数据透传。
 * 替代原来的物理文件传递模式，所有节点间数据交换均通过内存总线完成。
 *
 * @see WorkflowNode
 * @see WorkflowEngine
 */
public class WorkflowContext {

    private static final Logger log = LoggerFactory.getLogger(WorkflowContext.class);

    private final String workflowId;

    // 父作用域：子引擎（迭代循环内部）通过此引用向上追溯变量
    private final WorkflowContext parentContext;

    // 外层 Key: 节点 ID, 内层 Map: 该节点输出的变量集合
    private final Map<String, Map<String, Object>> globalMemory = new ConcurrentHashMap<>();

    /** 根上下文构造（顶层 DAG 使用） */
    public WorkflowContext(String workflowId) {
        this.workflowId = workflowId;
        this.parentContext = null;
    }

    /** 子引擎局部上下文（如迭代循环内部），可沿作用域链向上查找变量 */
    public WorkflowContext(String workflowId, WorkflowContext parentContext) {
        this.workflowId = workflowId;
        this.parentContext = parentContext;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    /** 将某个节点的所有输出快照写入总线 */
    public void commitNodeOutput(String nodeId, Map<String, Object> output) {
        globalMemory.put(nodeId, new ConcurrentHashMap<>(output));
    }

    /** 下游节点读取上游变量 (如: 从 "spider_node" 读取 "url_list")。
     *  作用域链：当前作用域找不到时，沿 parentContext 向上追溯。 */
    public Object getVariable(String sourceNodeId, String variableKey) {
        Map<String, Object> nodeMem = globalMemory.get(sourceNodeId);
        if (nodeMem != null && nodeMem.containsKey(variableKey)) {
            return nodeMem.get(variableKey);
        }
        // 当前作用域没有，向上追溯
        if (parentContext != null) {
            return parentContext.getVariable(sourceNodeId, variableKey);
        }
        return null;
    }

    /** 获取某个节点的全部输出 */
    public Map<String, Object> getNodeOutput(String nodeId) {
        return globalMemory.getOrDefault(nodeId, Map.of());
    }

    /**
     * 获取某个节点的内存快照 — 供 Boulder 持久化使用。
     * 返回该节点输出的深拷贝，防止外部修改污染内存总线。
     */
    public Map<String, Object> getNodeMemorySnapshot(String nodeId) {
        Map<String, Object> nodeMem = globalMemory.get(nodeId);
        if (nodeMem == null) return Map.of();
        return new ConcurrentHashMap<>(nodeMem);
    }

    /** 检查某个节点是否已有输出（可用于判断上游是否完成） */
    public boolean hasOutput(String nodeId) {
        return globalMemory.containsKey(nodeId);
    }

    /**
     * 智能变量解析器 (Dify 灵魂所在)。
     * <p>
     * 如果输入参数是 {@code "{{fetch_node.raw_html}}"}，则动态从全局内存中
     * 提取对应上游节点的输出；如果是普通文本，则原样返回字符串。
     *
     * @param paramValue 参数值，可能包含 {{nodeId.variable}} 引用
     * @return 解析后的值（可能是从内存总线取出的 Object，或原样字符串）
     */
    public Object resolveValue(String paramValue) {
        if (paramValue != null && paramValue.trim().startsWith("{{") && paramValue.trim().endsWith("}}")) {
            String content = paramValue.trim();
            content = content.substring(2, content.length() - 2).trim();
            int dotIndex = content.indexOf('.');
            if (dotIndex > 0) {
                String sourceNodeId = content.substring(0, dotIndex);
                String variableKey = content.substring(dotIndex + 1);
                Object val = getVariable(sourceNodeId, variableKey);
                if (val != null) {
                    return val;
                }
                log.warn("[WorkflowContext] Variable pool missed reference: {}", paramValue);
            }
        }
        return paramValue;
    }
}
