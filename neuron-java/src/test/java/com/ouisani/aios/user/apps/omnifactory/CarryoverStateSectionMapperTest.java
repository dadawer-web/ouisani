package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.snapshot.CarryoverSection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CarryoverStateSectionMapper 单元测试 — 验证 CarryoverState ↔ CarryoverSection
 * 深拷贝往返、隔离性与空字段处理。
 */
class CarryoverStateSectionMapperTest {

    @Test
    void toSection_thenApplyTo_roundtripsAllFourFields() {
        WorkflowContext context = new WorkflowContext("wf-roundtrip");
        WorkflowContext.CarryoverState cs = context.getCarryoverState();
        cs.updateTaskFocus("current_goal", "scan files");
        cs.updateTaskFocus("next_step", "parse output");
        cs.recordFileRead("/etc/config.yml", "1-50");
        cs.recordFileRead("/var/log/app.log", "all");
        cs.recordToolInvocation("read_file", "read config");
        cs.recordToolInvocation("read_file", "read log");
        cs.recordToolInvocation("grep", "search pattern");
        cs.recordWorkLog("started scan");
        cs.recordWorkLog("finished scan");

        CarryoverSection section = CarryoverStateSectionMapper.toSection(cs);

        // roundtrip 到一个全新的空 state
        WorkflowContext target = new WorkflowContext("wf-target");
        WorkflowContext.CarryoverState targetCs = target.getCarryoverState();
        CarryoverStateSectionMapper.applyTo(section, targetCs);

        assertEquals(cs.getTaskFocus(), targetCs.getTaskFocus());
        assertEquals(cs.getReadFiles(), targetCs.getReadFiles());
        assertEquals(cs.getInvokedTools(), targetCs.getInvokedTools());
        assertEquals(cs.getWorkLog(), targetCs.getWorkLog());
    }

    @Test
    void toSection_isolatesFromLiveStateMutation() {
        WorkflowContext context = new WorkflowContext("wf-iso");
        WorkflowContext.CarryoverState cs = context.getCarryoverState();
        cs.updateTaskFocus("goal", "v1");
        cs.recordFileRead("/a.txt", "1-10");
        cs.recordToolInvocation("tool", "first");
        cs.recordWorkLog("entry-1");

        CarryoverSection section = CarryoverStateSectionMapper.toSection(cs);

        // 捕获后修改运行态,section 不应受影响(深拷贝隔离)
        cs.updateTaskFocus("goal", "v2");
        cs.recordFileRead("/b.txt", "all");
        cs.recordToolInvocation("tool", "second");
        cs.recordWorkLog("entry-2");

        assertEquals("v1", section.taskFocus().get("goal"));
        assertEquals("1-10", section.readFiles().get("/a.txt"));
        assertEquals(1, section.readFiles().size());
        assertEquals(List.of("first"), section.invokedTools().get("tool"));
        assertEquals(1, section.workLog().size());
        assertTrue(section.workLog().get(0).endsWith("entry-1"));
    }

    @Test
    void toSection_andApplyTo_useDistinctCollectionInstances() {
        WorkflowContext context = new WorkflowContext("wf-distinct");
        WorkflowContext.CarryoverState cs = context.getCarryoverState();
        cs.updateTaskFocus("k", "v");
        cs.recordToolInvocation("tool", "summary");

        CarryoverSection section = CarryoverStateSectionMapper.toSection(cs);

        // section 内部集合与原 state 集合不是同一引用
        assertNotSame(cs.getTaskFocus(), section.taskFocus());
        assertNotSame(cs.getInvokedTools(), section.invokedTools());
        assertNotSame(cs.getInvokedTools().get("tool"), section.invokedTools().get("tool"));
    }

    @Test
    void applyTo_clearsExistingBeforeFill() {
        WorkflowContext context = new WorkflowContext("wf-clear");
        WorkflowContext.CarryoverState cs = context.getCarryoverState();
        cs.updateTaskFocus("old", "stale");
        cs.recordFileRead("/old.txt", "all");
        cs.recordWorkLog("old-entry");

        CarryoverSection fresh = new CarryoverSection(
                Map.of("new", "fresh"),
                Map.of(),
                Map.of(),
                List.of());

        CarryoverStateSectionMapper.applyTo(fresh, cs);

        // 旧数据应被清空,仅剩 fresh 内容
        assertEquals(1, cs.getTaskFocus().size());
        assertEquals("fresh", cs.getTaskFocus().get("new"));
        assertTrue(cs.getReadFiles().isEmpty());
        assertTrue(cs.getWorkLog().isEmpty());
    }

    @Test
    void toSection_handlesEmptyState() {
        WorkflowContext context = new WorkflowContext("wf-empty");
        WorkflowContext.CarryoverState cs = context.getCarryoverState();

        CarryoverSection section = CarryoverStateSectionMapper.toSection(cs);

        assertTrue(section.taskFocus().isEmpty());
        assertTrue(section.readFiles().isEmpty());
        assertTrue(section.invokedTools().isEmpty());
        assertTrue(section.workLog().isEmpty());
    }

    @Test
    void toMap_thenFromMap_roundtripsAllFourFields() {
        WorkflowContext context = new WorkflowContext("wf-tomap");
        WorkflowContext.CarryoverState cs = context.getCarryoverState();
        cs.updateTaskFocus("current_goal", "scan files");
        cs.recordFileRead("/etc/config.yml", "1-50");
        cs.recordToolInvocation("read_file", "read config");
        cs.recordWorkLog("started scan");

        Map<String, Object> flat = CarryoverStateSectionMapper.toMap(cs);

        WorkflowContext target = new WorkflowContext("wf-tomap-target");
        WorkflowContext.CarryoverState targetCs = target.getCarryoverState();
        CarryoverStateSectionMapper.fromMap(flat, targetCs);

        assertEquals(cs.getTaskFocus(), targetCs.getTaskFocus());
        assertEquals(cs.getReadFiles(), targetCs.getReadFiles());
        assertEquals(cs.getInvokedTools(), targetCs.getInvokedTools());
        assertEquals(cs.getWorkLog(), targetCs.getWorkLog());
    }

    @Test
    void fromMap_nullMap_clearsStateOnly() {
        WorkflowContext context = new WorkflowContext("wf-nullmap");
        WorkflowContext.CarryoverState cs = context.getCarryoverState();
        cs.updateTaskFocus("old", "stale");
        cs.recordWorkLog("old-entry");

        CarryoverStateSectionMapper.fromMap(null, cs);

        assertTrue(cs.getTaskFocus().isEmpty());
        assertTrue(cs.getWorkLog().isEmpty());
    }

    @Test
    void fromMap_typeUnsafeInput_handlesGracefully() {
        WorkflowContext context = new WorkflowContext("wf-unsafe");
        WorkflowContext.CarryoverState cs = context.getCarryoverState();

        Map<String, Object> bad = new java.util.HashMap<>();
        bad.put("taskFocus", "not-a-map");
        bad.put("readFiles", 42);
        bad.put("invokedTools", Map.of("tool", "not-a-list"));
        bad.put("workLog", "not-a-list");

        CarryoverStateSectionMapper.fromMap(bad, cs);

        assertTrue(cs.getTaskFocus().isEmpty());
        assertTrue(cs.getReadFiles().isEmpty());
        assertTrue(cs.getInvokedTools().isEmpty());
        assertTrue(cs.getWorkLog().isEmpty());
    }
}
