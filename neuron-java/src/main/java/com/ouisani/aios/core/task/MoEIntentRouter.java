package com.ouisani.aios.core.task;

import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.llm.LlmRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * MoE 门控网络 (Gating Network / Router) — 混合专家架构的核心组件。
 * <p>
 * 当用户发来长篇大论时，系统通过 E_CORE（快速小模型）瞬间计算出该用哪些专家。
 * <p>
 * 核心特性：
 * <ul>
 *   <li><b>Top-K 路由</b>：支持同时返回多个专家领域。
 *       例如"写一个爬虫脚本并总结数据" → [SOFTWARE_ENGINEERING, DATA_RESEARCH]</li>
 *   <li><b>E_CORE 执行</b>：使用快速小模型，延迟极低（通常 < 1 秒）</li>
 *   <li><b>JSON 解析</b>：解析模型的 JSON 返回值，映射为 {@code List<ExpertDomain>}</li>
 * </ul>
 *
 * @see ExpertDomain
 * @see com.ouisani.aios.user.apps.omnifactory.TopologyCompiler
 */
public class MoEIntentRouter {

    private static final Logger log = LoggerFactory.getLogger(MoEIntentRouter.class);

    /** 门控网络使用的 LLM 后端名称（E_CORE 快速模型） */
    private static final String GATE_BACKEND = "fast_model";

    /** 最大返回专家数（Top-K） */
    private static final int MAX_EXPERTS = 3;

    private static final class Holder {
        static final MoEIntentRouter INSTANCE = new MoEIntentRouter();
    }

    public static MoEIntentRouter getInstance() {
        return Holder.INSTANCE;
    }

    private MoEIntentRouter() {
    }

    /**
     * 门控网络路由 — 判断用户任务需要调用哪些领域的专家。
     * <p>
     * 使用 E_CORE（快速小模型）作为门控网络，支持 Top-K 路由。
     *
     * @param userInput 用户的自然语言输入
     * @param llmRouter LLM 路由器（用于获取 E_CORE provider）
     * @return 匹配的专家领域列表（按相关性排序）
     */
    public List<ExpertDomain> route(String userInput, LlmRouter llmRouter) {
        if (userInput == null || userInput.isBlank()) {
            return List.of(ExpertDomain.SYSTEM_OPERATION);
        }

        // 获取 E_CORE provider
        LlmProvider gateProvider = llmRouter != null ? llmRouter.getProvider(GATE_BACKEND) : null;
        if (gateProvider == null) {
            log.warn("[MoEIntentRouter] E_CORE provider 不可用，降级为 SYSTEM_OPERATION");
            return List.of(ExpertDomain.SYSTEM_OPERATION);
        }

        // 构建门控网络 Prompt
        String gatePrompt = buildGatePrompt(userInput);

        // 调用 E_CORE 小模型
        String llmResponse;
        try {
            long startMs = System.currentTimeMillis();
            llmResponse = gateProvider.think(gatePrompt);
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.info("[MoEIntentRouter] 门控网络路由完成 ({}ms)", elapsedMs);
        } catch (Exception e) {
            log.error("[MoEIntentRouter] 门控网络调用失败，降级为 SYSTEM_OPERATION: {}", e.getMessage());
            return List.of(ExpertDomain.SYSTEM_OPERATION);
        }

        // 解析 JSON 返回值
        List<ExpertDomain> domains = parseExpertDomains(llmResponse);

        if (domains.isEmpty()) {
            log.warn("[MoEIntentRouter] 未解析到任何专家领域，降级为 SYSTEM_OPERATION");
            return List.of(ExpertDomain.SYSTEM_OPERATION);
        }

        log.info("[MoEIntentRouter] 路由结果: {} → {}", truncate(userInput, 50),
                domains.stream().map(ExpertDomain::displayName).toList());
        return domains;
    }

    /**
     * 构建门控网络的 System Prompt。
     * <p>
     * 极度精简，让小模型快速判断。
     */
    private String buildGatePrompt(String userInput) {
        return """
                你是 MoE (混合专家) 架构的门控网络。你的任务是判断用户的任务需要调用哪些领域的专家。

                可选专家领域：
                - SOFTWARE_ENGINEERING: 写代码、Debug、部署、架构设计、重构
                - DATA_RESEARCH: 联网搜索、爬虫、总结、报表、统计分析
                - CONTENT_CREATION: 写长文、PPT、翻译、摘要、创意写作
                - WORKFLOW_AUTOMATION: 定时发邮件、操作Excel、RPA物理点击
                - SYSTEM_OPERATION: 管理VFS文件、配置网络、安装依赖

                规则：
                1. 必须返回 JSON 数组，包含 1-%d 个最相关的专家领域
                2. 按相关性从高到低排序
                3. 只返回 JSON，不要任何解释

                示例：
                用户: "写一个Python爬虫抓取豆瓣电影数据并生成统计图表"
                返回: ["SOFTWARE_ENGINEERING", "DATA_RESEARCH"]

                用户: "帮我翻译这段英文"
                返回: ["CONTENT_CREATION"]

                用户: "安装nginx并配置反向代理"
                返回: ["SYSTEM_OPERATION"]

                用户输入: %s

                返回 (JSON 数组):
                """.formatted(MAX_EXPERTS, userInput);
    }

    /**
     * 解析 LLM 返回的 JSON 数组，映射为 ExpertDomain 列表。
     * <p>
     * 支持多种格式容错：
     * - 标准 JSON: ["SOFTWARE_ENGINEERING", "DATA_RESEARCH"]
     * - 带引号: ["software_engineering", "data_research"]
     * - 无引号: [SOFTWARE_ENGINEERING, DATA_RESEARCH]
     * - 纯文本: SOFTWARE_ENGINEERING, DATA_RESEARCH
     */
    private List<ExpertDomain> parseExpertDomains(String response) {
        if (response == null || response.isBlank()) {
            return List.of();
        }

        List<ExpertDomain> result = new ArrayList<>();
        Set<ExpertDomain> seen = new HashSet<>(); // 去重

        // 清理响应文本
        String cleaned = response.trim();
        // 去除 Markdown 代码块标记
        cleaned = cleaned.replaceAll("```json\\s*", "").replaceAll("```\\s*", "");
        // 去除 <think> 标签
        cleaned = cleaned.replaceAll("<think>.*?</think>", "");
        cleaned = cleaned.trim();

        // 提取所有大写枚举名
        for (ExpertDomain domain : ExpertDomain.values()) {
            if (cleaned.contains(domain.name()) && !seen.contains(domain)) {
                result.add(domain);
                seen.add(domain);
                if (result.size() >= MAX_EXPERTS) break;
            }
        }

        // 如果没找到大写枚举名，尝试小写匹配
        if (result.isEmpty()) {
            String lower = cleaned.toLowerCase();
            for (ExpertDomain domain : ExpertDomain.values()) {
                if (lower.contains(domain.sopFileName()) && !seen.contains(domain)) {
                    result.add(domain);
                    seen.add(domain);
                    if (result.size() >= MAX_EXPERTS) break;
                }
            }
        }

        return result;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
