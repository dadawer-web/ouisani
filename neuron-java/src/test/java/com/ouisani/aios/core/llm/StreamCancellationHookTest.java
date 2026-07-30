package com.ouisani.aios.core.llm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link StreamCancellationHook} 单元测试 — 验证流式中断机制。
 * <p>
 * 借鉴 OpenWorker engine.py:120-148 的 request_interrupt()：
 * <ul>
 *   <li>cancel() 设置 flag + 关闭 InputStream → 打断阻塞中的 readLine()</li>
 *   <li>ThreadLocal 绑定/解绑 → 每个 Agent 线程独立</li>
 *   <li>reset() 清除状态 → 每次 query() 开始时重置</li>
 * </ul>
 */
class StreamCancellationHookTest {

    /**
     * 模拟真实 HTTP InputStream — close() 后 read() 抛 IOException。
     * （ByteArrayInputStream.close() 是 no-op，不能测试关闭行为）
     */
    private static class RealisticInputStream extends InputStream {
        private final ByteArrayInputStream delegate;
        private volatile boolean closed = false;

        RealisticInputStream(String data) {
            this.delegate = new ByteArrayInputStream(data.getBytes());
        }

        @Override
        public int read() throws IOException {
            if (closed) throw new IOException("Stream closed");
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (closed) throw new IOException("Stream closed");
            return delegate.read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            delegate.close();
        }
    }

    @AfterEach
    void cleanup() {
        StreamCancellationHook.unbindCurrent();
    }

    @Test
    void freshHook_notCancelled() {
        StreamCancellationHook hook = new StreamCancellationHook();
        assertFalse(hook.isCancelled(), "新创建的 hook 不应处于 cancelled 状态");
    }

    @Test
    void cancel_setsFlag() {
        StreamCancellationHook hook = new StreamCancellationHook();
        hook.cancel();
        assertTrue(hook.isCancelled(), "cancel() 后应处于 cancelled 状态");
    }

    @Test
    void cancel_closesBoundInputStream() throws IOException {
        StreamCancellationHook hook = new StreamCancellationHook();
        InputStream is = new RealisticInputStream("data: test");
        hook.bind(is);
        hook.cancel();
        // InputStream 应被关闭 — 读取应抛出 IOException
        assertThrows(IOException.class, () -> is.read(),
                "cancel() 应关闭绑定的 InputStream，后续 read() 应抛出 IOException");
    }

    @Test
    void cancel_withoutBoundStream_doesNotThrow() {
        StreamCancellationHook hook = new StreamCancellationHook();
        // 未绑定 InputStream 时 cancel() 不应抛异常
        assertDoesNotThrow(() -> hook.cancel());
        assertTrue(hook.isCancelled());
    }

    @Test
    void cancel_withAlreadyClosedStream_doesNotThrow() throws IOException {
        StreamCancellationHook hook = new StreamCancellationHook();
        InputStream is = new ByteArrayInputStream("data".getBytes());
        is.close();
        hook.bind(is);
        // 已关闭的 stream 再 close 不应抛异常
        assertDoesNotThrow(() -> hook.cancel());
        assertTrue(hook.isCancelled());
    }

    @Test
    void reset_clearsCancelledFlag() {
        StreamCancellationHook hook = new StreamCancellationHook();
        hook.cancel();
        assertTrue(hook.isCancelled());
        hook.reset();
        assertFalse(hook.isCancelled(), "reset() 后应清除 cancelled 状态");
    }

    @Test
    void reset_clearsBoundStream() {
        StreamCancellationHook hook = new StreamCancellationHook();
        hook.bind(new ByteArrayInputStream("data".getBytes()));
        hook.reset();
        // reset 后无绑定的 stream — cancel 不应抛异常
        assertDoesNotThrow(() -> hook.cancel());
    }

    @Test
    void unbind_clearsBoundStream() {
        StreamCancellationHook hook = new StreamCancellationHook();
        hook.bind(new ByteArrayInputStream("data".getBytes()));
        hook.unbind();
        hook.cancel(); // 无 stream 可关，不应抛异常
        assertTrue(hook.isCancelled());
    }

    // ════════════════════════════════════════════════════════════════
    //  ThreadLocal 绑定
    // ════════════════════════════════════════════════════════════════

    @Test
    void bindCurrent_makesHookAccessibleViaCurrent() {
        StreamCancellationHook hook = new StreamCancellationHook();
        StreamCancellationHook.bindCurrent(hook);
        assertSame(hook, StreamCancellationHook.current(),
                "bindCurrent 后 current() 应返回同一 hook 实例");
    }

    @Test
    void unbindCurrent_clearsHook() {
        StreamCancellationHook.bindCurrent(new StreamCancellationHook());
        StreamCancellationHook.unbindCurrent();
        assertNull(StreamCancellationHook.current(),
                "unbindCurrent 后 current() 应返回 null");
    }

    @Test
    void current_returnsNullWhenNotBound() {
        assertNull(StreamCancellationHook.current(),
                "未绑定时 current() 应返回 null");
    }

    @Test
    void bindCurrent_replacesPreviousHook() {
        StreamCancellationHook hook1 = new StreamCancellationHook();
        StreamCancellationHook hook2 = new StreamCancellationHook();
        StreamCancellationHook.bindCurrent(hook1);
        StreamCancellationHook.bindCurrent(hook2);
        assertSame(hook2, StreamCancellationHook.current(),
                "二次 bindCurrent 应替换前一个 hook");
    }

    // ════════════════════════════════════════════════════════════════
    //  端到端：cancel 后 InputStream 读取行为
    // ════════════════════════════════════════════════════════════════

    @Test
    void endToEnd_cancelDuringRead_throwsIOException() throws IOException {
        // 模拟 OpenAiAdapter 的 SSE 读取场景
        InputStream is = new RealisticInputStream("data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n");
        StreamCancellationHook hook = new StreamCancellationHook();
        hook.bind(is);

        // 先读一行（模拟 SSE 解析）
        byte[] buf = new byte[100];
        is.read(buf);

        // 用户按 Stop
        hook.cancel();

        // 再次读取应抛出 IOException（stream 已关闭）
        assertThrows(IOException.class, () -> is.read(),
                "cancel() 关闭 InputStream 后，read() 应抛出 IOException");
        assertTrue(hook.isCancelled());
    }

    @Test
    void endToEnd_threadLocalIsolation() throws Exception {
        // 验证不同线程的 ThreadLocal 互不干扰
        StreamCancellationHook mainHook = new StreamCancellationHook();
        StreamCancellationHook.bindCurrent(mainHook);

        Thread t = new Thread(() -> {
            // 子线程不应看到主线程的 hook
            assertNull(StreamCancellationHook.current(),
                    "子线程不应看到主线程的 ThreadLocal hook");

            // 子线程绑定自己的 hook
            StreamCancellationHook childHook = new StreamCancellationHook();
            StreamCancellationHook.bindCurrent(childHook);
            assertSame(childHook, StreamCancellationHook.current());
            StreamCancellationHook.unbindCurrent();
        });
        t.start();
        t.join(5000);

        // 主线程的 hook 仍在
        assertSame(mainHook, StreamCancellationHook.current(),
                "子线程的操作不应影响主线程的 ThreadLocal hook");
    }
}
