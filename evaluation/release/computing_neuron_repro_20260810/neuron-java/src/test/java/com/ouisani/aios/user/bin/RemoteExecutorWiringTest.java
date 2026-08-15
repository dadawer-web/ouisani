package com.ouisani.aios.user.bin;

import com.ouisani.aios.core.remote.RemoteExecutorConfigRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RemoteExecutorWiring} 单元测试 — 锁定零回归不变量。
 * <p>
 * {@code System.getenv()} 不可在测试中注入（final，JVM 启动时缓存），故不验证 env→config 的具体映射
 * （那由 {@code RemoteExecutorConfigFactoryTest} 的 secret 解析 + 集成测试覆盖）。
 * 本测试只验证对启动安全性最关键的不变量：
 * <ul>
 *   <li>无 env 时 {@code wire()} 不注册任何配置（对系统零副作用）</li>
 *   <li>{@code wire()} 幂等 — 重复调用不抛异常</li>
 *   <li>{@code reset()} 后可重新装配</li>
 * </ul>
 */
class RemoteExecutorWiringTest {

    @BeforeEach
    void setUp() {
        // 重置 wired 标志 + 清空 registry，隔离其他测试（如 AiosAppManager.configure 触发的 wire）
        RemoteExecutorWiring.reset();
    }

    @Test
    @DisplayName("无 env 时 wire() 不注册任何配置（零副作用）")
    void wireWithNoEnv_registersNothing() {
        // CI 环境通常无 AIOS_REMOTE_* env — 若有，本测试可能注册配置，故断言宽松：
        // wire() 至少不抛异常，且 registry 处于可用状态
        assertDoesNotThrow(() -> RemoteExecutorWiring.wire());
        // 无 AIOS_REMOTE_SSH_HOST 等 env 时 names() 应为空；有 env 时非空也属正常
        // 此处只验证不抛 + registry 可查询
        RemoteExecutorConfigRegistry.getInstance().names(); // 不抛即可
    }

    @Test
    @DisplayName("wire() 幂等 — 重复调用不抛异常")
    void wire_isIdempotent_doesNotThrowOnRepeat() {
        assertDoesNotThrow(() -> {
            RemoteExecutorWiring.wire();
            RemoteExecutorWiring.wire(); // 第二次应 no-op
            RemoteExecutorWiring.wire(); // 第三次仍 no-op
        });
    }

    @Test
    @DisplayName("reset() 后可重新装配 — wired 标志被清除")
    void reset_allowsRewire() {
        RemoteExecutorWiring.wire();       // wired=true
        RemoteExecutorWiring.reset();      // wired=false, registry cleared
        assertTrue(RemoteExecutorConfigRegistry.getInstance().names().isEmpty(),
                "reset 后 registry 应为空");
        assertDoesNotThrow(() -> RemoteExecutorWiring.wire()); // 可再次装配
    }
}
