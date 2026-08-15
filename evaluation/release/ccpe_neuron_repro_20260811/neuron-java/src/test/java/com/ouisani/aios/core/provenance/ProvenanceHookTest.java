package com.ouisani.aios.core.provenance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProvenanceHook 单元测试 — 验证 R1 数据模型。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>onWrite 成功写入追加记录 + 版本递增</li>
 *   <li>失败/禁用时不记录</li>
 *   <li>ThreadLocal 上下文传递 agentId/sessionId</li>
 *   <li>listByPath / listByAgent 查询</li>
 *   <li>JSONL 文件持久化 + 格式正确</li>
 *   <li>best-effort：异常不抛出</li>
 * </ul>
 */
class ProvenanceHookTest {

    @TempDir
    Path tempDir;

    private Path provenanceFile;

    @BeforeEach
    void setUp() {
        provenanceFile = tempDir.resolve("provenance.jsonl");
        ProvenanceHook.setProvenanceFile(provenanceFile);
        ProvenanceHook.setEnabled(true);
        ProvenanceHook.resetForTesting();
        ProvenanceHook.CURRENT_AGENT_ID.remove();
        ProvenanceHook.CURRENT_SESSION_ID.remove();
    }

    @AfterEach
    void tearDown() {
        ProvenanceHook.CURRENT_AGENT_ID.remove();
        ProvenanceHook.CURRENT_SESSION_ID.remove();
        ProvenanceHook.resetForTesting();
        ProvenanceHook.setEnabled(true);
    }

    @Test
    @DisplayName("onWrite 成功时追加记录 + 版本递增")
    void onWrite_success_appendsRecordAndIncrementsVersion() throws Exception {
        ProvenanceHook.CURRENT_AGENT_ID.set("agent_5");
        ProvenanceHook.CURRENT_SESSION_ID.set("sess_abc");

        ProvenanceHook.onWrite("/output/survey.md", "content v1", true);
        ProvenanceHook.onWrite("/output/survey.md", "content v2", true);

        List<ProvenanceRecord> history = ProvenanceHook.listByPath("/output/survey.md");
        assertEquals(2, history.size());
        assertEquals(1, history.get(0).version());
        assertEquals(2, history.get(1).version());
        assertEquals("agent_5", history.get(0).agentId());
        assertEquals("sess_abc", history.get(0).sessionId());
        assertEquals("write", history.get(0).tool());
        assertEquals("content v1", history.get(0).content());
        assertEquals(2, ProvenanceHook.currentVersion("/output/survey.md"));
    }

    @Test
    @DisplayName("onWrite 失败时不记录")
    void onWrite_failure_doesNotRecord() {
        ProvenanceHook.onWrite("/output/fail.md", "content", false);

        assertTrue(ProvenanceHook.listByPath("/output/fail.md").isEmpty());
        assertEquals(0, ProvenanceHook.currentVersion("/output/fail.md"));
    }

    @Test
    @DisplayName("禁用时 onWrite 不记录")
    void onWrite_disabled_doesNotRecord() {
        ProvenanceHook.setEnabled(false);
        ProvenanceHook.onWrite("/output/disabled.md", "content", true);

        assertTrue(ProvenanceHook.listByPath("/output/disabled.md").isEmpty());
    }

    @Test
    @DisplayName("null/空 path 不记录")
    void onWrite_nullOrEmptyPath_doesNotRecord() {
        ProvenanceHook.onWrite(null, "content", true);
        ProvenanceHook.onWrite("", "content", true);

        // 无异常抛出即通过
        assertTrue(ProvenanceHook.listByPath("").isEmpty());
    }

    @Test
    @DisplayName("无 ThreadLocal 上下文时 agentId/sessionId 为 null")
    void onWrite_noContext_agentIdIsNull() {
        ProvenanceHook.onWrite("/output/noctx.md", "content", true);

        List<ProvenanceRecord> history = ProvenanceHook.listByPath("/output/noctx.md");
        assertEquals(1, history.size());
        assertNull(history.get(0).agentId());
        assertNull(history.get(0).sessionId());
    }

