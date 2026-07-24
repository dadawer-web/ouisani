package com.ouisani.aios.core.action;

/**
 * Syscall 风险等级 — 行动治理的核心分级。
 * <p>
 * 决定 {@link ActionGovernor} 是否在执行前打"动作前快照"、是否允许 undo、
 * 执行后违反 {@link com.ouisani.aios.core.snapshot.DiffExpectation} 时是否自动回滚。
 * <ul>
 *   <li>{@link #SAFE}        — 纯读/无副作用（如 llm.think、vfs.read）。不打快照、不进 undo 栈。</li>
 *   <li>{@link #REVERSIBLE}  — 有副作用但可恢复（如 vfs.write、memory.store）。
 *                              打动作前快照、进 undo 栈、违反期望时自动 restore。</li>
 *   <li>{@link #DESTRUCTIVE} — 不可逆或外部副作用（如 tool 发邮件/下单、rpa 物理操作、bin.kill）。
 *                              打快照仅用于事后审计，不提供 undo，违反期望时仅告警不自动回滚。</li>
 * </ul>
 */
public enum RiskLevel {
    SAFE,
    REVERSIBLE,
    DESTRUCTIVE
}
