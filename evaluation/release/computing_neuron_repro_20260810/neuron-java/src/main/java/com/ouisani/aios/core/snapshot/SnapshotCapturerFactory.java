package com.ouisani.aios.core.snapshot;

import java.util.List;
import java.util.Set;

/**
 * 快照捕获器工厂契约 — 为 fork 分支批量创建隔离的 capturer 集合。
 * <p>
 * 依赖倒置:core/snapshot 定义此接口,user 态(如
 * {@code OmnifactoryCapturerFactory})实现。当 {@link EnvironmentSnapshotManager#forkFromSnapshot}
 * 需要派生 N 个并行分支时,遍历所有已注册的 factory,为每个 branchId
 * 调用 {@link #createForFork},由 factory 内部新建隔离的运行态对象
 * (独立 WorkflowContext、命名空间前缀的 VariablePool 访问等)。
 * <p>
 * OS 类比:类比 Linux clone() 的 flags 参数(COPY_MM / COPY_FILES / COPY_SIGHAND),
 * factory 决定为 fork 分支复制哪些子系统、如何隔离。
 */
public interface SnapshotCapturerFactory {

    /** 此工厂能产出的 section 类型集合。 */
    Set<String> sectionTypes();

    /**
     * 为 fork 分支创建隔离的 capturer 列表。
     * <p>
     * 一个 factory 可能产出多个 capturer(如 OmnifactoryCapturerFactory 同时
     * 产出 NodeOutput 和 Carryover 两个 capturer,共享同一隔离 WorkflowContext)。
     *
     * @param branchId fork 分支标识(用作命名空间前缀)
     * @param seed     种子快照(从中提取各 section 作为分支初始状态)
     * @return 该分支隔离的 capturer 列表(已绑定到隔离的运行态对象)
     */
    List<SnapshotCapturer> createForFork(String branchId, EnvironmentSnapshot seed);
}
