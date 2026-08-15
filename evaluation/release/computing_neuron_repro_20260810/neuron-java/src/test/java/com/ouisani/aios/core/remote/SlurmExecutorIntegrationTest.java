package com.ouisani.aios.core.remote;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SlurmExecutor} 真实集群集成测试 — env-flag-gated，默认 skip。
 * <p>
 * 启用方式：
 * <pre>
 * AIOS_R4_SLURM_INTEGRATION=1
 * AIOS_R4_SLURM_LOGIN_HOST=<登录节点>   # 可选；缺省表示本地 sbatch（集群头节点即本机）
 * AIOS_R4_SLURM_PARTITION=<分区>         # 可选
 * AIOS_R4_SLURM_CPUS=<cpu 数>            # 可选
 * AIOS_R4_SLURM_GPUS=<gpu 数>            # 可选
 * AIOS_R4_SLURM_WORKDIR=<远程工作目录>   # 可选
 * </pre>
 * flag 已设但既无 loginHost 也无 partition 时优雅 skip。
 */
@EnabledIfEnvironmentVariable(named = "AIOS_R4_SLURM_INTEGRATION", matches = "1")
class SlurmExecutorIntegrationTest {

    private SlurmExecutor executor;
    private RemoteExecutorConfig config;

    @BeforeEach
    void setUp() {
        String loginHost = System.getenv("AIOS_R4_SLURM_LOGIN_HOST");
        String partition = System.getenv("AIOS_R4_SLURM_PARTITION");
        Assumptions.assumeTrue(
                (loginHost != null && !loginHost.isBlank())
                        || (partition != null && !partition.isBlank()),
                "既无 AIOS_R4_SLURM_LOGIN_HOST 也无 AIOS_R4_SLURM_PARTITION — 跳过 Slurm 集成测试");

        int cpus = parseIntOr(System.getenv("AIOS_R4_SLURM_CPUS"), 1);
        int gpus = parseIntOr(System.getenv("AIOS_R4_SLURM_GPUS"), 0);
        String workDir = System.getenv("AIOS_R4_SLURM_WORKDIR");

        executor = new SlurmExecutor();
        config = (loginHost != null && !loginHost.isBlank())
                ? RemoteExecutorConfig.slurm(loginHost, partition, cpus, gpus, workDir)
                : RemoteExecutorConfig.slurm(partition, cpus, gpus);
    }

    @Test
    @DisplayName("同步 execute：sbatch echo slurm-ok → COMPLETED，stdout 含 slurm-ok")
    void execute_echo_returnsSuccess() {
        RemoteResult r = executor.execute(config, "echo slurm-ok", null);

        assertTrue(r.success(), "应成功: " + r.errorMessage());
        assertTrue(r.stdout().contains("slurm-ok"),
                "stdout 应含 slurm-ok: " + r.stdout());
    }

    @Test
    @DisplayName("异步 submit→poll→retrieve：作业终态后取回 stdout 含 slurm-ok")
    void submitPollRetrieve_echo_returnsStdout() throws InterruptedException {
        RemoteJobHandle handle = executor.submit(config, "echo async-slurm-ok", null);

        // 轮询到终态
        RemoteJobSnapshot snap;
        long deadline = handle.submittedAt() + config.timeoutSeconds() * 1000L;
        do {
            Thread.sleep(1000);
            snap = executor.poll(config, handle);
        } while (!snap.status().isTerminal() && System.currentTimeMillis() < deadline);

        assertTrue(snap.status().isTerminal(), "作业应在超时内终态: " + snap.status());

        RemoteResult r = executor.retrieve(config, handle);
        assertTrue(r.success(), "retrieve 应成功: " + r.errorMessage());
        assertTrue(r.stdout().contains("async-slurm-ok"),
                "stdout 应含 async-slurm-ok: " + r.stdout());
    }

    private static int parseIntOr(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
