package com.ouisani.aios.core.task;

import com.ouisani.aios.core.task.TaskAssignmentStrategy.AgentInfo;
import com.ouisani.aios.core.task.TaskAssignmentStrategy.AssignableTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TaskAssignmentStrategy 单元测试 — 验证四种分配策略的正确性。
 */
class TaskAssignmentStrategyTest {

    private static final List<AgentInfo> AGENTS = List.of(
            new AgentInfo("alpha", "代码审查", "gpt-4o"),
            new AgentInfo("beta", "数据分析", "gpt-4o-mini"),
            new AgentInfo("gamma", "文档编写", "gpt-4o")
    );

    private static AssignableTask task(String id, String title, String desc, String... deps) {
        return new AssignableTask(id, title, desc, "pending", null, List.of(deps));
    }

    private static AssignableTask inProgress(String id, String assignee) {
        return new AssignableTask(id, "running", "", "in_progress", assignee, List.of());
    }

    // ════════════════════════════════════════════════════════════════
    //  公共行为
    // ════════════════════════════════════════════════════════════════

    @Test
    void emptyAgents_returnsEmptyMap() {
        for (String name : new String[]{"round-robin", "least-busy", "capability-match", "dependency-first"}) {
            TaskAssignmentStrategy s = TaskAssignmentStrategy.of(name);
            assertTrue(s.assign(List.of(task("t1", "task", "desc")), List.of()).isEmpty(),
                    name + " should return empty for no agents");
        }
    }

    @Test
    void noPendingTasks_returnsEmptyMap() {
        TaskAssignmentStrategy s = TaskAssignmentStrategy.of("round-robin");
        List<AssignableTask> done = List.of(
                new AssignableTask("t1", "done", "", "done", "alpha", List.of()));
        assertTrue(s.assign(done, AGENTS).isEmpty());
    }

    @Test
    void alreadyAssignedSkipped() {
        TaskAssignmentStrategy s = TaskAssignmentStrategy.of("round-robin");
        List<AssignableTask> tasks = List.of(
                new AssignableTask("t1", "pending assigned", "", "pending", "alpha", List.of()),
                task("t2", "pending unassigned", "desc"));
        Map<String, String> result = s.assign(tasks, AGENTS);
        assertFalse(result.containsKey("t1"));
        assertTrue(result.containsKey("t2"));
    }

    // ════════════════════════════════════════════════════════════════
    //  Round-Robin
    // ════════════════════════════════════════════════════════════════

    @Test
    void roundRobin_distributesEvenly() {
        TaskAssignmentStrategy s = TaskAssignmentStrategy.of("round-robin");
        List<AssignableTask> tasks = List.of(
                task("t1", "task 1", "desc"),
                task("t2", "task 2", "desc"),
                task("t3", "task 3", "desc"));
        Map<String, String> result = s.assign(tasks, AGENTS);

        assertEquals("alpha", result.get("t1"));
        assertEquals("beta", result.get("t2"));
        assertEquals("gamma", result.get("t3"));
    }

    @Test
    void roundRobin_wrapsAround() {
        TaskAssignmentStrategy s = TaskAssignmentStrategy.of("round-robin");
        List<AssignableTask> tasks = List.of(
                task("t1", "a", ""), task("t2", "b", ""),
                task("t3", "c", ""), task("t4", "d", ""));
        Map<String, String> result = s.assign(tasks, AGENTS);

        // t4 应该循环回到 alpha
        assertEquals("alpha", result.get("t4"));
    }

    // ════════════════════════════════════════════════════════════════
    //  Least-Busy
    // ════════════════════════════════════════════════════════════════

    @Test
    void leastBusy_assignsToIdleAgent() {
        TaskAssignmentStrategy s = TaskAssignmentStrategy.of("least-busy");
        // alpha 有 2 个 in_progress, beta 有 1, gamma 有 0
        List<AssignableTask> tasks = List.of(
                inProgress("r1", "alpha"), inProgress("r2", "alpha"),
                inProgress("r3", "beta"),
                task("t1", "new task", "desc"));
        Map<String, String> result = s.assign(tasks, AGENTS);

        // gamma 最闲
        assertEquals("gamma", result.get("t1"));
    }

    @Test
    void leastBusy_updatesSimulatedLoad() {
        TaskAssignmentStrategy s = TaskAssignmentStrategy.of("least-busy");
        // 所有 agent 负载为 0
        List<AssignableTask> tasks = List.of(
                task("t1", "first", ""),
                task("t2", "second", ""),
                task("t3", "third", ""));
        Map<String, String> result = s.assign(tasks, AGENTS);

        // 第一次全 0,选 alpha;之后 alpha 负载 1,选 beta;之后 beta 负载 1,选 gamma
        assertEquals("alpha", result.get("t1"));
        assertEquals("beta", result.get("t2"));
        assertEquals("gamma", result.get("t3"));
    }

    // ════════════════════════════════════════════════════════════════
    //  Capability-Match
    // ════════════════════════════════════════════════════════════════

