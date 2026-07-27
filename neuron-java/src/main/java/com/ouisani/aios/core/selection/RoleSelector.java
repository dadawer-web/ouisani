package com.ouisani.aios.core.selection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ouisani.aios.core.cost.CostTracker;
import com.ouisani.aios.core.role.RoleBlueprint;
import com.ouisani.aios.user.apps.omnifactory.StructuredOutputValidator;
import com.ouisani.aios.user.sdk.AiosSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Listwise 角色选择器 — 借鉴 DyLAN（arXiv:2310.02170）的 listwise_ranker。
 * <p>
 * <b>DyLAN 原版</b>：第 3 层起一次 LLM 调用，喂上一轮所有回复，输出 [1,2] 选 top-2。
 * 输入 shuffle 防位置偏置。解析失败 fallback {@code random.sample}（[LLM_Neuron.py:152-153]
 * <file:///home/xmy/tryaios/DyLAN/code/demo/LLM_Neuron.py#L152-L153>）——生产不可接受。
 * <p>
 * <b>neuron-java 适配（方案 C 混合渐进）</b>：
 * <ul>
 *   <li><b>编译后裁剪</b>：第一版只喂 query + role 描述（不看上一轮回复），在
 *       WorkflowEngine 执行前裁剪角色池。RoleSelector 预留 {@code previousOutputs}
 *       参数，follow-up 接入层式调度时激活层间动态选择。</li>
 *   <li><b>结构化输出</b>：要求 LLM 输出 {@code {"selected":["role1","role2"]}}，
 *       复用 {@link StructuredOutputValidator#extractJson} 三级 fallback 提取 +
 *       Jackson parse + schema 验证。替代 DyLAN 脆弱的 [X,Y] 正则。</li>
 *   <li><b>fallback 全选</b>（非 random）：LLM 调用失败 / JSON 解析失败 / selected 为空 /
 *       selected 全不匹配候选 → 返回全部候选。确定性，生产可接受。</li>
 *   <li><b>触发阈值</b>：候选数 ≤ {@link SelectionPolicy#minAgents()} 不触发 LLM（省成本），
 *       对齐 DyLAN "agents > 3 触发"。</li>
 * </ul>
 *
 * <h2>算法</h2>
 * <pre>
 * 1. 触发检查: candidates.size() <= minAgents → 全选; k >= candidates.size() → 全选
 * 2. shuffle candidates（防位置偏置，借鉴 DyLAN LLMNeuron.activate:69-71）
 * 3. 构造 prompt: query + 候选 role 列表（shuffled）+ 输出格式指令
 * 4. LLM 调用: AiosSdk.think("role_selector", prompt)
 *    - CostTracker.recordUsage 估算 token（prompt.length()/4 + response.length()/4）
 * 5. 解析: StructuredOutputValidator.extractJson → Jackson parse → 取 "selected" 数组
 *    - 验证每个元素是字符串且 ∈ candidates 名集合
 *    - 取前 k 个（防 LLM 输出超额）
 * 6. 任何失败 → fallback 全选
 * </pre>
 *
 * <p>纯函数，无副作用（除 CostTracker 记账 + log）。线程安全。
 */
public final class RoleSelector {

    private static final Logger log = LoggerFactory.getLogger(RoleSelector.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AGENT_ID = "role_selector";

    private RoleSelector() {}

    // ════════════════════════════════════════════════════════════════
    //  生产入口
    // ════════════════════════════════════════════════════════════════

    /**
     * 选择 top-K 角色 — 生产入口（无 previousOutputs，编译后裁剪用）。
     *
     * @param query      任务描述（第一版用 workflowId 代理）
     * @param candidates 候选角色蓝图列表
     * @param policy     选择策略；null 或 none → 全选不触发 LLM
     * @return 被选中的 role 名集合（fallback 时为全部候选名）
     */
    public static Set<String> select(String query, List<RoleBlueprint> candidates, SelectionPolicy policy) {
        return select(query, candidates, policy, null,
                (agentId, prompt) -> AiosSdk.getInstance().think(agentId, prompt),
                new Random());
    }

    /**
     * 选择 top-K 角色 — 预留层间接口。
     * <p>
     * 第一版 {@code previousOutputs} 忽略（编译后裁剪只看 query）。follow-up 接入
     * WorkflowEngine 层式调度时，传入上一轮所有节点输出实现真 DyLAN 层间动态选择。
     *
     * @param previousOutputs 上一轮节点输出（第一版忽略，follow-up 用）
     */
    public static Set<String> select(String query, List<RoleBlueprint> candidates,
                                     SelectionPolicy policy, List<String> previousOutputs) {
        return select(query, candidates, policy, previousOutputs,
                (agentId, prompt) -> AiosSdk.getInstance().think(agentId, prompt),
                new Random());
    }

    // ════════════════════════════════════════════════════════════════
    //  测试可注入入口（package-private）
    // ════════════════════════════════════════════════════════════════

    /**
     * 完整入口 — 可注入 LLM 调用函数 + 随机源（测试用）。
     *
     * @param llmCaller LLM 调用函数：(agentId, prompt) → response
     * @param random    shuffle 随机源（测试可注入固定 seed 验证顺序）
     */
    static Set<String> select(String query, List<RoleBlueprint> candidates, SelectionPolicy policy,
                             List<String> previousOutputs,
                             BiFunction<String, String, String> llmCaller,
                             Random random) {
        // ── 防御：空 candidates → 空集 ──
        if (candidates == null || candidates.isEmpty()) {
            return new LinkedHashSet<>();
        }
        // ── 防御：无策略 / none → 全选不触发 LLM ──
        if (policy == null || policy.isNone()) {
            return allRoleNames(candidates);
        }

        // ── 触发检查 1:候选数 ≤ minAgents → 全选不触发 LLM（省成本） ──
        if (candidates.size() <= policy.minAgents()) {
            log.debug("[RoleSelector] 候选数 {} ≤ minAgents {}，全选不触发 LLM",
                    candidates.size(), policy.minAgents());
            return allRoleNames(candidates);
        }
        // ── 触发检查 2:k ≥ 候选数 → 全选（k 太大无需裁剪） ──
        if (policy.k() >= candidates.size()) {
            log.debug("[RoleSelector] k {} ≥ 候选数 {}，全选", policy.k(), candidates.size());
            return allRoleNames(candidates);
        }

        // ── shuffle 候选防位置偏置（借鉴 DyLAN LLMNeuron.activate:69-71） ──
        List<RoleBlueprint> shuffled = new ArrayList<>(candidates);
        Collections.shuffle(shuffled, random);

        // ── 构造 prompt ──
        String prompt = buildRankerPrompt(query, shuffled, policy.k(), previousOutputs);

        // ── LLM 调用 ──
        String response;
        try {
            response = llmCaller.apply(AGENT_ID, prompt);
        } catch (Exception e) {
            log.warn("[RoleSelector] LLM 调用失败，fallback 全选: {}", e.getMessage());
            return allRoleNames(candidates);
        }
        if (response == null || response.isBlank()) {
            log.warn("[RoleSelector] LLM 返回空，fallback 全选");
            return allRoleNames(candidates);
        }

        // ── CostTracker 记账（估算 token，第一版 OpenAiAdapter 未回传真实 token） ──
        recordEstimatedCost(prompt, response);

        // ── 解析 + 验证 ──
        Set<String> candidateNames = allRoleNames(candidates);
        Set<String> selected = parseAndValidate(response, candidateNames, policy.k());

        if (selected.isEmpty()) {
            log.warn("[RoleSelector] 解析后 selected 为空，fallback 全选");
            return allRoleNames(candidates);
        }

        log.info("[RoleSelector] listwise 选择: 候选 {} → 选中 {}",
                candidates.size(), selected);
        return selected;
    }

    // ════════════════════════════════════════════════════════════════
    //  内部
    // ════════════════════════════════════════════════════════════════

    /** 构造 ranker prompt — query + 候选 role 列表（shuffled）+ 输出格式指令 */
    private static String buildRankerPrompt(String query, List<RoleBlueprint> shuffled,
                                            int k, List<String> previousOutputs) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("你是角色选择器。根据以下任务，从候选角色中选出最适合的 top-").append(k).append(" 个角色。\n\n");
        sb.append("任务: ").append(query == null ? "(未提供)" : query).append("\n\n");

        // previousOutputs 第一版忽略，但若非空提示 follow-up 可用
        // 此处不写入 prompt，保持第一版"只看 query"语义

        sb.append("候选角色（已打乱顺序，避免位置偏置）:\n");
        for (RoleBlueprint bp : shuffled) {
            sb.append("- ").append(bp.name()).append(": ");
            sb.append(bp.description() != null && !bp.description().isBlank()
                    ? bp.description() : "(无描述)");
            sb.append('\n');
        }

        sb.append("\n请只输出 JSON，格式: {\"selected\": [\"role_name1\", \"role_name2\"]}\n");
        sb.append("要求:\n");
        sb.append("1. 严格选 ").append(k).append(" 个角色（不多不少）\n");
        sb.append("2. 角色名必须来自候选列表\n");
        sb.append("3. 只输出 JSON，不要任何其他文字或 markdown 代码块标记\n");
        return sb.toString();
    }

    /**
     * 解析 LLM 响应 + 验证 selected 数组 — 复用 StructuredOutputValidator.extractJson。
     *
     * @return 选中的 role 名集合（按 LLM 输出顺序，截断到 k 个）；解析失败返回空集触发 fallback
     */
    private static Set<String> parseAndValidate(String response, Set<String> candidateNames, int k) {
        // 三级 fallback 提取 JSON（直接 parse → markdown fence → 首尾大括号）
        String json = StructuredOutputValidator.extractJson(response);
        if (json == null || json.isBlank()) {
            log.warn("[RoleSelector] extractJson 返回空，raw 长度={}", response.length());
            return Set.of();
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            log.warn("[RoleSelector] JSON 解析失败: {}", e.getMessage());
            return Set.of();
        }

        JsonNode selectedNode = root.get("selected");
        if (selectedNode == null || !selectedNode.isArray()) {
            log.warn("[RoleSelector] 响应缺少 selected 数组节点");
            return Set.of();
        }

        // 收集有效 role 名（∈ candidates），按 LLM 输出顺序，截断到 k
        Set<String> selected = new LinkedHashSet<>();
        int collected = 0;
        for (JsonNode elem : selectedNode) {
            if (collected >= k) break;
            if (!elem.isTextual()) continue;
            String name = elem.asText().trim();
            if (candidateNames.contains(name)) {
                selected.add(name);
                collected++;
            } else {
                log.debug("[RoleSelector] 过滤无效 role 名（不在候选中）: {}", name);
            }
        }
        return selected;
    }

    /** CostTracker 记账 — 估算 token（prompt+response 长度 / 4） */
    private static void recordEstimatedCost(String prompt, String response) {
        try {
            long inTokens = Math.max(1, prompt.length() / 4);
            long outTokens = Math.max(1, response.length() / 4);
            // cost 第一版记 0.0（无价格表）；token 累计是 CostTracker 的主要价值
            CostTracker.instance().recordUsage(inTokens, outTokens, 0.0);
        } catch (Exception e) {
            log.debug("[RoleSelector] CostTracker 记账失败（不影响主流程）: {}", e.getMessage());
        }
    }

    /** 返回全部候选 role 名集合（保序） */
    private static Set<String> allRoleNames(List<RoleBlueprint> candidates) {
        Set<String> names = new LinkedHashSet<>();
        for (RoleBlueprint bp : candidates) {
            if (bp != null && bp.name() != null) names.add(bp.name());
        }
        return names;
    }
}
