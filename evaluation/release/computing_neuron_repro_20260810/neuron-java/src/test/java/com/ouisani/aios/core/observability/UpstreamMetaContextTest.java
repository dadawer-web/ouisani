package com.ouisani.aios.core.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UpstreamMetaContext 单元测试 — 验证 ThreadLocal 容器的 bind/runWith 范式
 * 与堆栈式嵌套语义。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>单层 set/clear</li>
 *   <li>嵌套 bind 后内层 close 时外层 current() 恢复</li>
 *   <li>runWithMeta 回调范式</li>
 *   <li>null meta 不抛异常</li>
 *   <li>无绑定时 current() 返回 null</li>
 * </ul>
 */
class UpstreamMetaContextTest {

    @AfterEach
    void tearDown() {
        UpstreamMetaContext.clear();
    }

    private UpstreamMeta sampleMeta(String name) {
        return new UpstreamMeta(name, 100L, 200, null, 1024L, null,
                System.currentTimeMillis(), "agent_test", "sess_test");
    }

    @Test
    @DisplayName("无绑定时 current() 返回 null")
    void current_noBinding_returnsNull() {
        assertNull(UpstreamMetaContext.current());
    }

    @Test
    @DisplayName("bind 后 current() 返回绑定的 meta，close 后恢复 null")
    void bind_singleLayer_setAndRestore() throws Exception {
        UpstreamMeta meta = sampleMeta("llm.think");

        try (var ignored = UpstreamMetaContext.bind(meta)) {
            assertSame(meta, UpstreamMetaContext.current());
            assertEquals("llm.think", UpstreamMetaContext.current().upstreamName());
        }

        assertNull(UpstreamMetaContext.current());
    }

    @Test
    @DisplayName("嵌套 bind：内层 close 后外层 current() 恢复为外层 meta（堆栈式语义）")
    void bind_nested_innerCloseRestoresOuter() throws Exception {
        UpstreamMeta outer = sampleMeta("llm.think");
        UpstreamMeta inner = sampleMeta("storage.write");

        try (var outerBind = UpstreamMetaContext.bind(outer)) {
            assertSame(outer, UpstreamMetaContext.current());

            try (var innerBind = UpstreamMetaContext.bind(inner)) {
                assertSame(inner, UpstreamMetaContext.current());
                assertEquals("storage.write", UpstreamMetaContext.current().upstreamName());
            }

            // 内层 close 后应恢复为外层
            assertSame(outer, UpstreamMetaContext.current());
            assertEquals("llm.think", UpstreamMetaContext.current().upstreamName());
        }

        assertNull(UpstreamMetaContext.current());
    }

    @Test
    @DisplayName("嵌套 bind：多层嵌套正确恢复（模拟 syscall 重入）")
    void bind_deepNesting_restoresCorrectly() throws Exception {
        UpstreamMeta m1 = sampleMeta("level1");
        UpstreamMeta m2 = sampleMeta("level2");
        UpstreamMeta m3 = sampleMeta("level3");

        try (var c1 = UpstreamMetaContext.bind(m1)) {
            assertEquals("level1", UpstreamMetaContext.current().upstreamName());
            try (var c2 = UpstreamMetaContext.bind(m2)) {
                assertEquals("level2", UpstreamMetaContext.current().upstreamName());
                try (var c3 = UpstreamMetaContext.bind(m3)) {
                    assertEquals("level3", UpstreamMetaContext.current().upstreamName());
                }
                assertEquals("level2", UpstreamMetaContext.current().upstreamName());
            }
            assertEquals("level1", UpstreamMetaContext.current().upstreamName());
        }
        assertNull(UpstreamMetaContext.current());
    }

    @Test
    @DisplayName("runWithMeta 回调：执行期间 current() 可见，结束后恢复")
    void runWithMeta_visibleDuringCallback_restoredAfter() {
        assertNull(UpstreamMetaContext.current());

        UpstreamMeta meta = sampleMeta("tool.web_search");
        UpstreamMetaContext.runWithMeta(meta, () -> {
            assertSame(meta, UpstreamMetaContext.current());
            assertEquals("tool.web_search", UpstreamMetaContext.current().upstreamName());
        });

        assertNull(UpstreamMetaContext.current());
    }

    @Test
    @DisplayName("runWithMeta 回调异常时也恢复 previous（finally 语义）")
    void runWithMeta_throwsException_stillRestoresPrevious() {
        UpstreamMeta outer = sampleMeta("outer");
        UpstreamMetaContext.runWithMeta(outer, () -> {
            // 进入外层
        });

        try {
            UpstreamMetaContext.runWithMeta(sampleMeta("inner"), () -> {
                throw new RuntimeException("simulated failure");
            });
            fail("Expected exception to propagate");
        } catch (RuntimeException e) {
            assertEquals("simulated failure", e.getMessage());
        }

        // 异常后 ThreadLocal 应已恢复 null（外层 runWithMeta 也已结束）
        assertNull(UpstreamMetaContext.current());
    }

    @Test
    @DisplayName("bind null meta 不抛异常，current() 返回 null")
    void bind_nullMeta_doesNotThrow() throws Exception {
        try (var ignored = UpstreamMetaContext.bind(null)) {
            assertNull(UpstreamMetaContext.current());
        }
        assertNull(UpstreamMetaContext.current());
    }

    @Test
    @DisplayName("clear() 后 current() 返回 null")
    void clear_resetsThreadLocal() throws Exception {
        UpstreamMeta meta = sampleMeta("temp");
        try (var ignored = UpstreamMetaContext.bind(meta)) {
            assertSame(meta, UpstreamMetaContext.current());
            UpstreamMetaContext.clear();
            assertNull(UpstreamMetaContext.current());
        }
        // bind 的 close 调用 set(null)（previous 为 null），结果仍为 null
        assertNull(UpstreamMetaContext.current());
    }

    @Test
    @DisplayName("bind 多次 close（重复 close）幂等不抛")
    void bind_multipleClose_idempotent() throws Exception {
        UpstreamMeta meta = sampleMeta("test");
        AutoCloseable closeable = UpstreamMetaContext.bind(meta);
        closeable.close();
        // 第二次 close 应不抛
        assertDoesNotThrow(closeable::close);
        assertNull(UpstreamMetaContext.current());
    }
}
