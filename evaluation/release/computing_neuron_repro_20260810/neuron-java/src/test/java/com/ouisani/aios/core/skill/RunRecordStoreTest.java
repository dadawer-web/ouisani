package com.ouisani.aios.core.skill;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.provenance.ProvenanceHook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RunRecordStore} 单元测试 — R3 运行记录持久化。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>record() — 写 per-run 目录 + JSONL 追加 + 更新内存索引</li>
 *   <li>get(runId) — 命中/未命中</li>
 *   <li>listByMetaSkill(name) / listByStatus(status)</li>
 *   <li>recent(n) — 按 startedAt 降序</li>
 *   <li>size()</li>
 *   <li>JSONL 懒加载 — 重新打开 setBaseDir 后能从磁盘恢复</li>
 *   <li>reproduce(runId, executor) — 用新 slug 重放，原 run 不被覆盖</li>
 *   <li>best-effort：磁盘失败不抛异常</li>
 *   <li>集成 SkillChain.run — 自动触发 record()</li>
 * </ul>
 */
class RunRecordStoreTest {

    @TempDir
    Path tempDir;

    private Path storeDir;

    @BeforeEach
    void setUp() {
        VfsManager.instance().init();
        ProvenanceHook.setProvenanceFile(tempDir.resolve("provenance.jsonl"));
        ProvenanceHook.setEnabled(true);
        ProvenanceHook.resetForTesting();
        // 隔离 RunRecordStore 写入到 tempDir
        storeDir = tempDir.resolve("run-records");
        RunRecordStore.instance().setBaseDir(storeDir);
    }

    // ── 测试用 Stub executor ──

    private static SkillChain.SkillExecutor stubExecutor(Map<String, String> outputs) {
        return (agentId, skillName, args, wd) -> outputs.getOrDefault(skillName, "default-out");
    }

    /** 跑一条 2 步骤链，返回 ChainRun */
    private SkillChain.ChainRun runChain(String metaSkillName, String input, String slug,
                                          Map<String, String> outputs) {
        MetaSkill meta = new MetaSkill(
                metaSkillName, "test meta",
                List.of(
                        new MetaSkill.SkillStep("s1", "${input}", "d1", false, ""),
                        new MetaSkill.SkillStep("s2", "${input}", "d2", false, "")
                ),
                "/output/" + metaSkillName
        );
        SkillChainContext ctx = new SkillChainContext("agent_t", "sess_t", "/work", slug);
        return SkillChain.run(meta, input, ctx, stubExecutor(outputs));
    }

    // ════════════════════════════════════════════════════════════════
    //  record + get
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("record() 写入后 get(runId) 可命中")
    void record_thenGet_returnsRecord() {
        SkillChain.ChainRun run = runChain("test-meta-a", "input-a", "slug-a",
                Map.of("s1", "out1", "s2", "out2"));

        Optional<RunRecord> fetched = RunRecordStore.instance().get(run.runId());

        assertTrue(fetched.isPresent());
        assertEquals(run.runId(), fetched.get().runId());
        assertEquals("test-meta-a", fetched.get().metaSkillName());
        assertEquals("COMPLETED", fetched.get().status());
        assertEquals(2, fetched.get().stepCount());
        assertEquals(2, fetched.get().successCount());
        assertEquals(0, fetched.get().failureCount());
        assertEquals("agent_t", fetched.get().agentId());
        assertEquals("slug-a", fetched.get().slug());
        assertEquals("input-a", fetched.get().input());
    }

    @Test
    @DisplayName("get(unknownId) 返回 empty")
    void get_unknownId_returnsEmpty() {
        assertTrue(RunRecordStore.instance().get("run-does-not-exist").isEmpty());
    }

    @Test
    @DisplayName("get(null) 返回 empty，不抛异常")
    void get_null_returnsEmpty() {
        assertTrue(RunRecordStore.instance().get(null).isEmpty());
    }

