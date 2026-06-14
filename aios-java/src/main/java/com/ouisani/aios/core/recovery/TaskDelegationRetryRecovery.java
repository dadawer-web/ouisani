package com.ouisani.aios.core.recovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 第 8 层：任务委派重试 — 子 Agent 失败时重试委派。
 * <p>
 * 对标 omo 的 delegate-task-retry hook。
 * 当通过 AgentTool 委派子任务失败时，注入提示要求换一种方式委派。
 */
public class TaskDelegationRetryRecovery implements RecoveryStrategy {
    private static final Logger log = LoggerFactory.getLogger(TaskDelegationRetryRecovery.class);

    @Override
    public String name() { return "TaskDelegationRetryRecovery"; }

    @Override
    public boolean shouldApply(RecoveryContext context) {
        return context.attempt() <= 2;
    }

    @Override
    public RecoveryResult apply(RecoveryContext context) {
        log.info("[TaskDelegationRetryRecovery] Injecting delegation retry for agent {}", context.agentId());
        String modifier = "\n\n[SYSTEM WARNING - TASK DELEGATION FAILED]:\n"
                + "A delegated sub-task failed. Please try a different approach:\n"
                + "1. Break the task into smaller, more specific sub-tasks\n"
                + "2. Provide more context and clearer instructions\n"
                + "3. If the sub-agent couldn't find the right tool, specify which tool to use\n"
                + "4. Consider doing the task yourself instead of delegating\n";
        context.appendPromptModifier(modifier);
        return RecoveryResult.ok("Task delegation retry: injected alternative approach", modifier);
    }
}
