package com.ouisani.aios.core.network;

import com.ouisani.aios.core.memory.VersionedMemoryStore;
import com.ouisani.aios.core.memory.providers.MemoryDomain;
import com.ouisani.aios.core.memory.providers.MemoryProvider;
import com.ouisani.aios.core.memory.providers.MemoryRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P3 单元测试 — {@link MemoryViewerRoutes} 三个 handler。
 * <p>
 * 直接调用 {@code handleList/handlePatch/handleDelete} 静态方法，不启动 Javalin，
 * 通过 {@link MemoryViewerRoutes.RouteResult} 的 status + body 断言。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>GET：正常列表、空列表、store 未配置 503、agentId 缺失 400</li>
 *   <li>PATCH：正常更新、confidence 越界 400、domain 非法 400、key 不存在 404、JSON 错误 400</li>
 *   <li>DELETE：正常删除、key 不存在 404</li>
 *   <li>JSON 输出格式：count/memories/record 字段</li>
 * </ul>
 */
class MemoryViewerRoutesTest {

    private FakeProvider delegate;
    private VersionedMemoryStore store;
    private Supplier<VersionedMemoryStore> supplier;

    @BeforeEach
    void setUp() {
        delegate = new FakeProvider();
        store = new VersionedMemoryStore(delegate);
        supplier = () -> store;
    }

    // ── GET /api/memory/{agentId} ──

    @Test
    @DisplayName("GET：列出 agent 的当前记忆（含 count + memories 数组）")
    void handleList_returnsMemoriesJson() {
        store.store("a1", new MemoryRecord("k1", "v1", "user-input",
                1000L, 0.9, MemoryDomain.USER, 1L));
        store.store("a1", new MemoryRecord("k2", "v2", "agent-inference",
                2000L, 0.5, MemoryDomain.AGENT, 1L));

        MemoryViewerRoutes.RouteResult rr = MemoryViewerRoutes.handleList(supplier, "a1");

        assertEquals(200, rr.status());
        assertTrue(rr.body().contains("\"agentId\":\"a1\""));
        assertTrue(rr.body().contains("\"count\":2"));
        assertTrue(rr.body().contains("\"key\":\"k1\""));
        assertTrue(rr.body().contains("\"key\":\"k2\""));
        assertTrue(rr.body().contains("\"domain\":\"USER\""));
        assertTrue(rr.body().contains("\"domain\":\"AGENT\""));
        assertTrue(rr.body().contains("\"confidence\":0.9"));
        assertTrue(rr.body().contains("\"confidence\":0.5"));
    }

    @Test
    @DisplayName("GET：空列表返回 count=0")
    void handleList_emptyAgent() {
        MemoryViewerRoutes.RouteResult rr = MemoryViewerRoutes.handleList(supplier, "empty-agent");

        assertEquals(200, rr.status());
        assertTrue(rr.body().contains("\"count\":0"));
        assertTrue(rr.body().contains("\"memories\":[]"));
    }

    @Test
    @DisplayName("GET：store 未配置返回 503")
    void handleList_storeNotConfigured() {
        Supplier<VersionedMemoryStore> nullSupplier = () -> null;
        MemoryViewerRoutes.RouteResult rr = MemoryViewerRoutes.handleList(nullSupplier, "a1");

        assertEquals(503, rr.status());
        assertTrue(rr.body().contains("\"error\":\"primary store not configured\""));
    }

    @Test
    @DisplayName("GET：agentId 缺失返回 400")
    void handleList_blankAgentId() {
        MemoryViewerRoutes.RouteResult rr = MemoryViewerRoutes.handleList(supplier, "");

        assertEquals(400, rr.status());
        assertTrue(rr.body().contains("\"error\":\"agentId required\""));
    }

    // ── PATCH /api/memory/{agentId}/{key} ──

    @Test
    @DisplayName("PATCH：更新 confidence + domain，返回 200 + 更新后记录")
    void handlePatch_updatesBothFields() {
        store.store("a1", new MemoryRecord("k1", "v1", "src",
                1000L, 0.5, MemoryDomain.AGENT, 1L));

        String body = "{\"confidence\":0.95,\"domain\":\"USER\"}";
        MemoryViewerRoutes.RouteResult rr = MemoryViewerRoutes.handlePatch(supplier, "a1", "k1", body);

        assertEquals(200, rr.status());
        assertTrue(rr.body().contains("\"ok\":true"));
        assertTrue(rr.body().contains("\"confidence\":0.95"));
        assertTrue(rr.body().contains("\"domain\":\"USER\""));
        assertTrue(rr.body().contains("\"version\":2"));

        // 验证 store 确实被更新
        assertEquals(0.95, store.current("a1", "k1").confidence(), 0.001);
    }

