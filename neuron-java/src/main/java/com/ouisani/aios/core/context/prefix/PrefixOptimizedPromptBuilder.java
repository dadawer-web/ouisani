package com.ouisani.aios.core.context.prefix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.HexFormat;

/**
 * 前缀优化 Prompt 构建器 — 强制执行前缀复用友好的渲染顺序。
 * <p>
 * 借鉴 LMCache 和 OpenAI/Anthropic 的 Prompt Caching 最佳实践：
 * <ul>
 *   <li>前面 80% 的内容顺序必须保持绝对稳定</li>
 *   <li>静态部分在前，动态部分在后</li>
 *   <li>每个段落计算哈希，用于前缀匹配</li>
 * </ul>
 * <p>
 * <h3>渲染顺序</h3>
 * <pre>
 * [静态系统预设] (最前面，永远不变)
 *     ↓
 * [全团队共享的项目上下文/文件内容] (通过 LMCache 命中的部分)
 *     ↓
 * [特定工具清单] (因 Agent 而异)
 *     ↓
 * [当前轮次的对话/动态 Task 变量] (放在最末尾)
 * </pre>
 * <p>
 * <h3>使用示例</h3>
 * <pre>{@code
 * String prompt = PrefixOptimizedPromptBuilder.create()
 *     .withStaticSystem("# AIOS Agent\nYou are an intelligent coding assistant...")
 *     .withSharedContext(projectAst)  // 全团队共享的项目源码树
 *     .withTools(toolRegistry.toolsDescription())
 *     .withDynamicTask("用户要求: 实现一个番茄钟Web应用")
 *     .build();
 * }</pre>
 * <p>
 * 构建结果会自动计算前缀哈希，可用于判断缓存是否仍然有效。
 *
 * @see PromptSegment
 * @see SegmentType
 */
