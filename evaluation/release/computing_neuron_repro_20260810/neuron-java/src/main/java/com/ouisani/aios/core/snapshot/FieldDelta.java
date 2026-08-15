package com.ouisani.aios.core.snapshot;

/**
 * 单个字段级差异 — diff 引擎产出的最小粒度。
 *
 * @param sectionType 所属 section 类型(如 "NodeOutput")
 * @param fieldPath   字段路径(如 "nodeOutputs.node-a" / "status")
 * @param kind        变更种类
 * @param before      变更前的值(ADDED 时为 null)
 * @param after       变更后的值(REMOVED 时为 null)
 */
public record FieldDelta(
        String sectionType,
        String fieldPath,
        DeltaKind kind,
        Object before,
        Object after
) {
}
