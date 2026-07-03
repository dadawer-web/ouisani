package com.ouisani.aios.core.compact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Cache 前缀稳定化检测器 — 借鉴 Headroom transforms/cache_aligner.py。
 * <p>
 * <b>核心问题：</b>LLM provider 的 KV cache 按 system prompt 前缀匹配。如果
 * system prompt 里塞了动态内容（UUID、时间戳、JWT、哈希），每次请求的前缀都不同，
 * cache 永远 miss — 每次都要重新计算整个 prompt 的 KV。
 * <p>
 * <b>设计原则（借鉴 Headroom PR-A2 修复）：</b>
 * <ul>
 *   <li><b>纯检测器</b> — 只检测动态内容并告警，<b>不改写</b> prompt</li>
 *   <li>改写会违反"热区不可变"不变量（I2 invariant）— system prompt 一旦构建就不能改</li>
 *   <li>检测结果暴露给调用方，由调用方决定如何处理（移动到 context block 等）</li>
 * </ul>
 * <p>
 * <b>检测的 4 种动态内容：</b>
 * <ol>
 *   <li><b>UUID</b> — RFC 4122 格式（36 字符 + 4 个连字符）</li>
 *   <li><b>ISO 8601 时间戳</b> — {@code 2026-06-25T12:34:56} 格式</li>
 *   <li><b>JWT</b> — 三段 base64url 编码（只检测形状，不验证签名）</li>
 *   <li><b>Hex 哈希</b> — MD5(32)/SHA1(40)/SHA256(64) 长度的十六进制串</li>
 * </ol>
 * <p>
 * <b>OS 类比：</b>相当于 Linux 的 cache profiling 工具 — 检测哪些内存行
 * 导致 cache 抖动，但不自动修改代码。
 *
 * @see com.ouisani.aios.core.context.SystemPromptBuilder
 */
public class CacheAligner {

    private static final Logger log = LoggerFactory.getLogger(CacheAligner.class);

    /** 单例 */
    private static final CacheAligner INSTANCE = new CacheAligner();

    public static CacheAligner instance() {
        return INSTANCE;
    }

    private CacheAligner() {}

    // ════════════════════════════════════════════════════════════════
    //  常量 — 借鉴 Headroom cache_aligner.py 的长度约束
    // ════════════════════════════════════════════════════════════════

    /** Hex 哈希的合法长度 — MD5=32, SHA1=40, SHA256=64 */
    private static final Set<Integer> HEX_HASH_LENGTHS = Set.of(32, 40, 64);

    /** 规范 UUID 长度（含连字符） — 不接受 32 字符无连字符形式（与 MD5 不可区分） */
    private static final int UUID_CANONICAL_LEN = 36;

    /** JWT 段数 */
    private static final int JWT_SEGMENT_COUNT = 3;
    private static final int JWT_MIN_SEGMENT_BYTES = 4;

    /** Token 分类标签 */
    public static final String LABEL_UUID = "uuid";
    public static final String LABEL_ISO8601 = "iso8601";
    public static final String LABEL_JWT = "jwt";
    public static final String LABEL_HEX_HASH = "hex_hash";

