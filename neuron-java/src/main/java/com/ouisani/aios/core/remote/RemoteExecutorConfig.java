package com.ouisani.aios.core.remote;

import java.util.Map;

/**
 * 远程执行器配置 — 涵盖 SSH/Slurm/Modal 三种后端的所有连接参数。
 * <p>
 * 采用「单 record 含三段字段 + 静态工厂」而非 sealed interface + 3 个子 record，
 * 理由：(1) 字段总数仅 ~15，单 record 更紧凑；(2) {@link RemoteExecutor#execute}
 * 只接收一个 config 参数，避免类型分发；(3) 静态工厂 {@link #ssh}/{@link #slurm}/{@link #modal}
 * 提供类型安全的构造路径，未设置的字段为 null/0/默认值。
 * <p>
 * <b>不可变</b>：所有字段为 final。修改配置应构造新实例（与 Java record 语义一致）。
 *
 * @param type              执行器类型："ssh" | "slurm" | "modal"
 * @param host              SSH 主机名
 * @param port              SSH 端口（默认 22；0 视为 22）
 * @param user              SSH 用户名
 * @param privateKeyPath    SSH 私钥路径（null 表示用 ssh-agent/默认 ~/.ssh/id_rsa）
 * @param knownHostsPath    known_hosts 路径（null 表示用 ~/.ssh/known_hosts）
 * @param slurmLoginHost    Slurm 登录节点（null/空 表示本地 sbatch，集群就在本机）
 * @param partition         Slurm 分区/队列名（null 表示用集群默认）
 * @param timeLimitMinutes  Slurm 作业时限（分钟；0 表示用集群默认）
 * @param cpus              Slurm 每任务 CPU 数（0 表示默认）
 * @param gpus              Slurm GPU 数（0 表示无 GPU）
 * @param remoteWorkDir     Slurm 远程工作目录（sbatch 在此目录运行；null 表示登录节点 ~）
 * @param modalAppPath      Modal app 文件路径（如 /work/my_app.py）
 * @param modalFunctionName Modal 函数名（如 "train"）
 * @param modalTokenId      Modal token id（null 表示从 MODAL_TOKEN_ID env 读）
 * @param modalTokenSecret  Modal token secret（null 表示从 MODAL_TOKEN_SECRET env 读）
 * @param modalWorkspace    Modal workspace 名（null 表示从 MODAL_WORKSPACE env 读）
 * @param timeoutSeconds    总超时秒数（默认 600s；Slurm 轮询也受此限制）
 * @param env               额外环境变量（合并到子进程；null 表示无）
 */
public record RemoteExecutorConfig(
        String type,
        // ── SSH 段 ──
        String host,
        int port,
        String user,
        String privateKeyPath,
        String knownHostsPath,
        // ── Slurm 段 ──
        String slurmLoginHost,
        String partition,
        int timeLimitMinutes,
        int cpus,
        int gpus,
        String remoteWorkDir,
        // ── Modal 段 ──
        String modalAppPath,
        String modalFunctionName,
        String modalTokenId,
        String modalTokenSecret,
        String modalWorkspace,
        // ── 公共段 ──
        long timeoutSeconds,
        Map<String, String> env
) {
    /** 默认超时 10 分钟。 */
    public static final long DEFAULT_TIMEOUT_SECONDS = 600L;

    public RemoteExecutorConfig {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type required (ssh|slurm|modal)");
        }
        if (port <= 0) port = 22;
        if (timeoutSeconds <= 0) timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        if (env == null) env = Map.of();
    }

    // ════════════════════════════════════════════════════════════════
    //  静态工厂 — 类型安全的构造路径
    // ════════════════════════════════════════════════════════════════

    /** SSH 配置：连到 host:port 用 user + privateKeyPath。 */
    public static RemoteExecutorConfig ssh(String host, int port, String user, String privateKeyPath) {
        return new RemoteExecutorConfig("ssh", host, port, user, privateKeyPath, null,
                null, null, 0, 0, 0, null,
                null, null, null, null, null,
                DEFAULT_TIMEOUT_SECONDS, null);
    }

    /** SSH 配置（默认 22 端口）。 */
    public static RemoteExecutorConfig ssh(String host, String user, String privateKeyPath) {
        return ssh(host, 22, user, privateKeyPath);
    }

    /** Slurm 配置：在本地提交（无登录节点），用 partition + cpus + gpus。 */
    public static RemoteExecutorConfig slurm(String partition, int cpus, int gpus) {
        return new RemoteExecutorConfig("slurm", null, 22, null, null, null,
                null, partition, 0, cpus, gpus, null,
                null, null, null, null, null,
                DEFAULT_TIMEOUT_SECONDS, null);
    }

    /** Slurm 配置：经 SSH 登录节点提交。 */
    public static RemoteExecutorConfig slurm(String loginHost, String partition,
                                              int cpus, int gpus, String remoteWorkDir) {
        return new RemoteExecutorConfig("slurm", null, 22, null, null, null,
                loginHost, partition, 0, cpus, gpus, remoteWorkDir,
                null, null, null, null, null,
                DEFAULT_TIMEOUT_SECONDS, null);
    }

    /** Modal 配置：跑 appPath 文件中的 functionName。Token 从 env 读。 */
    public static RemoteExecutorConfig modal(String appPath, String functionName) {
        return new RemoteExecutorConfig("modal", null, 22, null, null, null,
                null, null, 0, 0, 0, null,
                appPath, functionName, null, null, null,
                DEFAULT_TIMEOUT_SECONDS, null);
    }

    /**
     * Modal REST 配置：直连已部署的 Modal Web Endpoint。
     * <p>
     * {@code endpointUrl} 复用 {@code modalAppPath} 字段（REST 场景语义为 URL），
     * {@code tokenId}/{@code tokenSecret} 经 {@code Modal-Token-Id}/{@code Modal-Token-Secret} header 传输。
     */
    public static RemoteExecutorConfig modalRest(String endpointUrl, String functionName,
                                                  String tokenId, String tokenSecret) {
        return new RemoteExecutorConfig("modal-rest", null, 22, null, null, null,
                null, null, 0, 0, 0, null,
                endpointUrl, functionName, tokenId, tokenSecret, null,
                DEFAULT_TIMEOUT_SECONDS, null);
    }

    // ── 字段访问 helper（屏蔽 null 检查）──

    /** 是否走 SSH 登录节点提交 Slurm 作业。 */
    public boolean slurmViaLoginNode() {
        return slurmLoginHost != null && !slurmLoginHost.isBlank();
    }

    /** SSH 端口（已规整为 22 默认值）。 */
    public int sshPort() {
        return port <= 0 ? 22 : port;
    }
}
