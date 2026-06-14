package com.ouisani.aios.core.recovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 第 4 层：工具调用错误恢复 — 工具执行异常时注入纠正提示。
 * <p>
 * 对标 omo 的 delegate-task-retry hook。
 * 当 Bash/FileWrite 等工具执行失败时，分析错误原因并注入纠正指令。
 */
public class ToolErrorRecovery implements RecoveryStrategy {
    private static final Logger log = LoggerFactory.getLogger(ToolErrorRecovery.class);

    @Override
    public String name() { return "ToolErrorRecovery"; }

    @Override
    public boolean shouldApply(RecoveryContext context) {
        return context.attempt() <= 3;
    }

    @Override
    public RecoveryResult apply(RecoveryContext context) {
        log.info("[ToolErrorRecovery] Analyzing tool error for agent {}", context.agentId());
        String errorMsg = context.exception().getMessage() != null ? context.exception().getMessage() : "Tool error";
        String modifier = "\n\n[SYSTEM CRITICAL - TOOL EXECUTION ERROR]:\n"
                + "A tool call failed with the following error:\n"
                + "```text\n" + errorMsg + "\n```\n"
                + "Common causes and fixes:\n"
                + "- If 'command not found': Use python3 instead of python, check the command name\n"
                + "- If 'permission denied': Use pip3 install --user instead of sudo pip3 install\n"
                + "- If 'no such file': Check the file path, create parent directories first\n"
                + "- If 'ModuleNotFoundError': Install the package with pip3 install --user\n"
                + "Please analyze the error and retry with a corrected approach.\n";
        context.appendPromptModifier(modifier);
        return RecoveryResult.ok("Tool error recovery: injected error analysis", modifier);
    }
}
