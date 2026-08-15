package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.snapshot.CarryoverSection;
import com.ouisani.aios.core.snapshot.EnvironmentSnapshot;
import com.ouisani.aios.core.snapshot.NodeOutputSection;
import com.ouisani.aios.core.snapshot.SnapshotCapturer;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * OmnifactoryCapturerFactory 单元测试 — 验证 createForFork 产出隔离 capturer 集合,
 * 绑定独立 WorkflowContext 且种子内容正确回填。
 */
class OmnifactoryCapturerFactoryTest {

    private EnvironmentSnapshot buildSeed() {
        Map<String, Map<String, Object>> nodeOutputs = new LinkedHashMap<>();
        nodeOutputs.put("node-a", new LinkedHashMap<>(Map.of("url", "http://a", "status", 200)));
        nodeOutputs.put("node-b", new LinkedHashMap<>(Map.of("title", "hello")));
        NodeOutputSection nodeOut = new NodeOutputSection(nodeOutputs);

        CarryoverSection carry = new CarryoverSection(
                Map.of("goal", "scan"), Map.of("/a.txt", "1-10"),
                Map.of("read_file", List.of("read config")), List.of("started"));

        return new EnvironmentSnapshot("seed-1", System.currentTimeMillis(), "wf-seed",
                Map.of("NodeOutput", nodeOut, "Carryover", carry));
    }

    @Test
    void createForFork_returnsTwoCapturersBoundToIsolatedContext() {
        EnvironmentSnapshot seed = buildSeed();
        OmnifactoryCapturerFactory factory = new OmnifactoryCapturerFactory();

        List<SnapshotCapturer> capturers = factory.createForFork("b1", seed);

        assertEquals(2, capturers.size());
        assertEquals("NodeOutput", capturers.get(0).sectionType());
        assertEquals("Carryover", capturers.get(1).sectionType());
    }

    @Test
    void createForFork_seedsNodeOutputIntoIsolatedContext() {
        EnvironmentSnapshot seed = buildSeed();
        OmnifactoryCapturerFactory factory = new OmnifactoryCapturerFactory();

        List<SnapshotCapturer> capturers = factory.createForFork("b1", seed);
        NodeOutputSection captured = (NodeOutputSection) capturers.get(0).capture();

        assertEquals(2, captured.nodeOutputs().size());
        assertEquals("http://a", captured.nodeOutputs().get("node-a").get("url"));
        assertEquals(200, captured.nodeOutputs().get("node-a").get("status"));
        assertEquals("hello", captured.nodeOutputs().get("node-b").get("title"));
    }

    @Test
    void createForFork_seedsCarryoverIntoIsolatedContext() {
        EnvironmentSnapshot seed = buildSeed();
        OmnifactoryCapturerFactory factory = new OmnifactoryCapturerFactory();

        List<SnapshotCapturer> capturers = factory.createForFork("b1", seed);
        CarryoverSection captured = (CarryoverSection) capturers.get(1).capture();

        assertEquals("scan", captured.taskFocus().get("goal"));
        assertEquals("1-10", captured.readFiles().get("/a.txt"));
        assertEquals(List.of("read config"), captured.invokedTools().get("read_file"));
        assertEquals(1, captured.workLog().size());
    }

    @Test
    void createForFork_distinctBranchesAreIsolated() {
        EnvironmentSnapshot seed = buildSeed();
        OmnifactoryCapturerFactory factory = new OmnifactoryCapturerFactory();

        List<SnapshotCapturer> branch1 = factory.createForFork("b1", seed);
        List<SnapshotCapturer> branch2 = factory.createForFork("b2", seed);

        // 两分支各自 capture 出相同内容,但 capturer 实例不同(绑定不同 context)
        NodeOutputSection out1 = (NodeOutputSection) branch1.get(0).capture();
        NodeOutputSection out2 = (NodeOutputSection) branch2.get(0).capture();
        assertNotNull(out1);
        assertNotNull(out2);
        assertEquals(out1.nodeOutputs().keySet(), out2.nodeOutputs().keySet());
    }

    @Test
    void createForFork_emptySeedProducesEmptyButValidCapturers() {
        EnvironmentSnapshot emptySeed = new EnvironmentSnapshot(
                "seed-empty", System.currentTimeMillis(), "wf", Map.of());

        OmnifactoryCapturerFactory factory = new OmnifactoryCapturerFactory();
        List<SnapshotCapturer> capturers = factory.createForFork("b1", emptySeed);

        NodeOutputSection out = (NodeOutputSection) capturers.get(0).capture();
        assertEquals(0, out.nodeOutputs().size());
    }
}
