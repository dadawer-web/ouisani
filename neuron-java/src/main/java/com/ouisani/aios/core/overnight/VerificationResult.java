package com.ouisani.aios.core.overnight;

import java.util.List;

/**
 * 确定性校验结果 — {@link NodeCompletionVerifier#verify} 的返回值。
 *
 * @param verdict  判定:PASS(全部通过)/ FAIL(任一失败)/ INCONCLUSIVE(无规格或有未实现规格)
 * @param evidence 证据列表(每条 spec 的校验输出,用于审计追踪)
 */
public record VerificationResult(Verdict verdict, List<String> evidence) {

    public enum Verdict { PASS, FAIL, INCONCLUSIVE }

    public boolean isPass() { return verdict == Verdict.PASS; }
    public boolean isFail() { return verdict == Verdict.FAIL; }
}
