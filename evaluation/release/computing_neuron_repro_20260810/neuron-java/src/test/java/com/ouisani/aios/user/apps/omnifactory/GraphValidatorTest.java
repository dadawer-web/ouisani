package com.ouisani.aios.user.apps.omnifactory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * GraphValidator 静态图分析单元测试 — 验证不可达节点检测 / 全环枚举 / 悬空引用 / 孤立节点。
 * <p>
 * 借鉴 mobilegym navigation.declaration.ts 既驱动运行时也驱动静态分析:反向利用 DAG 拓扑,
 * 在 LLM 编译产物落盘前以纯图论方式检测合法性。本测试覆盖 10 类典型 fixture 拓扑。
 * <p>
 * 节点构造用 3 参 {@link WorkflowNode#WorkflowNode(String, String, String)} + {@link WorkflowNode#addDependency}。
 */
class GraphValidatorTest {

    private final GraphValidator validator = GraphValidator.getInstance();

    // ── Fixture 1: 干净 DAG,应通过 ──
    @Test
    void cleanDag_shouldPass() {
        WorkflowNode a = new WorkflowNode("a", "源", "omni");
        WorkflowNode b = new WorkflowNode("b", "中", "omni");
        WorkflowNode c = new WorkflowNode("c", "汇", "omni");
        WorkflowNode d = new WorkflowNode("d", "分支", "omni");
        b.addDependency("a");
        c.addDependency("b");
        d.addDependency("a");

        assertDoesNotThrow(() -> validator.validate(List.of(a, b, c, d)));
    }

    // ── Fixture 2: 单环 a↔b,应报环内 a,b ──
    @Test
    void singleCycle_shouldReportBothCycleNodes() {
        WorkflowNode a = new WorkflowNode("a", "n", "omni");
        WorkflowNode b = new WorkflowNode("b", "n", "omni");
        a.addDependency("b");
        b.addDependency("a");

        TopologyCompileException ex = assertThrows(TopologyCompileException.class,
                () -> validator.validate(List.of(a, b)));
        assertTrue(containsAllCycleNodes(ex), "errors 应含环内节点枚举");
        assertErrorContains(ex, "a");
        assertErrorContains(ex, "b");
    }

    // ── Fixture 3: 多环 a↔b + c↔d,Kahn 全枚举应报全部 4 节点 ──
    @Test
    void multiCycle_shouldEnumerateAllCycleNodes() {
        WorkflowNode a = new WorkflowNode("a", "n", "omni");
        WorkflowNode b = new WorkflowNode("b", "n", "omni");
        WorkflowNode c = new WorkflowNode("c", "n", "omni");
        WorkflowNode d = new WorkflowNode("d", "n", "omni");
        a.addDependency("b");
        b.addDependency("a");
        c.addDependency("d");
        d.addDependency("c");

        TopologyCompileException ex = assertThrows(TopologyCompileException.class,
                () -> validator.validate(List.of(a, b, c, d)));
        // 全枚举:旧 DFS 首路径只能报一个环,新版 Kahn 应报全部 4 个
        assertErrorContains(ex, "a");
        assertErrorContains(ex, "b");
        assertErrorContains(ex, "c");
        assertErrorContains(ex, "d");
    }

    // ── Fixture 4: 悬空引用 a→[missing],应报缺失依赖 ──
    @Test
    void danglingRef_shouldReportMissingDependency() {
        WorkflowNode a = new WorkflowNode("a", "n", "omni");
        a.addDependency("missing");

        TopologyCompileException ex = assertThrows(TopologyCompileException.class,
                () -> validator.validate(List.of(a)));
        assertErrorContains(ex, "missing");
        assertErrorContains(ex, "悬空");
    }

    // ── Fixture 5: 环下游受害者 a↔b→c,c 应归入 cyclicSet 不被双重报为不可达 ──
    @Test
    void downstreamOfCycle_shouldNotDoubleReportCycleDownstream() {
        WorkflowNode a = new WorkflowNode("a", "n", "omni");
        WorkflowNode b = new WorkflowNode("b", "n", "omni");
        WorkflowNode c = new WorkflowNode("c", "n", "omni");
        a.addDependency("b");
        b.addDependency("a");
        c.addDependency("b");

        TopologyCompileException ex = assertThrows(TopologyCompileException.class,
                () -> validator.validate(List.of(a, b, c)));
        // a,b,c 均在 cyclicSet(Kahn 无法拓扑排序到),由检查 2 统一报
        assertErrorContains(ex, "a");
        assertErrorContains(ex, "b");
        assertErrorContains(ex, "c");
        // c 不应被检查 5 重复报为"不可达"
        assertNoUnreachableFor(ex, "c");
    }

    // ── Fixture 6: 悬空引用下游受害者 — w 依赖 y,y 上游悬空,w 应被检查 5 报为不可达 ──
    @Test
    void downstreamOfDangling_shouldReportVictimAsUnreachable() {
        WorkflowNode source = new WorkflowNode("source", "源", "omni");
        WorkflowNode y = new WorkflowNode("y", "悬空节点", "omni");
        WorkflowNode w = new WorkflowNode("w", "下游受害者", "omni");
        y.addDependency("missingZ");  // y 上游悬空(检查 1 报 y)
        w.addDependency("y");          // w 的上游 y 存在但不可达 → w 是下游受害者

        TopologyCompileException ex = assertThrows(TopologyCompileException.class,
                () -> validator.validate(List.of(source, y, w)));
        // 检查 1: y 的悬空引用
        assertErrorContains(ex, "missingZ");
        // 检查 5: w 作为下游受害者被报为不可达
        assertUnreachableFor(ex, "w");
    }

    // ── Fixture 7: 两个独立合法子图 a→b + c→d,都源点可达,应通过 ──
    @Test
    void disconnectedValidDag_shouldPass() {
        WorkflowNode a = new WorkflowNode("a", "源1", "omni");
        WorkflowNode b = new WorkflowNode("b", "汇1", "omni");
        WorkflowNode c = new WorkflowNode("c", "源2", "omni");
        WorkflowNode d = new WorkflowNode("d", "汇2", "omni");
        b.addDependency("a");
        d.addDependency("c");

        assertDoesNotThrow(() -> validator.validate(List.of(a, b, c, d)));
    }

    // ── Fixture 8: 孤立节点 c(无入无出),应被检查 4 报告 ──
    @Test
    void isolatedNode_shouldReportIsolated() {
        WorkflowNode a = new WorkflowNode("a", "源", "omni");
        WorkflowNode b = new WorkflowNode("b", "汇", "omni");
        WorkflowNode c = new WorkflowNode("c", "孤岛", "omni");
        b.addDependency("a");
        // c 无依赖,无人依赖 c

        TopologyCompileException ex = assertThrows(TopologyCompileException.class,
                () -> validator.validate(List.of(a, b, c)));
        assertErrorContains(ex, "c");
        assertErrorContains(ex, "孤立");
    }

    // ── Fixture 9: 自环 a→a,应报环内 a ──
    @Test
    void selfLoop_shouldReportCycle() {
        WorkflowNode a = new WorkflowNode("a", "n", "omni");
        a.addDependency("a");

        TopologyCompileException ex = assertThrows(TopologyCompileException.class,
                () -> validator.validate(List.of(a)));
        assertErrorContains(ex, "a");
        assertTrue(containsAllCycleNodes(ex), "应识别为循环依赖");
    }

    // ── Fixture 10: 无源点全环 a↔b + 独立合法 s→t,环节点不应被双重报为不可达 ──
    @Test
    void noSourceAllCycle_shouldNotDoubleReportAsUnreachable() {
        WorkflowNode a = new WorkflowNode("a", "n", "omni");
        WorkflowNode b = new WorkflowNode("b", "n", "omni");
        WorkflowNode s = new WorkflowNode("s", "源", "omni");
        WorkflowNode t = new WorkflowNode("t", "汇", "omni");
        a.addDependency("b");
        b.addDependency("a");
        t.addDependency("s");

        TopologyCompileException ex = assertThrows(TopologyCompileException.class,
                () -> validator.validate(List.of(a, b, s, t)));
        // 环内 a,b 由检查 2 报告
        assertErrorContains(ex, "a");
        assertErrorContains(ex, "b");
        // a,b 不应被检查 5 重复报为不可达(源点集虽含 s,但 a,b 已在 cyclicSet 中扣除)
        assertNoUnreachableFor(ex, "a");
        assertNoUnreachableFor(ex, "b");
        // s,t 合法,不应出现在错误中
        assertErrorNotContains(ex, "s");
        assertErrorNotContains(ex, "t");
    }

    // ════════════════════════════════════════════════════════════════
    //  断言辅助
    // ════════════════════════════════════════════════════════════════

    private void assertErrorContains(TopologyCompileException ex, String fragment) {
        assertTrue(
                ex.validationErrors().stream().anyMatch(e -> e.contains(fragment)),
                "expected error containing '" + fragment + "', but errors were: " + ex.validationErrors()
        );
    }

    private void assertErrorNotContains(TopologyCompileException ex, String fragment) {
        assertTrue(
                ex.validationErrors().stream().noneMatch(e -> e.contains(fragment)),
                "expected no error containing '" + fragment + "', but errors were: " + ex.validationErrors()
        );
    }

    private void assertUnreachableFor(TopologyCompileException ex, String nodeId) {
        boolean unreachableReported = ex.validationErrors().stream()
                .anyMatch(e -> e.contains(nodeId) && e.contains("不可达"));
        assertTrue(unreachableReported,
                "expected unreachable error for '" + nodeId + "', but errors were: " + ex.validationErrors());
    }

    private void assertNoUnreachableFor(TopologyCompileException ex, String nodeId) {
        boolean unreachableReported = ex.validationErrors().stream()
                .anyMatch(e -> e.contains(nodeId) && e.contains("不可达"));
        assertTrue(!unreachableReported,
                "expected NO unreachable error for '" + nodeId + "' (should be in cyclicSet), but errors were: " + ex.validationErrors());
    }

    private boolean containsAllCycleNodes(TopologyCompileException ex) {
        return ex.validationErrors().stream().anyMatch(e -> e.contains("环内节点"));
    }
}
