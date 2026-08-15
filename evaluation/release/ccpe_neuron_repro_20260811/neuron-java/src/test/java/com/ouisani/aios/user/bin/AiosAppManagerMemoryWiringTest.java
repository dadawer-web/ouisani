package com.ouisani.aios.user.bin;

import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.memory.VersionedMemoryStore;
import com.ouisani.aios.core.network.MemoryViewerRoutes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P3 启动注入集成测试 — 验证 AiosAppManager.configure 自动注入 primary store
 * 后，MemoryViewerRoutes 端点不再返回 503。
 * <p>
 * <b>背景</b>：补强前 P3 端点路由虽挂载到 AppGateway，但
 * {@code VersionedMemoryStore.setPrimaryStore} 从未在生产代码中调用，
 * 导致端点默认 supplier {@code VersionedMemoryStore::getPrimaryStore}
 * 永远返回 null，所有请求返回 503 Service Unavailable。
 * <p>
 * 补强方案：{@link AiosAppManager#configure(TaskScheduler)} 末尾调用
 * 幂等的 {@code wirePrimaryMemoryStore()}，自动用
 * {@code MemoryManager.getInstance().currentProvider()} 包装一个
 * VersionedMemoryStore 并注册为 primary。
 */
class AiosAppManagerMemoryWiringTest {

    private VersionedMemoryStore savedPrimary;

    @BeforeEach
    void saveState() {
        savedPrimary = VersionedMemoryStore.getPrimaryStore();
        VersionedMemoryStore.setPrimaryStore(null);
    }

    @AfterEach
    void restoreState() {
        VersionedMemoryStore.setPrimaryStore(savedPrimary);
    }

    @Test
    @DisplayName("configure() 后 primary store 已注入（非 null）")
    void configure_injectsPrimaryStore() {
        assertNull(VersionedMemoryStore.getPrimaryStore(),
                "测试前应已重置为 null");

        AiosAppManager.configure(new TaskScheduler());

        VersionedMemoryStore store = VersionedMemoryStore.getPrimaryStore();
        assertNotNull(store, "configure 后 primary store 应已注入");
    }

    @Test
    @DisplayName("configure() 后 MemoryViewerRoutes.handleList 返回 200（不再 503）")
    void configure_endpointReturns200Not503() {
        AiosAppManager.configure(new TaskScheduler());

        MemoryViewerRoutes.RouteResult result =
                MemoryViewerRoutes.handleList(VersionedMemoryStore::getPrimaryStore, "test-agent-no-data");

        assertNotEquals(503, result.status(),
                "已注入 store 后端点不应再返回 503 — actual body: " + result.body());
        assertEquals(200, result.status(),
                "正常空列表应返回 200 — actual body: " + result.body());
    }

    @Test
    @DisplayName("幂等：重复调用 configure 不覆盖已注入的 store")
    void configure_idempotentDoesNotClobberExisting() {
        AiosAppManager.configure(new TaskScheduler());
        VersionedMemoryStore firstStore = VersionedMemoryStore.getPrimaryStore();
        assertNotNull(firstStore);

        // 第二次调用 — 不应覆盖
        AiosAppManager.configure(new TaskScheduler());
        VersionedMemoryStore secondStore = VersionedMemoryStore.getPrimaryStore();

        assertSame(firstStore, secondStore,
                "重复 configure 应跳过注入，保留首次创建的 store");
    }

    @Test
    @DisplayName("未调用 configure 时端点返回 503 — 验证测试本身的负向基线")
    void withoutConfigure_endpointReturns503() {
        // 这是补强前的默认行为，作为对照测试
        assertNull(VersionedMemoryStore.getPrimaryStore());

        MemoryViewerRoutes.RouteResult result =
                MemoryViewerRoutes.handleList(VersionedMemoryStore::getPrimaryStore, "any-agent");

        assertEquals(503, result.status(),
                "未注入 store 时应返回 503 — actual: " + result.status());
    }
}
