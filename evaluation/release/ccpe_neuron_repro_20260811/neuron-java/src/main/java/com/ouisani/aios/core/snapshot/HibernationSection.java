package com.ouisani.aios.core.snapshot;

import com.ouisani.aios.core.hibernation.AgentSnapshot;

/**
 * 工作区级分片 — 包装 {@link AgentSnapshot}(Semantic Core Hibernation)。
 * <p>
 * 由内建 {@code HibernationSectionCapturer} 产出,捕获整个工作区的
 * VariablePool、任务队列、KV Cache 引用、上下文指针、共享内存段。
 * AgentSnapshot 位于 core/hibernation(core→core 依赖,合规),已 implements Serializable。
 *
 * @param agentSnapshot 被包装的工作区快照
 */
public record HibernationSection(AgentSnapshot agentSnapshot) implements SnapshotSection {

    @Override
    public String sectionType() {
        return "Hibernation";
    }
}
