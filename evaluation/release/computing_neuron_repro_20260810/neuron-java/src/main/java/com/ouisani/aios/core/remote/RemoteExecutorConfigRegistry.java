package com.ouisani.aios.core.remote;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 远程执行器<b>配置</b>注册表 — 单例，按名字查找 {@link RemoteExecutorConfig}。
 * <p>
 * 与 {@link RemoteExecutorRegistry}（存 executor <b>实例</b>）正交：
 * <ul>
 *   <li>{@link RemoteExecutorRegistry} — 按 type 存无状态 executor 实例（ssh/slurm/modal/modal-rest），
 *       启动时预注册，全局共享。</li>
 *   <li>{@code RemoteExecutorConfigRegistry}（本表）— 按名字存<b>命名配置</b>
 *       （如 {@code "gpu-cluster"} → slurm 配置、{@code "ssh-default"} → ssh 配置），
 *       由启动装配（{@code RemoteExecutorWiring}）从环境变量读取并注册。
 *       调用方按名字查 config，再用 {@code new RemoteSkillExecutorAdapter(type, config)} 构造适配器。</li>
 * </ul>
 * <p>
 * <b>Explicit opt-in 路由</b>：本表不与 {@code SkillChain} 核心循环耦合 —
 * 调用方显式查 config + 显式构造 adapter。无 env 装配时表为空，对系统零影响。
 * <p>
 * <b>默认空</b>：构造时不预注册任何 config（config 含连接参数，无法提供有意义的默认）。
 * <p>
 * <b>测试隔离</b>：package-private {@link #clear()} 清空所有注册，供单测 {@code @BeforeEach} 重置。
 * <p>
 * <b>OS 类比</b>：相当于 Linux 的 {@code /etc/fstab} — 命名挂载点表，启动时填充，运行时查询。
 *
 * @see RemoteExecutorConfig
 * @see RemoteExecutorRegistry
 */
public final class RemoteExecutorConfigRegistry {

    private static final RemoteExecutorConfigRegistry INSTANCE = new RemoteExecutorConfigRegistry();

    /** 单例访问。 */
    public static RemoteExecutorConfigRegistry getInstance() {
        return INSTANCE;
    }

    private final Map<String, RemoteExecutorConfig> configs = new ConcurrentHashMap<>();

    private RemoteExecutorConfigRegistry() {
        // 默认空 — 无 env 装配时不预注册任何 config
    }

    /**
     * 注册或覆盖命名配置。
     *
     * @param name   配置名（如 "ssh-default"、"gpu-cluster"）
     * @param config 执行器配置
     */
    public void register(String name, RemoteExecutorConfig config) {
        configs.put(Objects.requireNonNull(name, "name"),
                Objects.requireNonNull(config, "config"));
    }

    /**
     * 按名字查找配置。
     *
     * @return 命中时 {@code Optional.of(config)}，否则 {@link Optional#empty()}
     */
    public Optional<RemoteExecutorConfig> get(String name) {
        return Optional.ofNullable(configs.get(name));
    }

    /** 所有已注册配置名（不可变视图）。 */
    public Set<String> names() {
        return Collections.unmodifiableSet(configs.keySet());
    }

    /**
     * 清空所有注册。
     * <p>
     * 用于测试隔离与热重载场景（如 {@code RemoteExecutorWiring.reset()} 跨包重置单例）。
     */
    public void clear() {
        configs.clear();
    }
}
