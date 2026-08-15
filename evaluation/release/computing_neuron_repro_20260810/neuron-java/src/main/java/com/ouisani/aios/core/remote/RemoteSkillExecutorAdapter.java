package com.ouisani.aios.core.remote;

import com.ouisani.aios.core.skill.SkillChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把 {@link SkillChain.SkillExecutor} 的调用路由到远程执行后端（SSH/Slurm/Modal）。
 * <p>
 * <b>opt-in 集成</b>：实现 {@link SkillChain.SkillExecutor} 函数式接口，调用方按需构造：
 * <pre>{@code
 * SkillExecutor remote = new RemoteSkillExecutorAdapter("slurm", slurmConfig);
 * SkillChain.run(meta, input, ctx, remote);  // 全链路跑在 Slurm 上
 * }</pre>
 * 现有本地 executor（{@code AiosSdk.queryWithTools} lambda）零修改，零回归。
 * <p>
 * <b>命令拼接</b>：{@code command = skillName + " " + args}。远程 box 上需有同名可执行 skill
 * （或 shell 别名/函数）。R4.2 文件传输能力落地后可考虑上传 skill 脚本。
 * <p>
 * <b>错误传播</b>：成功时返回 {@link RemoteResult#stdout()}；失败时返回 {@code ""}（空串）
 * + {@code log.warn} 单独记错误信息。这样 {@link SkillChain} 第 124 行
 * {@code outputText.isBlank()} 自然判 {@code StepStatus.FAILED}，与本地 executor 的失败语义一致。
 * <p>
 * <b>构造 fail-fast</b>：未知 type 在构造器抛 {@link IllegalArgumentException}（不在 execute 时延迟抛），
 * 让调用方在装配阶段就能发现配置错误。
 *
 * @see SkillChain.SkillExecutor
 * @see RemoteExecutor
 * @see RemoteExecutorRegistry
 */
public final class RemoteSkillExecutorAdapter implements SkillChain.SkillExecutor {

    private static final Logger log = LoggerFactory.getLogger(RemoteSkillExecutorAdapter.class);

    private final RemoteExecutor executor;
    private final RemoteExecutorConfig config;

    /**
     * 生产构造器：从 {@link RemoteExecutorRegistry} 查 executor。
     *
     * @param type   执行器类型（"ssh" / "slurm" / "modal"，需已注册）
     * @param config 执行器配置
     * @throws IllegalArgumentException 当 type 未注册
     */
    public RemoteSkillExecutorAdapter(String type, RemoteExecutorConfig config) {
        this(instantiateFromRegistry(type), config);
    }

    /**
     * 测试构造器：注入 {@link CommandRunner}，内部按 type 实例化 executor（绕过 Registry）。
     * <p>
     * 用于单测注入 mock runner，无需真实网络/集群。
     *
     * @param type   执行器类型（"ssh" / "slurm" / "modal"）
     * @param config 执行器配置
     * @param runner mock runner
     * @throws IllegalArgumentException 当 type 不支持
     */
    public RemoteSkillExecutorAdapter(String type, RemoteExecutorConfig config, CommandRunner runner) {
        this(instantiateFromType(type, runner), config);
    }

    private RemoteSkillExecutorAdapter(RemoteExecutor executor, RemoteExecutorConfig config) {
        this.executor = executor;
        this.config = config;
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

        RemoteResult r = executor.execute(config, command, workingDir);
        if (r.success()) {
            return r.stdout();
        }
        log.warn("[RemoteSkillExecutorAdapter] 远程执行失败: type={}, agentId={}, skillName={}, err={}",
                executor.type(), agentId, skillName, r.errorMessage());
        // 返回空串 → SkillChain 第 124 行 isBlank() → StepStatus.FAILED
        return "";
    }
}
