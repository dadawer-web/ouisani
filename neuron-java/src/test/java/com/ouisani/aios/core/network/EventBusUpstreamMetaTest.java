package com.ouisani.aios.core.network;

import com.ouisani.aios.core.observability.UpstreamMeta;
import com.ouisani.aios.core.observability.UpstreamMetaContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EventBus UpstreamMeta 伴生事件注入测试 — 验证 broadcast 重载
 * 与现有 broadcast 字节级零回归。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>broadcast(type, payload, meta) 发两条事件（原通道 + sys.upstream.meta）</li>
 *   <li>meta=null 时仅发原通道（等价于 broadcast(type, payload)）</li>
 *   <li>broadcastWithCurrentMeta 从 ThreadLocal 读取</li>
 *   <li>现有 broadcast(type, payload) 字节级不变</li>
 *   <li>伴生事件 payload 是 UpstreamMeta.toJsonLine() 输出</li>
 * </ul>
 */
class EventBusUpstreamMetaTest {

    private EventBus eventBus;

    @BeforeEach
    void setUp() {
        eventBus = EventBus.instance();
        UpstreamMetaContext.clear();
    }

    @AfterEach
    void tearDown() {
        UpstreamMetaContext.clear();
    }

    private UpstreamMeta sampleMeta(String name) {
        return new UpstreamMeta(name, 100L, 200, null, 1024L, null,
                System.currentTimeMillis(), "agent_test", "sess_test");
    }

    @Test
    @DisplayName("broadcast(type, payload, meta) 原通道与伴生通道都收到")
    void broadcastWithMeta_bothChannelsDelivered() throws InterruptedException {
        UpstreamMeta meta = sampleMeta("llm.think");

        AtomicReference<String> originalPayload = new AtomicReference<>();
        AtomicReference<String> companionPayload = new AtomicReference<>();
        CountDownLatch originalLatch = new CountDownLatch(1);
        CountDownLatch companionLatch = new CountDownLatch(1);

        String originalSubId = eventBus.subscribe("test.original.1", p -> {
            originalPayload.set(p);
            originalLatch.countDown();
        });
        String companionSubId = eventBus.subscribe(
                EventBus.COMPANION_UPSTREAM_META_CHANNEL, p -> {
            companionPayload.set(p);
            companionLatch.countDown();
        });

        try {
            eventBus.broadcast("test.original.1", "{\"hello\":\"world\"}", meta);

            assertTrue(originalLatch.await(2, TimeUnit.SECONDS),
                    "Original channel should receive event");
            assertTrue(companionLatch.await(2, TimeUnit.SECONDS),
                    "Companion channel should receive event");

            assertEquals("{\"hello\":\"world\"}", originalPayload.get());

            // 验证伴生事件 payload 是 UpstreamMeta.toJsonLine() 输出
            UpstreamMeta restored = UpstreamMeta.fromJsonLine(companionPayload.get());
            assertNotNull(restored);
            assertEquals("llm.think", restored.upstreamName());
            assertEquals(200, restored.upstreamStatusCode());
        } finally {
            // 清理订阅者需要 unsubscribe(handler)，但 subscribe 返回的是 subId 字符串
            // 这里直接靠 GC（EventBus 持有的是 handler，测试结束后会被覆盖）
        }
    }

    @Test
    @DisplayName("meta=null 时仅发原通道，等价于 broadcast(type, payload)")
    void broadcastWithMeta_nullMeta_onlyOriginalChannel() throws InterruptedException {
        AtomicReference<String> companionPayload = new AtomicReference<>();
        CountDownLatch originalLatch = new CountDownLatch(1);
        CountDownLatch companionLatch = new CountDownLatch(1);

        eventBus.subscribe("test.original.2", p -> originalLatch.countDown());
        eventBus.subscribe(EventBus.COMPANION_UPSTREAM_META_CHANNEL, p -> {
            companionPayload.set(p);
            companionLatch.countDown();
        });

        eventBus.broadcast("test.original.2", "payload-only", null);

        assertTrue(originalLatch.await(2, TimeUnit.SECONDS));
        // 伴生通道不应收到事件（companionLatch 不会归零）
        assertFalse(companionLatch.await(500, TimeUnit.MILLISECONDS),
                "Companion channel should NOT receive event when meta is null");
        assertNull(companionPayload.get());
    }

