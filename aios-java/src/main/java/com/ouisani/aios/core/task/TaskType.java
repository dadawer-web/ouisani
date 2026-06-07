package com.ouisani.aios.core.task;

/**
 * 任务类型 — 对标 Claude Code 的 TaskType。
 * <p>
 * 7 种任务类型，覆盖所有异步执行场景：
 * - LOCAL_BASH: 本地 Shell 命令（后台执行）
 * - LOCAL_AGENT: 本地 Agent 子代理（异步执行）
 * - REMOTE_AGENT: 远程 Agent（跨节点执行）
 * - IN_PROCESS_TEAMMATE: 进程内队友（Swarm 协作）
 * - LOCAL_WORKFLOW: 本地工作流（DAG 执行）
 * - MONITOR_MCP: MCP 监控任务
 * - DREAM: 自动思考/整合任务
 * <p>
 * OS 类比：相当于 Linux 的进程类型 — 内核线程/用户进程/守护进程/工作队列。
 */
public enum TaskType {

    LOCAL_BASH("b", "Local Shell"),
    LOCAL_AGENT("a", "Local Agent"),
    REMOTE_AGENT("r", "Remote Agent"),
    IN_PROCESS_TEAMMATE("t", "In-Process Teammate"),
    LOCAL_WORKFLOW("w", "Local Workflow"),
    MONITOR_MCP("m", "Monitor MCP"),
    DREAM("d", "Dream");

    private final String idPrefix;
    private final String description;

    TaskType(String idPrefix, String description) {
        this.idPrefix = idPrefix;
        this.description = description;
    }

    public String idPrefix() { return idPrefix; }
    public String description() { return description; }
}
