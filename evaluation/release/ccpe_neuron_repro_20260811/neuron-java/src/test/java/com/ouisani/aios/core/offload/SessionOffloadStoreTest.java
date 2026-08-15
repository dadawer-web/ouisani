package com.ouisani.aios.core.offload;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SessionOffloadStore} 单元测试 — 覆盖 offloadContext / offloadToolResult /
 * 读取 / 碰撞后缀 / 跨会话隔离 / base64 自动 offload / best-effort 容错。
 */
class SessionOffloadStoreTest {

    @TempDir
    Path tempDir;

    private Path sessionsRoot;
    private Path dataDir;
    private SessionOffloadStore store;

    @BeforeEach
    void setUp() {
        sessionsRoot = tempDir.resolve("sessions");
        dataDir = tempDir.resolve("data");
        // 内部 DataBlockOffloader 阈值默认 4KB；测试用大 payload（>4KB）触发 offload
        store = new SessionOffloadStore(sessionsRoot, dataDir);
    }

    // ════════════════════════════════════════════════════════════════
    //  offloadContext
    // ════════════════════════════════════════════════════════════════

    @Test
    void offloadContextAppendsJsonlLine() {
        int n = store.offloadContext("sess_1", "user", "hello world");
        assertEquals(1, n);

        Path ctxFile = sessionsRoot.resolve("sess_1").resolve(SessionOffloadStore.CONTEXT_FILE);
        assertTrue(Files.exists(ctxFile));

        List<String> lines = store.readContext("sess_1");
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\"role\":\"user\""));
        assertTrue(lines.get(0).contains("\"content\":\"hello world\""));
        assertTrue(lines.get(0).contains("\"ts\":"));
    }

    @Test
    void offloadContextAppendsMultipleLines() {
        store.offloadContext("sess_1", "user", "msg 1");
        store.offloadContext("sess_1", "assistant", "msg 2");
        store.offloadContext("sess_1", "user", "msg 3");

        List<String> lines = store.readContext("sess_1");
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("msg 1"));
        assertTrue(lines.get(1).contains("msg 2"));
        assertTrue(lines.get(2).contains("msg 3"));
    }

    @Test
    void offloadContextAutoOffloadsBase64DataBlock() throws Exception {
        // >4KB payload 触发 offload
        byte[] bigPayload = new byte[8 * 1024];
        for (int i = 0; i < bigPayload.length; i++) bigPayload[i] = (byte) (i % 256);
        String base64 = Base64.getEncoder().encodeToString(bigPayload);
        String content = "screenshot: data:image/png;base64," + base64;

        store.offloadContext("sess_1", "user", content);

        List<String> lines = store.readContext("sess_1");
        assertEquals(1, lines.size());
        String line = lines.get(0);
        // base64 应被替换为 file:// URL
        assertTrue(line.contains("file://"), "应含 file:// URL: " + line);
        assertFalse(line.contains(base64), "原始 base64 不应出现在 JSONL 行中");
        // blob 文件应存在
        try (var stream = Files.list(dataDir)) {
            assertEquals(1, stream.count(), "应创建 1 个 blob 文件");
        }
    }

    @Test
    void offloadContextKeepsSmallContentInline() {
        // 普通文本不触发 offload
        store.offloadContext("sess_1", "user", "just plain text, no binary");
        List<String> lines = store.readContext("sess_1");
        assertTrue(lines.get(0).contains("just plain text"));
        assertFalse(lines.get(0).contains("file://"));
    }

    @Test
    void offloadContextRejectsEmptySessionId() {
        assertEquals(0, store.offloadContext("", "user", "x"));
        assertEquals(0, store.offloadContext(null, "user", "x"));
    }

    @Test
    void offloadContextIsolatesSessionsByDirectory() {
        store.offloadContext("sess_A", "user", "from A");
        store.offloadContext("sess_B", "user", "from B");

        List<String> aLines = store.readContext("sess_A");
        List<String> bLines = store.readContext("sess_B");
        assertEquals(1, aLines.size());
        assertEquals(1, bLines.size());
        assertTrue(aLines.get(0).contains("from A"));
        assertTrue(bLines.get(0).contains("from B"));
        // 互相不污染
        assertFalse(aLines.get(0).contains("from B"));
    }

    @Test
    void readContextReturnsEmptyForMissingSession() {
        List<String> lines = store.readContext("never_existed");
        assertTrue(lines.isEmpty());
    }

    @Test
    void offloadContextHandlesNullRoleAndContent() {
        assertEquals(1, store.offloadContext("sess_1", null, null));
        List<String> lines = store.readContext("sess_1");
        assertTrue(lines.get(0).contains("\"role\":\"unknown\""));
        assertTrue(lines.get(0).contains("\"content\":\"\""));
    }

    @Test
    void offloadContextEscapesJsonSpecialChars() {
        store.offloadContext("sess_1", "user", "text with \"quotes\" and \n newline \t tab");
        List<String> lines = store.readContext("sess_1");
        assertEquals(1, lines.size());
        // 单行 JSONL — 换行符应被转义为 \n
        assertFalse(lines.get(0).contains("\n") || lines.size() > 1 && lines.get(1).isEmpty(),
                "换行符应被转义，不应破坏 JSONL 单行结构");
    }

    // ════════════════════════════════════════════════════════════════
    //  offloadToolResult
    // ════════════════════════════════════════════════════════════════

    @Test
    void offloadToolResultWritesFile() {
        Path path = store.offloadToolResult("sess_1", "call_001", "tool output content");
        assertNotNull(path);
        assertTrue(Files.exists(path));
        assertTrue(path.getFileName().toString().equals("tool_result-call_001.txt"));

        String content = store.readToolResult(path);
        assertEquals("tool output content", content);
    }

    @Test
    void offloadToolResultCollisionAddsSuffix() {
        Path p1 = store.offloadToolResult("sess_1", "call_001", "first");
        Path p2 = store.offloadToolResult("sess_1", "call_001", "second");
        Path p3 = store.offloadToolResult("sess_1", "call_001", "third");

        assertNotNull(p1);
        assertNotNull(p2);
        assertNotNull(p3);
        assertNotEquals(p1, p2);
        assertNotEquals(p2, p3);
        assertNotEquals(p1, p3);

        assertTrue(p1.getFileName().toString().equals("tool_result-call_001.txt"));
        assertTrue(p2.getFileName().toString().equals("tool_result-call_001(1).txt"));
        assertTrue(p3.getFileName().toString().equals("tool_result-call_001(2).txt"));

        assertEquals("first", store.readToolResult(p1));
        assertEquals("second", store.readToolResult(p2));
        assertEquals("third", store.readToolResult(p3));
    }

    @Test
    void offloadToolResultAutoOffloadsBase64DataBlock() {
        byte[] bigPayload = new byte[8 * 1024];
        String base64 = Base64.getEncoder().encodeToString(bigPayload);
        String result = "result data:image/png;base64," + base64;

        Path path = store.offloadToolResult("sess_1", "call_001", result);
        assertNotNull(path);

        String stored = store.readToolResult(path);
        assertTrue(stored.contains("file://"), "存储内容应含 file:// URL");
        assertFalse(stored.contains(base64), "原始 base64 不应出现在工具结果文件中");
    }

    @Test
    void offloadToolResultRejectsEmptySessionId() {
        assertNull(store.offloadToolResult("", "call_001", "x"));
        assertNull(store.offloadToolResult(null, "call_001", "x"));
    }

    @Test
    void offloadToolResultHandlesNullIdAndContent() {
        Path path = store.offloadToolResult("sess_1", null, null);
        assertNotNull(path);
        assertTrue(path.getFileName().toString().startsWith("tool_result-ts_"));
        String content = store.readToolResult(path);
        assertEquals("", content);
    }

    @Test
    void offloadToolResultSanitizesDangerousFileName() {
        // 路径分隔符与 .. 应被替换，防止路径逃逸
        Path path = store.offloadToolResult("sess_1", "../etc/passwd", "evil");
        assertNotNull(path);
        Path sessionDir = sessionsRoot.resolve("sess_1");
        assertTrue(path.startsWith(sessionDir), "文件应落在 session 目录内，防路径逃逸");
        assertTrue(path.getFileName().toString().contains("etc_passwd"),
                "危险字符应被净化: " + path.getFileName());
    }

    @Test
    void readToolResultReturnsNullForMissingFile() {
        Path missing = sessionsRoot.resolve("sess_1").resolve("tool_result-nonexistent.txt");
        assertNull(store.readToolResult(missing));
    }

    @Test
    void readToolResultByStringIdFindsAllSuffixVariants() {
        store.offloadToolResult("sess_1", "call_001", "first");
        store.offloadToolResult("sess_1", "call_001", "second");

        // 通过 id 读取应返回第一个（无后缀）
        String first = store.readToolResult("sess_1", "call_001");
        assertEquals("first", first);
    }

    // ════════════════════════════════════════════════════════════════
    //  listToolResults
    // ════════════════════════════════════════════════════════════════

    @Test
    void listToolResultsReturnsAllFilesSorted() {
        store.offloadToolResult("sess_1", "call_003", "c");
        store.offloadToolResult("sess_1", "call_001", "a");
        store.offloadToolResult("sess_1", "call_002", "b");

        List<Path> files = store.listToolResults("sess_1");
        assertEquals(3, files.size());
        // 按文件名排序
        assertTrue(files.get(0).getFileName().toString().contains("call_001"));
        assertTrue(files.get(1).getFileName().toString().contains("call_002"));
        assertTrue(files.get(2).getFileName().toString().contains("call_003"));
    }

    @Test
    void listToolResultsExcludesContextJsonl() {
        store.offloadContext("sess_1", "user", "context line");
        store.offloadToolResult("sess_1", "call_001", "result");

        List<Path> files = store.listToolResults("sess_1");
        assertEquals(1, files.size(), "context.jsonl 不应出现在 tool_result 列表中");
    }

    @Test
    void listToolResultsReturnsEmptyForMissingSession() {
        List<Path> files = store.listToolResults("never_existed");
        assertTrue(files.isEmpty());
    }

    // ════════════════════════════════════════════════════════════════
    //  跨会话检索（核心场景）
    // ════════════════════════════════════════════════════════════════

    @Test
    void crossSessionRetrievalWithInlineRoundTrip() {
        // 场景：sess_1 写入含 base64 DataBlock 的上下文 → 新进程读出 → inline 还原
        byte[] bigPayload = new byte[6 * 1024];
        for (int i = 0; i < bigPayload.length; i++) bigPayload[i] = (byte) (i % 256);
        String base64 = Base64.getEncoder().encodeToString(bigPayload);
        String original = "screenshot: data:image/png;base64," + base64;

        // 写入（offload）
        store.offloadContext("sess_1", "user", original);

        // 模拟新进程：用同一 dataDir 创建新 store 实例（读端）
        SessionOffloadStore readStore = new SessionOffloadStore(sessionsRoot, dataDir);
        List<String> lines = readStore.readContext("sess_1");
        assertEquals(1, lines.size());

        // 行内含 file:// URL，不含原始 base64
        assertTrue(lines.get(0).contains("file://"));
        assertFalse(lines.get(0).contains(base64));

        // inline 还原
        String restored = readStore.offloader().inline(lines.get(0));
        // 还原后应含 base64（mime 统一为 octet-stream）
        assertTrue(restored.contains("data:application/octet-stream;base64," + base64),
                "inline 应还原 base64 DataBlock");
    }

    @Test
    void crossSessionToolResultRetrievalRoundTrip() {
        byte[] bigPayload = new byte[6 * 1024];
        String base64 = Base64.getEncoder().encodeToString(bigPayload);
        String original = "image data:image/png;base64," + base64;

        Path written = store.offloadToolResult("sess_1", "call_X", original);
        assertNotNull(written);

        // 新 store 实例读回
        SessionOffloadStore readStore = new SessionOffloadStore(sessionsRoot, dataDir);
        String stored = readStore.readToolResult(written);
        assertNotNull(stored);
        assertTrue(stored.contains("file://"));

        String restored = readStore.offloader().inline(stored);
        assertTrue(restored.contains("data:application/octet-stream;base64," + base64));
    }

    // ════════════════════════════════════════════════════════════════
    //  访问器
    // ════════════════════════════════════════════════════════════════

    @Test
    void accessorsReturnConfiguredPaths() {
        assertEquals(sessionsRoot, store.sessionsRoot());
        assertNotNull(store.offloader());
        assertEquals(dataDir, store.offloader().dataDir());
    }

    @Test
    void defaultConstructorUsesAiosPaths() {
        // 不直接断言路径值（依赖环境变量），仅验证不抛异常
        // 用显式 lambda 消除 assertDoesNotThrow(Executable) vs (ThrowingSupplier) 的歧义
        assertDoesNotThrow((org.junit.jupiter.api.function.Executable) SessionOffloadStore::new);
    }
}
