package com.ouisani.aios.core.snapshot;

import com.ouisani.aios.core.AgentTask;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProcessSectionCapturer 单元测试 — 注入 mock SnapshotManager 验证委托与包装,
 * 不触发真实 CRIU 冻结副作用。
 */
class ProcessSectionCapturerTest {

    private ProcessSnapshot sampleSnapshot() {
        return new ProcessSnapshot(
                "snap-test", 0L, null, 1,
                null, null, null, null, null,
                0, 0, 0, null, null, null, null, 0L,
                null, null, null, null, null, 0, null);
    }

    @Test
    void capture_delegatesToSnapshotManager_andWrapsResult() {
        SnapshotManager mockMgr = mock(SnapshotManager.class);
        ProcessSnapshot snap = sampleSnapshot();
        when(mockMgr.createSnapshot(any(AgentTask.class))).thenReturn(snap);

        ProcessSectionCapturer capturer = new ProcessSectionCapturer(mock(AgentTask.class)) {
            @Override protected SnapshotManager snapshotManager() { return mockMgr; }
        };

        SnapshotSection section = capturer.capture();

        assertTrue(section instanceof ProcessSection);
        assertSame(snap, ((ProcessSection) section).processSnapshot());
    }

    @Test
    void restore_delegatesToSnapshotManager_withUnwrappedSnapshot() {
        SnapshotManager mockMgr = mock(SnapshotManager.class);
        ProcessSnapshot snap = sampleSnapshot();
        ProcessSection section = new ProcessSection(snap);

        ProcessSectionCapturer capturer = new ProcessSectionCapturer(mock(AgentTask.class)) {
            @Override protected SnapshotManager snapshotManager() { return mockMgr; }
        };

        capturer.restore(section);

        verify(mockMgr).restore(snap);
    }

    @Test
    void restore_ignoresNonProcessSection() {
        SnapshotManager mockMgr = mock(SnapshotManager.class);
        ProcessSectionCapturer capturer = new ProcessSectionCapturer(mock(AgentTask.class)) {
            @Override protected SnapshotManager snapshotManager() { return mockMgr; }
        };

        // 传入其它 section 类型不应触发 restore
        capturer.restore(new NodeOutputSection(java.util.Map.of()));

        verify(mockMgr, never()).restore(any());
    }

    @Test
    void sectionType_isProcess() {
        ProcessSectionCapturer capturer = new ProcessSectionCapturer(mock(AgentTask.class));
        assertEquals("Process", capturer.sectionType());
    }
}
