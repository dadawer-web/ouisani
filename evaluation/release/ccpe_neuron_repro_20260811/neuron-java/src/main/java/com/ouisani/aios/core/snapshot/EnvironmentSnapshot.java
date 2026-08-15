package com.ouisani.aios.core.snapshot;

import java.io.Serializable;
import java.util.Map;

/**
 * 统一执行环境快照 — 借鉴 mobilegym 的结构化状态模型。
 * <p>
 * 整个执行环境被切分为若干 {@link SnapshotSection}(NodeOutput/Carryover/
 * Process/Hibernation/Vfs/Boulder),每个 section 独立捕获/恢复/diff/fork。
 * EnvironmentSnapshot 是顶层容器,持有一个 sectionType → section 的映射。
 * <p>
 * 与现有三套机制的关系:各机制被内建 capturer 包装为对应 section,
 * EnvironmentSnapshotManager 编排所有 capturer 完成统一 capture/restore。
 * <p>
 * OS 类比:类比 mobilegym 的 "整个环境是一份 JSON 快照,可 snapshot/reset/
 * fork/diff",也类比 Linux CRIU 的完整 images-dir(含 pages/fdinfo/sigacts 等)。
 *
 * @param snapshotId 快照 ID(唯一)
 * @param createdAt  创建时间戳
 * @param scopeId    作用域标识(如 workflowId / "overnight-{runId}-card-{cardId}")
 * @param sections   sectionType → 状态分片
 */
public record EnvironmentSnapshot(
        String snapshotId,
        long createdAt,
        String scopeId,
        Map<String, SnapshotSection> sections
) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 按类型获取 section,缺失返回空。 */
    @SuppressWarnings("unchecked")
    public <T extends SnapshotSection> java.util.Optional<T> getSection(String sectionType, Class<T> type) {
        SnapshotSection s = sections == null ? null : sections.get(sectionType);
        return type.isInstance(s) ? java.util.Optional.of((T) s) : java.util.Optional.empty();
    }
}