    @Test
    void capabilityMatch_assignsToBestRole() {
        TaskAssignmentStrategy s = TaskAssignmentStrategy.of("capability-match");
        List<AssignableTask> tasks = List.of(
                task("t1", "审查代码变更", "review code changes"),
                task("t2", "分析数据", "analyze data"));
        Map<String, String> result = s.assign(tasks, AGENTS);

        // t1 "代码审查" 应该匹配 alpha (role=代码审查)
        assertEquals("alpha", result.get("t1"));
        // t2 "数据分析" 应该匹配 beta (role=数据分析)
        assertEquals("beta", result.get("t2"));
    }

    @Test
    void capabilityMatch_noMatch_fallsBackToFirstAgent() {
        TaskAssignmentStrategy s = TaskAssignmentStrategy.of("capability-match");
        List<AssignableTask> tasks = List.of(
                task("t1", "unknown task", "completely unrelated"));
        Map<String, String> result = s.assign(tasks, AGENTS);

        // 无匹配时 fallback 到第一个
        assertEquals("alpha", result.get("t1"));
    }

    // ════════════════════════════════════════════════════════════════
    //  Dependency-First
    // ════════════════════════════════════════════════════════════════

    @Test
    void dependencyFirst_prioritizesCriticalPath() {
        TaskAssignmentStrategy s = TaskAssignmentStrategy.of("dependency-first");
        // DAG: t1 → t2 → t3, t1 → t4
        // t1 阻塞 t2 和 t4 (传递性: t1 阻塞 t2, t3, t4 = 3)
        // t2 阻塞 t3 = 1
        // t3 阻塞 0
        // t4 阻塞 0
        List<AssignableTask> tasks = List.of(
                task("t1", "fetch data", "desc"),
                task("t2", "process", "desc", "t1"),
                task("t3", "report", "desc", "t2"),
                task("t4", "validate", "desc", "t1"));
        Map<String, String> result = s.assign(tasks, AGENTS);

        // 所有 4 个任务都应被分配
        assertEquals(4, result.size());
        // t1 是关键路径起点,应该被分配
        assertTrue(result.containsKey("t1"));
    }

    @Test
    void dependencyFirst_countBlockedDependents() {
        // DAG: A → B → C, A → D
        // A 阻塞 B, C, D (3 dependents)
        // B 阻塞 C (1 dependent)
        // C 阻塞 0
        // D 阻塞 0
        List<AssignableTask> tasks = List.of(
                task("A", "root", ""),
                task("B", "mid", "", "A"),
                task("C", "leaf", "", "B"),
                task("D", "branch", "", "A"));

        assertEquals(3, DependencyFirstStrategy.countBlockedDependents("A", tasks));
        assertEquals(1, DependencyFirstStrategy.countBlockedDependents("B", tasks));
        assertEquals(0, DependencyFirstStrategy.countBlockedDependents("C", tasks));
        assertEquals(0, DependencyFirstStrategy.countBlockedDependents("D", tasks));
    }

    @Test
    void dependencyFirst_diamondDag() {
        // 菱形 DAG: A → B, A → C, B → D, C → D
        // A 阻塞 B, C, D (3)
        // B 阻塞 D (1)
        // C 阻塞 D (1)
        // D 阻塞 0
        List<AssignableTask> tasks = List.of(
                task("A", "start", ""),
                task("B", "left", "", "A"),
                task("C", "right", "", "A"),
                task("D", "end", "", "B", "C"));

        assertEquals(3, DependencyFirstStrategy.countBlockedDependents("A", tasks));
        assertEquals(1, DependencyFirstStrategy.countBlockedDependents("B", tasks));
        assertEquals(1, DependencyFirstStrategy.countBlockedDependents("C", tasks));
        assertEquals(0, DependencyFirstStrategy.countBlockedDependents("D", tasks));
    }

    @Test
    void dependencyFirst_noDeps_treatedEqual() {
        TaskAssignmentStrategy s = TaskAssignmentStrategy.of("dependency-first");
        // 无依赖的任务,dependents 数全为 0,等价于 round-robin
        List<AssignableTask> tasks = List.of(
                task("t1", "independent 1", ""),
                task("t2", "independent 2", ""),
                task("t3", "independent 3", ""));
        Map<String, String> result = s.assign(tasks, AGENTS);

        assertEquals(3, result.size());
        // 无依赖时应该均匀分配
        assertEquals("alpha", result.get("t1"));
        assertEquals("beta", result.get("t2"));
        assertEquals("gamma", result.get("t3"));
    }

    // ════════════════════════════════════════════════════════════════
    //  工厂方法
    // ════════════════════════════════════════════════════════════════

    @Test
    void of_createsCorrectStrategy() {
        assertInstanceOf(RoundRobinStrategy.class, TaskAssignmentStrategy.of("round-robin"));
        assertInstanceOf(LeastBusyStrategy.class, TaskAssignmentStrategy.of("least-busy"));
        assertInstanceOf(CapabilityMatchStrategy.class, TaskAssignmentStrategy.of("capability-match"));
        assertInstanceOf(DependencyFirstStrategy.class, TaskAssignmentStrategy.of("dependency-first"));
    }

    @Test
    void of_unknownStrategy_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> TaskAssignmentStrategy.of("unknown"));
    }
}
