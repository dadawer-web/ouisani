package com.ouisani.aios.core.remote;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 远程执行器注册表 — 单例，按 type 名查找 {@link RemoteExecutor}。
 * <p>
 * <b>默认注册</b>：构造时注册 4 个执行器：
 * <ul>
 *   <li>{@code "ssh"} → {@link SshExecutor}</li>
 *   <li>{@code "slurm"} → {@link SlurmExecutor}</li>
 *   <li>{@code "modal"} → {@link ModalExecutor}</li>
 *   <li>{@code "modal-rest"} → {@link ModalRestExecutor}</li>
 * </ul>
 * <p>
 * <b>覆盖</b>：{@link #register(String, RemoteExecutor)} 可覆盖默认注册（如注入测试用的 mock runner 实例）。
 * <p>
 * <b>测试隔离</b>：package-private {@link #clear()} 清空所有注册，供单测 {@code @BeforeEach} 重置。
 * <p>
 * <b>OS 类比</b>：相当于 Linux 的 {@code /proc/filesystems} — 内核启动时注册默认文件系统，
 * 模块加载时可注册新类型。
 *
 * @see RemoteExecutor
 * @see RemoteSkillExecutorAdapter（调用方按 type 从本表查 executor）
 */
public final class RemoteExecutorRegistry {

    private static final RemoteExecutorRegistry INSTANCE = new RemoteExecutorRegistry();

    /** 单例访问。 */
    public static RemoteExecutorRegistry getInstance() {
        return INSTANCE;
    }

    private final Map<String, RemoteExecutor> executors = new ConcurrentHashMap<>();

    private RemoteExecutorRegistry() {
        register("ssh", new SshExecutor());
        register("slurm", new SlurmExecutor());
        register("modal", new ModalExecutor());
        register("modal-rest", new ModalRestExecutor());
    }

    /**
     * 注册或覆盖执行器。
     *
     * @param type     执行器类型标识（与 {@link RemoteExecutor#type()} 一致）
     * @param executor 执行器实例
     */
    public void register(String type, RemoteExecutor executor) {
        executors.put(Objects.requireNonNull(type, "type"),
                Objects.requireNonNull(executor, "executor"));
    }

    /**
     * 按 type 查找执行器。
     *
     * @return 命中时 {@code Optional.of(executor)}，否则 {@link Optional#empty()}
     */
    public Optional<RemoteExecutor> get(String type) {
        return Optional.ofNullable(executors.get(type));
    }

    /** 清空所有注册（测试隔离用 — package-private）。 */
    void clear() {
        executors.clear();
    }
}
