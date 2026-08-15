package com.ouisani.aios.core.state;

import com.ouisani.aios.core.snapshot.BoulderSection;
import com.ouisani.aios.user.apps.omnifactory.WorkflowNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * BoulderSectionCapturer 单元测试 — 验证 BoulderCheckpoint ↔ BoulderSection 平铺映射
 * (纯逻辑,不触碰文件系统)及无检查点时 capture 返回 null。
 */
class BoulderSectionCapturerTest {

    @Test
    void toSection_flattensCheckpointFields() {
        BoulderCheckpoint cp = new BoulderCheckpoint();
        cp.setWorkflowId("wf-1");
        cp.setNodeId("node-1");
        cp.setStatus(WorkflowNode.Status.SUCCESS);
        cp.setOutputSnapshot(Map.of("result", "ok"));
        cp.setErrorMessage(null);
        cp.setDurationMs(42L);
        cp.setRetryCount(2);
        cp.setCarryoverSnapshot(Map.of("taskFocus", Map.of("goal", "scan")));
        cp.setEnvironmentSnapshotId("env-snap-1");

        BoulderSection section = BoulderSectionCapturer.toSection(cp);

        assertEquals("wf-1", section.workflowId());
        assertEquals("node-1", section.nodeId());
        assertEquals("SUCCESS", section.status());
        assertEquals("ok", section.outputSnapshot().get("result"));
        assertEquals(42L, section.durationMs());
        assertEquals(2, section.retryCount());
        assertEquals("scan", ((Map<?, ?>) section.carryoverSnapshot().get("taskFocus")).get("goal"));
        assertEquals("env-snap-1", section.environmentSnapshotId());
    }

    @Test
    void toCheckpoint_rebuildsFromSection_withStatusEnum() {
        BoulderSection section = new BoulderSection(
                "wf-2", "node-2", "SUSPENDED",
                Map.of("k", "v"), Map.of("taskFocus", Map.of("goal", "fix")), "boom", 99L, 1, "env-snap-2");

        BoulderCheckpoint cp = BoulderSectionCapturer.toCheckpoint(section);

        assertEquals("wf-2", cp.getWorkflowId());
        assertEquals("node-2", cp.getNodeId());
        assertEquals(WorkflowNode.Status.SUSPENDED, cp.getStatus());
        assertEquals("v", cp.getOutputSnapshot().get("k"));
        assertEquals("fix", ((Map<?, ?>) cp.getCarryoverSnapshot().get("taskFocus")).get("goal"));
        assertEquals("env-snap-2", cp.getEnvironmentSnapshotId());
        assertEquals("boom", cp.getErrorMessage());
        assertEquals(99L, cp.getDurationMs());
        assertEquals(1, cp.getRetryCount());
    }

    @Test
    void toSection_nullStatus_yieldsNullStatusString() {
        BoulderCheckpoint cp = new BoulderCheckpoint();
        cp.setWorkflowId("wf-3");
        cp.setNodeId("node-3");
        // status 未设置 → null

        BoulderSection section = BoulderSectionCapturer.toSection(cp);

        assertNull(section.status());
    }

    @Test
    void capture_returnsNullWhenNoCheckpointExists() {
        // 不存在的 workflowId/nodeId → loadCheckpoint 返回 empty → capture 返回 null
        BoulderSectionCapturer capturer = new BoulderSectionCapturer(
                "nonexistent-wf-" + System.nanoTime(), "no-node");
        assertNull(capturer.capture());
    }

    @Test
    void sectionType_isBoulder() {
        BoulderSectionCapturer capturer = new BoulderSectionCapturer("wf", "node");
        assertEquals("Boulder", capturer.sectionType());
    }
}
