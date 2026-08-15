package com.ouisani.aios.core.selection;

import com.ouisani.aios.core.role.RoleBlueprint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RoleSelector#select} 单测 — 用 package-private 重载注入 mock LLM + 固定 Random。
 * <p>
 * 覆盖：触发检查（不调 LLM）、正常解析、超额截断、无效 role 过滤、fallback 全选、
 * 空 candidates、null/none policy、previousOutputs 预留接口、shuffle 防偏置（prompt 含全候选）。
 */
class RoleSelectorTest {

    // ════════════════════════════════════════════════════════════════
    //  触发检查：候选数 ≤ minAgents → 全选不调 LLM
    // ════════════════════════════════════════════════════════════════

    @Test
    void candidatesBelowMinAgents_skipsLlm_returnsAll() {
        // 3 个候选，minAgents=3 → 不触发 LLM
        List<RoleBlueprint> candidates = roles("coder", "reviewer", "tester");
        SelectionPolicy policy = SelectionPolicy.listwiseTopK(2, 3);
        AtomicInteger llmCalls = new AtomicInteger(0);
        BiFunction<String, String, String> llm = (id, p) -> {
            llmCalls.incrementAndGet();
            return "{\"selected\":[\"coder\"]}";
        };

        Set<String> selected = RoleSelector.select("task", candidates, policy, null, llm, new Random(42));

        assertEquals(0, llmCalls.get(), "候选数 ≤ minAgents 不应调 LLM");
        assertEquals(3, selected.size(), "应全选 3 个");
        assertTrue(selected.containsAll(Arrays.asList("coder", "reviewer", "tester")));
    }

    @Test
    void kGreaterThanOrEqualCandidateCount_skipsLlm_returnsAll() {
        // 3 个候选，k=5 ≥ 3 → 全选不裁剪
        List<RoleBlueprint> candidates = roles("coder", "reviewer", "tester");
        SelectionPolicy policy = SelectionPolicy.listwiseTopK(5, 1);  // minAgents=1 触发，但 k=5 太大
        AtomicInteger llmCalls = new AtomicInteger(0);
        BiFunction<String, String, String> llm = (id, p) -> {
            llmCalls.incrementAndGet();
            return "{\"selected\":[\"coder\"]}";
        };

        Set<String> selected = RoleSelector.select("task", candidates, policy, null, llm, new Random(42));

        assertEquals(0, llmCalls.get(), "k ≥ 候选数不应调 LLM");
        assertEquals(3, selected.size(), "应全选 3 个");
    }

    // ════════════════════════════════════════════════════════════════
    //  正常解析：LLM 返回合法 JSON → 选中指定 role
    // ════════════════════════════════════════════════════════════════

    @Test
    void normalParse_selectsSpecifiedRoles() {
        // 5 个候选，minAgents=3 触发，k=2
        List<RoleBlueprint> candidates = roles("coder", "reviewer", "tester", "architect", "devops");
        SelectionPolicy policy = SelectionPolicy.listwiseTopK(2, 3);
        BiFunction<String, String, String> llm = (id, p) ->
                "{\"selected\":[\"coder\",\"reviewer\"]}";

        Set<String> selected = RoleSelector.select("task", candidates, policy, null, llm, new Random(42));

        assertEquals(2, selected.size(), "应选 2 个");
        assertTrue(selected.contains("coder"));
        assertTrue(selected.contains("reviewer"));
    }

    @Test
    void normalParse_acceptsMarkdownFencedJson() {
        // LLM 输出带 markdown 代码块（复用 StructuredOutputValidator.extractJson 三级 fallback）
        List<RoleBlueprint> candidates = roles("a", "b", "c", "d");
        SelectionPolicy policy = SelectionPolicy.listwiseTopK(2, 2);
        BiFunction<String, String, String> llm = (id, p) ->
                "```json\n{\"selected\": [\"a\", \"b\"]}\n```";

        Set<String> selected = RoleSelector.select("task", candidates, policy, null, llm, new Random(42));

        assertEquals(2, selected.size());
        assertTrue(selected.contains("a"));
        assertTrue(selected.contains("b"));
    }

    // ════════════════════════════════════════════════════════════════
    //  超额截断：LLM 返回 > k 个 → 取前 k 个
    // ════════════════════════════════════════════════════════════════

    @Test
    void excessSelection_truncatesToK() {
        List<RoleBlueprint> candidates = roles("a", "b", "c", "d");
        SelectionPolicy policy = SelectionPolicy.listwiseTopK(2, 2);
        // LLM 返回 4 个，但 k=2
        BiFunction<String, String, String> llm = (id, p) ->
                "{\"selected\":[\"a\",\"b\",\"c\",\"d\"]}";

        Set<String> selected = RoleSelector.select("task", candidates, policy, null, llm, new Random(42));

        assertEquals(2, selected.size(), "应截断到 k=2");
        // 取前 2 个（a, b）
        assertTrue(selected.contains("a"));
        assertTrue(selected.contains("b"));
        assertFalse(selected.contains("c"));
        assertFalse(selected.contains("d"));
    }

