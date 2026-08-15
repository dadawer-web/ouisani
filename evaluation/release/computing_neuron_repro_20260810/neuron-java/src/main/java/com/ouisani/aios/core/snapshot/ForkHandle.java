package com.ouisani.aios.core.snapshot;

import java.util.List;

/**
 * Fork 分支句柄 — 从同一种子快照派生的隔离执行环境引用。
 * <p>
 * 借鉴 mobilegym 的 "fork 结构化状态成 N 个并行 rollout"。每个 ForkHandle
 * 持有独立的 capturer 集合(绑定到隔离的运行态对象,如独立 WorkflowContext、
 * 命名空间前缀的 VariablePool 访问),激活后即可在该分支内独立执行,
 * 互不污染。
 * <p>
 * <b>非 Serializable</b>:ForkHandle 是运行态句柄,不持久化;其种子快照
 * 已持久化,需要时重新 fork 即可。
 *
 * @param branchId       分支标识(用作命名空间前缀)
 * @param seedSnapshotId 种子快照 ID(派生自此快照)
 * @param branchCapturers 该分支隔离的 capturer 列表(已绑定隔离运行态)
 * @param activator      激活回调:注册 capturers 到 manager + restore 种子状态到隔离运行态
 */
public record ForkHandle(
        String branchId,
        String seedSnapshotId,
        List<SnapshotCapturer> branchCapturers,
        Runnable activator
) {

    /** 激活此分支:执行 activator,使分支进入可执行状态。 */
    public void activate() {
        activator.run();
    }
}
