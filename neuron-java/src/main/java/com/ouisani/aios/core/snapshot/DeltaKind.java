package com.ouisani.aios.core.snapshot;

/**
 * 差异种类 — 借鉴 mobilegym 的结构化状态 diff,枚举 section 字段级变更类型。
 *
 * @see FieldDelta
 */
public enum DeltaKind {
    /** 字段/节点仅在 after 快照中出现 */
    ADDED,
    /** 字段/节点仅在 before 快照中出现 */
    REMOVED,
    /** 字段/节点在两快照中都存在但值不同 */
    CHANGED,
    /** 字段/节点在两快照中都存在且值相同(通常不记录,仅完整性) */
    UNCHANGED
}
