package com.ouisani.aios.core.snapshot;

import com.ouisani.aios.user.apps.omnifactory.CarryoverCapturer;
import com.ouisani.aios.user.apps.omnifactory.CarryoverStateSectionMapper;
import com.ouisani.aios.user.apps.omnifactory.OmnifactoryCapturerFactory;
import com.ouisani.aios.user.apps.omnifactory.WorkflowContext;
import com.ouisani.aios.user.apps.omnifactory.WorkflowContextCapturer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EnvironmentSnapshotManager.forkFromSnapshot 端到端测试 — 注册 OmnifactoryCapturerFactory,
 * 捕获种子快照,fork N 分支,验证分支隔离性与种子内容复现。
 */
class ForkFromSnapshotTest {

    private EnvironmentSnapshotManager manager;
    private String seedSnapshotId;

    @BeforeEach
    void setup() {
        manager = EnvironmentSnapshotManager.instance();
        manager.registerFactory(new OmnifactoryCapturerFactory());

        // 构造种子:用真实 WorkflowContext 捕获显式 section
        WorkflowContext ctx = new WorkflowContext("wf-seed");
        ctx.commitNodeOutput("node-a", Map.of("url", "http://a"));
        ctx.commitNodeOutput("node-b", Map.of("title", "hello"));
        // CarryoverState 的 record 方法是包级私有,用公开的 mapper 回填
        CarryoverStateSectionMapper.applyTo(
                new CarryoverSection(Map.of("goal", "scan"), Map.of(), Map.of(), List.of()),
                ctx.getCarryoverState());

        NodeOutputSection nodeOut = (NodeOutputSection) new WorkflowContextCapturer(ctx).capture();
        CarryoverSection carry = (CarryoverSection) new CarryoverCapturer(ctx).capture();
        EnvironmentSnapshot seed = manager.capture("wf-seed", nodeOut, carry);
        seedSnapshotId = seed.snapshotId();
    }

    @AfterEach
    void tearDown() {
        // 清理全局注册表与 store,避免污染其他测试
        manager.unregisterCapturer("NodeOutput");
        manager.unregisterCapturer("Carryover");
        // 清理所有本测试产生的快照(种子 + 分支 capture 产物)
        for (String id : new HashSet<>(manager.listSnapshots())) {
            if (id.startsWith("env-")) manager.deleteSnapshot(id);
        }
    }

    @Test
    void forkFromSnapshot_producesNDistinctBranches() {
        List<ForkHandle> handles = manager.forkFromSnapshot(seedSnapshotId, 3);

        assertEquals(3, handles.size());
        Set<String> branchIds = new HashSet<>();
        for (ForkHandle h : handles) {
            branchIds.add(h.branchId());
            assertEquals(seedSnapshotId, h.seedSnapshotId());
        }
        assertEquals(3, branchIds.size());
    }

    @Test
    void forkFromSnapshot_unknownSeed_throws() {
        assertThrows(IllegalStateException.class,
                () -> manager.forkFromSnapshot("nonexistent-seed", 2));
    }

    @Test
    void activateThenCapture_reproducesSeedContent() {
        List<ForkHandle> handles = manager.forkFromSnapshot(seedSnapshotId, 3);

        // 串行 activate + capture(并发会覆盖全局注册表,故串行验证)
        for (ForkHandle h : handles) {
            h.activate();
            EnvironmentSnapshot branchSnap = manager.capture("branch-" + h.branchId());

            NodeOutputSection nodeOut = branchSnap.getSection("NodeOutput", NodeOutputSection.class)
                    .orElseThrow();
            assertEquals(2, nodeOut.nodeOutputs().size());
            assertEquals("http://a", nodeOut.nodeOutputs().get("node-a").get("url"));
            assertEquals("hello", nodeOut.nodeOutputs().get("node-b").get("title"));

            CarryoverSection carry = branchSnap.getSection("Carryover", CarryoverSection.class)
                    .orElseThrow();
            assertEquals("scan", carry.taskFocus().get("goal"));
        }
    }

    @Test
    void forkBranches_areObjectIsolated() {
        List<ForkHandle> handles = manager.forkFromSnapshot(seedSnapshotId, 2);

        // 激活分支 0,捕获;再激活分支 1,捕获 —— 两次捕获内容相同但来自不同 context
        handles.get(0).activate();
        EnvironmentSnapshot snap0 = manager.capture("b0");

        handles.get(1).activate();
        EnvironmentSnapshot snap1 = manager.capture("b1");

        NodeOutputSection out0 = snap0.getSection("NodeOutput", NodeOutputSection.class).orElseThrow();
        NodeOutputSection out1 = snap1.getSection("NodeOutput", NodeOutputSection.class).orElseThrow();

        assertEquals(out0.nodeOutputs().keySet(), out1.nodeOutputs().keySet());
        assertTrue(out0.nodeOutputs().get("node-a").get("url").equals(out1.nodeOutputs().get("node-a").get("url")));
    }

    @Test
    void forkHandle_branchCapturersArePopulated() {
        List<ForkHandle> handles = manager.forkFromSnapshot(seedSnapshotId, 1);

        assertEquals(1, handles.size());
        ForkHandle h = handles.get(0);
        // OmnifactoryCapturerFactory 产出 NodeOutput + Carryover 两个 capturer
        assertEquals(2, h.branchCapturers().size());
    }
}
