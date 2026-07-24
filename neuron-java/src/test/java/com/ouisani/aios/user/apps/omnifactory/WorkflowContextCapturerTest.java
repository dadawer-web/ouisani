package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.snapshot.NodeOutputSection;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorkflowContextCapturer 单元测试 — 验证 NodeOutputSection 的 capture(深拷贝)
 * 与 restore(commitNodeOutput 回填)往返,以及与 CarryoverState 的隔离。
 */
class WorkflowContextCapturerTest {

    @Test
    void capture_deepCopiesAllNodeOutputs() {
        WorkflowContext context = new WorkflowContext("wf-capture");
        context.commitNodeOutput("node-a", Map.of("url", "http://a", "status", 200));
        context.commitNodeOutput("node-b", Map.of("title", "hello"));
        context.commitNodeOutput("node-c", Map.of("items", 3));

        WorkflowContextCapturer capturer = new WorkflowContextCapturer(context);
        NodeOutputSection section = (NodeOutputSection) capturer.capture();

        assertEquals(3, section.nodeOutputs().size());
        assertEquals("http://a", section.nodeOutputs().get("node-a").get("url"));
        assertEquals(200, section.nodeOutputs().get("node-a").get("status"));
        assertEquals("hello", section.nodeOutputs().get("node-b").get("title"));
        assertEquals(3, section.nodeOutputs().get("node-c").get("items"));
        assertEquals("NodeOutput", section.sectionType());
    }

    @Test
    void capture_isolatedFromLiveContextMutation() {
        WorkflowContext context = new WorkflowContext("wf-iso");
        context.commitNodeOutput("node-a", new LinkedHashMap<>(Map.of("v", 1)));

        WorkflowContextCapturer capturer = new WorkflowContextCapturer(context);
        NodeOutputSection section = (NodeOutputSection) capturer.capture();

        // 捕获后修改运行态,section 不应受影响(深拷贝隔离)
        context.commitNodeOutput("node-a", Map.of("v", 999));
        assertEquals(1, section.nodeOutputs().get("node-a").get("v"));
    }

    @Test
    void restore_replaysNodeOutputsToTargetContext() {
        WorkflowContext source = new WorkflowContext("wf-src");
        source.commitNodeOutput("node-a", Map.of("url", "http://a"));
        source.commitNodeOutput("node-b", Map.of("title", "hello"));

        NodeOutputSection section = (NodeOutputSection) new WorkflowContextCapturer(source).capture();

        WorkflowContext target = new WorkflowContext("wf-dst");
        new WorkflowContextCapturer(target).restore(section);

        assertEquals("http://a", target.getNodeOutput("node-a").get("url"));
        assertEquals("hello", target.getNodeOutput("node-b").get("title"));
        assertTrue(target.getNodeIds().contains("node-a"));
        assertTrue(target.getNodeIds().contains("node-b"));
    }

    @Test
    void restore_ignoresNonNodeOutputSection() {
        WorkflowContext context = new WorkflowContext("wf-ignore");
        // 传入错误类型 section,restore 应静默跳过不抛异常
        com.ouisani.aios.core.snapshot.CarryoverSection wrong =
                new com.ouisani.aios.core.snapshot.CarryoverSection(
                        Map.of(), Map.of(), Map.of(), java.util.List.of());
        new WorkflowContextCapturer(context).restore(wrong);
        assertTrue(context.getNodeIds().isEmpty());
    }
}
