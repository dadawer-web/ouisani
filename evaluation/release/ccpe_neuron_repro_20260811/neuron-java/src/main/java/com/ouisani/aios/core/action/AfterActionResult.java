package com.ouisani.aios.core.action;

import com.ouisani.aios.core.snapshot.StateDiff;

/**
 * afterAction 的结果摘要，返回给调用方。
 *
 * @param diff            执行前后 diff；SAFE 或无快照时为 null
 * @param autoRolledBack  是否因违反期望而自动回滚（仅 REVERSIBLE 会自动回滚）
 * @param success         动作最终是否成功（自动回滚后视为 false）
 */
public record AfterActionResult(
        StateDiff diff,
        boolean autoRolledBack,
        boolean success
) {
    static AfterActionResult noSnapshot(boolean success) {
        return new AfterActionResult(null, false, success);
    }
}
