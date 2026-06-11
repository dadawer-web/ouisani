package com.ouisani.aios.core.trace;

/**
 * 追踪模式 — 控制 TraceProxyFactory 的拦截行为。
 * <p>
 * OS 类比: strace 的 -e trace= 选项，控制追踪哪些系统调用。
 * <ul>
 *   <li>DISABLED — 不追踪（生产模式，零开销）</li>
 *   <li>RECORD — 录制模式，所有 LLM 调用被记录到 TraceManager</li>
 *   <li>REPLAY — 回放模式，优先从历史记录返回结果，跳过真实 LLM 调用</li>
 * </ul>
 */
public enum TraceMode {
    DISABLED,
    RECORD,
    REPLAY
}