    @Test
    @DisplayName("PATCH：仅更新 confidence（domain 字段缺失）")
    void handlePatch_onlyConfidence() {
        store.store("a1", new MemoryRecord("k1", "v1", "src",
                1000L, 0.5, MemoryDomain.AGENT, 1L));

        MemoryViewerRoutes.RouteResult rr = MemoryViewerRoutes.handlePatch(
                supplier, "a1", "k1", "{\"confidence\":0.8}");

        assertEquals(200, rr.status());
        assertEquals(0.8, store.current("a1", "k1").confidence(), 0.001);
        assertEquals(MemoryDomain.AGENT, store.current("a1", "k1").domain(),
                "domain 不变");
    }

    @Test
    @DisplayName("PATCH：confidence 越界返回 400")
    void handlePatch_confidenceOutOfRange() {
        store.store("a1", new MemoryRecord("k1", "v1", "src",
                1000L, 0.5, MemoryDomain.AGENT, 1L));

        MemoryViewerRoutes.RouteResult rr = MemoryViewerRoutes.handlePatch(
                supplier, "a1", "k1", "{\"confidence\":1.5}");

        assertEquals(400, rr.status());
        assertTrue(rr.body().contains("confidence must be in [0.0, 1.0]"));
        // 未更新
        assertEquals(0.5, store.current("a1", "k1").confidence(), 0.001);
    }

    @Test
    @DisplayName("PATCH：domain 非法值返回 400")
    void handlePatch_invalidDomain() {
        store.store("a1", new MemoryRecord("k1", "v1", "src",
                1000L, 0.5, MemoryDomain.AGENT, 1L));

        MemoryViewerRoutes.RouteResult rr = MemoryViewerRoutes.handlePatch(
                supplier, "a1", "k1", "{\"domain\":\"INVALID\"}");

        assertEquals(400, rr.status());
        assertTrue(rr.body().contains("domain must be USER or AGENT"));
    }

    @Test
    @DisplayName("PATCH：key 不存在返回 404")
    void handlePatch_keyNotFound() {
        MemoryViewerRoutes.RouteResult rr = MemoryViewerRoutes.handlePatch(
                supplier, "a1", "nonexistent", "{\"confidence\":0.8}");

        assertEquals(404, rr.status());
        assertTrue(rr.body().contains("\"error\":\"key not found\""));
    }

    @Test
    @DisplayName("PATCH：JSON 解析错误返回 400")
    void handlePatch_invalidJson() {
        store.store("a1", new MemoryRecord("k1", "v1", "src",
                1000L, 0.5, MemoryDomain.AGENT, 1L));

        MemoryViewerRoutes.RouteResult rr = MemoryViewerRoutes.handlePatch(
                supplier, "a1", "k1", "not a json");

        assertEquals(400, rr.status());
        assertTrue(rr.body().contains("\"error\":\"invalid JSON"));
    }

    @Test
    @DisplayName("PATCH：body 缺 confidence 与 domain 返回 400")
    void handlePatch_emptyBody() {
        store.store("a1", new MemoryRecord("k1", "v1", "src",
                1000L, 0.5, MemoryDomain.AGENT, 1L));

        MemoryViewerRoutes.RouteResult rr = MemoryViewerRoutes.handlePatch(
                supplier, "a1", "k1", "{}");

        assertEquals(400, rr.status());
        assertTrue(rr.body().contains("at least one of confidence/domain"));
    }

    @Test
    @DisplayName("PATCH：store 未配置返回 503")
    void handlePatch_storeNotConfigured() {
        Supplier<VersionedMemoryStore> nullSupplier = () -> null;
        MemoryViewerRoutes.RouteResult rr = MemoryViewerRoutes.handlePatch(
                nullSupplier, "a1", "k1", "{\"confidence\":0.8}");

        assertEquals(503, rr.status());
    }

