package com.ouisani.aios.core.context.prefix;

/**
 * Prompt 段落类型 — 前缀复用优化的段落分类。
 * <p>
 * 借鉴 LMCache 和 OpenAI/Anthropic 的 Prompt Caching 最佳实践：
 * 公共部分必须在最前面，且保持绝对稳定。
 * <p>
 * 段落按渲染顺序排列（ordinal 即渲染优先级）：
 * <ol>
 *   <li>{@link #STATIC_SYSTEM} — 静态系统预设（最前面，永远不变）</li>
 *   <li>{@link #SHARED_CONTEXT} — 全团队共享的项目上下文（通过 LMCache 命中的部分）</li>
 *   <li>{@link #TOOL_LIST} — 特定工具清单（因 Agent 而异）</li>
 *   <li>{@link #DYNAMIC_TASK} — 当前轮次的对话/动态 Task 变量（放在最末尾）</li>
 * </ol>
 * <p>
 * 只要保证前面 80% 的内容顺序一个字符都不差，
 * 在本地接 LMCache 或调公网 API 时，费用和时间都能直接断崖式下降 80%。
 */
public enum SegmentType {

    /** 静态系统预设 — 最前面，永远不变（角色定义、行为准则等） */
    STATIC_SYSTEM,

    /** 全团队共享的项目上下文 — 通过 LMCache 命中的部分（项目源码树、API 文档等） */
    SHARED_CONTEXT,

    /** 特定工具清单 — 因 Agent 而异（不同 Agent 有不同工具集） */
    TOOL_LIST,

    /** 当前轮次的对话/动态 Task 变量 — 放在最末尾（用户输入、任务状态等） */
    DYNAMIC_TASK
}
