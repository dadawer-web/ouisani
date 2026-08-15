package com.ouisani.aios.core.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;

/**
 * 流式中断钩子 — 让 Stop 按钮能立即取消正在进行的 LLM 流式响应。
 * <p>
 * 借鉴 OpenWorker {@code engine.py:120-148} 的 {@code request_interrupt()}：
 * <ul>
 *   <li><b>mid-stream</b>：cancel() 关闭 InputStream，导致阻塞中的
 *       {@code BufferedReader.readLine()} 抛出 {@code IOException}，
 *       SSE 循环捕获后返回已收到的 partial response</li>
 *   <li><b>loop 检查点</b>：QueryEngine 每轮开始和工具执行后检查
 *       {@code interruptRequested}，提前退出 Agent Loop</li>
 * </ul>
 * <p>
 * <b>ThreadLocal 设计</b>：QueryEngine → AiosSdk → LlmRouter → OpenAiAdapter
 * 整个调用链在同一虚拟线程上同步执行，ThreadLocal 确保每个 Agent 的
 * 中断钩子互不干扰。
 * <p>
 * <b>使用流程</b>：
 * <pre>
 * // QueryEngine 侧
 * StreamCancellationHook hook = new StreamCancellationHook();
 * StreamCancellationHook.bindCurrent(hook);
 * try {
 *     llmResponse = sdk.thinkStream(...);  // OpenAiAdapter 内部读取 hook
 * } finally {
 *     StreamCancellationHook.unbindCurrent();
 * }
 *
 * // Stop 按钮侧
 * queryEngine.requestInterrupt();  // → hook.cancel()
 * </pre>
 */
public class StreamCancellationHook {

    private static final Logger log = LoggerFactory.getLogger(StreamCancellationHook.class);

    /** ThreadLocal 钩子 — 每个 Agent 线程独立 */
    private static final ThreadLocal<StreamCancellationHook> CURRENT = new ThreadLocal<>();

    /** cancel flag — volatile 确保跨线程可见性 */
    private volatile boolean cancelled = false;

    /** 当前绑定的 InputStream — cancel() 时关闭它以打断 readLine() */
    private volatile InputStream stream;

    /**
     * 绑定当前线程的钩子。
     * 在 QueryEngine 调用 LLM 前调用。
     */
    public static void bindCurrent(StreamCancellationHook hook) {
        CURRENT.set(hook);
    }

    /**
     * 解绑当前线程的钩子。
     * 在 QueryEngine LLM 调用完成后（finally 中）调用。
     */
    public static void unbindCurrent() {
        CURRENT.remove();
    }

    /**
     * 获取当前线程的钩子（供 OpenAiAdapter 使用）。
     * 可能为 null（非 QueryEngine 发起的调用，如测试）。
     */
    public static StreamCancellationHook current() {
        return CURRENT.get();
    }

    /**
     * 绑定 InputStream — OpenAiAdapter 在开始读取 SSE 流前调用。
     * cancel() 会关闭此 stream 以打断阻塞中的 readLine()。
     */
    public void bind(InputStream is) {
        this.stream = is;
    }

    /**
     * 解绑 InputStream — OpenAiAdapter 在读取完成后调用。
     */
    public void unbind() {
        this.stream = null;
    }

    /** 是否已被取消 */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * 取消当前流式请求 — 由 QueryEngine.requestInterrupt() 调用。
     * <p>
     * 设置 cancel flag 并关闭 InputStream：
     * <ul>
     *   <li>如果 readLine() 正在阻塞 → 抛出 IOException → SSE 循环捕获，返回 partial</li>
     *   <li>如果 readLine() 正在解析 → 下次循环检查 isCancelled() → break</li>
     *   <li>如果 HTTP 请求还未返回 → send() 的 InputStream 尚未绑定，
     *       cancel flag 会在 SSE 循环开始后被检查</li>
     * </ul>
     */
    public void cancel() {
        cancelled = true;
        InputStream is = stream;
        if (is != null) {
            try {
                is.close();
                log.info("[StreamCancellationHook] InputStream closed to interrupt blocked readLine()");
            } catch (Exception e) {
                log.debug("[StreamCancellationHook] InputStream close exception (expected during cancel): {}", e.getMessage());
            }
        }
    }

    /** 重置状态 — 供复用（每次 query() 开始时调用） */
    public void reset() {
        cancelled = false;
        stream = null;
    }
}
