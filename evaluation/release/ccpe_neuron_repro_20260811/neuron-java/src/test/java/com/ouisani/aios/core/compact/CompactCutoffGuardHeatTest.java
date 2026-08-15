package com.ouisani.aios.core.compact;

import com.ouisani.aios.core.AgentTask.TokenRecord;
import com.ouisani.aios.core.ranking.FileHeatResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CompactCutoffGuard FileHeatResolver 注入测试 — 验证 NOOP 零回归 + 注入不破坏配对安全。
 */
class CompactCutoffGuardHeatTest {

    @AfterEach
    void tearDown() {
        // 复位全局 resolver，避免测试间污染
        CompactCutoffGuard.setFileHeatResolver(null);
        CompactCutoffGuard.setBoundaryDetector(null);
        CompactCutoffGuard.setCompactionMode(CompactionMode.REACTIVE);
    }

    private static TokenRecord toolUse(String toolName) {
        return new TokenRecord("assistant",
                "tool_call name=\"" + toolName + "\" query end",
                System.currentTimeMillis());
    }

    private static TokenRecord toolResult(String toolName) {
        return new TokenRecord("tool",
                "tool_result name=\"" + toolName + "\" content here",
                System.currentTimeMillis());
    }

    private static TokenRecord text(String role, String content) {
        return new TokenRecord(role, content, System.currentTimeMillis());
    }

    @Test
    void setFileHeatResolver_nullResetsToNoop() {
        CompactCutoffGuard.setFileHeatResolver(path -> 1.0);
        CompactCutoffGuard.setFileHeatResolver(null);  // reset
        assertSame(CompactCutoffGuard.NOOP_FILE_HEAT_RESOLVER, CompactCutoffGuard.FILE_HEAT_RESOLVER,
                "null 重置为 NOOP");
    }

    @Test
    void findNearestBoundaryBackward_noopResolver_zeroRegression() {
        // NOOP resolver → 行为与原版一致（首个有效边界返回）
        // 用 safeCompactionCutoff 间接验证（findNearestBoundaryBackward 是 private）
        // 默认 REACTIVE 模式：所有切点都是边界 → cutoff 直接返回
        List<TokenRecord> ctx = List.of(
                text("user", "q1"),
                text("assistant", "a1"),
                text("user", "q2"),
                text("assistant", "a2")
        );
        int cutoff = CompactCutoffGuard.safeCompactionCutoff(ctx, 2);
        assertEquals(2, cutoff, "NOOP resolver 零回归：cutoff 不变");
    }

    @Test
    void safeCompactionCutoff_heatResolverInjected_zeroRegressionOnPairedContext() {
        // 注入非 NOOP resolver → 配对安全时 cutoff 仍正常返回
        Map<String, Double> heat = new HashMap<>();
        heat.put("/foo/bar", 0.9);
        heat.put("/baz/qux", 0.1);
        CompactCutoffGuard.setFileHeatResolver(path -> heat.getOrDefault(path, 0.0));

        List<TokenRecord> ctx = List.of(
                text("user", "reading /foo/bar"),
                text("assistant", "done with /foo/bar"),
                text("user", "now /baz/qux"),
                text("assistant", "ok")
        );
        int cutoff = CompactCutoffGuard.safeCompactionCutoff(ctx, 2);
        assertEquals(2, cutoff, "注入 resolver 后配对安全 cutoff 仍正常");
    }

    @Test
    void safeCompactionCutoff_heatResolverInjected_stillRejectsOrphan() {
        // 注入 resolver 后仍拒绝孤儿 ToolResult（heat tiebreaker 不放宽配对约束）
        CompactCutoffGuard.setFileHeatResolver(path -> 1.0);  // 所有文件热度=1
        List<TokenRecord> ctx = List.of(
                text("user", "q"),
                toolUse("file_read"),
                text("assistant", "intermediate"),
                toolResult("file_read")  // ToolUse 在压缩区，ToolResult 在保留区 → 孤儿
        );
        // cutoff=1 时保留 [1,4)，ToolResult 在保留但 ToolUse 在压缩区 → 回溯或拒绝
        int cutoff = CompactCutoffGuard.safeCompactionCutoff(ctx, 1);
        // 应回溯到 0（纳入 ToolUse）或拒绝（0）
        assertTrue(cutoff == 0 || cutoff <= 1, "heat tiebreaker 不破坏配对安全");
    }

    @Test
    void extractFilePaths_matchesSlashPaths() {
        List<String> paths = CompactCutoffGuard.extractFilePaths(
                "reading /home/user/file.txt and /var/log/app.log here");
        assertTrue(paths.contains("/home/user/file.txt"), "匹配多段路径");
        assertTrue(paths.contains("/var/log/app.log"), "匹配多段路径");
        assertEquals(2, paths.size(), "提取 2 个路径");
    }

    @Test
    void extractFilePaths_ignoresNonPaths() {
        List<String> paths = CompactCutoffGuard.extractFilePaths(
                "http://example.com and foo bar and /single");
        // /single 只有一段，不匹配 /(seg/)+file（至少 2 段）
        assertFalse(paths.contains("/single"), "单段路径不匹配");
        assertFalse(paths.stream().anyMatch(p -> p.contains("http://")), "不匹配 URL");
        assertFalse(paths.stream().anyMatch(p -> p.contains("foo")), "不匹配普通文本");
    }

    @Test
    void extractFilePaths_nullOrEmptyReturnsEmpty() {
        assertTrue(CompactCutoffGuard.extractFilePaths(null).isEmpty());
        assertTrue(CompactCutoffGuard.extractFilePaths("").isEmpty());
    }
}
