package com.ouisani.aios.core.snapshot;

import com.ouisani.aios.core.hibernation.AgentSnapshot;
import com.ouisani.aios.core.hibernation.HibernationManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HibernationSectionCapturer 单元测试 — 注入 mock HibernationManager 验证委托与包装,
 * 不触发真实 VFS 写入副作用。
 */
class HibernationSectionCapturerTest {

    private AgentSnapshot sampleSnapshot() {
        return new AgentSnapshot("ws-1", 0L, null, null, null, null, null, 0);
    }

    @Test
    void capture_delegatesToHibernationManager_andWrapsResult() {
        HibernationManager mockMgr = mock(HibernationManager.class);
        AgentSnapshot snap = sampleSnapshot();
        when(mockMgr.suspendToDisk("ws-1")).thenReturn(snap);

        HibernationSectionCapturer capturer = new HibernationSectionCapturer("ws-1") {
            @Override protected HibernationManager hibernationManager() { return mockMgr; }
        };

        SnapshotSection section = capturer.capture();

        assertTrue(section instanceof HibernationSection);
        assertSame(snap, ((HibernationSection) section).agentSnapshot());
    }

    @Test
    void capture_returnsNullWhenSuspendReturnsNull() {
        HibernationManager mockMgr = mock(HibernationManager.class);
        when(mockMgr.suspendToDisk("ws-empty")).thenReturn(null);

        HibernationSectionCapturer capturer = new HibernationSectionCapturer("ws-empty") {
            @Override protected HibernationManager hibernationManager() { return mockMgr; }
        };

        assertNull(capturer.capture());
    }

    @Test
    void restore_delegatesToHibernationManager_withWorkspaceId() {
        HibernationManager mockMgr = mock(HibernationManager.class);
        AgentSnapshot snap = sampleSnapshot();
        HibernationSection section = new HibernationSection(snap);

        HibernationSectionCapturer capturer = new HibernationSectionCapturer("ws-1") {
            @Override protected HibernationManager hibernationManager() { return mockMgr; }
        };

        capturer.restore(section);

        verify(mockMgr).resumeFromDisk("ws-1");
    }

    @Test
    void sectionType_isHibernation() {
        HibernationSectionCapturer capturer = new HibernationSectionCapturer("ws-1");
        assertEquals("Hibernation", capturer.sectionType());
    }
}
