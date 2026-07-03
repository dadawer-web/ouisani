package com.ouisani.aios.core.compact;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.AgentTask.TokenRecord;
import com.ouisani.aios.core.ranking.FileHeatResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CCR 切点完整性守护 — 借鉴 jcode {@code safe_compaction_cutoff}（jcode-compaction-core/src/lib.rs:238）。
 * <p>
 * 从 {@link CompactService} 提取的独立模块，承载切点验证/拒绝/图片 token 估算/压缩模式状态。
 * 与 CompactService 的对话管线职责正交——本类只负责"切点是否安全"判定。
 * <p>
 * <b>核心原则</b>：宁可不动也不破坏。若保留段内 ToolResult 无法找到配对 ToolUse，
 * 返回 0（拒绝压缩）；若切点不在语义边界（仅 SEMANTIC 模式强制），向后回溯找最近的非 0 边界，
 * 找不到仍返回 0。
 * <p>
 * <b>设计要点</b>：
 * <ul>
 *   <li>默认模式（REACTIVE/PROACTIVE）：orphan scan 保证 ToolUse↔ToolResult 配对后，
 *       所有切点都视为安全边界——不强制话题边界，避免纯文本对话过度拒绝。</li>
 *   <li>SEMANTIC 模式：强制话题边界（工具调用闭合点 / write_todos / user 轮次 / 注入检测器）。</li>
 *   <li>图片 base64 data URI 按固定 {@link #IMAGE_TOKEN_COST}（1600）计费，
 *       避免字节长度高估 ~100x 触发"三连压缩死循环"。</li>
 * </ul>
 *
 * @see CompactService#splitMessagesProtected(List, int)
 * @see CompactService#splitMessagesProtectedStrict(List, int)
 */
public class CompactCutoffGuard {

    private static final Logger log = LoggerFactory.getLogger(CompactCutoffGuard.class);

    // ── 图片 token 固定计费（借鉴 jcode IMAGE_TOKEN_COST） ──
    // base64 图片按字节长度估算会高估约 100x，触发"三连压缩死循环"：
    // 一张 100KB 截图按 4/3 字符比 = ~130K tokens，但实际 LLM 只计 ~1600 tokens。
    private static final int IMAGE_TOKEN_COST = 1600;
    private static final Pattern IMAGE_DATA_URI =
            Pattern.compile("data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]+");

    /** 可压缩的工具结果列表（搬迁自 CompactService） */
    private static final List<String> COMPACTABLE_TOOLS = List.of(
            "file_read", "bash", "grep", "glob", "web_fetch", "web_search", "file_edit", "file_write"
    );

    // ── 文件路径提取模式（用于 FILE_HEAT_RESOLVER heat tiebreaker）──
    // 匹配 /seg/seg/file 形式的绝对路径；不匹配 http://、foo bar 等
    private static final Pattern FILE_PATH_PATTERN =
            Pattern.compile("(/(?:[a-zA-Z0-9_.-]+/)+[a-zA-Z0-9_.-]+)");

    // ── 压缩模式 + 语义边界检测器（借鉴 jcode 三模式） ──
    private static volatile CompactionMode ACTIVE_MODE = CompactionMode.REACTIVE;

    /**
     * NOOP 边界检测器 — SEMANTIC 模式未注入实现时退化为内在边界判定。
     * 必须在 BOUNDARY_DETECTOR 之前声明：Java 静态字段按文本顺序初始化，
     * 否则 BOUNDARY_DETECTOR 引用 NOOP_BOUNDARY_DETECTOR 时为 null。
     */
    public static final SemanticBoundaryDetector NOOP_BOUNDARY_DETECTOR = (ctx, i) -> false;

    private static volatile SemanticBoundaryDetector BOUNDARY_DETECTOR = NOOP_BOUNDARY_DETECTOR;

    // ── FileHeatResolver 注入（镜像 BOUNDARY_DETECTOR 三件套）──
    /** NOOP 文件热度解析器：默认零回归，所有 heatOf 返回 0 */
    public static final FileHeatResolver NOOP_FILE_HEAT_RESOLVER = path -> 0.0;

    /** 当前文件热度解析器（package-private，供同包测试访问） */
    static volatile FileHeatResolver FILE_HEAT_RESOLVER = NOOP_FILE_HEAT_RESOLVER;

    /** 注入文件热度解析器；传 null 重置为 NOOP（零回归） */
    public static void setFileHeatResolver(FileHeatResolver resolver) {
        FILE_HEAT_RESOLVER = resolver == null ? NOOP_FILE_HEAT_RESOLVER : resolver;
    }

    /** 切换压缩模式（运行时可调，用于 PROACTIVE 后台预压缩或 SEMANTIC 话题切换） */
    public static void setCompactionMode(CompactionMode mode) {
        ACTIVE_MODE = mode == null ? CompactionMode.REACTIVE : mode;
    }

    /** 注入语义边界检测器（SEMANTIC 模式生效）；传 null 重置为 NOOP */
    public static void setBoundaryDetector(SemanticBoundaryDetector detector) {
        BOUNDARY_DETECTOR = detector == null ? NOOP_BOUNDARY_DETECTOR : detector;
    }

    /**
     * 计算 autoCompact 触发阈值，根据当前 ACTIVE_MODE 调整。
     * 便捷方法让 CompactService 不必直接引用 ACTIVE_MODE 字段。
     */
    public static int autoCompactThreshold(int baseThreshold) {
        return CompactionMode.thresholdForMode(ACTIVE_MODE, baseThreshold);
    }

    // ════════════════════════════════════════════════════════════════
    //  CCR 切点完整性验证 — 借鉴 jcode safe_compaction_cutoff（lib.rs:238）
    // ════════════════════════════════════════════════════════════════

    /**
     * CCR 切点完整性验证 — 借鉴 jcode {@code safe_compaction_cutoff}（lib.rs:238）。
     * <p>
     * 从初始 cutoff 扫描保留段，验证每个 ToolResult 都有配对的 ToolUse 也在保留段内
     * （未被压缩掉）。若 ToolResult 孤儿化（其 ToolUse 在压缩区），尝试回溯调整 cutoff
     * 以纳入 ToolUse；若回溯到 0 仍无法配对，返回 0（拒绝压缩——"宁可不动也不破坏"）。
     * <p>
     * <b>配对启发式</b>：{@link AgentTask.TokenRecord} 无 {@code tool_call_id}，
     * 按 tool name + 位置匹配——保留段内"最近前序同名 ToolUse"。连续同名调用不可靠区分，
     * 此为已知限制（与现有 {@link #isToolCallRequest}/{@link #isToolResult} 一致）。
     * <p>
     * <b>语义边界</b>：默认模式下配对保证后所有切点都安全（跳过此步）；
     * SEMANTIC 模式下额外强制话题边界（工具调用闭合点 / write_todos / user 轮次 / 注入检测器），
     * 切点不在边界时向 0 方向回溯找最近的非 0 边界（retain more，符合"宁可不动"）。
     *
     * @param context        完整上下文记录列表
     * @param initialCutoff  初始切点索引（切点之前的内容将被压缩/摘要）
     * @return 验证后的切点索引；返回 0 表示拒绝压缩（无法保证配对完整性或找不到非 0 边界）
     */
    public static int safeCompactionCutoff(List<AgentTask.TokenRecord> context, int initialCutoff) {
        if (context == null || context.isEmpty()) return 0;
        int size = context.size();
        int cutoff = Math.max(0, Math.min(initialCutoff, size));
        if (cutoff == 0 || cutoff >= size) return cutoff;

        // ── 第 1 步：扫描保留段，找出孤儿 ToolResult（配对 ToolUse 在压缩区）──
        Set<String> orphaned = findOrphanedToolResults(context, cutoff);
        if (orphaned == null) {
            // 无任何 ToolResult — 默认模式配对安全直接返回；
            // SEMANTIC 模式仍需 step 3 边界校验（强制话题边界，即使无工具调用）
            if (ACTIVE_MODE != CompactionMode.SEMANTIC) return cutoff;
        } else if (!orphaned.isEmpty()) {
            // ── 第 2 步：回溯调整 cutoff，纳入孤儿 ToolResult 的 ToolUse ──
            int adjusted = adjustCutoffBackward(context, cutoff, orphaned);
            if (adjusted < 0) {
                // 回溯到 0 仍无法配对 → 拒绝压缩
                log.warn("[CompactCutoffGuard] safeCompactionCutoff: 拒绝压缩（无法为孤儿 ToolResult 找到配对 ToolUse: {}）",
                        orphaned);
                return 0;
            }
            cutoff = adjusted;
        }

        // ── 第 3 步：语义边界校验（默认模式下 isSemanticBoundary 总返回 true，跳过此步）──
        if (!isSemanticBoundary(context, cutoff)) {
            int boundaryCutoff = findNearestBoundaryBackward(context, cutoff);
            if (boundaryCutoff <= 0) {
                log.warn("[CompactCutoffGuard] safeCompactionCutoff: 拒绝压缩（cutoff={} 不在语义边界且无可用非 0 边界）",
                        cutoff);
                return 0;
            }
            cutoff = boundaryCutoff;
        }

        return cutoff;
    }

    /**
     * 扫描保留段 {@code [cutoff, size)}，返回孤儿 ToolResult 的 tool name 集合。
     * 若保留段无任何 ToolResult，返回 null（表示无需校验，安全）。
     */
    private static Set<String> findOrphanedToolResults(List<AgentTask.TokenRecord> context, int cutoff) {
        Set<String> orphaned = new HashSet<>();
        boolean hasToolResult = false;
        for (int i = cutoff; i < context.size(); i++) {
            AgentTask.TokenRecord r = context.get(i);
            if (!isToolResult(r)) continue;
            hasToolResult = true;
            String toolName = extractToolName(r.content());
            // 在保留前缀 [cutoff, i) 找最近前序同名 ToolUse
            if (!hasPairedToolUseInRetained(context, cutoff, i, toolName)) {
                orphaned.add(toolName);
            }
        }
        return hasToolResult ? orphaned : null;
    }

    /** 保留段 {@code [cutoff, idx)} 内是否存在同名 ToolUse（最近前序匹配启发式） */
    private static boolean hasPairedToolUseInRetained(
            List<AgentTask.TokenRecord> context, int cutoff, int idx, String toolName) {
        for (int j = idx - 1; j >= cutoff; j--) {
            AgentTask.TokenRecord r = context.get(j);
            if (isToolCallRequest(r) && r.content() != null && r.content().contains(toolName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 {@code cutoff-1} 向 0 回溯，纳入 orphaned 集合中各 tool name 的 ToolUse。
     * orphaned 清空时返回调整后的 cutoff；回溯到 0 仍有 orphaned 返回 -1（拒绝）。
     */
    private static int adjustCutoffBackward(
            List<AgentTask.TokenRecord> context, int cutoff, Set<String> orphaned) {
        Set<String> remaining = new HashSet<>(orphaned);
        for (int i = cutoff - 1; i >= 0; i--) {
            AgentTask.TokenRecord r = context.get(i);
            if (isToolCallRequest(r) && r.content() != null) {
                // 从 remaining 移除任何此 ToolUse 能配对的 tool name
                remaining.removeIf(name -> r.content().contains(name));
            }
            if (remaining.isEmpty()) {
                return i; // 纳入此 ToolUse，新 cutoff = i
            }
        }
        return -1; // 回溯到 0 仍无法配对
    }

    /**
     * 在 {@code [0, cutoff]} 范围内向 0 方向搜索（retain more），找最近的既满足语义边界
     * 又保持 ToolUse↔ToolResult 配对的索引。
     * <p>
     * <b>向后搜索</b>（{@code cutoff, cutoff-1, ..., 0}）符合 jcode "宁可不动也不破坏" 精神——
     * 宁可保留更多（压缩更少）也不破坏配对。找不到非 0 边界返回 0（拒绝压缩）。
     * <p>
     * <b>heat 信号通道</b>：当 {@link #FILE_HEAT_RESOLVER} 注入后，候选边界的选择会
     * 考虑保留段文件热度总和（保留更热的文件，压缩更冷的）。NOOP 默认零回归——
     * 首个有效边界即返回，行为与改前一致。heat tiebreaker 为未来「扫描全部有效边界
     * 取热度最高」扩展预留信号通道，本任务不改变保守的「首个有效边界即返回」策略。
     */
    private static int findNearestBoundaryBackward(
            List<AgentTask.TokenRecord> context, int cutoff) {
        int bestIdx = 0;
        double bestHeat = -1.0;
        for (int i = cutoff; i >= 0; i--) {
            if (i == 0) {
                // 兜底边界 0：仅在没找到更好的非 0 边界时使用
                return bestIdx == 0 ? 0 : bestIdx;
            }
            if (!isSemanticBoundary(context, i)) continue;
            // 验证新 cutoff=i 时保留段无孤儿
            Set<String> orphaned = findOrphanedToolResults(context, i);
            if (orphaned != null && !orphaned.isEmpty()) continue;
            // heat 信号通道：累计保留段文件热度（NOOP 时恒为 0，不影响选择）
            double heat = sumRetainedFileHeat(context, i);
            if (heat > bestHeat) {
                bestHeat = heat;
                bestIdx = i;
            }
            // 保守策略：首个有效非 0 边界即返回（保留 more）
            return bestIdx;
        }
        return bestIdx == 0 ? 0 : bestIdx;  // i==0 已返回，理论不可达
    }

    /**
     * 累计保留段 {@code [cutoff, size)} 内所有文件路径的热度总和。
     * 遍历每条记录的 content，提取文件路径后查 {@link #FILE_HEAT_RESOLVER}。
     * NOOP resolver 时所有 heatOf 返回 0，总和恒为 0。
     */
    private static double sumRetainedFileHeat(List<AgentTask.TokenRecord> context, int cutoff) {
        double sum = 0.0;
        for (int i = cutoff; i < context.size(); i++) {
            AgentTask.TokenRecord r = context.get(i);
            if (r == null || r.content() == null) continue;
            for (String path : extractFilePaths(r.content())) {
                sum += FILE_HEAT_RESOLVER.heatOf(path);
            }
        }
        return sum;
    }

    /** 从 content 中提取 {@code /seg/seg/file} 形式的绝对路径 */
    static List<String> extractFilePaths(String content) {
        List<String> paths = new ArrayList<>();
        if (content == null || content.isEmpty()) return paths;
        Matcher m = FILE_PATH_PATTERN.matcher(content);
        while (m.find()) paths.add(m.group());
        return paths;
    }

    /**
     * 语义边界判定（列表重载）— 切点是否落在安全边界。
     * <p>
     * <b>默认模式（REACTIVE/PROACTIVE）</b>：配对保证后所有切点都安全（return true），
     * 不强制话题边界——避免纯文本对话 {@code [user, text, text, text]} 的中间切点被过度拒绝。
     * 仅 mid-tool-block（ToolUse 在压缩区，ToolResult 在保留区）返回 false——但 orphan scan
     * 已保证配对，此处是双重保险。
     * <p>
     * <b>SEMANTIC 模式</b>：强制话题边界，要求满足以下之一：
     * <ul>
     *   <li>工具调用闭合点（{@code isToolResult(prev)}）</li>
     *   <li>内在边界（{@link #isSemanticBoundary(TokenRecord)}：write_todos / user 轮次）</li>
     *   <li>注入的 {@link SemanticBoundaryDetector} 返回 true</li>
     * </ul>
     */
    private static boolean isSemanticBoundary(List<AgentTask.TokenRecord> context, int idx) {
        if (idx == 0 || idx == context.size()) return true;
        AgentTask.TokenRecord prev = context.get(idx - 1);
        AgentTask.TokenRecord curr = context.get(idx);
        // mid-tool-block：禁止切（ToolUse 在压缩区，ToolResult 在保留区）。
        // 双重保险——orphan scan 已保证配对，此处再拒绝以明确语义。
        if (isToolCallRequest(prev) && isToolResult(curr)) return false;
        // 默认模式（REACTIVE/PROACTIVE）：配对保证后所有切点都安全，
        // 不强制话题边界——避免纯文本对话过度拒绝。
        if (ACTIVE_MODE != CompactionMode.SEMANTIC) return true;
        // SEMANTIC 模式：强制话题边界
        return isToolResult(prev)                        // 工具调用闭合点
                || isSemanticBoundary(curr)              // write_todos / user 轮次（内在重载）
                || BOUNDARY_DETECTOR.isBoundary(context, idx);  // 注入检测器
    }

    /**
     * 语义边界判定（单条重载）— 内在信号：write_todos 标记 或 user 轮次边界。
     */
    private static boolean isSemanticBoundary(AgentTask.TokenRecord record) {
        if (record == null || record.content() == null) return false;
        if ("user".equals(record.role())) return true; // 轮次边界
        if (record.content().contains("write_todos")) return true; // TODO 提交点
        return false;
    }

    // ════════════════════════════════════════════════════════════════
    //  图片感知 token 估算 — 借鉴 jcode IMAGE_TOKEN_COST
    // ════════════════════════════════════════════════════════════════

    /**
     * 通用 token 估算（委托 {@link #estimateTokensWithImageCost}）。
     * 保留方法名是为了让 CompactService 通过静态导入无前缀调用。
     */
    public static int estimateTokens(String text) {
        return estimateTokensWithImageCost(text);
    }

    /**
     * 图片感知 token 估算 — 借鉴 jcode IMAGE_TOKEN_COST。
     * <p>
     * base64 data URI 图片按固定 {@link #IMAGE_TOKEN_COST}（1600）计费，
     * 非图片文本保留 4/3 字符比。避免 base64 字节长度高估 ~100x 导致"三连压缩死循环"。
     * <p>
     * <b>零回归保证</b>：无图片内容（不匹配 {@link #IMAGE_DATA_URI}）时，
     * 输出与原 {@code (int)(text.length() * 4.0 / 3.0)} 完全一致。
     *
     * @param text 待估算文本，可能含 base64 图片 data URI
     * @return 估算 token 数
     */
    public static int estimateTokensWithImageCost(String text) {
        if (text == null || text.isEmpty()) return 0;
        java.util.regex.Matcher m = IMAGE_DATA_URI.matcher(text);
        int imageCount = 0;
        int lastEnd = 0;
        StringBuilder stripped = new StringBuilder(text.length());
        while (m.find()) {
            imageCount++;
            stripped.append(text, lastEnd, m.start());
            lastEnd = m.end();
        }
        // 拼接图片之后的尾部文本（即使无图片，下面这行也补全原文）
        stripped.append(text, lastEnd, text.length());
        int textTokens = (int) (stripped.length() * 4.0 / 3.0);
        return textTokens + imageCount * IMAGE_TOKEN_COST;
    }

    // ════════════════════════════════════════════════════════════════
    //  ToolUse / ToolResult 识别 helper（package-private，CompactService 调用）
    // ════════════════════════════════════════════════════════════════

    /**
     * 判断是否为工具调用请求 — AI 消息中包含 tool_calls。
     * <p>
     * 在我们的系统中，工具调用请求通常以特定标签出现在 assistant 消息中。
     */
    static boolean isToolCallRequest(AgentTask.TokenRecord record) {
        if (record == null || record.content() == null) return false;
        String content = record.content();
        // 检测工具调用请求模式：<tool_call, tool_call, write_todos 等
        return content.contains("<tool_call") || content.contains("tool_call")
                || content.contains("write_todos") || content.contains("<bash")
                || content.contains("<file_read") || content.contains("<file_write")
                || content.contains("<file_edit") || content.contains("<grep")
                || content.contains("<glob") || content.contains("<web_fetch");
    }

    /**
     * 判断是否为工具结果消息 — 工具调用的返回结果。
     */
    static boolean isToolResult(AgentTask.TokenRecord record) {
        if (record == null || record.content() == null) return false;
        return isCompactableToolResult(record.content());
    }

    static boolean isCompactableToolResult(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        return COMPACTABLE_TOOLS.stream().anyMatch(tool ->
                lower.contains("<" + tool + ">") || lower.contains("tool_result name=\"" + tool + "\""));
    }

    /**
     * 从工具结果内容中提取工具名。
     */
    static String extractToolName(String content) {
        if (content == null) return "unknown";
        // 尝试从 tool_result name="xxx" 格式提取
        int idx = content.indexOf("tool_result name=\"");
        if (idx >= 0) {
            int start = idx + "tool_result name=\"".length();
            int end = content.indexOf("\"", start);
            if (end > start) {
                return content.substring(start, end);
            }
        }
        // 尝试从 <tool_name> 格式提取
        for (String tool : COMPACTABLE_TOOLS) {
            if (content.contains("<" + tool + ">")) {
                return tool;
            }
        }
        return "unknown";
    }
}
