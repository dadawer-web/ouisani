package com.ouisani.aios.core.importance;

import com.ouisani.aios.user.apps.omnifactory.WorkflowNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ImportanceBackwardPass} + {@link ImportanceStore} 单测。
 * <p>
 * 测试技巧：给每个节点唯一 role（role = instanceId），则 roleImportance[role] 直接等于
 * 该节点的 importance，无需暴露内部节点级 map。
 * <p>
 * 覆盖：钻石图等权分裂、链式守恒、全失败归零、单叶子全归路径、多成功叶子均分、
 * role 聚合、环路不死锁、空输入、持久化往返。
 */
class ImportanceBackwardPassTest {

    @TempDir
    Path tempDir;

    // ════════════════════════════════════════════════════════════════
    //  算法：钻石图 A→B,C→D（D SUCCESS）
    //  期望：D=1.0, B=0.5, C=0.5, A=1.0（守恒：每层和=1.0）
    // ════════════════════════════════════════════════════════════════

    @Test
    void diamondGraph_splitsImportanceEqually() {
        WorkflowNode a = node("A", "A");
        WorkflowNode b = node("B", "B");
        WorkflowNode c = node("C", "C");
        WorkflowNode d = node("D", "D");
        b.addDependency("A");
        c.addDependency("A");
        d.addDependency("B");
        d.addDependency("C");
        d.setStatus(WorkflowNode.Status.SUCCESS);

        ImportanceRecord rec = ImportanceBackwardPass.compute(List.of(a, b, c, d), "wf", "wf");

        Map<String, Double> imp = rec.roleImportance();
        assertEquals(1.0, imp.get("D"), 1e-6, "叶子 D 应得全部 1.0");
        assertEquals(0.5, imp.get("B"), 1e-6, "B 应均分 D 的 importance（D 有 2 上游）");
        assertEquals(0.5, imp.get("C"), 1e-6, "C 应均分 D 的 importance");
        assertEquals(1.0, imp.get("A"), 1e-6, "A 应聚合 B+C 的 0.5+0.5=1.0");
    }

    // ════════════════════════════════════════════════════════════════
    //  链式图 A→B→C（C SUCCESS）— 守恒：每层 importance=1.0
    // ════════════════════════════════════════════════════════════════

    @Test
    void chainGraph_propagatesLevelByLevel() {
        WorkflowNode a = node("A", "A");
        WorkflowNode b = node("B", "B");
        WorkflowNode c = node("C", "C");
        b.addDependency("A");
        c.addDependency("B");
        c.setStatus(WorkflowNode.Status.SUCCESS);

        ImportanceRecord rec = ImportanceBackwardPass.compute(List.of(a, b, c), "wf", "wf");

        Map<String, Double> imp = rec.roleImportance();
        assertEquals(1.0, imp.get("C"), 1e-6);
        assertEquals(1.0, imp.get("B"), 1e-6, "B 应继承 C 的 1.0（C 唯一上游 B）");
        assertEquals(1.0, imp.get("A"), 1e-6, "A 应继承 B 的 1.0");
    }

    // ════════════════════════════════════════════════════════════════
    //  全失败图 — 无 SUCCESS 叶子 → 全 0
    // ════════════════════════════════════════════════════════════════

    @Test
    void allFailedGraph_zeroImportance() {
        WorkflowNode a = node("A", "A");
        WorkflowNode b = node("B", "B");
        b.addDependency("A");
        b.setStatus(WorkflowNode.Status.FAILED);  // 叶子失败

        ImportanceRecord rec = ImportanceBackwardPass.compute(List.of(a, b), "wf", "wf");

        Map<String, Double> imp = rec.roleImportance();
        assertEquals(0.0, imp.get("B"), 1e-6, "失败叶子应得 0");
        assertEquals(0.0, imp.get("A"), 1e-6, "无 SUCCESS 叶子时上游也应得 0");
    }

    // ════════════════════════════════════════════════════════════════
    //  多成功叶子均分 — A→B,A→C, B,C 都 SUCCESS → 各 0.5, A=1.0
    // ════════════════════════════════════════════════════════════════

    @Test
    void multipleSuccessLeaves_splitEvenly() {
        WorkflowNode a = node("A", "A");
        WorkflowNode b = node("B", "B");
        WorkflowNode c = node("C", "C");
        b.addDependency("A");
        c.addDependency("A");
        b.setStatus(WorkflowNode.Status.SUCCESS);
        c.setStatus(WorkflowNode.Status.SUCCESS);

        ImportanceRecord rec = ImportanceBackwardPass.compute(List.of(a, b, c), "wf", "wf");

        Map<String, Double> imp = rec.roleImportance();
        assertEquals(0.5, imp.get("B"), 1e-6, "两成功叶子各 0.5");
        assertEquals(0.5, imp.get("C"), 1e-6);
        assertEquals(1.0, imp.get("A"), 1e-6, "A 聚合 0.5+0.5=1.0");
    }

    // ════════════════════════════════════════════════════════════════
    //  失败叶子不计入 — A→B(SUCCESS),A→C(FAILED) → B=1.0, C=0, A=1.0
    // ════════════════════════════════════════════════════════════════

