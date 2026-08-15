package com.ouisani.aios.user.apps.omnifactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 领域偏差检测器 — 拦截"科研意图被锚定到系统环境搭建"的拓扑偏差。
 * <p>
 * 修复 Terminal#374-430 bug：用户说"帮我做一个环境的科研"，LLM 却生成
 * INSTALL OS UTILS / FETCH_AIOS_ARCHITECTURE / CLONE_SOURCE / CONFIGURE_ENV
 * 等系统搭建节点，把"环境"歧义词错误锚定到 AIOS 系统环境。
 * <p>
 * 检测逻辑（独立于 think 标签，只看实际节点内容）：
 * <ul>
 *   <li>用户未显式要求系统搭建（不含搭建/部署/安装/配置等动词）—— 混合意图豁免</li>
 *   <li>且用户意图为科研/调研（含科研意图词）</li>
 *   <li>且拓扑 JSON 中出现系统搭建动作标记（INSTALL/CLONE/CONFIGURE/项目结构 等）</li>
 *   <li>→ 判定偏差，由 {@link TopologyCompiler} 触发重编译</li>
 * </ul>
 * <p>
 * 设计要点（来自上一会话验证的关键调整）：
 * <ul>
 *   <li><b>think 标签独立性</b>：检测前剥离 {@code <think>...</think>} 块，只看实际节点，
 *       避免 LLM 在 think 中 musing "INSTALL" 误触发偏差。</li>
 *   <li><b>混合意图豁免</b>：用户显式说"搭建/部署/安装/配置"时，系统搭建节点是合法的，
 *       即使同时含"调研"也不判偏差（如"调研并搭建XX环境"）。</li>
 *   <li><b>优雅降级</b>：本类只做无状态判定，重试与降级策略由 {@link TopologyCompiler}
 *       的重编译循环负责（maxAttempts 后返回 best-effort 拓扑而非死循环）。</li>
 * </ul>
 */
public final class DomainBiasCheck {

    private DomainBiasCheck() {}

    /**
     * 系统搭建动作标记 — 出现在拓扑节点 instanceId/role 中即疑似偏差。
     * 包含用户 bug report 实证的 AIOS 专属标记与通用项目脚手架标记。
     */
    private static final Set<String> SYSTEM_SETUP_MARKERS = Set.of(
            // 强动词（大写英文，匹配时大小写不敏感）
            "INSTALL", "CLONE", "CONFIGURE",
            // AIOS 专属系统搭建（Terminal#374-430 bug report 实证）
            "FETCH_SYSTEM", "FETCH_AIOS", "AIOS_ARCHITECTURE", "OS_UTILS",
            "ANALYZE_DEPENDENCIES", "FETCH_SYSTEM_REQUIREMENTS",
            // 项目脚手架
            "项目结构", "pom.xml", "dockerfile", "requirements.txt", "package.json",
            // bug report 中的 STEP_x_xxx 模式
            "STEP_1_INSTALL", "STEP_2_CONFIGURE", "STEP_3_CLONE"
    );

    /** 科研/调研意图词 — 触发偏差检测的前提 */
    private static final Set<String> RESEARCH_INTENT_MARKERS = Set.of(
            "调研", "科研", "研究", "调查", "综述", "进展", "动态"
    );

    /** 显式系统搭建意图动词 — 出现则触发混合意图豁免（即使含科研词也不判偏差） */
    private static final Set<String> SETUP_INTENT_VERBS = Set.of(
            "搭建", "部署", "安装", "配置", "setup", "install", "deploy"
    );

    /** think 标签剥离模式 — 检测只看实际节点内容，独立于 LLM 的思考过程 */
    private static final Pattern THINK_PATTERN = Pattern.compile("(?s)<think>.*?</think>");

    /** 偏差检测结果 */
    public record BiasResult(boolean biased, String reason, List<String> hitMarkers) {}

    /**
     * 检测拓扑是否将科研意图错误锚定到系统环境搭建。
     *
     * @param userPrompt    用户原始输入
     * @param topologyJson  LLM 生成的拓扑 JSON（可能含残余 think 标签）
     * @return 偏差检测结果；{@code biased=true} 时调用方应注入去偏反馈重编译
     */
    public static BiasResult check(String userPrompt, String topologyJson) {
        if (topologyJson == null || topologyJson.isBlank()) {
            return new BiasResult(false, "empty topology, skip", List.of());
        }
        // 混合意图豁免：用户显式要求搭建/部署/安装/配置 → 系统搭建节点合法
        if (containsAny(userPrompt, SETUP_INTENT_VERBS)) {
            return new BiasResult(false, "mixed/setup intent, exempt", List.of());
        }
        // 非科研意图 → 不检测（搭建 Python 环境等合法系统任务）
        if (!containsAny(userPrompt, RESEARCH_INTENT_MARKERS)) {
            return new BiasResult(false, "non-research intent, skip", List.of());
        }
        // 剥离 think 标签后检测系统搭建标记（think 独立性）
        String nodesOnly = THINK_PATTERN.matcher(topologyJson).replaceAll("");
        String lower = nodesOnly.toLowerCase();
        List<String> hits = new ArrayList<>();
        for (String marker : SYSTEM_SETUP_MARKERS) {
            if (isAscii(marker)) {
                if (lower.contains(marker.toLowerCase())) hits.add(marker);
            } else {
                if (nodesOnly.contains(marker)) hits.add(marker);
            }
        }
        if (hits.isEmpty()) {
            return new BiasResult(false, "research intent, no system-setup markers", List.of());
        }
        return new BiasResult(true,
                "research intent but topology contains system-setup markers: " + hits, hits);
    }

    private static boolean containsAny(String text, Set<String> keywords) {
        if (text == null || text.isEmpty()) return false;
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 128) return false;
        }
        return true;
    }
}
