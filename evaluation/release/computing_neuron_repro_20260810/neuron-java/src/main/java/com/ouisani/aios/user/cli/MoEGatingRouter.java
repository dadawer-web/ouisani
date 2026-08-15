package com.ouisani.aios.user.cli;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.exception.SkillFaultException;
import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.llm.VectorMath;
import com.ouisani.aios.core.task.AiosTask;
import com.ouisani.aios.core.task.ExpertDomain;
import com.ouisani.aios.core.task.SopDescriptor;
import com.ouisani.aios.core.tool.ToolRegistry;
import com.ouisani.aios.user.sdk.AiosSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MoE 动态门控路由器 — 基于认知置信度的专家领域路由。
 * <p>
 * 取代硬编码的 {@link ExpertDomain} 枚举路由，实现基于 VFS 驱动的动态专家加载：
 * <ol>
 *   <li>读取 VFS 路径 {@code /system/drivers/sops/} 下的所有 {@link SopDescriptor}</li>
 *   <li>计算用户输入与每个 SOP 描述的匹配度（向量余弦相似度 或 LLM 评分）</li>
 *   <li>如果最高匹配得分低于 {@link #cognitiveThreshold}，抛出 {@link SkillFaultException}</li>
 *   <li>校验匹配领域的 {@code requiredTools} 是否在 {@link ToolRegistry} 中已注册</li>
 * </ol>
 * <p>
 * <b>核心拦截机制</b>：宁可拒绝，不可幻觉。
 * 当系统对任何领域的认知置信度都不足时，坚决中断执行，
 * 将 AiosTask + ToolRegistry 现场打包挂起，等待 SOP 驱动补全后恢复。
 * <p>
 * <b>双引擎匹配</b>：
 * <ul>
 *   <li><b>主引擎</b>：向量余弦相似度（快速，通过 LlmProvider.embed()）</li>
 *   <li><b>降级引擎</b>：LLM 评分（精确，通过 AiosSdk.think() 调用 E_CORE）</li>
 * </ul>
 *
 * @see SopDescriptor
 * @see SkillFaultException
 */
public class MoEGatingRouter {

    private static final Logger log = LoggerFactory.getLogger(MoEGatingRouter.class);

    /** SOP 驱动在 VFS 中的挂载路径 */
    private static final String SOP_VFS_ROOT = "/system/drivers/sops/";

    /** 认知置信度阈值 — 低于此值坚决拒绝路由 */
    private double cognitiveThreshold = 0.65;

    /** 最大返回专家数（Top-K） */
    private static final int MAX_EXPERTS = 3;

    /** SOP 描述向量缓存 — domainName → embedding（避免每次调用都重新 embed） */
    private final Map<String, float[]> sopEmbeddingCache = new ConcurrentHashMap<>();

    /** SOP 描述符缓存 — 避免每次路由都读 VFS */
    private volatile List<SopDescriptor> sopCache = null;

    private static final class Holder {
        static final MoEGatingRouter INSTANCE = new MoEGatingRouter();
    }

    public static MoEGatingRouter getInstance() {
        return Holder.INSTANCE;
    }

    private MoEGatingRouter() {
    }

    /**
     * 门控路由 — 判断用户任务需要调用哪些领域的专家。
     * <p>
     * 流程：
     * <ol>
     *   <li>从 VFS 加载所有已注册的 SopDescriptor</li>
     *   <li>计算用户输入与每个 SOP 描述的匹配度</li>
     *   <li>按得分降序排序，取 Top-K</li>
     *   <li>如果最高得分低于 {@link #cognitiveThreshold}，抛出 {@link SkillFaultException}</li>
     *   <li>校验 Top-K 领域的 requiredTools 是否已注册</li>
     * </ol>
     *
     * @param userInput 用户的自然语言输入
     * @param task      触发路由的原始任务（用于异常现场打包）
     * @return 匹配的专家领域描述符列表（按相关性降序）
     * @throws SkillFaultException 如果最高匹配得分低于认知阈值
     */
    public List<SopDescriptor> route(String userInput, AiosTask task) {
        if (userInput == null || userInput.isBlank()) {
            throw new SkillFaultException(task, ToolRegistry.instance(),
                    0.0, cognitiveThreshold, null);
        }

        // ── Step 1: 从 VFS 加载 SopDescriptor ──
        List<SopDescriptor> sops = loadSopDescriptors();
        if (sops.isEmpty()) {
            log.error("[MoEGatingRouter] VFS 路径 {} 下无任何 SOP 驱动！拒绝路由。", SOP_VFS_ROOT);
            throw new SkillFaultException(task, ToolRegistry.instance(),
                    0.0, cognitiveThreshold, null);
        }

        log.info("[MoEGatingRouter] 已加载 {} 个 SOP 驱动: {}", sops.size(),
                sops.stream().map(SopDescriptor::domainName).toList());

        // ── Step 1.5: 粗筛 — 按 requiredTools 交集快速过滤（借鉴 JARVIS 两阶段筛选） ──
        // 排除工具需求完全不满足的 SOP，降低后续向量/LLM 计算成本
        sops = prefilterByToolAvailability(sops);
        if (sops.isEmpty()) {
            log.warn("[MoEGatingRouter] 粗筛后所有 SOP 的必需工具均未注册！拒绝路由。");
            throw new SkillFaultException(task, ToolRegistry.instance(),
                    0.0, cognitiveThreshold, null);
        }
        log.info("[MoEGatingRouter] 粗筛后剩余 {} 个 SOP 候选", sops.size());

        // ── Step 2: 计算匹配度 ──
        Map<SopDescriptor, Double> scores = computeMatchScores(userInput, sops);

        // ── Step 3: 按得分降序排序，取 Top-K ──
        List<Map.Entry<SopDescriptor, Double>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        double bestScore = sorted.isEmpty() ? 0.0 : sorted.get(0).getValue();
        SopDescriptor bestMatch = sorted.isEmpty() ? null : sorted.get(0).getKey();

        log.info("[MoEGatingRouter] 匹配结果: bestScore={}, threshold={}, bestDomain={}",
                String.format("%.4f", bestScore), String.format("%.4f", cognitiveThreshold),
                bestMatch != null ? bestMatch.domainName() : "(无)");

        // ── Step 4: 【核心拦截】认知置信度检查 ──
        if (bestScore < cognitiveThreshold) {
            log.warn("[MoEGatingRouter] ⚠ 缺能中断！最高得分 {} < 阈值 {}，拒绝路由",
                    String.format("%.4f", bestScore), String.format("%.4f", cognitiveThreshold));
            throw new SkillFaultException(task, ToolRegistry.instance(),
                    bestScore, cognitiveThreshold,
                    bestMatch != null ? bestMatch.domainName() : null);
        }

        // ── Step 5: 取 Top-K 并校验 requiredTools ──
        List<SopDescriptor> result = new ArrayList<>();
        ToolRegistry toolRegistry = ToolRegistry.instance();
        Set<String> availableTools = new HashSet<>();
        for (var tool : toolRegistry.all()) {
            availableTools.add(tool.name());
        }

        for (int i = 0; i < Math.min(MAX_EXPERTS, sorted.size()); i++) {
            SopDescriptor sop = sorted.get(i).getKey();
            double score = sorted.get(i).getValue();

            // 校验 requiredTools 是否已注册
            List<String> missing = sop.requiredTools().stream()
                    .filter(t -> !availableTools.contains(t))
                    .toList();

            if (!missing.isEmpty()) {
                log.warn("[MoEGatingRouter] 领域 '{}' 缺少必需工具: {} — 跳过",
                        sop.domainName(), missing);
                continue;
            }

            result.add(sop);
            log.info("[MoEGatingRouter]   #{}: {} (score={})", i + 1, sop.domainName(),
                    String.format("%.4f", score));
        }

        if (result.isEmpty()) {
            log.warn("[MoEGatingRouter] 所有匹配领域的必需工具均未注册！拒绝路由。");
            throw new SkillFaultException(task, toolRegistry,
                    bestScore, cognitiveThreshold,
                    bestMatch != null ? bestMatch.domainName() : null);
        }

        return result;
    }

    /**
     * 粗筛 — 按 requiredTools 与 ToolRegistry 的交集快速过滤 SOP 候选。
     * <p>
     * 借鉴 JARVIS 两阶段模型选择：先粗筛（低成本）再精选（高成本）。
     * 排除 requiredTools 中有任何一个未在 ToolRegistry 注册的 SOP，
     * 避免对不可能执行的领域浪费向量计算或 LLM 调用成本。
     * <p>
     * 特殊规则：requiredTools 为空的 SOP 不被过滤（通用领域，无工具依赖）。
     *
     * @param sops 待筛选的 SOP 列表
     * @return 工具需求全部满足的 SOP 子集
     */
    private List<SopDescriptor> prefilterByToolAvailability(List<SopDescriptor> sops) {
        ToolRegistry toolRegistry = ToolRegistry.instance();
        Set<String> availableTools = new HashSet<>();
        for (var tool : toolRegistry.all()) {
            availableTools.add(tool.name());
        }

        List<SopDescriptor> filtered = new ArrayList<>();
        for (SopDescriptor sop : sops) {
            if (sop.requiredTools() == null || sop.requiredTools().isEmpty()) {
                // 无工具需求 — 直接通过
                filtered.add(sop);
                continue;
            }
            // 检查所有必需工具是否已注册
            boolean allAvailable = sop.requiredTools().stream()
                    .allMatch(availableTools::contains);
            if (allAvailable) {
                filtered.add(sop);
            } else {
                List<String> missing = sop.requiredTools().stream()
                        .filter(t -> !availableTools.contains(t))
                        .toList();
                log.info("[MoEGatingRouter] 粗筛排除 '{}': 缺少工具 {}", sop.domainName(), missing);
            }
        }
        return filtered;
    }

    /**
     * 计算用户输入与每个 SOP 描述的匹配度。
     * <p>
     * 双引擎策略：
     * <ol>
     *   <li><b>主引擎</b>：向量余弦相似度（通过 LlmProvider.embed()）</li>
     *   <li><b>降级引擎</b>：LLM 评分（通过 AiosSdk.think() 调用 E_CORE）</li>
     * </ol>
     *
     * @param userInput 用户输入
     * @param sops     SOP 描述符列表
     * @return SOP → 匹配得分（0.0-1.0）
     */
    private Map<SopDescriptor, Double> computeMatchScores(String userInput, List<SopDescriptor> sops) {
        // 尝试向量引擎
        Map<SopDescriptor, Double> scores = computeVectorScores(userInput, sops);
        if (scores != null) {
            return scores;
        }

        // 降级到 LLM 引擎
        log.info("[MoEGatingRouter] 向量引擎不可用，降级到 LLM 评分引擎");
        return computeLlmScores(userInput, sops);
    }

    /**
     * 向量余弦相似度引擎 — 通过 LlmProvider.embed() 计算匹配度。
     *
     * @return SOP → 得分映射，如果 LlmProvider 不可用则返回 null
     */
    private Map<SopDescriptor, Double> computeVectorScores(String userInput, List<SopDescriptor> sops) {
        LlmProvider llmProvider = VfsManager.instance().getLlmProvider();
        if (llmProvider == null || !llmProvider.isAvailable()) {
            return null;
        }

        try {
            // Embed 用户输入
            float[] userVec = llmProvider.embed(userInput);
            if (userVec == null || userVec.length == 0) {
                log.warn("[MoEGatingRouter] 用户输入 embed 返回空向量");
                return null;
            }

            Map<SopDescriptor, Double> scores = new LinkedHashMap<>();
            for (SopDescriptor sop : sops) {
                // 使用缓存的 SOP 向量（避免重复 embed）
                float[] sopVec = sopEmbeddingCache.computeIfAbsent(
                        sop.domainName(),
                        k -> {
                            try {
                                return llmProvider.embed(sop.description());
                            } catch (Exception e) {
                                log.warn("[MoEGatingRouter] SOP '{}' embed 失败: {}",
                                        sop.domainName(), e.getMessage());
                                return null;
                            }
                        }
                );

                if (sopVec == null || sopVec.length == 0) {
                    scores.put(sop, 0.0);
                    continue;
                }

                // 余弦相似度范围 [-1, 1]，归一化到 [0, 1]
                float cosine = VectorMath.cosineSimilarity(userVec, sopVec);
                double score = (cosine + 1.0) / 2.0; // [-1,1] → [0,1]
                scores.put(sop, score);
            }

            log.info("[MoEGatingRouter] 向量引擎计算完成 ({} 个 SOP)", scores.size());
            return scores;

        } catch (Exception e) {
            log.warn("[MoEGatingRouter] 向量引擎异常: {} — 将降级到 LLM 引擎", e.getMessage());
            return null;
        }
    }

    /**
     * LLM 评分引擎 — 通过 AiosSdk.think() 调用 E_CORE 计算匹配度。
     * <p>
     * 构建评分 Prompt，要求 LLM 返回 JSON 格式的匹配度评分。
     */
    private Map<SopDescriptor, Double> computeLlmScores(String userInput, List<SopDescriptor> sops) {
        AiosSdk sdk = AiosSdk.getInstance();

        // 构建评分 Prompt
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是 MoE 门控网络。请计算用户输入与以下专家领域的匹配度（0.0-1.0）。\n\n");
        prompt.append("专家领域：\n");
        for (int i = 0; i < sops.size(); i++) {
            prompt.append(String.format("%d. %s: %s\n", i + 1,
                    sops.get(i).domainName(), sops.get(i).description()));
        }
        prompt.append("\n用户输入: ").append(userInput).append("\n\n");
        prompt.append("请返回 JSON 数组，格式为 [{\"domainName\": \"xxx\", \"score\": 0.85}, ...]\n");
        prompt.append("只返回 JSON，不要任何解释。");

        String response;
        try {
            response = sdk.think("moe_gating_router", prompt.toString());
        } catch (Exception e) {
            log.error("[MoEGatingRouter] LLM 评分引擎调用失败: {}", e.getMessage());
            // 全部给 0 分，触发缺能中断
            Map<SopDescriptor, Double> scores = new LinkedHashMap<>();
            for (SopDescriptor sop : sops) {
                scores.put(sop, 0.0);
            }
            return scores;
        }

        // 解析 LLM 返回的 JSON 评分
        Map<SopDescriptor, Double> scores = new LinkedHashMap<>();
        Map<String, Double> rawScores = parseLlmScores(response);

        for (SopDescriptor sop : sops) {
            double score = rawScores.getOrDefault(sop.domainName(), 0.0);
            scores.put(sop, score);
        }

        log.info("[MoEGatingRouter] LLM 引擎计算完成 ({} 个 SOP)", scores.size());
        return scores;
    }

    /**
     * 解析 LLM 返回的 JSON 评分数组。
     * <p>
     * 支持格式：[{"domainName": "xxx", "score": 0.85}, ...]
     */
    private Map<String, Double> parseLlmScores(String response) {
        Map<String, Double> scores = new HashMap<>();
        if (response == null || response.isBlank()) return scores;

        // 清理 Markdown 标记
        String cleaned = response.trim()
                .replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .replaceAll("(?s)<think.*?</think>", "")
                .trim();

        // 提取所有 {"domainName": "xxx", "score": 0.xx} 对象
        // 使用简单正则提取，不依赖 Jackson
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\\{[^}]*\"domainName\"\\s*:\\s*\"([^\"]+)\"[^}]*\"score\"\\s*:\\s*([0-9.]+)[^}]*\\}",
                java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher matcher = pattern.matcher(cleaned);
        while (matcher.find()) {
            String domain = matcher.group(1).trim();
            try {
                double score = Double.parseDouble(matcher.group(2).trim());
                scores.put(domain, Math.max(0.0, Math.min(1.0, score)));
            } catch (NumberFormatException e) {
                log.warn("[MoEGatingRouter] 无法解析评分: {}", matcher.group(2));
            }
        }

        // 如果正则没匹配到，尝试更宽松的提取
        if (scores.isEmpty()) {
            java.util.regex.Pattern loosePattern = java.util.regex.Pattern.compile(
                    "\"([^\"]+)\"\\s*[:,]\\s*([0-9]\\.[0-9]+)"
            );
            java.util.regex.Matcher looseMatcher = loosePattern.matcher(cleaned);
            while (looseMatcher.find()) {
                String key = looseMatcher.group(1).trim();
                try {
                    double score = Double.parseDouble(looseMatcher.group(2).trim());
                    if (score >= 0.0 && score <= 1.0) {
                        scores.put(key, score);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        return scores;
    }

    /**
     * 从 VFS 加载所有已注册的 SopDescriptor。
     * <p>
     * 读取 VFS 路径 {@code /system/drivers/sops/} 下的所有 JSON 文件，
     * 解析为 SopDescriptor 列表。使用缓存避免重复 I/O。
     *
     * @return SopDescriptor 列表（可能为空）
     */
    private List<SopDescriptor> loadSopDescriptors() {
        // 使用缓存
        if (sopCache != null && !sopCache.isEmpty()) {
            return sopCache;
        }

        List<SopDescriptor> sops = new ArrayList<>();
        VfsManager vfs = VfsManager.instance();

        // 列出 SOP 目录下的所有文件
        List<String> sopFiles;
        try {
            sopFiles = vfs.listFilesUnder(SOP_VFS_ROOT);
        } catch (Exception e) {
            log.warn("[MoEGatingRouter] 读取 VFS 目录 {} 失败: {}", SOP_VFS_ROOT, e.getMessage());
            sopFiles = List.of();
        }

        if (sopFiles == null || sopFiles.isEmpty()) {
            // VFS 中无 SOP 驱动 — 尝试初始化默认驱动
            log.info("[MoEGatingRouter] VFS 中无 SOP 驱动，初始化默认驱动...");
            sops = initializeDefaultSops(vfs);
        } else {
            // 解析每个 SOP 文件
            for (String path : sopFiles) {
                if (!path.endsWith(".json")) continue;
                try {
                    String json = vfs.readText(path);
                    if (json != null && !json.isBlank()) {
                        SopDescriptor sop = parseSopDescriptor(json);
                        if (sop != null) {
                            sops.add(sop);
                        }
                    }
                } catch (Exception e) {
                    log.warn("[MoEGatingRouter] 解析 SOP 文件 {} 失败: {}", path, e.getMessage());
                }
            }
        }

        sopCache = sops;
        return sops;
    }

    /**
     * 初始化默认 SOP 驱动 — 从 ExpertDomain 枚举生成默认 SopDescriptor 并写入 VFS。
     * <p>
     * 这是系统的"自举"机制：首次启动时，从硬编码枚举生成 VFS 驱动文件，
     * 之后系统完全依赖 VFS 驱动，不再引用枚举。
     */
    private List<SopDescriptor> initializeDefaultSops(VfsManager vfs) {
        List<SopDescriptor> defaults = new ArrayList<>();

        for (ExpertDomain domain : ExpertDomain.values()) {
            String description = getDefaultDescription(domain);
            List<String> requiredTools = getDefaultRequiredTools(domain);

            SopDescriptor sop = new SopDescriptor(domain.sopFileName(), description, requiredTools);
            defaults.add(sop);

            // 写入 VFS
            String vfsPath = SOP_VFS_ROOT + domain.sopFileName() + ".json";
            String json = sopToJson(sop);
            try {
                vfs.writeText(vfsPath, json);
                log.info("[MoEGatingRouter] 已写入默认 SOP 驱动: {}", vfsPath);
            } catch (Exception e) {
                log.warn("[MoEGatingRouter] 写入 VFS 失败: {} - {}", vfsPath, e.getMessage());
            }
        }

        return defaults;
    }

    /** 获取默认领域描述（从 ExpertDomain 枚举的语义映射） */
    private String getDefaultDescription(ExpertDomain domain) {
        return switch (domain) {
            case SOFTWARE_ENGINEERING -> "写代码、Debug、部署、架构设计、重构、代码审查";
            case DATA_RESEARCH -> "联网搜索、爬虫、数据收集、总结、报表、统计分析";
            case CONTENT_CREATION -> "写长文、PPT、翻译、摘要、创意写作、文案策划";
            case WORKFLOW_AUTOMATION -> "定时任务、操作Excel、RPA物理点击、工作流自动化";
            case SYSTEM_OPERATION -> "管理VFS文件、配置网络、安装依赖、系统运维";
        };
    }

    /** 获取默认必需工具列表 */
    private List<String> getDefaultRequiredTools(ExpertDomain domain) {
        return switch (domain) {
            case SOFTWARE_ENGINEERING -> List.of("file_read", "file_write", "bash", "grep", "glob");
            case DATA_RESEARCH -> List.of("web_search", "web_fetch", "file_write");
            case CONTENT_CREATION -> List.of("file_read", "file_write");
            case WORKFLOW_AUTOMATION -> List.of("bash", "file_write");
            case SYSTEM_OPERATION -> List.of("bash", "file_read", "file_write");
        };
    }

    /**
     * 将 SopDescriptor 序列化为 JSON 字符串。
     */
    private String sopToJson(SopDescriptor sop) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"domainName\": \"").append(escapeJson(sop.domainName())).append("\",\n");
        sb.append("  \"description\": \"").append(escapeJson(sop.description())).append("\",\n");
        sb.append("  \"requiredTools\": [");
        for (int i = 0; i < sop.requiredTools().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(escapeJson(sop.requiredTools().get(i))).append("\"");
        }
        sb.append("]\n");
        sb.append("}");
        return sb.toString();
    }

    /**
     * 从 JSON 字符串解析 SopDescriptor。
     * <p>
     * 使用简单字符串提取，不依赖 Jackson。
     */
    private SopDescriptor parseSopDescriptor(String json) {
        if (json == null || json.isBlank()) return null;

        String domainName = extractJsonString(json, "domainName");
        String description = extractJsonString(json, "description");
        List<String> requiredTools = extractJsonStringArray(json, "requiredTools");

        if (domainName == null || domainName.isBlank()) {
            log.warn("[MoEGatingRouter] SOP JSON 缺少 domainName 字段");
            return null;
        }

        return new SopDescriptor(domainName.trim(),
                description != null ? description.trim() : "",
                requiredTools);
    }

    /** 从 JSON 中提取字符串字段值 */
    private String extractJsonString(String json, String key) {
        // 匹配 "key": "value" 或 "key" : "value"
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /** 从 JSON 中提取字符串数组字段值 */
    private List<String> extractJsonStringArray(String json, String key) {
        List<String> result = new ArrayList<>();
        // 提取 "key": [...] 部分
        int keyIdx = json.indexOf("\"" + key + "\"");
        if (keyIdx < 0) return result;

        int bracketStart = json.indexOf('[', keyIdx);
        int bracketEnd = json.indexOf(']', bracketStart);
        if (bracketStart < 0 || bracketEnd < 0) return result;

        String arrayContent = json.substring(bracketStart + 1, bracketEnd);
        // 提取所有引号内的字符串
        java.util.regex.Pattern strPattern = java.util.regex.Pattern.compile("\"([^\"]+)\"");
        java.util.regex.Matcher matcher = strPattern.matcher(arrayContent);
        while (matcher.find()) {
            result.add(matcher.group(1).trim());
        }
        return result;
    }

    /** JSON 字符串转义 */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    // ════════════════════════════════════════════════════════════════
    //  配置 API
    // ════════════════════════════════════════════════════════════════

    /**
     * 设置认知置信度阈值。
     *
     * @param threshold 阈值（0.0-1.0），低于此值拒绝路由
     */
    public void setCognitiveThreshold(double threshold) {
        this.cognitiveThreshold = Math.max(0.0, Math.min(1.0, threshold));
    }

    /** 获取当前认知置信度阈值 */
    public double getCognitiveThreshold() {
        return cognitiveThreshold;
    }

    /**
     * 刷新 SOP 缓存 — 下次路由时重新从 VFS 加载。
     * <p>
     * 当 SOP 驱动文件更新后调用此方法，使新驱动立即生效。
     */
    public void refreshCache() {
        sopCache = null;
        sopEmbeddingCache.clear();
        log.info("[MoEGatingRouter] SOP 缓存已刷新");
    }
}