    @Test
    void failedLeafGetsZero_successLeafGetsFullImportance() {
        WorkflowNode a = node("A", "A");
        WorkflowNode b = node("B", "B");
        WorkflowNode c = node("C", "C");
        b.addDependency("A");
        c.addDependency("A");
        b.setStatus(WorkflowNode.Status.SUCCESS);
        c.setStatus(WorkflowNode.Status.FAILED);

        ImportanceRecord rec = ImportanceBackwardPass.compute(List.of(a, b, c), "wf", "wf");

        Map<String, Double> imp = rec.roleImportance();
        assertEquals(1.0, imp.get("B"), 1e-6, "唯一成功叶子得 1.0");
        assertEquals(0.0, imp.get("C"), 1e-6, "失败叶子得 0");
        assertEquals(1.0, imp.get("A"), 1e-6, "A 仅从 B 获得 1.0");
    }

    // ════════════════════════════════════════════════════════════════
    //  role 聚合 — 同 role 多节点求和
    // ════════════════════════════════════════════════════════════════

    @Test
    void roleAggregation_sumsNodesWithSameRole() {
        // 两条独立链，role 都是 "Worker"
        WorkflowNode a1 = node("A1", "Worker");
        WorkflowNode b1 = node("B1", "Worker");
        b1.addDependency("A1");
        b1.setStatus(WorkflowNode.Status.SUCCESS);

        ImportanceRecord rec = ImportanceBackwardPass.compute(List.of(a1, b1), "wf", "wf");

        // B1=1.0, A1=1.0 → Worker 聚合 = 2.0
        assertEquals(2.0, rec.roleImportance().get("Worker"), 1e-6,
                "同 role 两节点各 1.0 应聚合为 2.0");
    }

    // ════════════════════════════════════════════════════════════════
    //  环路保护 — A→B, B→A 不死锁，全 0（无叶子）
    // ════════════════════════════════════════════════════════════════

    @Test
    void cycleGraph_doesNotDeadlock() {
        WorkflowNode a = node("A", "A");
        WorkflowNode b = node("B", "B");
        a.addDependency("B");
        b.addDependency("A");

        // 不应抛异常或死循环
        ImportanceRecord rec = ImportanceBackwardPass.compute(List.of(a, b), "wf", "wf");

        Map<String, Double> imp = rec.roleImportance();
        assertEquals(0.0, imp.get("A"), 1e-6, "环内节点 importance=0");
        assertEquals(0.0, imp.get("B"), 1e-6);
    }

    // ════════════════════════════════════════════════════════════════
    //  空输入 — 返回空记录
    // ════════════════════════════════════════════════════════════════

    @Test
    void emptyNodes_returnsEmptyRecord() {
        ImportanceRecord rec = ImportanceBackwardPass.compute(List.of(), "wf", "wf");
        assertNotNull(rec);
        assertTrue(rec.roleImportance().isEmpty(), "空节点应返回空 roleImportance");
    }

    // ════════════════════════════════════════════════════════════════
    //  持久化往返 — append → loadAll 一致
    // ════════════════════════════════════════════════════════════════

    @Test
    void persistenceRoundTrip_appendAndLoadAll() {
        Path file = tempDir.resolve("importance.jsonl");
        ImportanceRecord r1 = new ImportanceRecord("wf-1", "task-A", 1000L,
                Map.of("Coder", 0.5, "Reviewer", 0.3));
        ImportanceRecord r2 = new ImportanceRecord("wf-2", "task-B", 2000L,
                Map.of("Coder", 1.0));

        ImportanceStore.append(r1, file);
        ImportanceStore.append(r2, file);

        List<ImportanceRecord> loaded = ImportanceStore.loadAll(file);
        assertEquals(2, loaded.size(), "应读回 2 条记录");
        assertEquals("wf-1", loaded.get(0).workflowId());
        assertEquals("task-A", loaded.get(0).taskType());
        assertEquals(1000L, loaded.get(0).ts());
        assertEquals(0.5, loaded.get(0).roleImportance().get("Coder"), 1e-6);
        assertEquals(0.3, loaded.get(0).roleImportance().get("Reviewer"), 1e-6);
        assertEquals("wf-2", loaded.get(1).workflowId());
        assertEquals(1.0, loaded.get(1).roleImportance().get("Coder"), 1e-6);
    }

    // ════════════════════════════════════════════════════════════════
    //  持久化容错 — 坏行被跳过
    // ════════════════════════════════════════════════════════════════

    @Test
    void loadAll_skipsCorruptLines() throws Exception {
        Path file = tempDir.resolve("importance.jsonl");
        ImportanceRecord good = new ImportanceRecord("wf-good", "task", 1L, Map.of("R", 0.1));
        java.nio.file.Files.writeString(file, good.toJsonLine() + "\n"
                + "this is not json\n"
                + good.toJsonLine() + "\n",
                java.nio.file.StandardOpenOption.CREATE);

        List<ImportanceRecord> loaded = ImportanceStore.loadAll(file);
        assertEquals(2, loaded.size(), "坏行应被跳过，读回 2 条好记录");
    }

    // ════════════════════════════════════════════════════════════════
    //  辅助
    // ════════════════════════════════════════════════════════════════

    /** 构造节点：instanceId == role（测试技巧，让 roleImportance 直接反映节点级 importance） */
    private static WorkflowNode node(String instanceId, String role) {
        return new WorkflowNode(instanceId, role, instanceId);
    }
}
