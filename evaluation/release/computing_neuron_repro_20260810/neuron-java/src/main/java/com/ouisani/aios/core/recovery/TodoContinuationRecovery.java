package com.ouisani.aios.core.recovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 第 9 层：Todo 继续强制器 — 未完成 todo 强制注入继续提示。
 * <p>
 * 对标 omo 的 todo-continuation-enforcer（14 个文件，2061 LOC）。
 * 当 Agent 的 todo 列表有未完成项但 Agent 似乎停滞时，
 * 强制注入继续提示驱动 Agent 前进。
 * <p>
 * 30s 冷却，最多 5 次连续失败后暂停 5 分钟。
 */
public class TodoContinuationRecovery implements RecoveryStrategy {
    private static final Logger log = LoggerFactory.getLogger(TodoContinuationRecovery.class);

    /** 冷却时间 (ms) */
    private static final long COOLDOWN_MS = 30_000;
    /** 最大连续注入次数 */
    private static final int MAX_CONSECUTIVE_INJECTIONS = 5;

    private long lastInjectionTime = 0;
    private int consecutiveInjections = 0;

    @Override
    public String name() { return "TodoContinuationRecovery"; }

    @Override
    public boolean shouldApply(RecoveryContext context) {
        // 冷却期内不注入
        if (System.currentTimeMillis() - lastInjectionTime < COOLDOWN_MS) {
            return false;
        }
        // 连续注入过多则暂停
        if (consecutiveInjections >= MAX_CONSECUTIVE_INJECTIONS) {
            return false;
        }
        return true;
    }

    @Override
    public RecoveryResult apply(RecoveryContext context) {
        log.info("[TodoContinuationRecovery] 正在为 Agent 注入续接 Prompt {}", context.agentId());
        lastInjectionTime = System.currentTimeMillis();
        consecutiveInjections++;

        String modifier = "\n\n[SYSTEM REMINDER - UNFINISHED WORK]:\n"
                + "You have unfinished tasks in your todo list. You MUST continue working.\n"
                + "Do NOT stop until all tasks are completed.\n"
                + "If you are stuck, try a different approach or ask for help.\n"
                + "IMPORTANT: Only reply 'NODE_VERIFIED_AND_READY' when ALL tasks are truly done.\n";

        context.appendPromptModifier(modifier);
        return RecoveryResult.ok("Todo continuation: injected continuation prompt", modifier);
    }

    /** 重置连续注入计数（在任务成功后调用） */
    public void resetConsecutiveInjections() {
        consecutiveInjections = 0;
    }
}