    // ════════════════════════════════════════════════════════════════
    //  无效 role 名过滤：LLM 返回候选外的 role → 过滤；全无效则 fallback 全选
    // ════════════════════════════════════════════════════════════════

    @Test
    void invalidRoleNames_filteredOut() {
        List<RoleBlueprint> candidates = roles("a", "b", "c", "d");
        SelectionPolicy policy = SelectionPolicy.listwiseTopK(2, 2);
        // LLM 返回 1 个有效 + 1 个无效
        BiFunction<String, String, String> llm = (id, p) ->
                "{\"selected\":[\"a\",\"nonexistent_role\"]}";

        Set<String> selected = RoleSelector.select("task", candidates, policy, null, llm, new Random(42));

        assertEquals(1, selected.size(), "无效 role 应过滤，剩 1 个有效");
        assertTrue(selected.contains("a"));
    }

    @Test
    void allInvalidRoleNames_fallbackToAll() {
        List<RoleBlueprint> candidates = roles("a", "b", "c", "d");
        SelectionPolicy policy = SelectionPolicy.listwiseTopK(2, 2);
        // LLM 返回的全是无效 role
        BiFunction<String, String, String> llm = (id, p) ->
                "{\"selected\":[\"x\",\"y\"]}";

        Set<String> selected = RoleSelector.select("task", candidates, policy, null, llm, new Random(42));

        assertEquals(4, selected.size(), "全无效应 fallback 全选");
    }

    // ════════════════════════════════════════════════════════════════
    //  fallback 全选：各种失败场景
    // ════════════════════════════════════════════════════════════════

    @Test
    void llmThrowsException_fallbackToAll() {
        List<RoleBlueprint> candidates = roles("a", "b", "c", "d");
        SelectionPolicy policy = SelectionPolicy.listwiseTopK(2, 2);
        BiFunction<String, String, String> llm = (id, p) -> {
            throw new RuntimeException("LLM 服务不可用");
        };

        Set<String> selected = RoleSelector.select("task", candidates, policy, null, llm, new Random(42));

        assertEquals(4, selected.size(), "LLM 异常应 fallback 全选");
    }

    @Test
    void llmReturnsBlank_fallbackToAll() {
        List<RoleBlueprint> candidates = roles("a", "b", "c", "d");
        SelectionPolicy policy = SelectionPolicy.listwiseTopK(2, 2);
        BiFunction<String, String, String> llm = (id, p) -> "";

        Set<String> selected = RoleSelector.select("task", candidates, policy, null, llm, new Random(42));

        assertEquals(4, selected.size(), "LLM 空响应应 fallback 全选");
    }

    @Test
    void llmReturnsGarbage_fallbackToAll() {
        List<RoleBlueprint> candidates = roles("a", "b", "c", "d");
        SelectionPolicy policy = SelectionPolicy.listwiseTopK(2, 2);
        BiFunction<String, String, String> llm = (id, p) -> "这不是 JSON，无法解析";

        Set<String> selected = RoleSelector.select("task", candidates, policy, null, llm, new Random(42));

        assertEquals(4, selected.size(), "乱码响应应 fallback 全选");
    }

    @Test
    void emptySelectedArray_fallbackToAll() {
        List<RoleBlueprint> candidates = roles("a", "b", "c", "d");
        SelectionPolicy policy = SelectionPolicy.listwiseTopK(2, 2);
        BiFunction<String, String, String> llm = (id, p) -> "{\"selected\":[]}";

        Set<String> selected = RoleSelector.select("task", candidates, policy, null, llm, new Random(42));

        assertEquals(4, selected.size(), "空 selected 数组应 fallback 全选");
    }

    @Test
    void missingSelectedField_fallbackToAll() {
        List<RoleBlueprint> candidates = roles("a", "b", "c", "d");
        SelectionPolicy policy = SelectionPolicy.listwiseTopK(2, 2);
        BiFunction<String, String, String> llm = (id, p) -> "{\"foo\":\"bar\"}";

        Set<String> selected = RoleSelector.select("task", candidates, policy, null, llm, new Random(42));

        assertEquals(4, selected.size(), "缺少 selected 字段应 fallback 全选");
    }

    // ════════════════════════════════════════════════════════════════
    //  空 candidates / null policy / none policy
    // ════════════════════════════════════════════════════════════════

    @Test
    void emptyCandidates_returnsEmptySet() {
        SelectionPolicy policy = SelectionPolicy.listwiseTopK(2, 3);
        AtomicInteger llmCalls = new AtomicInteger(0);
        BiFunction<String, String, String> llm = (id, p) -> {
            llmCalls.incrementAndGet();
            return "{\"selected\":[\"a\"]}";
        };

        Set<String> selected = RoleSelector.select("task", List.of(), policy, null, llm, new Random(42));

        assertTrue(selected.isEmpty(), "空候选应返回空集");
        assertEquals(0, llmCalls.get(), "空候选不应调 LLM");
    }

