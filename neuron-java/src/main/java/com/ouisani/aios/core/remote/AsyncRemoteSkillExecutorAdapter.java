package com.ouisani.aios.core.remote;

import com.ouisani.aios.core.skill.SkillChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 异步版 {@link SkillExecutor} 适配器 — 内部用 submit/poll/retrieve 轮询到终态再返回 stdout，
 * 对外仍是同步 {@link SkillChain.SkillExecutor}（返回 String），让 {@link SkillChain#run} 无感接入。
 * <p>
 * <b>为何"对外同步"</b>：{@link SkillChain.SkillExecutor#execute} 的契约是同步返回输出文本。
 * 真正的异步由 {@link SkillChain#runAsync} 在外层（虚拟线程）提供。本适配器把"submit 后立即返回 jobId，
 * 后续 poll/retrieve"的异步后端语义，收敛回同步调用方期望的 String 返回值。
 * <p>
 * <b>轮询循环</b>：submit 拿到 handle 后，每 {@link #pollIntervalMs} 调一次 {@link RemoteExecutor#poll}，
 * 终态即 retrieve 取回 stdout；超过 {@link RemoteExecutorConfig#timeoutSeconds()} 则 best-effort 返回空串
 * （不调 cancel — 异步 cancel 语义留 R4.2）。
 * <p>
 * <b>错误传播</b>：与同步 {@link RemoteSkillExecutorAdapter} 一致 — 成功返回 stdout，失败/超时/异常返回 {@code ""}，
 * 让 {@link SkillChain} 的 {@code outputText.isBlank()} 自然判 {@code StepStatus.FAILED}。
 * <p>
 * <b>构造 fail-fast</b>：未知 type 在构造器抛 {@link IllegalArgumentException}（不在 execute 时延迟抛）。
 *
 * @see RemoteSkillExecutorAdapter
 * @see RemoteExecutor#submit
 * @see SkillChain#runAsync
 */
public final class AsyncRemoteSkillExecutorAdapter implements SkillChain.SkillExecutor {

    private static final Logger log = LoggerFactory.getLogger(AsyncRemoteSkillExecutorAdapter.class);

    /** 默认轮询间隔（1 秒）。 */
    private static final long DEFAULT_POLL_INTERVAL_MS = 1000L;

    private final RemoteExecutor executor;
    private final RemoteExecutorConfig config;
    private long pollIntervalMs = DEFAULT_POLL_INTERVAL_MS;

    /**
     * 生产构造器：从 {@link RemoteExecutorRegistry} 查 executor。
     *
     * @param type   执行器类型（"ssh"/"slurm"/"modal"/"modal-rest"，需已注册）
     * @param config 执行器配置
     * @throws IllegalArgumentException 当 type 未注册
     */
    public AsyncRemoteSkillExecutorAdapter(String type, RemoteExecutorConfig config) {
        this(instantiateFromRegistry(type), config);
    }

    /**
     * 测试构造器：注入 {@link CommandRunner}，内部按 type 实例化 executor（绕过 Registry）。
     *
     * @param type   执行器类型（"ssh"/"slurm"/"modal"/"modal-rest"）
     * @param config 执行器配置
     * @param runner mock runner（modal-rest 忽略此参数 — REST 走 HttpClient）
     * @throws IllegalArgumentException 当 type 不支持
     */
    public AsyncRemoteSkillExecutorAdapter(String type, RemoteExecutorConfig config, CommandRunner runner) {
        this(instantiateFromType(type, runner), config);
    }

    private AsyncRemoteSkillExecutorAdapter(RemoteExecutor executor, RemoteExecutorConfig config) {
        this.executor = executor;
        this.config = config;
    }

    /** 设置轮询间隔（测试用，可设为 20ms 加速）。 */
    void setPollIntervalMs(long ms) {
        this.pollIntervalMs = ms;
    }

    private static RemoteExecutor instantiateFromRegistry(String type) {
        return RemoteExecutorRegistry.getInstance().get(type)
                .orElseThrow(() -> new IllegalArgumentException("unknown executor type: " + type));
    }

    private static RemoteExecutor instantiateFromType(String type, CommandRunner runner) {
        return switch (type) {
            case "ssh"        -> new SshExecutor(runner);
            case "slurm"      -> new SlurmExecutor(runner);
            case "modal"      -> new ModalExecutor(runner);
            // modal-rest 走 HttpClient 而非 CommandRunner，忽略 runner 参数
            case "modal-rest" -> new ModalRestExecutor();
            default           -> throw new IllegalArgumentException("unknown executor type: " + type);
        };
    }

    @Override
    public String execute(String agentId, String skillName, String args, String workingDir) {
        String command = (args == null || args.isBlank())
                ? skillName
                : skillName + " " + args;

        try {
            RemoteJobHandle handle = executor.submit(config, command, workingDir);
            long deadline = handle.submittedAt() + config.timeoutSeconds() * 1000L;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(pollIntervalMs);
                RemoteJobSnapshot snap = executor.poll(config, handle);
                if (snap.status().isTerminal()) {
                    RemoteResult r = executor.retrieve(config, handle);
                    if (r.success()) {
                        return r.stdout();
                    }
                    log.warn("[AsyncRemoteSkillExecutorAdapter] 远程作业终态非成功: type={}, jobId={}, err={}",
                            executor.type(), handle.jobId(), r.errorMessage());
                    return "";
                }
            }
            // 超时 — best-effort 不 cancel（异步 cancel 语义留 R4.2）
            log.warn("[AsyncRemoteSkillExecutorAdapter] 超时: type={}, jobId={}",
                    executor.type(), handle.jobId());
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[AsyncRemoteSkillExecutorAdapter] 轮询被中断: type={}", executor.type());
            return "";
        } catch (Exception e) {
            // submit 抛 RemoteJobException、或其他运行时异常 → 统一降级为空串
            log.warn("[AsyncRemoteSkillExecutorAdapter] 异步远程执行失败: type={}, skillName={}, err={}",
                    executor.type(), skillName, e.getMessage());
            return "";
        }
    }
}
