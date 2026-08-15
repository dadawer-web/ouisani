package com.ouisani.aios.core.skill;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.provenance.ProvenanceHook;
import com.ouisani.aios.core.provenance.ProvenanceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SkillChain 执行引擎单元测试 — 验证 R2 Meta-skill 编排执行。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>正常链路：4 步骤顺序执行，全部 SUCCESS</li>
 *   <li>每步骤输出落 VFS（outputBasePath/slug/outputDir/output.md）</li>
 *   <li>chain-manifest.json 写入 VFS</li>
 *   <li>ProvenanceHook 自动归属 agentId/sessionId</li>
 *   <li>参数模板 ${input} ${slug} ${prev.output} ${prev.outputPath} ${step.index} ${defaults.x}</li>
 *   <li>非可选步骤失败 → 链中止（PARTIAL/FAILED）</li>
 *   <li>可选步骤失败 → 链继续</li>
 *   <li>executor 抛异常 → 步骤标记 FAILED</li>
 *   <li>SkillExecutor 函数式接口解耦（核心层不依赖 user.sdk.AiosSdk）</li>
 *   <li>ChainRun.toJson() 序列化</li>
 * </ul>
 */
class SkillChainTest {

    @TempDir
    Path tempDir;

    private Path provenanceFile;

    @BeforeEach
    void setUp() {
        // 初始化 VFS（idempotent — 已初始化则 no-op）
        VfsManager.instance().init();
        // 隔离 ProvenanceHook 状态：每个测试用独立的 jsonl 文件 + 清空计数器
        provenanceFile = tempDir.resolve("provenance.jsonl");
        ProvenanceHook.setProvenanceFile(provenanceFile);
        ProvenanceHook.setEnabled(true);
        ProvenanceHook.resetForTesting();
        // R3：隔离 RunRecordStore 写入目录（避免污染 ~/aios-java/var/run/overnight）
        RunRecordStore.instance().setBaseDir(tempDir.resolve("run-records"));
    }

    @AfterEach
    void tearDown() {
        ProvenanceHook.CURRENT_AGENT_ID.remove();
        ProvenanceHook.CURRENT_SESSION_ID.remove();
        ProvenanceHook.resetForTesting();
    }

    /**
     * 测试用 Stub executor — 按顺序返回预设输出，记录每次调用参数。
     * <p>
     * 模拟 4 个 specialist skill 的 LLM 回复，并捕获参数模板解析结果。
     */
    static final class StubExecutor implements SkillChain.SkillExecutor {
        final List<String> calls = new ArrayList<>();
        final Map<String, String> outputsBySkill;
        final String defaultOutput;

        StubExecutor(Map<String, String> outputsBySkill, String defaultOutput) {
            this.outputsBySkill = outputsBySkill;
            this.defaultOutput = defaultOutput;
        }

        @Override
        public String execute(String agentId, String skillName, String args, String workingDir) {
            calls.add(skillName + "|" + args);
            String out = outputsBySkill.get(skillName);
            return out != null ? out : defaultOutput;
        }
    }

    @Test
    @DisplayName("4 步骤链全部成功 — 顺序执行，每步输出落 VFS")
    void run_fullChainSuccess() {
        MetaSkill meta = MetaSkills.ai4sAgent();
        SkillChainContext ctx = new SkillChainContext("agent_5", "sess_abc", "/work", "my-slug");
        StubExecutor executor = new StubExecutor(
                Map.of(
                        "research-explorer", "# Exploration\n\ntopic: transformer forecasting",
                        "literature-survey", "# Survey\n\n60+ citations",
                        "experiment-suite", "# Experiment\n\nresults.json",
                        "paper-writer", "# Paper\n\n8-14pp"
                ),
                "default"
        );

        SkillChain.ChainRun run = SkillChain.run(meta, "Transformer time series", ctx, executor);

        // 验证整体状态
        assertEquals(SkillChain.ChainStatus.COMPLETED, run.status());
        assertEquals(4, run.steps().size());
        assertEquals(4, run.successCount());
        assertEquals(0, run.failureCount());
        assertEquals("ai4s-agent", run.metaSkillName());
        assertEquals("/output/ai4s-agent/my-slug", run.outputBasePath());

        // 验证 4 个步骤都按顺序调用了
        assertEquals(4, executor.calls.size());
        assertTrue(executor.calls.get(0).startsWith("research-explorer|"));
        assertTrue(executor.calls.get(1).startsWith("literature-survey|"));
        assertTrue(executor.calls.get(2).startsWith("experiment-suite|"));
        assertTrue(executor.calls.get(3).startsWith("paper-writer|"));

        // 验证每步骤的 VFS 输出路径
        assertEquals("/output/ai4s-agent/my-slug/research-explorer/output.md",
                run.steps().get(0).outputVfsPath());
        assertEquals("/output/ai4s-agent/my-slug/paper-writer/output.md",
                run.steps().get(3).outputVfsPath());

        // 验证 VFS 中确实有这些文件
        String paperContent = VfsManager.instance().readText(
                "/output/ai4s-agent/my-slug/paper-writer/output.md");
        assertNotNull(paperContent);
        assertTrue(paperContent.contains("# Paper"));
    }

