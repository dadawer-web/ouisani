package com.ouisani.aios.core.snapshot;

import java.util.Map;

/**
 * 节点输出分片 — 镜像 {@code WorkflowContext.globalMemory}。
 * <p>
 * 捕获 DAG 各节点的输出变量集合(节点 ID → 变量键值),由 user 态
 * {@code WorkflowContextCapturer} 产出。是 fork 分支回填初始状态、
 * diff 检测节点输出变更的核心数据。
 *
 * @param nodeOutputs 节点 ID → (变量键 → 变量值);深拷贝,与运行态隔离
 */
public record NodeOutputSection(Map<String, Map<String, Object>> nodeOutputs) implements SnapshotSection {

    @Override
    public String sectionType() {
        return "NodeOutput";
    }
}