public final class PrefixOptimizedPromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(PrefixOptimizedPromptBuilder.class);

    private final List<PromptSegment> segments = new ArrayList<>();

    private PrefixOptimizedPromptBuilder() {
    }

    /**
     * 创建新的构建器实例。
     *
     * @return 新的 PrefixOptimizedPromptBuilder
     */
    public static PrefixOptimizedPromptBuilder create() {
        return new PrefixOptimizedPromptBuilder();
    }

    // ════════════════════════════════════════════════════════════════
    //  段落添加（按类型）
    // ════════════════════════════════════════════════════════════════

    /**
     * 添加静态系统预设段落 — 最前面，永远不变。
     * <p>
     * 包含 Agent 角色定义、行为准则等不变内容。
     *
     * @param content 静态系统预设内容
     * @return this（链式调用）
     */
    public PrefixOptimizedPromptBuilder withStaticSystem(String content) {
        addSegment(SegmentType.STATIC_SYSTEM, content);
        return this;
    }

    /**
     * 添加全团队共享的项目上下文段落 — 通过 LMCache 命中的部分。
     * <p>
     * 包含项目源码树（AST）、API 文档、CLAUDE.md 等全团队共享的内容。
     * 这部分内容可以被 LMCache 缓存，下游 Agent 直接从 KV Cache 加载。
     *
     * @param content 共享上下文内容
     * @return this（链式调用）
     */
    public PrefixOptimizedPromptBuilder withSharedContext(String content) {
        addSegment(SegmentType.SHARED_CONTEXT, content);
        return this;
    }

    /**
     * 添加特定工具清单段落 — 因 Agent 而异。
     * <p>
     * 不同 Agent 有不同的工具集，这部分内容会变化但相对稳定。
     *
     * @param content 工具清单内容
     * @return this（链式调用）
     */
    public PrefixOptimizedPromptBuilder withTools(String content) {
        addSegment(SegmentType.TOOL_LIST, content);
        return this;
    }

    /**
     * 添加当前轮次的对话/动态 Task 变量段落 — 放在最末尾。
     * <p>
     * 包含用户输入、任务状态、运行时变量等动态内容。
     *
     * @param content 动态任务内容
     * @return this（链式调用）
     */
    public PrefixOptimizedPromptBuilder withDynamicTask(String content) {
        addSegment(SegmentType.DYNAMIC_TASK, content);
        return this;
    }

    // ════════════════════════════════════════════════════════════════
    //  构建
    // ════════════════════════════════════════════════════════════════

    /**
     * 构建最终的 Prompt 字符串。
     * <p>
     * 段落按 {@link SegmentType} 的 ordinal 顺序排列，
     * 确保静态部分在前，动态部分在后。
     *
     * @return 拼接后的完整 Prompt
     */
    public String build() {
        // 按 SegmentType ordinal 排序（确保渲染顺序正确）
        List<PromptSegment> sorted = new ArrayList<>(segments);
        sorted.sort(Comparator.comparingInt(s -> s.type().ordinal()));

        StringBuilder sb = new StringBuilder();
        for (PromptSegment segment : sorted) {
            if (!segment.content().isEmpty()) {
                sb.append(segment.content());
                if (!segment.content().endsWith("\n")) {
                    sb.append('\n');
                }
                sb.append('\n');
            }
        }
        return sb.toString().trim();
    }

    /**
     * 构建并返回所有段落（用于前缀哈希计算和缓存匹配）。
     *
     * @return 排序后的段落列表
     */
    public List<PromptSegment> buildSegments() {
        List<PromptSegment> sorted = new ArrayList<>(segments);
        sorted.sort(Comparator.comparingInt(s -> s.type().ordinal()));
        return Collections.unmodifiableList(sorted);
    }

    /**
     * 计算前缀哈希 — 所有静态段落的累积哈希。
     * <p>
     * 借鉴 LMCache 的前缀匹配：只要前缀哈希不变，缓存就仍然有效。
     * 前缀哈希 = SHA-256(STATIC_SYSTEM 内容 + SHARED_CONTEXT 内容)
     *
     * @return 前缀哈希，如果没有静态段落返回空字符串
     */
    public String prefixHash() {
        List<PromptSegment> sorted = new ArrayList<>(segments);
        sorted.sort(Comparator.comparingInt(s -> s.type().ordinal()));

        StringBuilder prefixContent = new StringBuilder();
        for (PromptSegment segment : sorted) {
            if (segment.isStatic()) {
                prefixContent.append(segment.content());
            } else {
                break; // 遇到第一个非静态段落就停止
            }
        }
        if (prefixContent.isEmpty()) {
            return "";
        }
        return hashContent(prefixContent.toString());
    }

    /**
     * 计算完整哈希 — 所有段落的累积哈希。
     *
     * @return 完整哈希
     */
    public String fullHash() {
        return hashContent(build());
    }

    /**
     * 静态内容占比 — 静态段落的总字符数 / 全部段落的总字符数。
     * <p>
     * 占比越高，前缀缓存命中率越高。建议保持在 80% 以上。
     *
     * @return 静态内容占比（0.0 ~ 1.0）
     */
    public double staticRatio() {
        int totalChars = segments.stream().mapToInt(PromptSegment::length).sum();
        if (totalChars == 0) return 0.0;
        int staticChars = segments.stream()
                .filter(PromptSegment::isStatic)
                .mapToInt(PromptSegment::length)
                .sum();
        return (double) staticChars / totalChars;
    }

    /**
     * 获取段落数量。
     *
     * @return 段落数量
     */
    public int segmentCount() {
        return segments.size();
    }

    // ════════════════════════════════════════════════════════════════
    //  内部方法
    // ════════════════════════════════════════════════════════════════

    private void addSegment(SegmentType type, String content) {
        if (content == null) content = "";
        segments.add(PromptSegment.of(type, content));
    }

    /**
     * 计算文本内容的 SHA-256 哈希。
     *
     * @param content 文本内容
     * @return 十六进制哈希字符串
     */
    public static String hashContent(String content) {
        if (content == null || content.isEmpty()) {
            return "0".repeat(64);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available in Java
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
