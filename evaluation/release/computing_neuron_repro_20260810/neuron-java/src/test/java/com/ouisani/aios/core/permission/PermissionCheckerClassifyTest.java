package com.ouisani.aios.core.permission;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PermissionChecker.classify 静态方法测试 — 镜像 jcode safety.rs:177-184 的 classify 主入口。
 * 覆盖白名单 5 项 AutoAllowed + 大小写不敏感 + 未知/null RequiresPermission。
 */
class PermissionCheckerClassifyTest {

    @Test
    void safe_auto_tools_whitelist_returns_auto_allowed() {
        assertEquals(ActionTier.AutoAllowed, PermissionChecker.classify("file_read"));
        assertEquals(ActionTier.AutoAllowed, PermissionChecker.classify("grep"));
        assertEquals(ActionTier.AutoAllowed, PermissionChecker.classify("glob"));
        assertEquals(ActionTier.AutoAllowed, PermissionChecker.classify("web_fetch"));
        assertEquals(ActionTier.AutoAllowed, PermissionChecker.classify("web_search"));
    }

    @Test
    void case_insensitive_match() {
        assertEquals(ActionTier.AutoAllowed, PermissionChecker.classify("FILE_READ"));
        assertEquals(ActionTier.AutoAllowed, PermissionChecker.classify("Grep"));
        assertEquals(ActionTier.AutoAllowed, PermissionChecker.classify("GLOB"));
    }

    @Test
    void unknown_tool_returns_requires_permission() {
        assertEquals(ActionTier.RequiresPermission, PermissionChecker.classify("bash"));
        assertEquals(ActionTier.RequiresPermission, PermissionChecker.classify("file_write"));
        assertEquals(ActionTier.RequiresPermission, PermissionChecker.classify("tool.run_docker"));
        assertEquals(ActionTier.RequiresPermission, PermissionChecker.classify("custom_tool"));
    }

    @Test
    void null_tool_returns_requires_permission() {
        assertEquals(ActionTier.RequiresPermission, PermissionChecker.classify(null));
    }

    @Test
    void blank_tool_returns_requires_permission() {
        assertEquals(ActionTier.RequiresPermission, PermissionChecker.classify(""));
        assertEquals(ActionTier.RequiresPermission, PermissionChecker.classify("   "));
    }

    @Test
    void safe_tools_not_prefix_matched() {
        // 必须精确等值匹配，前缀不算（镜像 jcode safety.rs:179 精确等值）
        assertEquals(ActionTier.RequiresPermission, PermissionChecker.classify("file_read_v2"));
        assertEquals(ActionTier.RequiresPermission, PermissionChecker.classify("grepprefix"));
        assertEquals(ActionTier.RequiresPermission, PermissionChecker.classify("web_fetch_url"));
    }
}