    @Test
    @DisplayName("chain-manifest.json 写入 VFS")
    void run_writesManifestToVfs() {
        MetaSkill meta = MetaSkills.ai4sAgent();
        SkillChainContext ctx = new SkillChainContext("a1", "/work", "slug-x");
        StubExecutor executor = new StubExecutor(Map.of(), "out");

        SkillChain.run(meta, "input", ctx, executor);

        String manifest = VfsManager.instance().readText(
                "/output/ai4s-agent/slug-x/chain-manifest.json");
        assertNotNull(manifest);
        assertTrue(manifest.contains("\"metaSkillName\":\"ai4s-agent\""));
        assertTrue(manifest.contains("\"status\":\"COMPLETED\""));
        assertTrue(manifest.contains("\"runId\":\"run-"));
        assertTrue(manifest.contains("\"steps\":["));
        assertTrue(manifest.contains("\"skillName\":\"research-explorer\""));
    }

    @Test
    @DisplayName("ProvenanceHook 自动归属 agentId/sessionId")
    void run_provenanceHookContextIsSet() {
        MetaSkill meta = new MetaSkill(
                "test", "d",
                List.of(new MetaSkill.SkillStep("s1", "${input}", "d1", false, "")),
                "/output/test"
        );
        SkillChainContext ctx = new SkillChainContext("agent_pq", "sess_xyz", "/work", "slug-p");
        StubExecutor executor = new StubExecutor(Map.of(), "out");

        SkillChain.run(meta, "input", ctx, executor);

        // 写入 VFS 触发 ProvenanceHook → provenance.jsonl 中应有 agent_pq/sess_xyz
        List<ProvenanceRecord> records = ProvenanceHook.listByAgent("agent_pq");
        assertFalse(records.isEmpty(), "Provenance 应归属到 agent_pq");
        assertEquals("agent_pq", records.get(0).agentId());
        assertEquals("sess_xyz", records.get(0).sessionId());
        assertEquals("/output/test/slug-p/d1/output.md", records.get(0).path());
    }

    @Test
    @DisplayName("参数模板 ${input} ${slug} ${prev.outputPath} ${step.index} 都被解析")
    void run_templateVariablesResolved() {
        MetaSkill meta = new MetaSkill(
                "tpl-test", "d",
                List.of(
                        new MetaSkill.SkillStep("s1", "input=${input} idx=${step.index}", "d1", false, ""),
                        new MetaSkill.SkillStep("s2", "prev=${prev.outputPath} slug=${slug}", "d2", false, "")
                ),
                "/output/tpl"
        );
        SkillChainContext ctx = new SkillChainContext("a1", "/work", "my-slug");
        StubExecutor executor = new StubExecutor(Map.of(), "ok");

        SkillChain.run(meta, "USER_INPUT", ctx, executor);

        // 第一步：input 和 step.index 都解析
        String call0 = executor.calls.get(0);
        assertTrue(call0.contains("input=USER_INPUT"), "第一步应解析 ${input}=" + call0);
        assertTrue(call0.contains("idx=0"), "第一步应解析 ${step.index}=0 — " + call0);

        // 第二步：prev.outputPath 应是第一步的 VFS 路径，slug 应解析
        String call1 = executor.calls.get(1);
        assertTrue(call1.contains("prev=/output/tpl/my-slug/d1/output.md"),
                "第二步应解析 ${prev.outputPath} — " + call1);
        assertTrue(call1.contains("slug=my-slug"),
                "第二步应解析 ${slug}=my-slug — " + call1);
    }

    @Test
    @DisplayName("非可选步骤失败 → 链中止（PARTIAL 当后续有成功）")
    void run_requiredStepFails_abortsChain() {
        MetaSkill meta = new MetaSkill(
                "fail-test", "d",
                List.of(
                        new MetaSkill.SkillStep("ok1", "${input}", "d1", false, ""),
                        new MetaSkill.SkillStep("boom", "${input}", "d2", false, ""),
                        new MetaSkill.SkillStep("never", "${input}", "d3", false, "")
                ),
                "/output/fail"
        );
        SkillChainContext ctx = new SkillChainContext("a1", "/work", "s");
        // boom 步骤返回空 → 视为 FAILED
        StubExecutor executor = new StubExecutor(Map.of("boom", ""), "ok");

        SkillChain.ChainRun run = SkillChain.run(meta, "in", ctx, executor);

        // 第一步成功，第二步失败，第三步不执行
        assertEquals(SkillChain.ChainStatus.PARTIAL, run.status());
        assertEquals(2, run.steps().size(), "第三步不应执行");
        assertEquals(SkillChain.StepStatus.SUCCESS, run.steps().get(0).status());
        assertEquals(SkillChain.StepStatus.FAILED, run.steps().get(1).status());
        assertNotNull(run.steps().get(1).errorMessage());
    }

