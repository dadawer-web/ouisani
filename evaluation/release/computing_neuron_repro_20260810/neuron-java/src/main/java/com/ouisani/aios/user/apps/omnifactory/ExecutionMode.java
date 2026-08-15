package com.ouisani.aios.user.apps.omnifactory;

import java.util.List;
import java.util.Set;

/**
 * 执行模式枚举 — 借鉴 n8n 的部分执行 (Partial Execution) 设计。
 * <p>
 * WorkflowEngine 默认是 FULL_RUN（一棍子插到底），但对于昂贵的 LLM 推理 DAG，
 * 如果第 9 步挂了，每次调试都要从头跑一遍会烧干 Token。
 * <p>
 * 此枚举允许指定执行模式，配合 PartialExecutionPlanner 过滤节点子集。
 * <p>
 * OS 类比：相当于 Linux 的 strace -e trace=open — 只执行到指定系统调用。
 *
 * @see PartialExecutionPlanner
 */
public enum ExecutionMode {

    /**
     * 全量执行 — 执行整个 DAG（默认行为，向后兼容）。
     */
    FULL_RUN,

    /**
     * 执行到目标节点 — 只执行目标节点及其所有上游依赖链。
     * <p>
     * 场景：调试第 9 步失败，只跑 1→9，不跑 10。
     * 上游节点的输出如果已有缓存（Boulder/Frozen），直接复用；
     * 如果没有缓存，可以通过 MockDataProvider 注入 Mock 数据短路。
     */
    EXECUTE_TO_NODE,

    /**
     * 仅执行单个节点 — 只执行目标节点，所有上游依赖用 Mock 数据填充。
     * <p>
     * 场景：单独调试某个节点的 prompt 输出，不关心上游逻辑。
     */
    EXECUTE_SINGLE_NODE
}
