package com.ouisani.aios.core.compact;

import com.ouisani.aios.core.AgentTask.TokenRecord;

import java.util.List;

/**
 * 语义边界检测器 — 供 {@link CompactionMode#SEMANTIC} 模式可插拔使用。
 * <p>
 * 默认实现 {@link CompactService#NOOP_BOUNDARY_DETECTOR} 永远返回 false，
 * 即 SEMANTIC 模式退化为按 PROACTIVE 阈值 + 内在边界（工具调用闭合点 / write_todos / 轮次边界）。
 * <p>
 * P2 实现示例（不在本次范围）：注入 {@code LlmProvider} + {@code VectorNode}，
 * 对每条消息前 512 字符 embed，连续窗口间余弦相似度跌破阈值 ⇒ 边界。
 *
 * @see CompactService#setBoundaryDetector(SemanticBoundaryDetector)
 */
@FunctionalInterface
public interface SemanticBoundaryDetector {

    /**
     * 判断给定索引是否为语义边界（话题切换点）。
     *
     * @param context 完整上下文记录列表
     * @param idx     待判定的记录索引
     * @return true 表示该索引处是安全切点
     */
    boolean isBoundary(List<TokenRecord> context, int idx);
}
