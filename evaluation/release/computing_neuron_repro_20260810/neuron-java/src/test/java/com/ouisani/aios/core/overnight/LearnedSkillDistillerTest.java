package com.ouisani.aios.core.overnight;

import com.ouisani.aios.core.VfsManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LearnedSkillDistiller 单元测试 — opt-in env 门控 / deterministic-PASS 硬门 /
 * ACCEPT 级别 / SKILL.md 格式 / 幂等。
 * <p>
 * 借鉴 OpenScience RSITrajectory.pipeline 的"带验证版本"：严格 deterministic-PASS
 * （NodeCompletionVerifier.verify()==PASS，不接受 LLM 回退）+ ACCEPT 级别才蒸馏。
 * <p>
 * env 在测试中不可改 → 走 {@link LearnedSkillDistiller#setEnabledForTesting} 覆盖入口
 * （同 ReviewGateConfig 范式）；learnedSkillsDir 通过系统属性重定向到 @TempDir。
 */
class LearnedSkillDistillerTest {

    private final LearnedSkillDistiller distiller = LearnedSkillDistiller.instance();

    @BeforeEach
    void setup() {
        LearnedSkillDistiller.setEnabledForTesting(null);  // reset → 走 env
        VfsManager.instance().init();
    }

    @AfterEach
    void cleanup() {
        LearnedSkillDistiller.setEnabledForTesting(null);
        System.clearProperty("aios.learned.skills.dir");
    }

    /** 构造一张任务卡 — status=done，可控 risk + deterministicChecks。 */
    private static OvernightTaskCard card(String id, String title,
                                          OvernightTaskCard.RiskLevel risk,
                                          List<VerificationSpec> checks) {
        return new OvernightTaskCard(
                id, title, "done", "P1", "overnight", "static-analysis", "file-exists",
                risk, "Fixed successfully",
                new OvernightTaskCard.Before("Problem description here", List.of()),
                new OvernightTaskCard.After("Applied fix", List.of("/src/Foo.java"), List.of()),
                new OvernightTaskCard.Validation(List.of("mvn test"), "pass", List.of()),
                List.of(), "2026-07-22", checks);
    }

    @Test
    @DisplayName("默认关（未启用）→ distill 返回 false，无文件写出")
    void testDisabledByDefault(@TempDir Path learnedDir) {
        System.setProperty("aios.learned.skills.dir", learnedDir.toString());
        VfsManager.instance().writeText("/vfs/disabled-proof.txt", "proof");
        OvernightTaskCard c = card("t1", "Test Task", OvernightTaskCard.RiskLevel.LOW,
                List.of(new VerificationSpec.FileExistsSpec("/vfs/disabled-proof.txt")));
        assertFalse(distiller.distill(c));
        assertEquals(0, countSkillMds(learnedDir));
    }

    @Test
    @DisplayName("启用 + deterministic-PASS + ACCEPT → 写出 SKILL.md")
    void testDistillOnDeterministicPassAccept(@TempDir Path learnedDir) throws IOException {
        System.setProperty("aios.learned.skills.dir", learnedDir.toString());
        LearnedSkillDistiller.setEnabledForTesting(true);
        VfsManager.instance().writeText("/vfs/learned-proof.txt", "proof");
        OvernightTaskCard c = card("t2", "Fix NPE", OvernightTaskCard.RiskLevel.LOW,
                List.of(new VerificationSpec.FileExistsSpec("/vfs/learned-proof.txt")));

        assertTrue(distiller.distill(c));
        assertEquals(1, countSkillMds(learnedDir));

        String md = readSingleSkillMd(learnedDir);
        assertTrue(md.contains("name: fix-npe-"), "应含 name frontmatter（slugify+hash）");
        assertTrue(md.contains("description: Fix NPE"));
        assertTrue(md.contains("category: learned"));
        assertTrue(md.contains("## Problem"));
        assertTrue(md.contains("Problem description here"));
        assertTrue(md.contains("## Change"));
        assertTrue(md.contains("Applied fix"));
    }

    @Test
    @DisplayName("启用 + 无 deterministicChecks → false（严格 deterministic 硬门）")
    void testNoDeterministicChecks(@TempDir Path learnedDir) {
        System.setProperty("aios.learned.skills.dir", learnedDir.toString());
        LearnedSkillDistiller.setEnabledForTesting(true);
        OvernightTaskCard c = card("t3", "No Checks", OvernightTaskCard.RiskLevel.LOW, List.of());
        assertFalse(distiller.distill(c));
        assertEquals(0, countSkillMds(learnedDir));
    }

    @Test
    @DisplayName("启用 + deterministic FAIL → false")
    void testDeterministicFail(@TempDir Path learnedDir) {
        System.setProperty("aios.learned.skills.dir", learnedDir.toString());
        LearnedSkillDistiller.setEnabledForTesting(true);
        // VFS 路径不存在 → verify FAIL
        OvernightTaskCard c = card("t4", "Fail Task", OvernightTaskCard.RiskLevel.LOW,
                List.of(new VerificationSpec.FileExistsSpec("/vfs/nonexistent-" + System.nanoTime())));
        assertFalse(distiller.distill(c));
        assertEquals(0, countSkillMds(learnedDir));
    }

    @Test
    @DisplayName("启用 + deterministic PASS 但 acceptanceLevel≠ACCEPT（HIGH risk → DEFER）→ false")
    void testPassButNotAccept(@TempDir Path learnedDir) {
        System.setProperty("aios.learned.skills.dir", learnedDir.toString());
        LearnedSkillDistiller.setEnabledForTesting(true);
        VfsManager.instance().writeText("/vfs/high-proof.txt", "proof");
        OvernightTaskCard c = card("t5", "High Risk Task", OvernightTaskCard.RiskLevel.HIGH,
                List.of(new VerificationSpec.FileExistsSpec("/vfs/high-proof.txt")));
        assertEquals(OvernightTaskCard.AcceptanceLevel.DEFER, c.acceptanceLevel(),
                "HIGH risk + COMPLETED + validated → DEFER (not ACCEPT)");
        assertFalse(distiller.distill(c));
        assertEquals(0, countSkillMds(learnedDir));
    }

    @Test
    @DisplayName("幂等：同卡 distill 两次 → 同名覆盖（仍 1 文件）")
    void testIdempotent(@TempDir Path learnedDir) {
        System.setProperty("aios.learned.skills.dir", learnedDir.toString());
        LearnedSkillDistiller.setEnabledForTesting(true);
        VfsManager.instance().writeText("/vfs/idem-proof.txt", "proof");
        OvernightTaskCard c = card("t6", "Idem Task", OvernightTaskCard.RiskLevel.LOW,
                List.of(new VerificationSpec.FileExistsSpec("/vfs/idem-proof.txt")));

        assertTrue(distiller.distill(c));
        assertTrue(distiller.distill(c));
        assertEquals(1, countSkillMds(learnedDir), "同卡重跑应同名覆盖，仍 1 文件");
    }

    @Test
    @DisplayName("isDeterministicPass: PASS / FAIL / 空 三分支")
    void testIsDeterministicPassBranches() {
        VfsManager.instance().writeText("/vfs/branch-proof.txt", "proof");
        // PASS
        OvernightTaskCard pass = card("b1", "Pass", OvernightTaskCard.RiskLevel.LOW,
                List.of(new VerificationSpec.FileExistsSpec("/vfs/branch-proof.txt")));
        assertTrue(distiller.isDeterministicPass(pass));
        // FAIL
        OvernightTaskCard fail = card("b2", "Fail", OvernightTaskCard.RiskLevel.LOW,
                List.of(new VerificationSpec.FileExistsSpec("/vfs/branch-missing.txt")));
        assertFalse(distiller.isDeterministicPass(fail));
        // 空
        OvernightTaskCard empty = card("b3", "Empty", OvernightTaskCard.RiskLevel.LOW, List.of());
        assertFalse(distiller.isDeterministicPass(empty));
    }

    // ── helpers ──

    private static long countSkillMds(Path dir) {
        try (var s = Files.walk(dir)) {
            return s.filter(p -> p.getFileName().toString().equals("SKILL.md")).count();
        } catch (IOException e) {
            return -1;
        }
    }

    private static String readSingleSkillMd(Path dir) throws IOException {
        try (var s = Files.walk(dir)) {
            Path md = s.filter(p -> p.getFileName().toString().equals("SKILL.md"))
                    .findFirst().orElseThrow();
            return Files.readString(md);
        }
    }
}
