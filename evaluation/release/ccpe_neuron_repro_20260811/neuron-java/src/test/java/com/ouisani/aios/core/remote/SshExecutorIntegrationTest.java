package com.ouisani.aios.core.remote;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SshExecutor} 真实集群集成测试 — env-flag-gated，默认 skip。
 * <p>
 * 启用方式（设置后本类才执行，否则 JUnit 整类 skip）：
 * <pre>
 * AIOS_R4_SSH_INTEGRATION=1
 * AIOS_R4_SSH_HOST=<远程主机>
 * AIOS_R4_SSH_USER=<用户名>
 * AIOS_R4_SSH_KEY=<私钥路径>      # 可选；缺省走 ssh-agent
 * AIOS_R4_SSH_PORT=<端口>          # 可选；缺省 22
 * </pre>
 * 若 flag 已设但连接变量缺失，{@code @BeforeEach} 内 {@link Assumptions#assumeTrue} 优雅 skip。
 */
@EnabledIfEnvironmentVariable(named = "AIOS_R4_SSH_INTEGRATION", matches = "1")
class SshExecutorIntegrationTest {

    private SshExecutor executor;
    private RemoteExecutorConfig config;

    @BeforeEach
    void setUp() {
        String host = System.getenv("AIOS_R4_SSH_HOST");
        Assumptions.assumeTrue(host != null && !host.isBlank(),
                "AIOS_R4_SSH_HOST 未设置 — 跳过 SSH 集成测试");
        String user = System.getenv("AIOS_R4_SSH_USER");
        String key = System.getenv("AIOS_R4_SSH_KEY");
        int port = parseIntOr(System.getenv("AIOS_R4_SSH_PORT"), 22);

        executor = new SshExecutor();
        config = RemoteExecutorConfig.ssh(host, port, user, key);
    }

    @Test
    @DisplayName("echo hello → success，stdout 含 hello")
    void echo_returnsSuccess() {
        RemoteResult r = executor.execute(config, "echo hello-ssh", null);

        assertTrue(r.success(), "应成功: " + r.errorMessage());
        assertTrue(r.stdout().contains("hello-ssh"),
                "stdout 应含 hello-ssh: " + r.stdout());
    }

    @Test
    @DisplayName("exit 3 → failure，exitCode==3")
    void exitCode_propagatedAsFailure() {
        RemoteResult r = executor.execute(config, "exit 3", null);

        assertTrue(!r.success(), "非零退出应 failure");
        assertEquals(3, r.exitCode(), "exitCode 应为 3: " + r);
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