    // ════════════════════════════════════════════════════════════════
    //  per-run 目录
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("record() 写 per-run 目录：run-record.json + reproduce.prompt + input.txt")
    void record_writesPerRunDirFiles() throws Exception {
        SkillChain.ChainRun run = runChain("test-meta-b", "input-b", "slug-b",
                Map.of("s1", "o1", "s2", "o2"));

        Path runDir = storeDir.resolve(run.runId());
        assertTrue(Files.isDirectory(runDir), "per-run 目录应存在: " + runDir);

        Path recordJson = runDir.resolve("run-record.json");
        assertTrue(Files.exists(recordJson), "run-record.json 应存在");
        String content = Files.readString(recordJson);
        assertTrue(content.contains("\"runId\":\"" + run.runId() + "\""));
        assertTrue(content.contains("\"metaSkillName\":\"test-meta-b\""));

        Path promptFile = runDir.resolve("reproduce.prompt");
        assertTrue(Files.exists(promptFile));
        assertTrue(Files.readString(promptFile).contains("# Reproduce Run: " + run.runId()));

        Path inputFile = runDir.resolve("input.txt");
        assertTrue(Files.exists(inputFile));
        assertEquals("input-b", Files.readString(inputFile).trim());
    }

    @Test
    @DisplayName("record() 有 snapshotId 时写 snapshot-id.txt")
    void record_writesSnapshotIdFileWhenPresent() throws Exception {
        MetaSkill meta = new MetaSkill(
                "snap-meta", "d",
                List.of(new MetaSkill.SkillStep("s1", "${input}", "d1", false, "")),
                "/output/snap-meta"
        );
        SkillChainContext ctx = new SkillChainContext(
                "agent_s", "sess_s", "/work", "slug-s", "env-snap-001");
        SkillChain.ChainRun run = SkillChain.run(meta, "input-s", ctx,
                stubExecutor(Map.of("s1", "out")));

        Path snapFile = storeDir.resolve(run.runId()).resolve("snapshot-id.txt");
        assertTrue(Files.exists(snapFile), "snapshot-id.txt 应存在");
        assertEquals("env-snap-001", Files.readString(snapFile).trim());
    }

    @Test
    @DisplayName("record() 无 snapshotId 时不写 snapshot-id.txt")
    void record_noSnapshotIdFile_whenAbsent() {
        SkillChain.ChainRun run = runChain("no-snap", "in", "slug",
                Map.of("s1", "o1", "s2", "o2"));

        Path snapFile = storeDir.resolve(run.runId()).resolve("snapshot-id.txt");
        assertFalse(Files.exists(snapFile), "无快照时不应写 snapshot-id.txt");
    }

    // ════════════════════════════════════════════════════════════════
    //  JSONL append
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("record() 追加 JSONL — 每次写入追加一行")
    void record_appendsToJsonl() throws Exception {
        SkillChain.ChainRun r1 = runChain("meta-j1", "i1", "s1", Map.of("s1", "o"));
        SkillChain.ChainRun r2 = runChain("meta-j2", "i2", "s2", Map.of("s1", "o"));

        Path jsonl = storeDir.resolve("skill-chain-runs.jsonl");
        assertTrue(Files.exists(jsonl), "JSONL 文件应存在");

        List<String> lines = Files.readAllLines(jsonl).stream()
                .filter(l -> !l.isBlank()).toList();
        assertEquals(2, lines.size(), "应有 2 行 JSONL");
        assertTrue(lines.get(0).contains(r1.runId()));
        assertTrue(lines.get(1).contains(r2.runId()));
    }

