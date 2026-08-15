package com.ouisani.aios.core.overnight;

import com.ouisani.aios.core.VfsManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 节点完成度确定性校验器 — 借鉴 mobilegym check_goals() 取代 LLM-as-judge。
 * <p>
 * 接收 {@link VerificationSpec} 列表,对每条规格执行代码级校验:
 * <ul>
 *   <li>{@link VerificationSpec.FileExistsSpec} → {@link VfsManager#exists}</li>
 *   <li>{@link VerificationSpec.FileHashSpec} → SHA-256({@link VfsManager#readText}) 对比</li>
 *   <li>{@link VerificationSpec.ToolReturnCodeSpec} → INCONCLUSIVE(待工具执行追踪)</li>
 * </ul>
 * 聚合判定:任一 FAIL → FAIL;无 FAIL 但有 INCONCLUSIVE → INCONCLUSIVE;全 PASS → PASS。
 * <p>
 * 单例(同 {@link OvernightResultAcceptor} 模式)。
 */
public final class NodeCompletionVerifier {

    private static final Logger log = LoggerFactory.getLogger(NodeCompletionVerifier.class);
    private static final NodeCompletionVerifier INSTANCE = new NodeCompletionVerifier();

    public static NodeCompletionVerifier instance() { return INSTANCE; }
    private NodeCompletionVerifier() {}

    public VerificationResult verify(List<VerificationSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return new VerificationResult(VerificationResult.Verdict.INCONCLUSIVE,
                    List.of("无确定性校验规格"));
        }
        List<String> evidence = new ArrayList<>();
        boolean anyFail = false;
        boolean anyInconclusive = false;

        for (VerificationSpec spec : specs) {
            switch (spec) {
                case VerificationSpec.FileExistsSpec fe -> {
                    boolean exists = VfsManager.instance().exists(fe.vfsPath());
                    evidence.add(fe.vfsPath() + ": " + (exists ? "EXISTS ✓" : "MISSING ✗"));
                    if (!exists) anyFail = true;
                }
                case VerificationSpec.FileHashSpec fh -> {
                    String content = VfsManager.instance().readText(fh.vfsPath());
                    if (content == null) {
                        evidence.add(fh.vfsPath() + ": MISSING ✗ (无法计算哈希)");
                        anyFail = true;
                    } else {
                        String actual = sha256(content);
                        boolean match = fh.expectedSha256().equalsIgnoreCase(actual);
                        evidence.add(fh.vfsPath() + ": hash " + (match ? "MATCH ✓" : "MISMATCH ✗")
                                + " (expected=" + fh.expectedSha256().substring(0, 8)
                                + " actual=" + actual.substring(0, 8) + ")");
                        if (!match) anyFail = true;
                    }
                }
                case VerificationSpec.ToolReturnCodeSpec trc -> {
                    evidence.add(trc.toolName() + ": INCONCLUSIVE (工具执行追踪未实现)");
                    anyInconclusive = true;
                }
            }
        }

        VerificationResult.Verdict verdict = anyFail
                ? VerificationResult.Verdict.FAIL
                : (anyInconclusive ? VerificationResult.Verdict.INCONCLUSIVE : VerificationResult.Verdict.PASS);
        log.debug("[NodeCompletionVerifier] 判定: {} specs={}, evidence={}", verdict, specs.size(), evidence);
        return new VerificationResult(verdict, List.copyOf(evidence));
    }

    private static String sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 计算失败", e);
        }
    }
}
