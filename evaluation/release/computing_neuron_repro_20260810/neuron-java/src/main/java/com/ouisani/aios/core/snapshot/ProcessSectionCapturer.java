package com.ouisani.aios.core.snapshot;

import com.ouisani.aios.core.AgentTask;

/**
 * 进程级快照捕获器 — 包装 {@link SnapshotManager} 为 {@link ProcessSection}。
 * <p>
 * 持有一个 {@link AgentTask} 引用,capture 时调
 * {@link SnapshotManager#createSnapshot(AgentTask)}(CRIU 式冻结,会把 task 置
 * BLOCKED),restore 时调 {@link SnapshotManager#restore(ProcessSnapshot)}。
 * <p>
 * <b>副作用提示</b>:{@code createSnapshot} 会暂停目标进程调度(置 BLOCKED)并
 * 捕获全局 SemanticCacheManager/VfsManager 状态,属重操作。仅用于显式进程级
 * 快照场景,不进入 WorkflowEngine 每节点双写(每节点双写只走 NodeOutput+Carryover)。
 * <p>
 * <b>测试缝</b>:{@link #snapshotManager()} 为 protected,单测可覆写注入 mock
 * SnapshotManager,避免触发真实冻结副作用。
 */
public class ProcessSectionCapturer implements SnapshotCapturer {

    private final AgentTask task;

    public ProcessSectionCapturer(AgentTask task) {
        this.task = task;
    }

    @Override
    public String sectionType() {
        return "Process";
    }

    @Override
    public SnapshotSection capture() {
        ProcessSnapshot snap = snapshotManager().createSnapshot(task);
        return new ProcessSection(snap);
    }

    @Override
    public void restore(SnapshotSection section) {
        if (!(section instanceof ProcessSection ps)) return;
        snapshotManager().restore(ps.processSnapshot());
    }

    /** 可覆写:返回 SnapshotManager 单例,便于单测注入 mock。 */
    protected SnapshotManager snapshotManager() {
        return SnapshotManager.instance();
    }
}