    @Test
    void nullPolicy_returnsAllWithoutLlm() {
        List<RoleBlueprint> candidates = roles("a", "b", "c");
        AtomicInteger llmCalls = new AtomicInteger(0);
        BiFunction<String, String, String> llm = (id, p) -> {
            llmCalls.incrementAndGet();
            return "{\"selected\":[\"a\"]}";
        };

        Set<String> selected = RoleSelector.select("task", candidates, null, null, llm, new Random(42));

        assertEquals(3, selected.size(), "null policy 应全选");
        assertEquals(0, llmCalls.get(), "null policy 不应调 LLM");
    }

    @Test
    void nonePolicy_returnsAllWithoutLlm() {
        List<RoleBlueprint> candidates = roles("a", "b", "c");
        AtomicInteger llmCalls = new AtomicInteger(0);
        BiFunction<String, String, String> llm = (id, p) -> {
            llmCalls.incrementAndGet();
            return "{\"selected\":[\"a\"]}";
        };

        Set<String> selected = RoleSelector.select("task", candidates,
                SelectionPolicy.NONE_POLICY, null, llm, new Random(42));

        assertEquals(3, selected.size(), "none policy 应全选");
        assertEquals(0, llmCalls.get(), "none policy 不应调 LLM");
    }

    // ════════════════════════════════════════════════════════════════
    //  previousOutputs 预留接口：第一版忽略不报错
    // ════════════════════════════════════════════════════════════════

    @Test
    void previousOutputs_ignoredInV1_doesNotFail() {
        List<RoleBlueprint> candidates = roles("a", "b", "c", "d");
        SelectionPolicy policy = SelectionPolicy.listwiseTopK(2, 2);
        BiFunction<String, String, String> llm = (id, p) ->
                "{\"selected\":[\"a\",\"b\"]}";
        List<String> prevOutputs = Arrays.asList("上一轮输出1", "上一轮输出2");

        Set<String> selected = RoleSelector.select("task", candidates, policy, prevOutputs, llm, new Random(42));

        assertEquals(2, selected.size(), "previousOutputs 第一版忽略，应正常选择");
        assertTrue(selected.contains("a"));
        assertTrue(selected.contains("b"));
    }

    // ════════════════════════════════════════════════════════════════
    //  shuffle 防偏置：prompt 含所有候选 role
    // ════════════════════════════════════════════════════════════════

    @Test
    void shufflePrompt_containsAllCandidates() {
        List<RoleBlueprint> candidates = roles("coder", "reviewer", "tester", "architect", "devops");
        SelectionPolicy policy = SelectionPolicy.listwiseTopK(2, 3);
        List<String> capturedPrompts = new ArrayList<>();
        BiFunction<String, String, String> llm = (id, p) -> {
            capturedPrompts.add(p);
            return "{\"selected\":[\"coder\",\"reviewer\"]}";
        };

        RoleSelector.select("task", candidates, policy, null, llm, new Random(42));

        assertEquals(1, capturedPrompts.size(), "应调一次 LLM");
        String prompt = capturedPrompts.get(0);
        // prompt 应含所有候选 role 名（shuffle 不丢候选）
        for (String name : Arrays.asList("coder", "reviewer", "tester", "architect", "devops")) {
            assertTrue(prompt.contains(name), "prompt 应含候选 role: " + name);
        }
        // prompt 应含"已打乱顺序"提示
        assertTrue(prompt.contains("打乱"), "prompt 应提示已 shuffle");
    }

    @Test
    void shuffleDifferentSeeds_produceDifferentOrders() {
        // 验证 shuffle 真的发生：不同 seed 产生不同 prompt 顺序（至少一次不同）
        List<RoleBlueprint> candidates = roles("a1", "a2", "a3", "a4", "a5", "a6", "a7");
        SelectionPolicy policy = SelectionPolicy.listwiseTopK(2, 3);

        String order1 = capturePromptOrder(candidates, policy, new Random(1));
        String order2 = capturePromptOrder(candidates, policy, new Random(2));

        // 不同 seed 应产生不同顺序（极小概率相同，7! 种排列）
        assertFalse(order1.equals(order2), "不同 Random seed 应产生不同候选顺序");
    }

    /** 辅助：捕获 prompt 中候选 role 出现的顺序串 */
    private String capturePromptOrder(List<RoleBlueprint> candidates, SelectionPolicy policy, Random random) {
        List<String> captured = new ArrayList<>();
        BiFunction<String, String, String> llm = (id, p) -> {
            captured.add(p);
            return "{\"selected\":[\"a1\",\"a2\"]}";
        };
        RoleSelector.select("task", candidates, policy, null, llm, random);
        // 提取 prompt 中 "- aX:" 出现的顺序
        String prompt = captured.get(0);
        StringBuilder order = new StringBuilder();
        for (RoleBlueprint bp : candidates) {
            int idx = prompt.indexOf("- " + bp.name() + ":");
            order.append(idx).append(",");
        }
        return order.toString();
    }

    // ════════════════════════════════════════════════════════════════
    //  辅助
    // ════════════════════════════════════════════════════════════════

    /** 构造候选 role 列表（name 同 description） */
    private static List<RoleBlueprint> roles(String... names) {
        List<RoleBlueprint> out = new ArrayList<>();
        for (String n : names) {
            out.add(new RoleBlueprint(n, n + " description", null));
        }
        return out;
    }
}