    @Test
    @DisplayName("JSONL 每行是合法的 RunRecord JSON — 可被 fromJson 还原")
    void jsonl_eachLineIsParseableRunRecord() throws Exception {
        runChain("meta-parse", "parse-me", "slug-p", Map.of("s1", "o", "s2", "o2"));

        Path jsonl = storeDir.resolve("skill-chain-runs.jsonl");
        for (String line : Files.readAllLines(jsonl)) {
            if (line.isBlank()) continue;
            RunRecord rec = RunRecord.fromJson(line);
            assertNotNull(rec, "JSONL 行应可被 fromJson 还原: " + line);
            assertEquals("meta-parse", rec.metaSkillName());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  二级索引
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("listByMetaSkill — 按 meta-skill 名分组")
    void listByMetaSkill_groupsCorrectly() {
        runChain("alpha", "i1", "s1", Map.of("s1", "o"));
        runChain("beta", "i2", "s2", Map.of("s1", "o"));
        runChain("alpha", "i3", "s3", Map.of("s1", "o"));

        List<RunRecord> alphaRuns = RunRecordStore.instance().listByMetaSkill("alpha");
        List<RunRecord> betaRuns = RunRecordStore.instance().listByMetaSkill("beta");
        List<RunRecord> gammaRuns = RunRecordStore.instance().listByMetaSkill("gamma");

        assertEquals(2, alphaRuns.size(), "alpha 应有 2 个 run");
        assertEquals(1, betaRuns.size(), "beta 应有 1 个 run");
        assertTrue(gammaRuns.isEmpty(), "gamma 应有 0 个 run");
    }

    @Test
    @DisplayName("listByStatus — 按链状态分组")
    void listByStatus_groupsCorrectly() {
        // 跑成功链
        runChain("ok-meta", "i1", "ok-slug", Map.of("s1", "o", "s2", "o"));
        // 跑失败链（第一步即失败）
        MetaSkill failMeta = new MetaSkill(
                "fail-meta", "d",
                List.of(new MetaSkill.SkillStep("s1", "${input}", "d1", false, "")),
                "/output/fail-meta"
        );
        SkillChain.run(failMeta, "i2",
                new SkillChainContext("a", "/w", "fail-slug"),
                (agentId, skillName, args, wd) -> "");  // 空输出 → FAILED

        List<RunRecord> completed = RunRecordStore.instance().listByStatus("COMPLETED");
        List<RunRecord> failed = RunRecordStore.instance().listByStatus("FAILED");

        assertTrue(completed.stream().anyMatch(r -> r.metaSkillName().equals("ok-meta")));
        assertTrue(failed.stream().anyMatch(r -> r.metaSkillName().equals("fail-meta")));
    }

    // ════════════════════════════════════════════════════════════════
    //  recent / size
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("recent(n) 返回最近 N 个（按 startedAt 降序）")
    void recent_returnsLatestN() throws Exception {
        runChain("m1", "i1", "s1", Map.of("s1", "o"));
        Thread.sleep(5);  // 保证 startedAt 不同
        runChain("m2", "i2", "s2", Map.of("s1", "o"));
        Thread.sleep(5);
        runChain("m3", "i3", "s3", Map.of("s1", "o"));

        List<RunRecord> recent2 = RunRecordStore.instance().recent(2);
        List<RunRecord> recent10 = RunRecordStore.instance().recent(10);

        assertEquals(2, recent2.size(), "recent(2) 应返回 2 条");
        assertEquals(3, recent10.size(), "recent(10) 应返回全部 3 条");

        // recent(2) 中第一条 startedAt >= 第二条
        assertTrue(recent2.get(0).startedAt() >= recent2.get(1).startedAt(),
                "recent 应按 startedAt 降序");
        // 最新的应是 m3
        assertEquals("m3", recent2.get(0).metaSkillName());
        assertEquals("m2", recent2.get(1).metaSkillName());
    }

    @Test
    @DisplayName("size() 返回总运行数")
    void size_returnsTotalCount() {
        assertEquals(0, RunRecordStore.instance().size());
        runChain("m1", "i1", "s1", Map.of("s1", "o"));
        runChain("m2", "i2", "s2", Map.of("s1", "o"));
        assertEquals(2, RunRecordStore.instance().size());
    }

    // ════════════════════════════════════════════════════════════════
    //  懒加载 — 重启后从 JSONL 恢复
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("懒加载 — setBaseDir 重置后重新加载 JSONL，索引恢复")
    void lazyLoad_recoversFromJsonlAfterReset() {
        runChain("persist-meta-1", "i1", "s1", Map.of("s1", "o"));
        runChain("persist-meta-2", "i2", "s2", Map.of("s1", "o"));
        assertEquals(2, RunRecordStore.instance().size());

        // 模拟重启：重新指向同一目录（清空内存索引，触发懒加载）
        RunRecordStore.instance().setBaseDir(storeDir);

        // 此时 size() 触发 ensureLoaded() → 应从 JSONL 恢复 2 条
        assertEquals(2, RunRecordStore.instance().size(),
                "setBaseDir 后应从 JSONL 恢复 2 条历史记录");

        List<RunRecord> m1Runs = RunRecordStore.instance().listByMetaSkill("persist-meta-1");
        assertEquals(1, m1Runs.size());
        assertEquals("i1", m1Runs.get(0).input());
    }

    @Test
    @DisplayName("懒加载 — 空 JSONL 时不报错，索引为空")
    void lazyLoad_emptyJsonl_noError() {
        // setBaseDir 到全新目录，无 JSONL
        RunRecordStore.instance().setBaseDir(tempDir.resolve("empty-store"));

        assertEquals(0, RunRecordStore.instance().size());
        assertTrue(RunRecordStore.instance().listByMetaSkill("any").isEmpty());
    }

    // ════════════════════════════════════════════════════════════════
    //  reproduce
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("reproduce(runId, executor) — 用新 slug 重放，原 VFS 输出不覆盖")
    void reproduce_replaysWithNewSlug() {
        // 注册 meta-skill（reproduce 通过 MetaSkillRegistry 查找）
        MetaSkill meta = new MetaSkill(
                "repro-meta", "d",
                List.of(new MetaSkill.SkillStep("s1", "${input}", "d1", false, "")),
                "/output/repro-meta"
        );
        MetaSkillRegistry.instance().register(meta);
        try {
            SkillChainContext ctx = new SkillChainContext("agent_r", "/work", "orig-slug");
            SkillChain.ChainRun orig = SkillChain.run(meta, "input-r", ctx,
                    stubExecutor(Map.of("s1", "out-r")));

            // 原 VFS 输出
            String origOutput = VfsManager.instance().readText(
                    "/output/repro-meta/orig-slug/d1/output.md");
            assertNotNull(origOutput);
            assertTrue(origOutput.contains("out-r"));

            // 重放
            Optional<SkillChain.ChainRun> rerunOpt = RunRecordStore.instance()
                    .reproduce(orig.runId(), stubExecutor(Map.of("s1", "replayed-out")));

            assertTrue(rerunOpt.isPresent());
            SkillChain.ChainRun rerun = rerunOpt.get();
            assertNotEquals(orig.runId(), rerun.runId(), "重放应生成新 runId");

            // 原 VFS 输出未被覆盖
            String origStillThere = VfsManager.instance().readText(
                    "/output/repro-meta/orig-slug/d1/output.md");
            assertTrue(origStillThere.contains("out-r"),
                    "原 run 的 VFS 输出不应被覆盖");

            // 重放输出在新 slug 路径下
            assertTrue(rerun.outputBasePath().contains("replay-"),
                    "重放应使用带 replay 后缀的新 slug: " + rerun.outputBasePath());
        } finally {
            MetaSkillRegistry.instance().unregister("repro-meta");
        }
    }

    @Test
    @DisplayName("reproduce(unknownId, executor) 返回 empty")
    void reproduce_unknownId_returnsEmpty() {
        MetaSkillRegistry.instance().register(new MetaSkill(
                "any", "d", List.of(new MetaSkill.SkillStep("s1")), "/o"));
        try {
            Optional<SkillChain.ChainRun> result = RunRecordStore.instance()
                    .reproduce("run-does-not-exist",
                            stubExecutor(Map.of()));
            assertTrue(result.isEmpty());
        } finally {
            MetaSkillRegistry.instance().unregister("any");
        }
    }

    @Test
    @DisplayName("reproduce — meta-skill 未注册时返回 empty")
    void reproduce_metaNotRegistered_returnsEmpty() {
        SkillChain.ChainRun run = runChain("meta-not-registered", "i", "s",
                Map.of("s1", "o"));
        // 注意：runChain 直接用 MetaSkill 构造，未注册到 MetaSkillRegistry

        Optional<SkillChain.ChainRun> result = RunRecordStore.instance()
                .reproduce(run.runId(), stubExecutor(Map.of()));

        assertTrue(result.isEmpty(), "meta-skill 未注册时应返回 empty");
    }

    // ════════════════════════════════════════════════════════════════
    //  best-effort 行为
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("best-effort — baseDir 为只读时 record() 不抛异常")
    void record_bestEffort_doesNotThrowOnIoFailure() throws Exception {
        // 指向一个不存在的父目录的相对路径，触发 I/O 失败
        // （但 setBaseDir 会尝试 createDirectories，所以我们需要让写入失败）
        // 用一个文件作为 baseDir：在文件下创建子目录会失败
        Path fileAsDir = tempDir.resolve("i-am-a-file");
        Files.writeString(fileAsDir, "content");

        // setBaseDir 不抛异常（createDirectories 失败只 warn）
        RunRecordStore.instance().setBaseDir(fileAsDir);

        // record 也应不抛异常（best-effort）
        MetaSkill meta = new MetaSkill(
                "be-meta", "d",
                List.of(new MetaSkill.SkillStep("s1", "${input}", "d1", false, "")),
                "/output/be"
        );
        SkillChainContext ctx = new SkillChainContext("a", "/w", "s");
        assertDoesNotThrow(() -> SkillChain.run(meta, "in", ctx,
                stubExecutor(Map.of("s1", "o"))));

        // 内存索引应仍然有记录（即使磁盘失败）
        assertFalse(RunRecordStore.instance().recent(10).isEmpty(),
                "内存索引应至少有本次 run — best-effort 不影响索引更新");
    }

    // ════════════════════════════════════════════════════════════════
    //  与 SkillChain.run 集成
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("SkillChain.run 自动触发 RunRecordStore.record — 链跑完后 store 中可查到")
    void skillChainRun_autoPersistsToStore() {
        SkillChain.ChainRun run = runChain("auto-meta", "auto-input", "auto-slug",
                Map.of("s1", "o1", "s2", "o2"));

        Optional<RunRecord> stored = RunRecordStore.instance().get(run.runId());
        assertTrue(stored.isPresent(), "SkillChain.run 应自动写入 RunRecordStore");
        assertEquals("auto-meta", stored.get().metaSkillName());
        assertEquals("auto-input", stored.get().input());
        assertEquals("auto-slug", stored.get().slug());
        assertEquals("COMPLETED", stored.get().status());
    }

    @Test
    @DisplayName("SkillChain.run 多次调用 — store 累积所有 run")
    void skillChainRun_multipleCalls_allStored() {
        assertEquals(0, RunRecordStore.instance().size());

        runChain("multi", "i1", "s1", Map.of("s1", "o", "s2", "o"));
        runChain("multi", "i2", "s2", Map.of("s1", "o", "s2", "o"));
        runChain("multi", "i3", "s3", Map.of("s1", "o", "s2", "o"));

        assertEquals(3, RunRecordStore.instance().size());
        List<RunRecord> multiRuns = RunRecordStore.instance().listByMetaSkill("multi");
        assertEquals(3, multiRuns.size());
    }
}