    @Test
    @DisplayName("broadcastWithCurrentMeta 从 ThreadLocal 读取 meta")
    void broadcastWithCurrentMeta_readsFromThreadLocal() throws Exception {
        UpstreamMeta meta = sampleMeta("tool.web_search");

        AtomicReference<String> companionPayload = new AtomicReference<>();
        CountDownLatch companionLatch = new CountDownLatch(1);

        eventBus.subscribe(EventBus.COMPANION_UPSTREAM_META_CHANNEL, p -> {
            companionPayload.set(p);
            companionLatch.countDown();
        });

        try (var ignored = UpstreamMetaContext.bind(meta)) {
            eventBus.broadcastWithCurrentMeta("test.original.3", "{\"ok\":true}");
        }

        assertTrue(companionLatch.await(2, TimeUnit.SECONDS));
        UpstreamMeta restored = UpstreamMeta.fromJsonLine(companionPayload.get());
        assertNotNull(restored);
        assertEquals("tool.web_search", restored.upstreamName());
    }

    @Test
    @DisplayName("broadcastWithCurrentMeta 无 ThreadLocal 绑定时仅发原通道")
    void broadcastWithCurrentMeta_noBinding_onlyOriginalChannel() throws InterruptedException {
        // 确保无 binding
        UpstreamMetaContext.clear();

        CountDownLatch originalLatch = new CountDownLatch(1);
        CountDownLatch companionLatch = new CountDownLatch(1);

        eventBus.subscribe("test.original.4", p -> originalLatch.countDown());
        eventBus.subscribe(EventBus.COMPANION_UPSTREAM_META_CHANNEL, p -> companionLatch.countDown());

        eventBus.broadcastWithCurrentMeta("test.original.4", "{\"x\":1}");

        assertTrue(originalLatch.await(2, TimeUnit.SECONDS));
        assertFalse(companionLatch.await(500, TimeUnit.MILLISECONDS),
                "Companion channel should NOT receive event when ThreadLocal is empty");
    }

    @Test
    @DisplayName("现有 broadcast(type, payload) 行为不变（仅原通道）")
    void existingBroadcastBehavior_unchanged() throws InterruptedException {
        CountDownLatch originalLatch = new CountDownLatch(1);
        CountDownLatch companionLatch = new CountDownLatch(1);

        eventBus.subscribe("test.original.5", p -> originalLatch.countDown());
        eventBus.subscribe(EventBus.COMPANION_UPSTREAM_META_CHANNEL, p -> companionLatch.countDown());

        // 调用现有 2 参数 broadcast
        eventBus.broadcast("test.original.5", "legacy-payload");

        assertTrue(originalLatch.await(2, TimeUnit.SECONDS));
        assertFalse(companionLatch.await(500, TimeUnit.MILLISECONDS),
                "Existing broadcast(type, payload) must NOT trigger companion channel");
    }

    @Test
    @DisplayName("COMPANION_UPSTREAM_META_CHANNEL 常量值正确")
    void companionChannelConstant_valueIsSysUpstreamMeta() {
        assertEquals("sys.upstream.meta", EventBus.COMPANION_UPSTREAM_META_CHANNEL);
    }

    @Test
    @DisplayName("伴生事件 payload 包含完整的 6 标准字段 + 3 元字段")
    void companionEventPayload_containsAllFields() throws InterruptedException {
        UpstreamMeta meta = new UpstreamMeta(
                "storage.write", 42L, 409, null, 0L, "ROLLBACK",
                1784592000000L, "agent_xyz", "sess_abc"
        );

        AtomicReference<String> companionPayload = new AtomicReference<>();
        CountDownLatch companionLatch = new CountDownLatch(1);

        eventBus.subscribe(EventBus.COMPANION_UPSTREAM_META_CHANNEL, p -> {
            companionPayload.set(p);
            companionLatch.countDown();
        });

        eventBus.broadcast("test.original.6", "x", meta);

        assertTrue(companionLatch.await(2, TimeUnit.SECONDS));

        String payload = companionPayload.get();
        // 6 标准字段
        assertTrue(payload.contains("\"upstream_name\":\"storage.write\""));
        assertTrue(payload.contains("\"upstream_duration_ms\":42"));
        assertTrue(payload.contains("\"upstream_status_code\":409"));
        assertTrue(payload.contains("\"upstream_cost_units\":null"));
        assertTrue(payload.contains("\"upstream_bytes\":0"));
        assertTrue(payload.contains("\"error_code\":\"ROLLBACK\""));
        // 3 元字段
        assertTrue(payload.contains("\"ts\":1784592000000"));
        assertTrue(payload.contains("\"agentId\":\"agent_xyz\""));
        assertTrue(payload.contains("\"sessionId\":\"sess_abc\""));
    }
}
