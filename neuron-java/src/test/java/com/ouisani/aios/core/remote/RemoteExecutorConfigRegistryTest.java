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
 * {@link RemoteExecutorConfigRegistry} 单元测试 — 默认空、register+get、覆盖、清空。
 * <p>
 * <b>测试隔离</b>：每个 {@code @BeforeEach} {@code clear()} 重置单例，避免污染其他测试类。
 */
class RemoteExecutorConfigRegistryTest {

    private RemoteExecutorConfigRegistry registry;

    @BeforeEach
    void setUp() {
        registry = RemoteExecutorConfigRegistry.getInstance();
        registry.clear();
    }

    @Test
    @DisplayName("默认空 — get 任意名返回 empty，names() 空")
    void emptyByDefault_getReturnsEmpty() {
        Optional<RemoteExecutorConfig> absent = registry.get("anything");

        assertFalse(absent.isPresent());
        assertTrue(registry.names().isEmpty());
    }

    @Test
    @DisplayName("register + get — 命中返回 config，type 正确，names 含该名")
    void registerAndGet_returnsConfig() {
        RemoteExecutorConfig sshCfg = RemoteExecutorConfig.ssh("host", "user", "/key");

        registry.register("ssh-default", sshCfg);

        Optional<RemoteExecutorConfig> fetched = registry.get("ssh-default");
        assertTrue(fetched.isPresent());
        assertSame(sshCfg, fetched.get());
        assertEquals("ssh", fetched.get().type());
        assertTrue(registry.names().contains("ssh-default"));
    }

    @Test
    @DisplayName("register 同名 — 覆盖已有配置")
    void register_overridesExisting() {
        RemoteExecutorConfig first = RemoteExecutorConfig.ssh("h1", "u1", "/k1");
        RemoteExecutorConfig second = RemoteExecutorConfig.slurm("gpu", 4, 1);

        registry.register("named", first);
        registry.register("named", second);

        Optional<RemoteExecutorConfig> fetched = registry.get("named");
        assertTrue(fetched.isPresent());
        assertSame(second, fetched.get(), "register should override existing");
        assertEquals("slurm", fetched.get().type());
    }

    @Test
    @DisplayName("clear 清空所有注册")
    void clear_emptiesAll() {
        registry.register("a", RemoteExecutorConfig.ssh("h", "u", "/k"));
        registry.register("b", RemoteExecutorConfig.slurm("p", 1, 0));

        registry.clear();

        assertFalse(registry.get("a").isPresent());
        assertFalse(registry.get("b").isPresent());
        assertTrue(registry.names().isEmpty());
    }
}