    /** 检测的 token 模式 — 用于从文本中提取候选词元 */
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "[a-fA-F0-9]{32,64}"              // hex hash 候选
            + "|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" // UUID
            + "|\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[.\\d+\\-:Z]*" // ISO 8601
            + "|[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{16,}" // JWT
    );

    // ════════════════════════════════════════════════════════════════
    //  检测结果
    // ════════════════════════════════════════════════════════════════

    /**
     * 检测到的一条动态内容 — 借鉴 Headroom VolatileFinding。
     */
    public record VolatileFinding(
            String label,    // uuid / iso8601 / jwt / hex_hash
            String sample,    // 截断的样本（前 32 字符）
            int position      // 在 prompt 中的字符偏移
    ) {
        @Override
        public String toString() {
            return String.format("[%s] at %d: \"%s\"", label, position, sample);
        }
    }

    /**
     * Cache 前缀检测结果 — 借鉴 Headroom CachePrefixMetrics。
     */
    public record CachePrefixMetrics(
            int totalFindings,
            List<VolatileFinding> findings,
            boolean cachePrefixStable,
            int estimatedPrefixTokens,
            int unstablePrefixTokens
    ) {
        /**
         * cache 命中率预估 — 稳定 token / 总 token。
         * 如果有不稳定内容在前缀中，命中率接近 0。
         */
        public double estimatedCacheHitRate() {
            if (totalFindings == 0) return 1.0;
            if (unstablePrefixTokens >= estimatedPrefixTokens) return 0.0;
            return 1.0 - ((double) unstablePrefixTokens / Math.max(1, estimatedPrefixTokens));
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  主检测方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 检测 system prompt 中的动态内容 — 借鉴 Headroom CacheAligner.apply()。
     * <p>
     * <b>纯检测不改写</b> — 只返回检测结果和告警，不修改 prompt。
     * 调用方可以根据检测结果决定如何处理（如把动态内容移到 context block）。
     *
     * @param systemPrompt 要检测的 system prompt
     * @return cache 前缀稳定性指标
     */
    public CachePrefixMetrics detectVolatileContent(String systemPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank()) {
            return new CachePrefixMetrics(0, List.of(), true, 0, 0);
        }

        List<VolatileFinding> findings = new ArrayList<>();
        java.util.regex.Matcher matcher = TOKEN_PATTERN.matcher(systemPrompt);

        while (matcher.find()) {
            String token = matcher.group();
            int start = matcher.start();

            String label = classifyToken(token);
            if (label != null) {
                String sample = token.substring(0, Math.min(token.length(), 32));
                findings.add(new VolatileFinding(label, sample, start));
            }
        }

        // 估算前缀 token 数（粗略 4 字符/token）
        int totalTokens = systemPrompt.length() / 4;
        int unstableTokens = findings.stream().mapToInt(f -> f.sample.length() / 4 + 1).sum();
        boolean stable = findings.isEmpty();

        CachePrefixMetrics metrics = new CachePrefixMetrics(
                findings.size(), findings, stable, totalTokens, unstableTokens
        );

        if (!stable) {
            // ── 告警 — 借鉴 Headroom 的 customer-visible warning log ──
            log.warn("[CacheAligner] ⚠️ 检测到 {} 处动态内容，KV cache 前缀不稳定！命中率预估: {}",
                    findings.size(), String.format("%.0f%%", metrics.estimatedCacheHitRate() * 100));
            for (VolatileFinding f : findings) {
                log.warn("[CacheAligner]   {} at pos {}", f, f.position);
            }
            System.out.println("[CacheAligner] ⚠️ 检测到 " + findings.size()
                    + " 处动态内容，KV cache 前缀不稳定！建议移动到 context block");
        }

        return metrics;
    }

    // ════════════════════════════════════════════════════════════════
    //  Token 分类 — 借鉴 Headroom _classify_token()
    //  顺序很重要：更具体的检查先做，避免误分类
    // ════════════════════════════════════════════════════════════════

    /**
     * 分类单个 token — 借鉴 Headroom _classify_token()。
     * <p>
     * 检查顺序：UUID（最具体）→ ISO 8601 → JWT → Hex Hash。
     *
     * @param token 要分类的词元
     * @return 标签（uuid/iso8601/jwt/hex_hash），不匹配返回 null
     */
    public String classifyToken(String token) {
        if (token == null || token.isEmpty()) return null;

        // 1. UUID（最具体 — 36 字符 + 4 个连字符）
        if (isUuid(token)) return LABEL_UUID;

        // 2. ISO 8601 时间戳
        if (isIso8601(token)) return LABEL_ISO8601;

        // 3. JWT（三段 base64url）
        if (isJwtShape(token)) return LABEL_JWT;

        // 4. Hex 哈希（MD5/SHA1/SHA256 长度）
        if (isHexHash(token)) return LABEL_HEX_HASH;

        return null;
    }

    // ════════════════════════════════════════════════════════════════
    //  各类型检测器 — 借鉴 Headroom 的结构化解析（无 regex）
    // ════════════════════════════════════════════════════════════════

    /**
     * UUID 检测 — 借鉴 Headroom _is_uuid()。
     * <p>
     * 只接受规范 36 字符形式（含连字符）。
     * 不接受 32 字符无连字符形式（与 MD5 hex 不可区分）。
     */
    public static boolean isUuid(String token) {
        if (token.length() != UUID_CANONICAL_LEN) return false;
        if (token.chars().filter(c -> c == '-').count() != 4) return false;
        try {
            UUID.fromString(token);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * ISO 8601 时间戳检测 — 借鉴 Headroom _is_iso8601()。
     */
    public static boolean isIso8601(String token) {
        if (token.length() < 8) return false;
        if (!token.contains("T") && !token.contains("-")) return false;

        // 处理 Z 后缀
        String candidate = token.endsWith("Z") ? token.substring(0, token.length() - 1) + "+00:00" : token;
        try {
            // 尝试解析（兼容各种 ISO 格式）
            if (candidate.contains("T")) {
                LocalDateTime.parse(candidate, DateTimeFormatter.ISO_DATE_TIME);
            } else {
                // 纯日期格式
                LocalDateTime.parse(candidate + "T00:00:00", DateTimeFormatter.ISO_DATE_TIME);
            }
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /**
     * JWT 形状检测 — 借鉴 Headroom _is_jwt_shape()。
     * <p>
     * 只检查形状（三段 base64url），不验证签名。
     */
    public static boolean isJwtShape(String token) {
        long dotCount = token.chars().filter(c -> c == '.').count();
        if (dotCount != JWT_SEGMENT_COUNT - 1) return false;

        String[] segments = token.split("\\.");
        if (segments.length != JWT_SEGMENT_COUNT) return false;

        for (String seg : segments) {
            if (seg.length() < JWT_MIN_SEGMENT_BYTES) return false;
            try {
                // base64url 解码需要填充到 4 的倍数
                String padded = seg + "=".repeat((-seg.length()) % 4);
                Base64.getUrlDecoder().decode(padded);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        return true;
    }

    /**
     * Hex 哈希检测 — 借鉴 Headroom _is_hex_hash()。
     * <p>
     * 长度必须是 32/40/64 之一，且所有字符都是十六进制。
     */
    public static boolean isHexHash(String token) {
        if (!HEX_HASH_LENGTHS.contains(token.length())) return false;
        try {
            new java.math.BigInteger(token, 16);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  辅助方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 检查 system prompt 是否有 cache 稳定性问题（便捷方法）。
     */
    public boolean hasVolatileContent(String systemPrompt) {
        return !detectVolatileContent(systemPrompt).cachePrefixStable();
    }

    /**
     * 获取检测到的动态内容数量。
     */
    public int volatileContentCount(String systemPrompt) {
        return detectVolatileContent(systemPrompt).totalFindings();
    }
}
