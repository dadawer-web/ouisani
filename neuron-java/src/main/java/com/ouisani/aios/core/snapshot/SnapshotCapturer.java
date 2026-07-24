package com.ouisani.aios.core.snapshot;

import java.util.Optional;

/**
 * 快照捕获器契约 — 依赖倒置的核心接口。
 * <p>
 * 镜像 {@code core/tool/ToolSdk} 模式:core/snapshot 定义此接口,
 * user 态(如 {@code WorkflowContextCapturer})与 core 内建实现
 * (如 {@code ProcessSectionCapturer})各自实现并注册到
 * {@link EnvironmentSnapshotManager}。
 * <p>
 * 每个 capturer 负责一种 {@link SnapshotSection} 的捕获与恢复,
 * 可选支持 fork(为并行分支创建隔离副本)。
 * <p>
 * OS 类比:类比 CRIU 的各 "dump routine"(dump_pages / dump_fdinfo / dump_sigacts),
 * 每个 routine 知道如何冻结/恢复自己负责的那类资源。
 */
public interface SnapshotCapturer {

    /** 此捕获器产出的 section 类型标识,须与 {@link SnapshotSection#sectionType()} 一致。 */
    String sectionType();

    /**
     * 从当前运行态捕获一个 section。
     * <p>
     * 实现方(user 态)持有自己的对象引用(如 WorkflowContext),
     * 此方法从中提取数据构造纯数据 record。
     *
     * @return 捕获到的状态分片
     */
    SnapshotSection capture();

    /**
     * 将 section 恢复回运行态。
     *
     * @param section 待恢复的分片(类型须与 {@link #sectionType()} 匹配)
     */
    void restore(SnapshotSection section);

    /**
     * 为 fork 分支创建隔离副本。
     * <p>
     * 默认不支持 fork。支持 fork 的 capturer(如持有 WorkflowContext 的)
     * 覆写此方法:基于 seed 快照中的对应 section,新建独立运行态对象
     * (如 {@code new WorkflowContext("fork-"+branchId)}),返回绑定到该
     * 隔离对象的 capturer。
     *
     * @param branchId fork 分支标识
     * @param seed     种子快照(从中提取本 capturer 负责的 section 作为初始状态)
     * @return 隔离的 capturer,若不支持 fork 则空
     */
    default Optional<SnapshotCapturer> fork(String branchId, EnvironmentSnapshot seed) {
        return Optional.empty();
    }
}