    @Test
    @DisplayName("第一步即失败 → ChainStatus.FAILED")
    void run_firstStepFails_statusFailed() {
        MetaSkill meta = new MetaSkill(
                "fail-first", "d",
                List.of(
                        new MetaSkill.SkillStep("boom", "${input}", "d1", false, ""),
                        new MetaSkill.SkillStep("ok2", "${input}", "d2", false, "")
                ),
                "/output/ff"
        );
        SkillChainContext ctx = new SkillChainContext("a1", "/work", "s");
        StubExecutor executor = new StubExecutor(Map.of("boom", ""), "ok");

        SkillChain.ChainRun run = SkillChain.run(meta, "in", ctx, executor);

        assertEquals(SkillChain.ChainStatus.FAILED, run.status());
        assertEquals(1, run.steps().size(), "第一步失败后不应继续");
    }

    @Test
    @DisplayName("可选步骤失败 → 链继续，最终 COMPLETED")
    void run_optionalStepFails_continuesChain() {
        MetaSkill meta = new MetaSkill(
                "opt-test", "d",
                List.of(
                        new MetaSkill.SkillStep("boom", "${input}", "d1", true, ""),  // optional=true
                        new MetaSkill.SkillStep("ok2", "${input}", "d2", false, "")
                ),
                "/output/opt"
        );
        SkillChainContext ctx = new SkillChainContext("a1", "/work", "s");
        StubExecutor executor = new StubExecutor(Map.of("boom", ""), "ok");

        SkillChain.ChainRun run = SkillChain.run(meta, "in", ctx, executor);

        assertEquals(SkillChain.ChainStatus.COMPLETED, run.status(),
                "可选步骤失败不应中止链");
        assertEquals(2, run.steps().size());
        assertEquals(SkillChain.StepStatus.FAILED, run.steps().get(0).status());
        assertEquals(SkillChain.StepStatus.SUCCESS, run.steps().get(1).status());
    }

    @Test
    @DisplayName("executor 抛异常 → 步骤标记 FAILED，链不崩溃")
    void run_executorThrows_stepFailed() {
        MetaSkill meta = new MetaSkill(
                "exc-test", "d",
                List.of(new MetaSkill.SkillStep("s1", "${input}", "d1", false, "")),
                "/output/exc"
        );
        SkillChainContext ctx = new SkillChainContext("a1", "/work", "s");
        SkillChain.SkillExecutor throwingExecutor = (agentId, skillName, args, wd) -> {
            throw new RuntimeException("simulated LLM failure");
        };

        SkillChain.ChainRun run = SkillChain.run(meta, "in", ctx, throwingExecutor);

        assertEquals(SkillChain.ChainStatus.FAILED, run.status());
        assertEquals(SkillChain.StepStatus.FAILED, run.steps().get(0).status());
        assertTrue(run.steps().get(0).errorMessage().contains("simulated LLM failure"));
    }

    @Test
    @DisplayName("失败步骤仍写错误日志到 VFS")
    void run_failedStepWritesErrorLogToVfs() {
        MetaSkill meta = new MetaSkill(
                "err-log", "d",
                List.of(new MetaSkill.SkillStep("boom", "${input}", "d1", true, "")),
                "/output/err"
        );
        SkillChainContext ctx = new SkillChainContext("a1", "/work", "s");
        StubExecutor executor = new StubExecutor(Map.of("boom", ""), "ok");

        SkillChain.run(meta, "in", ctx, executor);

        String content = VfsManager.instance().readText("/output/err/s/d1/output.md");
        assertNotNull(content);
        assertTrue(content.contains("# Step Failed"), "失败步骤应写错误日志");
        assertTrue(content.contains("boom"));
    }

    @Test
    @DisplayName("prev.output 变量传递上一步的输出文本")
    void run_prevOutputVariableCarriesText() {
        MetaSkill meta = new MetaSkill(
                "prev-out", "d",
                List.of(
                        new MetaSkill.SkillStep("s1", "${input}", "d1", false, ""),
                        new MetaSkill.SkillStep("s2", "prev_output=${prev.output}", "d2", false, "")
                ),
                "/output/po"
        );
        SkillChainContext ctx = new SkillChainContext("a1", "/work", "s");
        StubExecutor executor = new StubExecutor(Map.of("s1", "FIRST_OUTPUT"), "ok");

        SkillChain.run(meta, "in", ctx, executor);

        // 第二步的 args 应包含上一步的输出文本
        String call1 = executor.calls.get(1);
        assertTrue(call1.contains("prev_output=FIRST_OUTPUT"),
                "第二步应通过 ${prev.output} 收到上一步输出 — " + call1);
    }

