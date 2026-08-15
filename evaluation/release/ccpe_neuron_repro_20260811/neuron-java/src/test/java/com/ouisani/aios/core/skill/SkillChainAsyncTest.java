package com.ouisani.aios.core.skill;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.provenance.ProvenanceHook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillChain#runAsync} 单元测试 — 验证 Additive 异步入口。
 * <p>
 * 三个关注点：
 * <ul>
 *   <li>runAsync 返回的 future 正常 complete，ChainRun 状态正确</li>
 *   <li>多条链在虚拟线程池上并行执行（总耗时远小于串行之和）</li>
 *   <li>executor 抛异常时 future 仍正常 complete（不 exceptionally），
 *       ChainRun.status==FAILED（run 内部已 catch）</li>
 * </ul>
 * <p>
 * VFS / ProvenanceHook / RunRecordStore 隔离设置镜像 {@link SkillChainTest}。
 */
class SkillChainAsyncTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        VfsManager.instance().init();
        ProvenanceHook.setProvenanceFile(tempDir.resolve("provenance.jsonl"));
        ProvenanceHook.setEnabled(true);
        ProvenanceHook.resetForTesting();
        RunRecordStore.instance().setBaseDir(tempDir.resolve("run-records"));
    }

    @AfterEach
    void tearDown() {
        ProvenanceHook.CURRENT_AGENT_ID.remove();
        ProvenanceHook.CURRENT_SESSION_ID.remove();
        ProvenanceHook.resetForTesting();
    }

    /** 1 步骤简单 meta — 用于异步测试，避免 ai4s 4 步链的额外耗时。 */
    private static MetaSkill simpleMeta(String name) {
        return new MetaSkill(
                name, "async-test",
                List.of(new MetaSkill.SkillStep("s1", "${input}", "d1", false, "")),
                "/output/" + name
        );
    }

    @Test
    @DisplayName("runAsync 返回正常 complete 的 future，ChainRun.status==COMPLETED")
    void runAsync_returnsCompletedFuture() {
        MetaSkill meta = simpleMeta("async-ok");
        SkillChainContext ctx = new SkillChainContext("a1", "/work", "slug");
        SkillChain.SkillExecutor executor = (agentId, skillName, args, wd) -> "async-output";

        CompletableFuture<SkillChain.ChainRun> future = SkillChain.runAsync(meta, "hello", ctx, executor);

        await().atMost(Duration.ofSeconds(2)).until(future::isDone);
        SkillChain.ChainRun run = future.join();

        assertEquals(SkillChain.ChainStatus.COMPLETED, run.status());
        assertEquals(1, run.successCount());
        assertEquals("async-output", run.steps().get(0).outputText());
        assertFalse(future.isCompletedExceptionally());
    }

    @Test
    @DisplayName("多条 runAsync 在虚拟线程池上并行 — 总耗时 < 3×sleep（串行需 ≥3×sleep）")
    void runAsync_multipleChains_runInParallel() throws Exception {
        MetaSkill meta = simpleMeta("async-par");
        // 每个 executor 调用 sleep 200ms；3 条串行需 ≥600ms，并行约 ~250ms
        SkillChain.SkillExecutor slowExecutor = (agentId, skillName, args, wd) -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "out-" + agentId;
        };

        long start = System.currentTimeMillis();
        CompletableFuture<SkillChain.ChainRun> f1 = SkillChain.runAsync(meta, "in",
                new SkillChainContext("a1", "/work", "slug1"), slowExecutor);
        CompletableFuture<SkillChain.ChainRun> f2 = SkillChain.runAsync(meta, "in",
                new SkillChainContext("a2", "/work", "slug2"), slowExecutor);
        CompletableFuture<SkillChain.ChainRun> f3 = SkillChain.runAsync(meta, "in",
                new SkillChainContext("a3", "/work", "slug3"), slowExecutor);

        CompletableFuture.allOf(f1, f2, f3).get(5, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - start;

        // 阈值 550ms < 串行下界 600ms → 必然并行才通过
        assertTrue(elapsed < 550,
                "应并行执行，总耗时 " + elapsed + "ms 应 < 550ms（串行需 ≥600ms）");
        assertEquals(SkillChain.ChainStatus.COMPLETED, f1.join().status());
        assertEquals(SkillChain.ChainStatus.COMPLETED, f2.join().status());
        assertEquals(SkillChain.ChainStatus.COMPLETED, f3.join().status());
    }

    @Test
    @DisplayName("executor 抛异常 → future 仍正常 complete（不 exceptionally），ChainRun.status==FAILED")
    void runAsync_executorThrows_futureCompletesWithFailedChain() {
        MetaSkill meta = simpleMeta("async-throw");
        SkillChainContext ctx = new SkillChainContext("a1", "/work", "slug");
        SkillChain.SkillExecutor throwingExecutor = (agentId, skillName, args, wd) -> {
            throw new RuntimeException("simulated async failure");
        };

        CompletableFuture<SkillChain.ChainRun> future = SkillChain.runAsync(meta, "in", ctx, throwingExecutor);

        await().atMost(Duration.ofSeconds(2)).until(future::isDone);
        // run() 内部 catch 了异常 → future 正常 complete，ChainRun 反映 FAILED
        assertFalse(future.isCompletedExceptionally(),
                "future 不应 exceptionally — run() 内部已 catch executor 异常");
        SkillChain.ChainRun run = future.join();
        assertEquals(SkillChain.ChainStatus.FAILED, run.status());
        assertEquals(SkillChain.StepStatus.FAILED, run.steps().get(0).status());
        assertTrue(run.steps().get(0).errorMessage().contains("simulated async failure"));
    }
}
