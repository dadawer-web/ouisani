package com.ouisani.aios.core.recovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 第 2 层：JSON 解析错误恢复 — 检测并纠正格式偏差。
 * <p>
 * 对标 omo 的 json-error-recovery hook。
 * 当 LLM 输出的工具调用格式不正确时，注入纠正提示。
 */
public class JsonParseErrorRecovery implements RecoveryStrategy {
    private static final Logger log = LoggerFactory.getLogger(JsonParseErrorRecovery.class);

    @Override
    public String name() { return "JsonParseErrorRecovery"; }

    @Override
    public boolean shouldApply(RecoveryContext context) {
        return context.attempt() <= 3; // 最多重试 3 次
    }

    @Override
    public RecoveryResult apply(RecoveryContext context) {
        log.info("[JsonParseErrorRecovery] Injecting format correction for agent {}", context.agentId());
        String errorMsg = context.exception().getMessage() != null ? context.exception().getMessage() : "Unknown parse error";
        String modifier = "\n\n[SYSTEM CRITICAL - FORMAT ERROR]:\n"
                + "Your previous response had a formatting error that could not be parsed:\n"
                + "```text\n" + errorMsg + "\n```\n"
                + "You MUST use the EXACT tool call format specified in your instructions.\n"
                + "Do NOT invent new tags or formats. Follow the XML format strictly:\n"
                + "<tool_call>\n"
                + "<function=tool_name><parameter=param_name>value</parameter></function=tool_name>\n"
                + "</tool_call>\n"
                + "Please retry with correct formatting.\n";
        context.appendPromptModifier(modifier);
        return RecoveryResult.ok("JSON parse error recovery: injected format correction", modifier);
    }
}
