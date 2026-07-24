package com.ouisani.aios.core.remote;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScpFileTransfer} 真实集群集成测试 — env-flag-gated，默认 skip。
 * <p>
 * 启用方式：
 * <pre>
 * AIOS_R4_SCP_INTEGRATION=1
 * AIOS_R4_SSH_HOST=<远程主机>
 * AIOS_R4_SSH_USER=<用户名>
 * AIOS_R4_SSH_KEY=<私钥路径>      # 可选；缺省走 ssh-agent
 * AIOS_R4_SSH_PORT=<端口>          # 可选；缺省 22
 * </pre>
 * 验证：upload 本地临时文件 → download 回来 → 内容一致（round-trip）。
 */
@EnabledIfEnvironmentVariable(named = "AIOS_R4_SCP_INTEGRATION", matches = "1")
class ScpFileTransferIntegrationTest {

    @TempDir
    Path tempDir;

    private ScpFileTransfer transfer;
    private RemoteExecutorConfig config;
    private String remotePath;

    @BeforeEach
    void setUp() {
        String host = System.getenv("AIOS_R4_SSH_HOST");
        Assumptions.assumeTrue(host != null && !host.isBlank(),
                "AIOS_R4_SSH_HOST 未设置 — 跳过 SCP 集成测试");
        String user = System.getenv("AIOS_R4_SSH_USER");
        String key = System.getenv("AIOS_R4_SSH_KEY");
        int port = parseIntOr(System.getenv("AIOS_R4_SSH_PORT"), 22);

        transfer = new ScpFileTransfer();
        config = RemoteExecutorConfig.ssh(host, port, user, key);
        // 唯一远程路径，避免并发/历史文件干扰
        remotePath = "~/aios-scp-it-" + System.nanoTime() + ".txt";
    }

    @Test
    @DisplayName("upload → download round-trip：内容一致")
    void uploadDownload_roundtrip_preservesContent() throws Exception {
        Path localFile = tempDir.resolve("payload.txt");
        String payload = "scp-integration-payload-" + System.nanoTime();
        Files.writeString(localFile, payload);

        // upload
        RemoteResult up = transfer.upload(config, localFile.toString(), remotePath);
        assertTrue(up.success(), "upload 应成功: " + up.errorMessage());

        // download 到另一本地文件
        Path downloaded = tempDir.resolve("downloaded.txt");
        RemoteResult down = transfer.download(config, remotePath, downloaded.toString());
        assertTrue(down.success(), "download 应成功: " + down.errorMessage());

        // 内容一致
        String got = Files.readString(downloaded);
        assertEquals(payload, got, "round-trip 后内容应一致");

        // best-effort 清理远程文件
        try {
            new SshExecutor().execute(config, "rm -f " + remotePath, null);
        } catch (Exception ignored) {
            // 清理失败不影响测试结论
        }
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
