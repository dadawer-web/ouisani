package com.ouisani.aios.core.snapshot;

import java.util.Map;

/**
 * Overlay diff 分片 — fork 分支上层(upper layer)的写入差异。
 * <p>
 * 借鉴 mobilegym "Final UI = World Data ⊕ Runtime Overlay":底层 /factory 只读共享,
 * 上层 /overlays/{branchId} 可写,capture 时仅持久化上层 diff(体积小、易回滚)。
 * fork 时携带此分片,使新分支继承原分支的文件修改;diff 时检测意外文件丢失。
 * <p>
 * 与 {@link VfsSection} 语义不同:VfsSection 冻结打开句柄,本分片冻结 overlay 写入差异。
 *
 * @param files 相对路径 → 文件内容(仅 upper 层文件,排除 .keep 标记)
 */
public record OverlayDiffSection(Map<String, String> files) implements SnapshotSection {

    @Override
    public String sectionType() {
        return "OverlayDiff";
    }
}
