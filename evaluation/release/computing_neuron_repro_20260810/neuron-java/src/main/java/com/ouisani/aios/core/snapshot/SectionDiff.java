package com.ouisani.aios.core.snapshot;

import java.util.List;

/**
 * 单个 section 的差异汇总 — 一个 section 内所有字段级 {@link FieldDelta} 集合。
 *
 * @param sectionType       section 类型
 * @param deltas            字段级差异列表(仅含非 UNCHANGED,空列表表示无变更)
 * @param structurallyEqual 两 section 结构与内容完全一致(无任何 delta)
 */
public record SectionDiff(
        String sectionType,
        List<FieldDelta> deltas,
        boolean structurallyEqual
) {
}