    @Test
    @DisplayName("defaults 变量 ${defaults.x} 可被引用")
    void run_defaultsVariableReferenced() {
        MetaSkill meta = new MetaSkill(
                "def-test", "d",
                List.of(new MetaSkill.SkillStep("s1", "mode=${defaults.mode}", "d1", false, "")),
                "/output/def",
                Map.of("mode", "topic")
        );
        SkillChainContext ctx = new SkillChainContext("a1", "/work", "s");
        StubExecutor executor = new StubExecutor(Map.of(), "ok");

        SkillChain.run(meta, "in", ctx, executor);

        assertEquals("s1|mode=topic", executor.calls.get(0),
                "defaults.mode 应被解析为 topic");
    }

    @Test
    @DisplayName("ChainRun.toJson() 包含完整字段")
    void chainRun_toJson() {
        MetaSkill meta = new MetaSkill(
                "json-test", "d",
                List.of(
                        new MetaSkill.SkillStep("s1", "${input}", "d1", false, ""),
                        new MetaSkill.SkillStep("s2", "${input}", "d2", true, "")
                ),
                "/output/json"
        );
        SkillChainContext ctx = new SkillChainContext("a1", "sess", "/work", "slug-j");
        StubExecutor executor = new StubExecutor(Map.of("s2", ""), "ok");

        SkillChain.ChainRun run = SkillChain.run(meta, "in", ctx, executor);

        String json = run.toJson();
        assertTrue(json.contains("\"runId\":\"run-"));
        assertTrue(json.contains("\"metaSkillName\":\"json-test\""));
        assertTrue(json.contains("\"status\":\"COMPLETED\""));
        assertTrue(json.contains("\"successCount\":1"));
        assertTrue(json.contains("\"failureCount\":1"));
        assertTrue(json.contains("\"outputBasePath\":\"/output/json/slug-j\""));
        assertTrue(json.contains("\"skillName\":\"s1\""));
        assertTrue(json.contains("\"skillName\":\"s2\""));
        assertTrue(json.contains("\"status\":\"FAILED\""));
    }

    @Test
    @DisplayName("SkillExecutor 是函数式接口 — 可用 lambda 直接传入")
    void skillExecutor_isFunctionalInterface() {
        MetaSkill meta = new MetaSkill(
                "lambda", "d",
                List.of(new MetaSkill.SkillStep("s1")),
                "/output/lambda"
        );
        SkillChainContext ctx = new SkillChainContext("a1", "/work", "s");

        // lambda 形式直接传入 — 验证 @FunctionalInterface 标注生效
        SkillChain.ChainRun run = SkillChain.run(meta, "in", ctx,
                (agentId, skillName, args, wd) -> "lambda-output: " + skillName);

        assertEquals(SkillChain.ChainStatus.COMPLETED, run.status());
        assertEquals("lambda-output: s1", run.steps().get(0).outputText());
    }

    @Test
    @DisplayName("resolveTemplate 未识别的 ${...} 保持原样")
    void resolveTemplate_unknownVarPreserved() {
        String result = SkillChain.resolveTemplate("hi ${unknown.var} !", Map.of("input", "x"));
        assertEquals("hi ${unknown.var} !", result);
    }

    @Test
    @DisplayName("resolveTemplate null/empty 返回空字符串")
    void resolveTemplate_nullOrEmpty() {
        assertEquals("", SkillChain.resolveTemplate(null, Map.of()));
        assertEquals("", SkillChain.resolveTemplate("", Map.of()));
    }

    @Test
    @DisplayName("ThreadLocal 清理 — run 结束后 CURRENT_AGENT_ID 被清除")
    void run_clearsThreadLocalAfterReturn() {
        // 预设一个值，验证 run 不会污染它（run 内部 set + finally remove）
        ProvenanceHook.CURRENT_AGENT_ID.set("before-run");

        MetaSkill meta = new MetaSkill(
                "tl-test", "d",
                List.of(new MetaSkill.SkillStep("s1")),
                "/output/tl"
        );
        SkillChainContext ctx = new SkillChainContext("during-run", "/work", "s");
        StubExecutor executor = new StubExecutor(Map.of(), "ok");

        SkillChain.run(meta, "in", ctx, executor);

        // run 结束后 ThreadLocal 应被清理（不是 "during-run"）
        assertNull(ProvenanceHook.CURRENT_AGENT_ID.get(),
                "run 结束后 CURRENT_AGENT_ID 应被 remove 清理");
    }
}
