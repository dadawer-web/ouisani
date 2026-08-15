package com.ouisani.aios.user.tools;

import com.ouisani.aios.core.importance.ImportanceRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RoleSubsetOptimizer#optimize} 离线聚合算法单测。
 * <p>
 * 不走 main（避免 System.exit 干扰），直接调 {@link RoleSubsetOptimizer#optimize}。
 * 测试 YAML 输出格式与统计聚合正确性。
 */
class RoleSubsetOptimizerTest {

    // ════════════════════════════════════════════════════════════════
    //  单 taskType 多次运行 — 算平均后降序选 top-K
    // ════════════════════════════════════════════════════════════════

    @Test
    void singleTaskType_averagesAndSortsDescending() {
        // 任务 "crypto" 跑 2 次：
        //   run1: coder=0.6, reviewer=0.2, tester=0.2
        //   run2: coder=0.4, reviewer=0.5, tester=0.1
        // 平均: coder=0.5, reviewer=0.35, tester=0.15 → 顺序 coder, reviewer, tester
        ImportanceRecord r1 = new ImportanceRecord("wf-1", "crypto", 1L,
                Map.of("coder", 0.6, "reviewer", 0.2, "tester", 0.2));
        ImportanceRecord r2 = new ImportanceRecord("wf-2", "crypto", 2L,
                Map.of("coder", 0.4, "reviewer", 0.5, "tester", 0.1));

        Map<String, String> result = RoleSubsetOptimizer.optimize(List.of(r1, r2), 3, 1);
        String yaml = result.get("body");

        assertNotNull(yaml);
        assertTrue(yaml.contains("crypto:"), "应输出 crypto taskType 区块");
        // 验证降序：coder 在 reviewer 之前
        int coderIdx = yaml.indexOf("- coder");
        int reviewerIdx = yaml.indexOf("- reviewer");
        int testerIdx = yaml.indexOf("- tester");
        assertTrue(coderIdx > 0 && reviewerIdx > coderIdx && testerIdx > reviewerIdx,
                "角色应按 importance 降序排列：coder < reviewer < tester（位置）");
    }

    // ════════════════════════════════════════════════════════════════
    //  topK 截断 — 5 个 role 但 topK=2 只保留 2 个
    // ════════════════════════════════════════════════════════════════

    @Test
    void topK_truncatesBeyondK() {
        ImportanceRecord r1 = new ImportanceRecord("wf-1", "task", 1L,
                Map.of("r1", 0.5, "r2", 0.4, "r3", 0.3, "r4", 0.2, "r5", 0.1));

        Map<String, String> result = RoleSubsetOptimizer.optimize(List.of(r1), 2, 1);
        String yaml = result.get("body");

        assertTrue(yaml.contains("- r1"), "最高应保留");
        assertTrue(yaml.contains("- r2"), "第二应保留");
        assertFalse(yaml.contains("- r3"), "topK=2 应截断 r3");
        assertFalse(yaml.contains("- r4"));
        assertFalse(yaml.contains("- r5"));
    }

    // ════════════════════════════════════════════════════════════════
    //  minRuns 过滤 — 运行数 < 阈值的 taskType 被跳过
    // ════════════════════════════════════════════════════════════════

    @Test
    void minRuns_filtersTaskTypeWithInsufficientRuns() {
        // task-A 跑 1 次，task-B 跑 3 次，minRuns=2 → 只有 task-B 进推荐表
        ImportanceRecord a = new ImportanceRecord("wf-a", "task-A", 1L, Map.of("coder", 1.0));
        ImportanceRecord b1 = new ImportanceRecord("wf-b1", "task-B", 2L, Map.of("coder", 1.0));
        ImportanceRecord b2 = new ImportanceRecord("wf-b2", "task-B", 3L, Map.of("coder", 1.0));
        ImportanceRecord b3 = new ImportanceRecord("wf-b3", "task-B", 4L, Map.of("coder", 1.0));

        Map<String, String> result = RoleSubsetOptimizer.optimize(List.of(a, b1, b2, b3), 3, 2);
        String yaml = result.get("body");
        String stats = result.get("stats");

        assertFalse(yaml.contains("task-A:"), "task-A 仅 1 次运行应被 minRuns=2 过滤");
        assertTrue(yaml.contains("task-B:"), "task-B 3 次运行应保留");
        assertTrue(stats.contains("SKIP"), "stats 应显示 task-A 被跳过");
    }

    // ════════════════════════════════════════════════════════════════
    //  多 taskType 分别排序
    // ════════════════════════════════════════════════════════════════

    @Test
    void multipleTaskTypes_sortedIndependently() {
        // task-X: coder 最强
        ImportanceRecord x1 = new ImportanceRecord("wf-x1", "task-X", 1L,
                Map.of("coder", 0.9, "reviewer", 0.1));
        // task-Y: reviewer 最强
        ImportanceRecord y1 = new ImportanceRecord("wf-y1", "task-Y", 2L,
                Map.of("coder", 0.2, "reviewer", 0.8));

        Map<String, String> result = RoleSubsetOptimizer.optimize(List.of(x1, y1), 1, 1);
        String yaml = result.get("body");

        assertTrue(yaml.contains("task-X:"), "应含 task-X 区块");
        assertTrue(yaml.contains("task-Y:"), "应含 task-Y 区块");
        // task-X top1 是 coder，task-Y top1 是 reviewer
        int xIdx = yaml.indexOf("task-X:");
        int yIdx = yaml.indexOf("task-Y:");
        // 在 task-X 区块（xIdx 到 yIdx 之间）应含 coder 不含 reviewer
        String xSection = yaml.substring(xIdx, yIdx);
        assertTrue(xSection.contains("- coder"), "task-X top1 应是 coder");
        assertFalse(xSection.contains("- reviewer"), "task-X top1=1 时不应含 reviewer");
        // task-Y 区块（yIdx 之后）应含 reviewer 不含 coder
        String ySection = yaml.substring(yIdx);
        assertTrue(ySection.contains("- reviewer"), "task-Y top1 应是 reviewer");
        assertFalse(ySection.contains("- coder"), "task-Y top1=1 时不应含 coder");
    }

    // ════════════════════════════════════════════════════════════════
    //  空记录 — 输出空推荐表
    // ════════════════════════════════════════════════════════════════

    @Test
    void emptyRecords_producesEmptyYaml() {
        Map<String, String> result = RoleSubsetOptimizer.optimize(List.of(), 3, 1);
        String yaml = result.get("body");

        assertNotNull(yaml);
        // 应含 header 注释
        assertTrue(yaml.startsWith("#"), "应有 header 注释");
        // 不应含任何 taskType 区块
        assertFalse(yaml.contains(":\n  -"), "无记录时不应输出任何 role");
    }

    // ════════════════════════════════════════════════════════════════
    //  role 在某些运行缺失 — 只对存在的运行求平均（不是补 0）
    // ════════════════════════════════════════════════════════════════

    @Test
    void missingRoleInSomeRun_averagesOnlyPresentRuns() {
        // run1: coder=1.0（无 reviewer）
        // run2: coder=0.0, reviewer=1.0
        // coder 平均 = (1.0+0.0)/2 = 0.5；reviewer 平均 = 1.0/1 = 1.0（不补 0）
        ImportanceRecord r1 = new ImportanceRecord("wf-1", "task", 1L, Map.of("coder", 1.0));
        ImportanceRecord r2 = new ImportanceRecord("wf-2", "task", 2L, Map.of("coder", 0.0, "reviewer", 1.0));

        Map<String, String> result = RoleSubsetOptimizer.optimize(List.of(r1, r2), 2, 1);
        String stats = result.get("stats");

        // reviewer 平均 1.0 应排第一（高于 coder 0.5）
        assertTrue(stats.contains("reviewer"), "stats 应含 reviewer");
        assertTrue(stats.contains("coder"), "stats 应含 coder");
        // 验证 reviewer(1.000) 排在 coder(0.500) 前
        int reviewerInStats = stats.indexOf("reviewer");
        int coderInStats = stats.indexOf("coder");
        assertTrue(reviewerInStats >= 0 && coderInStats >= 0 && reviewerInStats < coderInStats,
                "reviewer 平均 1.0 应排在 coder 平均 0.5 之前");
    }

    // ════════════════════════════════════════════════════════════════
    //  全 0 importance — 仍输出角色（顺序稳定，按出现次序）
    // ════════════════════════════════════════════════════════════════

    @Test
    void allZeroImportance_stillOutputsRoles() {
        ImportanceRecord r1 = new ImportanceRecord("wf-1", "task", 1L,
                Map.of("coder", 0.0, "reviewer", 0.0));

        Map<String, String> result = RoleSubsetOptimizer.optimize(List.of(r1), 3, 1);
        String yaml = result.get("body");

        // 全 0 时仍应输出角色（importance 都相等，按 TreeMap 自然序）
        assertTrue(yaml.contains("- coder") || yaml.contains("- reviewer"),
                "全 0 时仍应输出 role");
    }

    // ════════════════════════════════════════════════════════════════
    //  taskType 为空/null — 归入 (unknown)
    // ════════════════════════════════════════════════════════════════

    @Test
    void nullOrBlankTaskType_groupedAsUnknown() {
        ImportanceRecord r1 = new ImportanceRecord("wf-1", null, 1L, Map.of("coder", 1.0));
        ImportanceRecord r2 = new ImportanceRecord("wf-2", "", 2L, Map.of("coder", 1.0));

        Map<String, String> result = RoleSubsetOptimizer.optimize(List.of(r1, r2), 3, 1);
        String yaml = result.get("body");

        assertTrue(yaml.contains("(unknown):"), "空/null taskType 应归入 (unknown)");
    }
}
