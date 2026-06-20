package com.ouisani.aios.user.apps.omnifactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
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

    // ════════════════════════════════════════════════════════════════
    //  内存清理 — 借鉴 Symphony 的内存回收策略
    //  工作流完成后，清理非终点（Sink Node）的中间缓存，防止内存泄漏
    // ════════════════════════════════════════════════════════════════

    /**
     * 清理中间节点的内存 — 保留 Sink 节点（终点节点）的输出。
     * <p>
     * 工作流执行完毕后，调用此方法清理非终点的中间节点缓存，
     * 防止 50 个节点的图跑完后所有中间输出死死卡在内存里。
     * <p>
     * Sink 节点定义：没有任何下游节点依赖它的输出（即不是其他节点的上游）。
     *
     * @param sinkNodeIds 终点节点 ID 集合（这些节点的输出会被保留）
     * @return 清理的节点数量
     */
    public int cleanupIntermediateNodes(Set<String> sinkNodeIds) {
        int cleaned = 0;
        // 遍历 globalMemory，删除非 Sink 节点的缓存
        for (String nodeId : globalMemory.keySet()) {
            if (!sinkNodeIds.contains(nodeId)) {
                Map<String, Object> removed = globalMemory.remove(nodeId);
                if (removed != null) {
                    cleaned++;
                    log.debug("[WorkflowContext] 清理中间节点内存: {} ({} 个变量)", nodeId, removed.size());
                }
            }
        }
        if (cleaned > 0) {
            log.info("[WorkflowContext] 内存清理完成: 保留 {} 个 Sink 节点, 清理 {} 个中间节点, 剩余 {} 个节点",
                    sinkNodeIds.size(), cleaned, globalMemory.size());
        }
        return cleaned;
    }

    /**
     * 获取当前内存总线中的所有节点 ID。
     */
    public Set<String> getNodeIds() {
        return globalMemory.keySet();
    }

    /**
     * 获取内存总线大小（节点数）。
     */
    public int getMemorySize() {
        return globalMemory.size();
    }
}
