package com.ouisani.aios.core.middleware;

import com.ouisani.aios.core.middleware.Middleware.ActingContext;
import com.ouisani.aios.core.observability.UpstreamMeta;
import com.ouisani.aios.core.observability.UpstreamMetaHook;
import com.ouisani.aios.core.tool.ToolContext;
import com.ouisani.aios.core.tool.ToolInput;
import com.ouisani.aios.core.tool.ToolOutput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link UpstreamMetaMiddleware} 增量观测测试 — D5（QueryEngine 层 tool.query.* 记录）。
 * <p>
 * 仿 {@code UpstreamMetaHookTest} 范式：@TempDir 重定向 + resetForTesting 清静态缓冲。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>成功路径：fireOnActing 后磁盘有 {@code tool.query.<name>} 记录，status_code=200，error_code=null</li>
 *   <li>异常路径：leaf 抛异常 → 记录 error_code=TOOL_FAIL, status_code=500，异常向上传播</li>
 *   <li>upstream_name 前缀 {@code tool.query.} 与 syscall 层 {@code tool.} 区分</li>
 *   <li>agentId 从 ActingContext 传递</li>
 * </ul>
 */
class UpstreamMetaMiddlewareTest {

    @TempDir
    Path tempDir;

    private Path metaFile;
    private MiddlewareRegistry registry;

    @BeforeEach
    void setUp() {
        metaFile = tempDir.resolve("upstream_meta.jsonl");
        UpstreamMetaHook.setUpstreamMetaFile(metaFile);
        UpstreamMetaHook.setEnabled(true);
        UpstreamMetaHook.resetForTesting();

        registry = MiddlewareRegistry.instance();
        registry.clearForTesting();
        registry.register(new UpstreamMetaMiddleware());
    }

    @AfterEach
    void tearDown() {
        UpstreamMetaHook.resetForTesting();
        UpstreamMetaHook.setEnabled(true);
        registry.clearForTesting();
    }

    private static final ToolContext TOOL_CTX = new ToolContext("agent_meta", null, "/tmp");
    private static final ToolInput INPUT = () -> "{}";

    private static ActingContext actingCtx(String toolName, String agentId) {
        return new ActingContext(agentId, toolName, INPUT, TOOL_CTX, Map.of());
    }

    @Test
    @DisplayName("成功路径：磁盘记录 tool.query.<name>，status=200，error_code=null")
    void success_path_recordsToolQueryMeta() throws Exception {
        registry.fireOnActing(actingCtx("web_search", "agent_42"),
                () -> ToolOutput.ok("search results"));

        assertTrue(Files.exists(metaFile));
        List<String> lines = Files.readAllLines(metaFile);
        assertEquals(1, lines.size());

        UpstreamMeta meta = UpstreamMeta.fromJsonLine(lines.get(0));
        assertNotNull(meta);
        assertEquals("tool.query.web_search", meta.upstreamName(),
                "upstream_name 前缀 tool.query. 与 syscall 层 tool. 区分");
        assertEquals(200, meta.upstreamStatusCode());
        assertNull(meta.errorCode());
        assertEquals("agent_42", meta.agentId());
        assertTrue(meta.upstreamDurationMs() >= 0);
    }

    @Test
    @DisplayName("异常路径：记录 error_code=TOOL_FAIL, status=500，异常向上传播")
    void exception_path_recordsFailureAndRethrows() throws Exception {
        RuntimeException leafEx = new RuntimeException("tool crashed");

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                registry.fireOnActing(actingCtx("bash", "agent_x"),
                        () -> { throw leafEx; }));

        assertSame(leafEx, thrown, "leaf 异常应原样向上传播");

        List<String> lines = Files.readAllLines(metaFile);
        assertEquals(1, lines.size(), "异常路径仍应记录一条 meta");

        UpstreamMeta meta = UpstreamMeta.fromJsonLine(lines.get(0));
        assertNotNull(meta);
        assertEquals("tool.query.bash", meta.upstreamName());
        assertEquals(500, meta.upstreamStatusCode());
        assertEquals("TOOL_FAIL", meta.errorCode());
        assertEquals("agent_x", meta.agentId());
    }

    @Test
    @DisplayName("逻辑失败（output.success()==false）：status=500，error_code=null")
    void logicalFailure_path_recordsStatus500() throws Exception {
        registry.fireOnActing(actingCtx("file_write", "agent_lf"),
                () -> ToolOutput.fail("write denied"));

        List<String> lines = Files.readAllLines(metaFile);
        assertEquals(1, lines.size());

        UpstreamMeta meta = UpstreamMeta.fromJsonLine(lines.get(0));
        assertNotNull(meta);
        assertEquals("tool.query.file_write", meta.upstreamName());
        assertEquals(500, meta.upstreamStatusCode(), "逻辑失败 → status=500");
        assertNull(meta.errorCode(), "逻辑失败（非异常）→ error_code=null");
    }

    @Test
    @DisplayName("upstream_name 前缀 tool.query. 不与 syscall 层 tool. 冲突")
    void upstreamNamePrefix_distinctFromSyscallLayer() throws Exception {
        registry.fireOnActing(actingCtx("grep", "agent_p"),
                () -> ToolOutput.ok("ok"));

        // 内存缓冲查询：tool.query.grep 有记录，tool.grep 无记录（syscall 层未触发）
        List<UpstreamMeta> queryRecords = UpstreamMetaHook.listByUpstream("tool.query.grep");
        List<UpstreamMeta> syscallRecords = UpstreamMetaHook.listByUpstream("tool.grep");

        assertEquals(1, queryRecords.size());
        assertEquals(0, syscallRecords.size(), "syscall 层 tool.grep 不应被 QueryEngine 中间件触发");
    }

    @Test
    @DisplayName("多次调用：每次都落盘一条记录")
    void multipleCalls_eachRecordsToDisk() throws Exception {
        for (int i = 0; i < 3; i++) {
            registry.fireOnActing(actingCtx("bash", "agent_multi"),
                    () -> ToolOutput.ok("ok"));
        }

        List<String> lines = Files.readAllLines(metaFile);
        assertEquals(3, lines.size(), "3 次调用 → 3 条记录");
        for (String line : lines) {
            UpstreamMeta m = UpstreamMeta.fromJsonLine(line);
            assertNotNull(m);
            assertEquals("tool.query.bash", m.upstreamName());
        }
    }

    @Test
    @DisplayName("implementedHooks() 声明 on_acting")
    void implementedHooks_declaresOnActing() {
        UpstreamMetaMiddleware m = new UpstreamMetaMiddleware();
        assertTrue(m.implementedHooks().contains(Middleware.ON_ACTING));
    }
}
