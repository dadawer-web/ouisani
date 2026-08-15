package com.ouisani.aios.core.snapshot;

import java.util.List;

/**
 * VFS 句柄分片 — 打开的 VFS 节点冻结内容。
 * <p>
 * 复用 {@link ProcessSnapshot.OpenHandle}(纯数据 record:vfsPath/nodeType/frozenContent,
 * 无跨包依赖)。由内建 {@code VfsSectionCapturer} 或 ProcessSectionCapturer 产出。
 *
 * @param handles VFS 路径 → 冻结内容快照列表
 */
public record VfsSection(List<ProcessSnapshot.OpenHandle> handles) implements SnapshotSection {

    @Override
    public String sectionType() {
        return "Vfs";
    }
}
