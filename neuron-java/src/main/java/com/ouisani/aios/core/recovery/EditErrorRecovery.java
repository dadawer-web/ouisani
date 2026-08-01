package com.ouisani.aios.core.recovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 第 3 层：编辑错误恢复 — 文件编辑失败时自动重试。
 * <p>
 * 对标 omo 的 edit-error-recovery hook。
 * 当 Hashline 不匹配或 AST 重写失败时，注入提示要求先重新读取文件再编辑。
 */
public class EditErrorRecovery implements RecoveryStrategy {
    private static final Logger log = LoggerFactory.getLogger(EditErrorRecovery.class);

    @Override
    public String name() { return "EditErrorRecovery"; }

    @Override
    public boolean shouldApply(RecoveryContext context) {
        return context.attempt() <= 3;
    }

    @Override
    public RecoveryResult apply(RecoveryContext context) {
        log.info("[EditErrorRecovery] 正在为 Agent 注入重新读取指令 {}", context.agentId());
        // 不可信错误文本 — 净化后再注入，防止载荷借恢复通道绕过权限（同 ReflectionInjectionRecovery）
        String errorMsg = RecoveryPromptSanitizer.sanitize(
                context.exception().getMessage() != null ? context.exception().getMessage() : "Edit failed");
        String modifier = "\n\n[SYSTEM CRITICAL - EDIT FAILED]:\n"
                + "Your previous file edit failed:\n"
                + "```text\n" + errorMsg + "\n```\n"
                + "This usually means the file has changed since you last read it.\n"
                + "You MUST:\n"
                + "1. Re-read the file using file_read or hashline_read FIRST\n"
                + "2. Note the current content and line hashes\n"
                + "3. Then perform the edit with the CORRECT old content\n"
                + "Do NOT attempt to edit without reading first!\n";
        context.appendPromptModifier(modifier);
        return RecoveryResult.ok("Edit error recovery: injected re-read instruction", modifier);
    }
}
