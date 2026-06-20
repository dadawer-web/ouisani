package com.ouisani.aios.core.recovery;

/**
 * 恢复策略接口 — 每种策略实现一种恢复机制。
 * <p>
 * 对标 oh-my-openagent 的各种 Recovery Hook：
 * 每个策略决定是否适用当前错误，以及如何恢复。
 */
public interface RecoveryStrategy {

    /** 策略名称（用于日志和遥测） */
    String name();

    /** 判断此策略是否适用于当前上下文 */
    boolean shouldApply(RecoveryContext context);

    /** 执行恢复逻辑 */
    RecoveryResult apply(RecoveryContext context);
}
