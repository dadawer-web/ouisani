package com.ouisani.aios.core.memory;

/**
 * Token 内存耗尽杀手 (OOM Killer) 的 7 层递进式压缩策略
 * <p>
 * OS 类比: Linux 的 OOM Killer 在内存压力下逐级回收——
 * 从最轻量的 Page Cache 驱逐，到最激进的进程杀除。
 * 每一层都比上一层代价更高、信息损失更大。
 */
public enum OomCompressionLevel {
    /** L1: 清理缓存 (Page Cache Evict) - 删除超长的纯文本工具输出 (如长篇 HTML)，只保留结果摘要 */
    TOOL_OUTPUT_TRUNCATION,

    /** L2: 淘汰僵尸进程 (Zombie Reap) - 删除对话历史中大模型那些不包含具体工具调用的废话回复 */
    REMOVE_AI_SLOPS,

    /** L3: 压入 ZRAM (ZRAM Swap) - 将对话的前 30% 原封不动地压入 TokenZram (基于特定算法的折叠) */
    SWAP_OUT_HISTORY,

    /** L4: 语义合并 (TLB Compaction) - 拉起廉价大模型，将前 50% 的对话总结成一段高密度的语义块 */
    SEMANTIC_COMPRESS,

    /** L5: 激进截断 (Aggressive Truncate) - 丢弃所有历史，只保留任务的原始指令和最后 2 轮对话 */
    AGGRESSIVE_TRUNCATE,

    /** L6: 提取长牛记忆 (Extract to Disk) - 将当前所有线索提取进向量库，清空除 System Prompt 外的所有内存 */
    EXTRACT_TO_LONG_TERM,

    /** L7: 内核恐慌 (Kernel Panic) - 真的救不回来了，抛出 TokenOomException 让上层建筑决定生死 */
    KERNEL_PANIC
}
