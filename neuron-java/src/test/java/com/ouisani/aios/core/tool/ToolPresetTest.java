package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.AgentTask;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolPreset + AgentTask default-deny 授权测试。
 */
class ToolPresetTest {

    // ════════════════════════════════════════════════════════════════
    //  ToolPreset 预设覆盖
    // ════════════════════════════════════════════════════════════════

    @Test
    void readonly_containsReadToolsOnly() {
        assertTrue(ToolPreset.READONLY.contains("file_read"));
        assertTrue(ToolPreset.READONLY.contains("grep"));
        assertTrue(ToolPreset.READONLY.contains("glob"));
    }

    @Test
    void readonly_excludesWriteTools() {
        assertFalse(ToolPreset.READONLY.contains("file_write"));
        assertFalse(ToolPreset.READONLY.contains("file_edit"));
        assertFalse(ToolPreset.READONLY.contains("bash"));
        assertFalse(ToolPreset.READONLY.contains("agent"));
    }

    @Test
    void readwrite_includesReadonlyPlusWrite() {
        // 包含所有 readonly 工具
        assertTrue(ToolPreset.READWRITE.contains("file_read"));
        assertTrue(ToolPreset.READWRITE.contains("grep"));
        assertTrue(ToolPreset.READWRITE.contains("glob"));
        // 加上写工具
        assertTrue(ToolPreset.READWRITE.contains("file_write"));
        assertTrue(ToolPreset.READWRITE.contains("file_edit"));
        assertTrue(ToolPreset.READWRITE.contains("bash"));
    }

    @Test
    void readwrite_excludesHighPrivilegeTools() {
        assertFalse(ToolPreset.READWRITE.contains("agent"));
        assertFalse(ToolPreset.READWRITE.contains("web_scrape"));
        assertFalse(ToolPreset.READWRITE.contains("security_scan"));
    }

    @Test
    void full_includesAllTools() {
        assertTrue(ToolPreset.FULL.contains("file_read"));
        assertTrue(ToolPreset.FULL.contains("bash"));
        assertTrue(ToolPreset.FULL.contains("agent"));
        assertTrue(ToolPreset.FULL.contains("web_scrape"));
        assertTrue(ToolPreset.FULL.contains("security_scan"));
    }

    @Test
    void full_isSupersetOfReadwrite() {
        assertTrue(ToolPreset.FULL.tools().containsAll(ToolPreset.READWRITE.tools()));
    }

    @Test
    void readwrite_isSupersetOfReadonly() {
        assertTrue(ToolPreset.READWRITE.tools().containsAll(ToolPreset.READONLY.tools()));
    }

    @Test
    void tools_isImmutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> ToolPreset.READONLY.tools().add("bash"));
    }

    // ════════════════════════════════════════════════════════════════
    //  AgentTask default-deny
    // ════════════════════════════════════════════════════════════════

    private static AgentTask createTask(int pid) {
        return new AgentTask(pid, AgentTask.TaskStatus.READY, null, null, null, List.of());
    }

    @Test
    void defaultDeny_emptyGrantedTools() {
        AgentTask task = createTask(1001);
        assertTrue(task.grantedTools().isEmpty());
    }

    @Test
    void defaultDeny_noToolAccess() {
        AgentTask task = createTask(1001);
        // 不声明任何工具,所有工具都应该被拒绝
        assertFalse(task.hasToolAccess("file_read"));
        assertFalse(task.hasToolAccess("bash"));
        assertFalse(task.hasToolAccess("agent"));
    }

    @Test
    void setGrantedTools_readonlyPreset() {
        AgentTask task = createTask(1001);
        task.setGrantedTools(ToolPreset.READONLY.tools());

        assertTrue(task.hasToolAccess("file_read"));
        assertTrue(task.hasToolAccess("grep"));
        assertFalse(task.hasToolAccess("file_write"));
        assertFalse(task.hasToolAccess("bash"));
    }

    @Test
    void setGrantedTools_readwritePreset() {
        AgentTask task = createTask(1001);
        task.setGrantedTools(ToolPreset.READWRITE.tools());

        assertTrue(task.hasToolAccess("file_read"));
        assertTrue(task.hasToolAccess("file_write"));
        assertTrue(task.hasToolAccess("bash"));
        assertFalse(task.hasToolAccess("agent"));
        assertFalse(task.hasToolAccess("web_scrape"));
    }

    @Test
    void setGrantedTools_fullPreset() {
        AgentTask task = createTask(1001);
        task.setGrantedTools(ToolPreset.FULL.tools());

        assertTrue(task.hasToolAccess("file_read"));
        assertTrue(task.hasToolAccess("bash"));
        assertTrue(task.hasToolAccess("agent"));
        assertTrue(task.hasToolAccess("security_scan"));
    }

    @Test
    void setGrantedTools_nullDefaultsToEmpty() {
        AgentTask task = createTask(1001);
        task.setGrantedTools(null);
        assertTrue(task.grantedTools().isEmpty());
        assertFalse(task.hasToolAccess("file_read"));
    }

    @Test
    void setGrantedTools_customSet() {
        AgentTask task = createTask(1001);
        task.setGrantedTools(java.util.Set.of("file_read", "grep"));

        assertTrue(task.hasToolAccess("file_read"));
        assertTrue(task.hasToolAccess("grep"));
        assertFalse(task.hasToolAccess("file_write"));
    }

    // ════════════════════════════════════════════════════════════════
    //  REALTIME 绕过检查
    // ════════════════════════════════════════════════════════════════

    @Test
    void realtimePriority_bypassesToolCheck() {
        // pid < 100 → REALTIME 优先级
        AgentTask task = createTask(50);
        // REALTIME 即使 grantedTools 为空,也能访问所有工具
        assertTrue(task.hasToolAccess("file_read"));
        assertTrue(task.hasToolAccess("bash"));
        assertTrue(task.hasToolAccess("agent"));
        assertTrue(task.hasToolAccess("security_scan"));
    }
}
