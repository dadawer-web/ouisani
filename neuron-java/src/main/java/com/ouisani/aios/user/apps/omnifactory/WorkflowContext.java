package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.selection.SelectionPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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

    // ── Carryover State（携带状态机）— 借鉴 OpenHarness 的 tool_metadata ──
    // 跨上下文压缩持久化的工作记忆：即使 TokenZram 压缩了对话历史，
    // Agent 也不会"失忆"——记住自己读过什么文件、做过什么操作。
    private final CarryoverState carryoverState = new CarryoverState();

    // ── Selection Policy（角色选择策略）— 借鉴 DyLAN listwise agent team selection ──
    // 工作流级配置：listwise top-K 裁剪。executeDagInternal 开头读取此字段，
    // 若声明了 listwise_top_k 则在 DAG 调度前裁剪未选中 role 的节点（标记 SKIPPED）。
    // null = 未声明（零行为变化，向后兼容）；NONE_POLICY = 显式无策略。
    // 不放入构造器：用 setter 注入，避免破坏现有 new WorkflowContext(workflowId) 调用。
    private SelectionPolicy selectionPolicy;

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

    /** 获取角色选择策略；null = 未声明（不触发 listwise 裁剪） */
    public SelectionPolicy getSelectionPolicy() {
        return selectionPolicy;
    }

    /** 注入角色选择策略（由 WorkflowEngine.executeWorkflow 从 manifest 传入） */
    public void setSelectionPolicy(SelectionPolicy selectionPolicy) {
        this.selectionPolicy = selectionPolicy;
    }

    /** 将某个节点的所有输出快照写入总线 */
    public void commitNodeOutput(String nodeId, Map<String, Object> output) {
        globalMemory.put(nodeId, new ConcurrentHashMap<>(output));
    }

    /** Remove a superseded node output before a continuation reruns that step. */
    public void removeNodeOutput(String nodeId) {
        if (nodeId != null) globalMemory.remove(nodeId);
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

    // ════════════════════════════════════════════════════════════════
    //  Carryover State — 携带状态机（借鉴 OpenHarness tool_metadata）
    //  跨上下文压缩持久化的工作记忆，cleanupIntermediateNodes 不会清理它。
    //  当 TokenZram 压缩上下文时，把 carryoverState 注入到压缩后的 prompt 头部。
    // ════════════════════════════════════════════════════════════════

    /** 获取携带状态机 */
    public CarryoverState getCarryoverState() {
        return carryoverState;
    }

    /**
     * 记录文件读取操作 — Agent 读过的文件不会因压缩而遗忘。
     *
     * @param filePath 文件路径
     * @param lineRange 读取的行范围（如 "1-50"），可为 null
     */
    public void recordFileRead(String filePath, String lineRange) {
        carryoverState.recordFileRead(filePath, lineRange);
    }

    /**
     * 记录工具调用 — Agent 用过的工具不会因压缩而遗忘。
     *
     * @param toolName 工具名称
     * @param summary 调用摘要
     */
    public void recordToolInvocation(String toolName, String summary) {
        carryoverState.recordToolInvocation(toolName, summary);
    }

    /**
     * 记录工作日志 — Agent 做过的操作按时间顺序记录。
     *
     * @param action 操作描述
     */
    public void recordWorkLog(String action) {
        carryoverState.recordWorkLog(action);
    }

    /**
     * 更新任务焦点状态 — 当前目标、下一步等。
     *
     * @param key 状态键（如 "current_goal", "next_step"）
     * @param value 状态值
     */
    public void updateTaskFocus(String key, String value) {
        carryoverState.updateTaskFocus(key, value);
    }

    /**
     * 生成携带状态的摘要文本 — 供 TokenZram 压缩后注入到 prompt 头部。
     * <p>
     * 格式示例：
     * <pre>
     * === CARRYOVER STATE (跨压缩持久化) ===
     * [任务焦点]
     *   current_goal: 实现用户登录功能
     *   next_step: 编写单元测试
     * [已读文件]
     *   - src/main/java/Login.java (1-50)
     *   - pom.xml (1-30)
     * [工具调用]
     *   - file_read: 读取 Login.java
     *   - bash: 运行 mvn compile
     * [工作日志]
     *   1. 读取了 Login.java 源码
     *   2. 编译项目，发现 2 个错误
     *   3. 修复了 import 缺失
     * === END CARRYOVER ===
     * </pre>
     *
     * @return 携带状态的文本摘要，如果没有状态则返回空字符串
     */
    public String renderCarryoverState() {
        return carryoverState.render();
    }

    /**
     * 携带状态机 — 借鉴 OpenHarness 的 tool_metadata 字典。
     * <p>
     * 维护跨上下文压缩持久化的工作记忆，包含：
     * <ul>
     *   <li>taskFocus: 任务焦点状态（当前目标、下一步等）</li>
     *   <li>readFiles: 已读文件列表（路径 + 行范围 + 预览）</li>
     *   <li>invokedTools: 已调用工具列表（工具名 + 摘要）</li>
     *   <li>workLog: 工作日志（按时间顺序的操作记录）</li>
     * </ul>
     * cleanupIntermediateNodes 不会清理这些状态。
     * 当 TokenZram 压缩上下文时，调用 render() 生成摘要文本注入到压缩后的 prompt 头部。
     */
    public static class CarryoverState {

        /** 任务焦点状态 — 当前目标、下一步等 */
        private final Map<String, String> taskFocus = new ConcurrentHashMap<>();

        /** 已读文件 — 路径 → 行范围（如 "1-50"） */
        private final Map<String, String> readFiles = new ConcurrentHashMap<>();

        /** 已调用工具 — 工具名 → 调用摘要列表 */
        private final Map<String, List<String>> invokedTools = new ConcurrentHashMap<>();

        /** 工作日志 — 按时间顺序的操作记录 */
        private final List<String> workLog = Collections.synchronizedList(new ArrayList<>());

        /** 最大工作日志条数 — 超过后自动裁剪旧条目 */
        private static final int MAX_WORK_LOG = 50;

        /** 最大已读文件数 — 超过后自动裁剪最旧的 */
        private static final int MAX_READ_FILES = 100;

        void recordFileRead(String filePath, String lineRange) {
            if (filePath == null || filePath.isBlank()) return;
            readFiles.put(filePath, lineRange != null ? lineRange : "all");
            // 裁剪：超过上限时移除最早的条目
            if (readFiles.size() > MAX_READ_FILES) {
                String oldest = readFiles.keySet().iterator().next();
                readFiles.remove(oldest);
            }
        }

        void recordToolInvocation(String toolName, String summary) {
            if (toolName == null || toolName.isBlank()) return;
            invokedTools.computeIfAbsent(toolName, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(summary != null ? summary : "");
        }

        void recordWorkLog(String action) {
            if (action == null || action.isBlank()) return;
            String entry = String.format("[%tT] %s", System.currentTimeMillis(), action);
            workLog.add(entry);
            // 裁剪：超过上限时移除最早的条目
            while (workLog.size() > MAX_WORK_LOG) {
                workLog.remove(0);
            }
        }

        void updateTaskFocus(String key, String value) {
            if (key == null || key.isBlank()) return;
            if (value == null || value.isBlank()) {
                taskFocus.remove(key);
            } else {
                taskFocus.put(key, value);
            }
        }

        /**
         * 渲染为文本摘要 — 供注入到压缩后的 prompt 头部。
         */
        String render() {
            if (taskFocus.isEmpty() && readFiles.isEmpty() && invokedTools.isEmpty() && workLog.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("=== CARRYOVER STATE (跨压缩持久化) ===\n");

            // 任务焦点
            if (!taskFocus.isEmpty()) {
                sb.append("[任务焦点]\n");
                for (Map.Entry<String, String> e : taskFocus.entrySet()) {
                    sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
                }
            }

            // 已读文件
            if (!readFiles.isEmpty()) {
                sb.append("[已读文件]\n");
                for (Map.Entry<String, String> e : readFiles.entrySet()) {
                    sb.append("  - ").append(e.getKey());
                    if (!"all".equals(e.getValue())) {
                        sb.append(" (").append(e.getValue()).append(")");
                    }
                    sb.append("\n");
                }
            }

            // 工具调用
            if (!invokedTools.isEmpty()) {
                sb.append("[工具调用]\n");
                for (Map.Entry<String, List<String>> e : invokedTools.entrySet()) {
                    for (String s : e.getValue()) {
                        sb.append("  - ").append(e.getKey()).append(": ").append(s).append("\n");
                    }
                }
            }

            // 工作日志
            if (!workLog.isEmpty()) {
                sb.append("[工作日志]\n");
                int start = Math.max(0, workLog.size() - 20); // 最多展示最近 20 条
                for (int i = start; i < workLog.size(); i++) {
                    sb.append("  ").append(i + 1).append(". ").append(workLog.get(i)).append("\n");
                }
            }

            sb.append("=== END CARRYOVER ===");
            return sb.toString();
        }

        // ── Getters ──

        public Map<String, String> getTaskFocus() { return taskFocus; }
        public Map<String, String> getReadFiles() { return readFiles; }
        public Map<String, List<String>> getInvokedTools() { return invokedTools; }
        public List<String> getWorkLog() { return workLog; }
    }
}
