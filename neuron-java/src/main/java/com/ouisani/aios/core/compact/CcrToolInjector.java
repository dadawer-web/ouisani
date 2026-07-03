package com.ouisani.aios.core.compact;

import com.ouisani.aios.core.tool.CcrRetrieveTool;
import com.ouisani.aios.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CCR 工具注入器 — 借鉴 Headroom 的 {@code CCRToolInjector}。
 * <p>
 * <b>核心机制：</b>扫描上下文中的压缩标记，检测到标记时自动将
 * {@link CcrRetrieveTool} 注册到 {@link ToolRegistry}，让 LLM 可以调用它取回原文。
 * <p>
 * <b>为什么需要注入器而不是永久注册？</b>
 * <ul>
 *   <li>如果上下文中没有压缩内容，ccr_retrieve 工具毫无意义，反而干扰 LLM 决策</li>
 *   <li>工具列表越精简，LLM 选择正确工具的准确率越高</li>
 *   <li>按需注入 = "有压缩才给检索能力"，避免工具噪声</li>
 * </ul>
 * <p>
 * <b>压缩标记格式（借鉴 Headroom 的 _marker_patterns）：</b>
 * <ul>
 *   <li>标准格式：{@code [N items compressed to M. Retrieve more: hash=abc123]}</li>
 *   <li>简化格式：{@code [N items compressed. hash=abc123]}</li>
 *   <li>通用格式：{@code [...compressed...hash=xxx]}</li>
 * </ul>
 * <p>
 * <b>Sticky-on 行为（借鉴 Headroom PR-B7）：</b>
 * 一旦会话中产生过压缩内容，ccr_retrieve 工具会持续保留在工具列表中，
 * 避免后续请求因工具列表变化而破坏 prompt cache 前缀。
 */
public class CcrToolInjector {

    private static final Logger log = LoggerFactory.getLogger(CcrToolInjector.class);

    /** 单例 */
    private static final CcrToolInjector INSTANCE = new CcrToolInjector();

    public static CcrToolInjector instance() {
        return INSTANCE;
    }

    private CcrToolInjector() {}

    // ── 压缩标记正则（借鉴 Headroom _marker_patterns）──

    /**
     * 标准格式：[N items compressed to M. Retrieve more: hash=<24hex>]
     */
    private static final Pattern MARKER_STANDARD = Pattern.compile(
            "\\[(\\d+) \\w+ compressed to (\\d+)\\. Retrieve more: hash=([a-f0-9]{24})\\]");

    /**
     * 简化格式：[N items compressed. hash=<24hex>]
     */
    private static final Pattern MARKER_SIMPLE = Pattern.compile(
            "\\[(\\d+) \\w+ compressed\\. hash=([a-f0-9]{24})\\]");

    /**
     * 通用格式：[...compressed...hash=<24hex>]（大小写不敏感）
     */
    private static final Pattern MARKER_GENERIC = Pattern.compile(
            "\\[.*?compressed.*?hash=([a-f0-9]{24})\\]", Pattern.CASE_INSENSITIVE);

    /** 所有标记模式 */
    private static final List<Pattern> MARKER_PATTERNS = List.of(
            MARKER_STANDARD, MARKER_SIMPLE, MARKER_GENERIC);

    // ── Sticky-on 状态追踪 ──

    /** 已产生过压缩内容的会话集合 — sticky-on 保证工具持续可用 */
    private final Set<String> sessionsWithCcr = ConcurrentHashMap.newKeySet();

    /** 工具是否已注册 */
    private volatile boolean toolRegistered = false;

    /**
     * 扫描消息列表，提取所有压缩标记中的哈希。
     * <p>
     * 借鉴 Headroom {@code CCRToolInjector.scan_for_markers()}。
     *
     * @param messages 消息列表
     * @return 检测到的哈希列表（可能为空）
     */
    public List<String> scanForMarkers(List<String> messages) {
        List<String> hashes = new ArrayList<>();
        if (messages == null || messages.isEmpty()) {
            return hashes;
        }

        for (String msg : messages) {
            if (msg == null || msg.isBlank()) continue;
            for (Pattern pattern : MARKER_PATTERNS) {
                Matcher matcher = pattern.matcher(msg);
                while (matcher.find()) {
                    // 最后一个捕获组是 hash
                    String hash = matcher.group(matcher.groupCount());
                    if (hash != null && !hashes.contains(hash)) {
                        hashes.add(hash);
                    }
                }
            }
        }

        return hashes;
    }

    /**
     * 扫描单条消息中的压缩标记。
     */
    public List<String> scanForMarkers(String message) {
        return scanForMarkers(List.of(message));
    }

    /**
     * 检测到压缩标记时注入 ccr_retrieve 工具。
     * <p>
     * 借鉴 Headroom {@code CCRToolInjector.inject_tool_definition()}。
     * <p>
     * <b>注入逻辑：</b>
     * <ol>
     *   <li>扫描消息中的压缩标记</li>
     *   <li>如果检测到标记 OR 会话之前产生过压缩内容（sticky-on），注册工具</li>
     *   <li>如果工具已注册，跳过（幂等）</li>
     * </ol>
     *
     * @param messages  当前上下文消息列表
     * @param sessionId 会话 ID（用于 sticky-on 追踪）
     * @return true 如果工具被注入（或已注入）
     */
    public boolean injectIfNeeded(List<String> messages, String sessionId) {
        List<String> detectedHashes = scanForMarkers(messages);
        boolean hasCompressedContent = !detectedHashes.isEmpty();

        // Sticky-on：如果会话之前有压缩内容，持续保留工具
        boolean stickyOn = sessionId != null && sessionsWithCcr.contains(sessionId);

        if (!hasCompressedContent && !stickyOn) {
            return false;
        }

        // 标记会话已有压缩内容（sticky-on）
        if (hasCompressedContent && sessionId != null) {
            sessionsWithCcr.add(sessionId);
        }

        // 注册工具（幂等）
        if (!toolRegistered) {
            ToolRegistry.instance().register(new CcrRetrieveTool());
            toolRegistered = true;
            log.info("[CCR Injector] ccr_retrieve 工具已注入 (hashes={}, session={}, sticky={})",
                    detectedHashes.size(), sessionId, stickyOn);
        }

        return true;
    }

    /**
     * 生成压缩标记字符串 — 供 CompactService 在替换内容时使用。
     * <p>
     * 生成格式：{@code [N items compressed to M. Retrieve more: hash=abc123]}
     *
     * @param originalItemCount 原始条目数
     * @param compressedItemCount 压缩后条目数
     * @param hash 哈希键
     * @return 压缩标记字符串
     */
    public static String createMarker(int originalItemCount, int compressedItemCount, String hash) {
        return String.format("[%d items compressed to %d. Retrieve more: hash=%s]",
                originalItemCount, compressedItemCount, hash);
    }

    /**
     * 生成带 token 信息的压缩标记。
     */
    public static String createMarkerWithTokens(int originalTokens, int compressedTokens, String hash) {
        return String.format("[%d tokens compressed to %d. Retrieve more: hash=%s]",
                originalTokens, compressedTokens, hash);
    }

    /**
     * 清理会话状态（会话结束时调用）。
     */
    public void clearSession(String sessionId) {
        if (sessionId != null) {
            sessionsWithCcr.remove(sessionId);
        }
    }

    /**
     * 检查工具是否已注册。
     */
    public boolean isToolRegistered() {
        return toolRegistered;
    }
}
