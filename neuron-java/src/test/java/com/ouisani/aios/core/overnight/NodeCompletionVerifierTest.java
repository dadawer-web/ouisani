package com.ouisani.aios.core.overnight;

import com.ouisani.aios.core.VfsManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NodeCompletionVerifier 单元测试 — 验证确定性校验规格的执行与聚合判定。
 * <p>
 * 借鉴 mobilegym check_goals():用代码级校验(文件存在性/内容哈希)取代 LLM-as-judge。
 */
class NodeCompletionVerifierTest {

    private VfsManager vfs;
    private final NodeCompletionVerifier verifier = NodeCompletionVerifier.instance();

    @BeforeEach
    void setup() {
        vfs = VfsManager.instance();
        vfs.init();
        vfs.writeText("/verify/exists.txt", "hello-world");
        vfs.writeText("/verify/known.txt", "known-content");
    }

    @Test
    void fileExistsSpec_passes_whenFileExists() {
        var r = verifier.verify(List.of(new VerificationSpec.FileExistsSpec("/verify/exists.txt")));
        assertEquals(VerificationResult.Verdict.PASS, r.verdict());
    }

    @Test
    void fileExistsSpec_fails_whenFileMissing() {
        var r = verifier.verify(List.of(new VerificationSpec.FileExistsSpec("/verify/missing.txt")));
        assertEquals(VerificationResult.Verdict.FAIL, r.verdict());
    }

    @Test
    void fileHashSpec_passes_whenHashMatches() throws Exception {
        String hash = sha256("known-content");
        var r = verifier.verify(List.of(new VerificationSpec.FileHashSpec("/verify/known.txt", hash)));
        assertEquals(VerificationResult.Verdict.PASS, r.verdict());
    }

    @Test
    void fileHashSpec_fails_whenHashMismatches() {
        var r = verifier.verify(List.of(new VerificationSpec.FileHashSpec("/verify/known.txt", "deadbeef")));
        assertEquals(VerificationResult.Verdict.FAIL, r.verdict());
    }

    @Test
    void toolReturnCodeSpec_returnsInconclusive() {
        var r = verifier.verify(List.of(new VerificationSpec.ToolReturnCodeSpec("read_file", 0)));
        assertEquals(VerificationResult.Verdict.INCONCLUSIVE, r.verdict());
    }

    @Test
    void mixedSpecs_allPass_returnsPass() throws Exception {
        String hash = sha256("hello-world");
        var r = verifier.verify(List.of(
                new VerificationSpec.FileExistsSpec("/verify/exists.txt"),
                new VerificationSpec.FileHashSpec("/verify/exists.txt", hash)
        ));
        assertEquals(VerificationResult.Verdict.PASS, r.verdict());
    }

    @Test
    void mixedSpecs_oneFail_returnsFail() {
        var r = verifier.verify(List.of(
                new VerificationSpec.FileExistsSpec("/verify/exists.txt"),
                new VerificationSpec.FileExistsSpec("/verify/missing.txt")
        ));
        assertEquals(VerificationResult.Verdict.FAIL, r.verdict());
    }

    @Test
    void emptySpecs_returnsInconclusive() {
        var r = verifier.verify(List.of());
        assertEquals(VerificationResult.Verdict.INCONCLUSIVE, r.verdict());
    }

    private static String sha256(String content) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(content.getBytes(StandardCharsets.UTF_8)));
    }
}