    @Test
    @DisplayName("PATCH：domain 大小写不敏感（user / user / User 都接受）")
    void handlePatch_domainCaseInsensitive() {
        store.store("a1", new MemoryRecord("k1", "v1", "src",
                1000L, 0.5, MemoryDomain.AGENT, 1L));

        MemoryViewerRoutes.RouteResult rr = MemoryViewerRoutes.handlePatch(
                supplier, "a1", "k1", "{\"domain\":\"user\"}");

        assertEquals(200, rr.status());
        assertEquals(MemoryDomain.USER, store.current("a1", "k1").domain());
    }

    // ── DELETE /api/memory/{agentId}/{key} ──

    @Test
    @DisplayName("DELETE：存在的 key 返回 200，store 中消失")
    void handleDelete_existingKey() {
        store.store("a1", new MemoryRecord("k1", "v1", "src",
                1000L, 0.5, MemoryDomain.AGENT, 1L));

        MemoryViewerRoutes.RouteResult rr = MemoryViewerRoutes.handleDelete(supplier, "a1", "k1");

        assertEquals(200, rr.status());
        assertTrue(rr.body().contains("\"ok\":true"));
        assertTrue(rr.body().contains("\"deletedKey\":\"k1\""));
        assertNull(store.current("a1", "k1"));
    }

    @Test
    @DisplayName("DELETE：不存在的 key 返回 404")
    void handleDelete_keyNotFound() {
        MemoryViewerRoutes.RouteResult rr = MemoryViewerRoutes.handleDelete(supplier, "a1", "ghost");

        assertEquals(404, rr.status());
        assertTrue(rr.body().contains("\"error\":\"key not found\""));
    }

    @Test
    @DisplayName("DELETE：store 未配置返回 503")
    void handleDelete_storeNotConfigured() {
        Supplier<VersionedMemoryStore> nullSupplier = () -> null;
        MemoryViewerRoutes.RouteResult rr = MemoryViewerRoutes.handleDelete(nullSupplier, "a1", "k1");

        assertEquals(503, rr.status());
    }

    // ── JSON 序列化辅助 ──

    @Test
    @DisplayName("recordToJson：所有字段都被序列化")
    void recordToJson_allFields() {
        MemoryRecord r = new MemoryRecord("k", "content with \"quotes\"",
                "src", 12345L, 0.75, MemoryDomain.USER, 3L);

        String json = MemoryViewerRoutes.recordToJson(r);

        assertTrue(json.contains("\"key\":\"k\""));
        assertTrue(json.contains("\"content\":\"content with \\\"quotes\\\"\""),
                "引号应被转义 — " + json);
        assertTrue(json.contains("\"source\":\"src\""));
        assertTrue(json.contains("\"timestamp\":12345"));
        assertTrue(json.contains("\"confidence\":0.75"));
        assertTrue(json.contains("\"domain\":\"USER\""));
        assertTrue(json.contains("\"version\":3"));
    }

    @Test
    @DisplayName("recordToJson：长 content 被截断到 500 字符")
    void recordToJson_longContentTruncated() {
        String longContent = "x".repeat(800);
        MemoryRecord r = new MemoryRecord("k", longContent, "src",
                1L, 0.5, MemoryDomain.AGENT, 1L);

        String json = MemoryViewerRoutes.recordToJson(r);

        assertTrue(json.contains("...(truncated)"), "长 content 应被截断");
        // 截断后总长度应远小于 800
        assertTrue(json.length() < 700, "JSON 长度应 < 700 — actual: " + json.length());
    }

    // ── FakeProvider（与 VersionedMemoryStoreTest 对齐） ──

    private static final class FakeProvider implements MemoryProvider {
        final AtomicInteger storeCount = new AtomicInteger();
        final ConcurrentHashMap<String, List<String>> pageStore = new ConcurrentHashMap<>();

        @Override
        public boolean store(String agentId, MemoryRecord record) {
            storeCount.incrementAndGet();
            pageStore.computeIfAbsent(agentId, k -> new java.util.ArrayList<>())
                    .add(record.content());
            return true;
        }

        @Override
        public String retrieve(String agentId, String query) {
            return "";
        }

        @Override
        public void clear(String agentId) {
            pageStore.remove(agentId);
        }

        @Override
        public String providerName() {
            return "fake";
        }
    }
}
