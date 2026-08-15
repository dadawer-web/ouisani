package com.ouisani.aios.core.remote;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RemoteExecutorRegistry} 单元测试 — 默认注册、覆盖、查询、清空。
 * <p>
 * <b>测试隔离</b>：每个 {@code @BeforeEach} 重置 Registry（{@code clear()} + 重注册默认 4 个），
 * 避免单例状态污染其他测试类。
 * <p>
 * <b>注意</b>：{@link RemoteExecutor} 是 sealed interface，只 permits SshExecutor/SlurmExecutor/
 * ModalExecutor/ModalRestExecutor。测试无法自定义新的 RemoteExecutor 实现。覆盖/新增注册测试用真实
 * executor 实例（验证 registry 的 Map 语义，不验证 type() 一致性 — 那是调用方的责任）。
 */
class RemoteExecutorRegistryTest {

    private RemoteExecutorRegistry registry;

    @BeforeEach
    void setUp() {
        registry = RemoteExecutorRegistry.getInstance();
        registry.clear();
        registry.register("ssh", new SshExecutor());
        registry.register("slurm", new SlurmExecutor());
        registry.register("modal", new ModalExecutor());
        registry.register("modal-rest", new ModalRestExecutor());
    }

    @Test
    @DisplayName("默认注册含 ssh/slurm/modal/modal-rest 4 个执行器，type() 对应")
    void defaultRegistry_containsAllFourTypes() {
        Optional<RemoteExecutor> ssh = registry.get("ssh");
        Optional<RemoteExecutor> slurm = registry.get("slurm");
        Optional<RemoteExecutor> modal = registry.get("modal");
        Optional<RemoteExecutor> modalRest = registry.get("modal-rest");

        assertTrue(ssh.isPresent());
        assertTrue(slurm.isPresent());
        assertTrue(modal.isPresent());
        assertTrue(modalRest.isPresent());
        assertEquals("ssh", ssh.get().type());
        assertEquals("slurm", slurm.get().type());
        assertEquals("modal", modal.get().type());
        assertEquals("modal-rest", modalRest.get().type());
    }

    @Test
    @DisplayName("get 未知 type → empty")
    void get_unknownType_returnsEmpty() {
        Optional<RemoteExecutor> unknown = registry.get("k8s");

        assertFalse(unknown.isPresent());
    }

    @Test
    @DisplayName("register 覆盖已有 type — 用新 SshExecutor 实例替换默认")
    void register_overridesExisting() {
        SshExecutor custom = new SshExecutor();

        registry.register("ssh", custom);

        Optional<RemoteExecutor> fetched = registry.get("ssh");
        assertTrue(fetched.isPresent());
        assertSame(custom, fetched.get(), "register should override existing");
    }

    @Test
    @DisplayName("clear 清空所有注册")
    void clear_emptiesRegistry() {
        registry.clear();

        assertFalse(registry.get("ssh").isPresent());
        assertFalse(registry.get("slurm").isPresent());
        assertFalse(registry.get("modal").isPresent());
        assertFalse(registry.get("modal-rest").isPresent());
        assertFalse(registry.get("anything").isPresent());
    }

    @Test
    @DisplayName("register 新 type 名后可查到（用真实 executor 实例 — sealed 限制无法自定义实现）")
    void register_newType_canBeRetrieved() {
        // 用 SlurmExecutor 实例注册到 "k8s" 名下 — 验证 registry Map 语义
        // （registry 不强制 type() 与 key 一致，那由调用方保证）
        SlurmExecutor extra = new SlurmExecutor();

        registry.register("k8s", extra);

        Optional<RemoteExecutor> fetched = registry.get("k8s");
        assertTrue(fetched.isPresent());
        assertSame(extra, fetched.get());
    }
}
