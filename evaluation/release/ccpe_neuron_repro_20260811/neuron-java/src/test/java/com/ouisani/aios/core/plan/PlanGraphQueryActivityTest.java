package com.ouisani.aios.core.plan;

import com.ouisani.aios.core.ranking.ActivityResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PlanGraphQuery ActivityResolver 注入测试 — 验证复合排序键 (priorityRank, -activity, id)。
 * <p>
 * 零回归保证：NOOP_ACTIVITY_RESOLVER 默认时排序与改前一致 (priorityRank asc, id asc)。
 */
class PlanGraphQueryActivityTest {

    @AfterEach
    void tearDown() {
        // 复位全局 resolver，避免测试间污染
        PlanGraphQuery.setActivityResolver(null);
    }

    private static PlanItem item(String id, String priority) {
        return PlanItem.queued(id, id + " content", priority, null, null, null);
    }

    @Test
    void nextRunnableItemIds_noResolver_zeroRegression() {
        // 原签名调用 → NOOP → (priorityRank asc, id asc)
        PlanItem b = item("b", "medium");
        PlanItem a = item("a", "medium");
        PlanItem c = item("c", "high");
        List<String> ids = PlanGraphQuery.nextRunnableItemIds(List.of(b, a, c), 0);
        // high (rank 0) → medium (rank 1); 同 medium 内 id 升序
        assertEquals(List.of("c", "a", "b"), ids, "零回归：priorityRank asc, id asc");
    }

    @Test
    void nextRunnableItemIds_noopResolver_zeroRegression() {
        // 显式传 NOOP → 行为一致
        PlanItem b = item("b", "medium");
        PlanItem a = item("a", "medium");
        List<String> ids = PlanGraphQuery.nextRunnableItemIds(
                List.of(b, a), 0, PlanGraphQuery.NOOP_ACTIVITY_RESOLVER);
        assertEquals(List.of("a", "b"), ids, "NOOP resolver 零回归");
    }

    @Test
    void nextRunnableItemIds_nullResolver_zeroRegression() {
        // null 退化为 NOOP
        PlanItem a = item("a", "medium");
        List<String> ids = PlanGraphQuery.nextRunnableItemIds(List.of(a), 0, null);
        assertEquals(List.of("a"), ids, "null resolver 退化为 NOOP");
    }

    @Test
    void nextRunnableItemIds_highActivityFirst() {
        // 同 priority → 高 activity 优先
        PlanItem a = item("a", "medium");
        PlanItem b = item("b", "medium");
        Map<String, Double> activity = new HashMap<>();
        activity.put("a", 0.1);
        activity.put("b", 0.9);
        ActivityResolver resolver = id -> activity.getOrDefault(id, 0.0);
        List<String> ids = PlanGraphQuery.nextRunnableItemIds(List.of(a, b), 0, resolver);
        assertEquals(List.of("b", "a"), ids, "同 priority → 高 activity 优先");
    }

    @Test
    void nextRunnableItemIds_priorityBeatsActivity() {
        // 高 priority 低 activity 仍优先于 低 priority 高 activity
        PlanItem highPri = item("a", "high");     // rank 0
        PlanItem lowPri = item("b", "medium");    // rank 1
        Map<String, Double> activity = new HashMap<>();
        activity.put("a", 0.0);   // high priority 但 activity 低
        activity.put("b", 100.0); // low priority 但 activity 高
        ActivityResolver resolver = id -> activity.getOrDefault(id, 0.0);
        List<String> ids = PlanGraphQuery.nextRunnableItemIds(List.of(lowPri, highPri), 0, resolver);
        assertEquals(List.of("a", "b"), ids, "priority 永远优先于 activity");
    }

    @Test
    void nextRunnableItemIds_tieBreakById() {
        // 同 priority 同 activity → id 升序
        PlanItem b = item("b", "medium");
        PlanItem a = item("a", "medium");
        ActivityResolver resolver = id -> 0.5;  // 所有 activity 相同
        List<String> ids = PlanGraphQuery.nextRunnableItemIds(List.of(b, a), 0, resolver);
        assertEquals(List.of("a", "b"), ids, "同 priority 同 activity → id 升序");
    }

    @Test
    void nextRunnableItemIds_limitTruncates() {
        PlanItem a = item("a", "medium");
        PlanItem b = item("b", "medium");
        PlanItem c = item("c", "medium");
        List<String> ids = PlanGraphQuery.nextRunnableItemIds(List.of(a, b, c), 2);
        assertEquals(2, ids.size(), "limit 截断");
        assertEquals(List.of("a", "b"), ids);
    }

    @Test
    void setActivityResolver_injectedResolverUsed() {
        // 注入全局 resolver 后，原签名调用应使用注入的 resolver
        PlanItem a = item("a", "medium");
        PlanItem b = item("b", "medium");
        Map<String, Double> activity = new HashMap<>();
        activity.put("a", 0.2);
        activity.put("b", 0.8);
        PlanGraphQuery.setActivityResolver(id -> activity.getOrDefault(id, 0.0));
        // 原签名调用 → 使用 ACTIVITY_RESOLVER（已注入）
        List<String> ids = PlanGraphQuery.nextRunnableItemIds(List.of(a, b), 0);
        assertEquals(List.of("b", "a"), ids, "注入全局 resolver 生效");
    }

    @Test
    void setActivityResolver_nullResetsToNoop() {
        PlanGraphQuery.setActivityResolver(id -> 1.0);
        PlanGraphQuery.setActivityResolver(null);  // reset
        assertSame(PlanGraphQuery.NOOP_ACTIVITY_RESOLVER, PlanGraphQuery.ACTIVITY_RESOLVER,
                "null 重置为 NOOP");
    }
}
