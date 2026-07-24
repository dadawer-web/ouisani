package com.ouisani.aios.core.overnight;

/**
 * 确定性校验规格 — 借鉴 mobilegym check_goals() 用代码级校验取代 LLM-as-judge。
 * <p>
 * 三种校验类型:
 * <ul>
 *   <li>{@link FileExistsSpec} — VFS 文件存在性检查(立即落地)</li>
 *   <li>{@link FileHashSpec} — VFS 文件内容 SHA-256 哈希校验(立即落地)</li>
 *   <li>{@link ToolReturnCodeSpec} — 工具调用返回码校验(当前 INCONCLUSIVE,待工具执行追踪基础设施)</li>
 * </ul>
 * <p>
 * 规格由 {@link NodeCompletionVerifier} 执行,结果驱动 {@link OvernightTaskCard#isValidated()}
 * 的 hard gate:有 spec 时确定性结果覆盖 LLM 自报。
 */
public sealed interface VerificationSpec
        permits VerificationSpec.FileExistsSpec,
                VerificationSpec.FileHashSpec,
                VerificationSpec.ToolReturnCodeSpec {

    String description();

    /** 文件存在性校验 — vfs.exists(path) */
    record FileExistsSpec(String vfsPath) implements VerificationSpec {
        @Override public String description() { return "FILE_EXISTS " + vfsPath; }
    }

    /** 文件内容哈希校验 — SHA-256(vfs.readText(path)) 对比 expectedSha256 */
    record FileHashSpec(String vfsPath, String expectedSha256) implements VerificationSpec {
        @Override public String description() { return "FILE_HASH " + vfsPath; }
    }

    /** 工具调用返回码校验 — 当前 INCONCLUSIVE(ToolRegistry 不追踪执行结果) */
    record ToolReturnCodeSpec(String toolName, int expectedCode) implements VerificationSpec {
        @Override public String description() { return "TOOL_RETURN_CODE " + toolName + "=" + expectedCode; }
    }
}