    @Test
    @DisplayName("onWrite 带 tool 名重载")
    void onWrite_withToolName() {
        ProvenanceHook.onWrite("/output/edit.md", "edited", true, "apply_patch");

        List<ProvenanceRecord> history = ProvenanceHook.listByPath("/output/edit.md");
        assertEquals(1, history.size());
        assertEquals("apply_patch", history.get(0).tool());
    }

    @Test
    @DisplayName("不同 path 独立版本号")
    void onWrite_differentPaths_independentVersions() {
        ProvenanceHook.onWrite("/output/a.md", "a1", true);
        ProvenanceHook.onWrite("/output/b.md", "b1", true);
        ProvenanceHook.onWrite("/output/a.md", "a2", true);

        assertEquals(2, ProvenanceHook.currentVersion("/output/a.md"));
        assertEquals(1, ProvenanceHook.currentVersion("/output/b.md"));
    }

    @Test
    @DisplayName("listByAgent 按 agentId 过滤")
    void listByAgent_filtersByAgentId() {
        ProvenanceHook.CURRENT_AGENT_ID.set("agent_A");
        ProvenanceHook.onWrite("/output/a1.md", "x", true);
        ProvenanceHook.CURRENT_AGENT_ID.set("agent_B");
        ProvenanceHook.onWrite("/output/b1.md", "y", true);

        List<ProvenanceRecord> aRecords = ProvenanceHook.listByAgent("agent_A");
        List<ProvenanceRecord> bRecords = ProvenanceHook.listByAgent("agent_B");

        assertEquals(1, aRecords.size());
        assertEquals("a1.md", aRecords.get(0).path().substring("/output/".length()));
        assertEquals(1, bRecords.size());
    }

    @Test
    @DisplayName("JSONL 文件持久化 — 每行一条 JSON")
    void jsonlFile_persistsRecords() throws Exception {
        ProvenanceHook.CURRENT_AGENT_ID.set("agent_persist");
        ProvenanceHook.onWrite("/output/p1.md", "first", true);
        ProvenanceHook.onWrite("/output/p2.md", "second", true);

        List<String> lines = Files.readAllLines(provenanceFile);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"path\":\"/output/p1.md\""));
        assertTrue(lines.get(0).contains("\"version\":1"));
        assertTrue(lines.get(0).contains("\"agentId\":\"agent_persist\""));
        assertTrue(lines.get(0).contains("\"tool\":\"write\""));
        assertTrue(lines.get(0).contains("\"content\":\"first\""));
        assertTrue(lines.get(1).contains("\"path\":\"/output/p2.md\""));
    }

    @Test
    @DisplayName("ProvenanceRecord JSON 转义特殊字符")
    void provenanceRecord_escapesSpecialChars() {
        ProvenanceRecord r = new ProvenanceRecord(
                "/output/quote.md", 1, 1000L, "write",
                "content with \"quote\" and \n newline \\ backslash",
                "agent", "sess"
        );
        String json = r.toJsonLine();
        // 验证转义后的字符
        assertTrue(json.contains("\\\"quote\\\""));
        assertTrue(json.contains("\\n"));
        assertTrue(json.contains("\\\\"));
        // 不应包含原始换行（JSONL 必须单行）
        assertFalse(json.contains("\n"));
    }

    @Test
    @DisplayName("ProvenanceRecord null 字段处理")
    void provenanceRecord_nullFields_handled() {
        ProvenanceRecord r = new ProvenanceRecord(
                null, 1, 1000L, null, null, null, null
        );
        assertEquals("", r.path());
        assertEquals("unknown", r.tool());
        String json = r.toJsonLine();
        assertTrue(json.contains("\"path\":\"\""));
        assertTrue(json.contains("\"tool\":\"unknown\""));
        assertTrue(json.contains("\"content\":null"));
        assertTrue(json.contains("\"agentId\":null"));
    }

    @Test
    @DisplayName("best-effort: 文件写入异常不抛出")
    void onWrite_fileException_doesNotThrow() {
        // 指向一个不存在的目录（无法创建）— 触发 IOException
        ProvenanceHook.setProvenanceFile(Path.of("/nonexistent-root-dir/cannot-create/provenance.jsonl"));
        // 不应抛出异常
        assertDoesNotThrow(() -> ProvenanceHook.onWrite("/output/x.md", "x", true));
    }
}
