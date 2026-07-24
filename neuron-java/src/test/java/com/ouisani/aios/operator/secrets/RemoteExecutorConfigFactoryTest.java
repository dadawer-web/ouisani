package com.ouisani.aios.operator.secrets;

import com.ouisani.aios.core.remote.RemoteExecutorConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RemoteExecutorConfigFactory} 单元测试 — SecretRef 解析、失败回退、字段保留。
 */
class RemoteExecutorConfigFactoryTest {

    private RemoteExecutorConfig modalRestBase() {
        // 基础 config：endpoint URL + functionName，token 字段为 null
        return RemoteExecutorConfig.modalRest(
                "https://myws--app.modal.run/exec", "train", null, null);
    }

    @Test
    @DisplayName("fromSecrets 解析 modalTokenId/Secret/Workspace 成功")
    void fromSecrets_resolvesModalTokens() {
        RemoteExecutorConfig base = modalRestBase();
        RemoteSecretsConfig secrets = new RemoteSecretsConfig(
                new SecretRef("env", "modal", "MODAL_TOKEN_ID"),
                new SecretRef("env", "modal", "MODAL_TOKEN_SECRET"),
                new SecretRef("env", "modal", "MODAL_WORKSPACE"),
                null);
        Map<String, String> env = Map.of(
                "MODAL_TOKEN_ID", "tid-abc",
                "MODAL_TOKEN_SECRET", "tsecret-xyz",
                "MODAL_WORKSPACE", "my-ws");

        RemoteExecutorConfig result = RemoteExecutorConfigFactory.fromSecrets(base, secrets, env);

        assertEquals("tid-abc", result.modalTokenId());
        assertEquals("tsecret-xyz", result.modalTokenSecret());
        assertEquals("my-ws", result.modalWorkspace());
    }

    @Test
    @DisplayName("SecretRef 指向不存在的 env var → 对应字段 null + 不抛异常")
    void fromSecrets_resolutionFailureLeavesNull() {
        RemoteExecutorConfig base = modalRestBase();
        RemoteSecretsConfig secrets = new RemoteSecretsConfig(
                new SecretRef("env", "modal", "MISSING_TOKEN_ID"),
                new SecretRef("env", "modal", "MODAL_TOKEN_SECRET"),
                null, null);
        Map<String, String> env = Map.of("MODAL_TOKEN_SECRET", "tsecret");

        // 不应抛
        RemoteExecutorConfig result = RemoteExecutorConfigFactory.fromSecrets(base, secrets, env);

        assertNull(result.modalTokenId(), "解析失败的字段应保持 null");
        assertEquals("tsecret", result.modalTokenSecret(), "解析成功的字段应填入");
        assertNull(result.modalWorkspace());
    }

    @Test
    @DisplayName("base 的非敏感字段（endpoint/functionName/timeout）原样保留")
    void fromSecrets_preservesBaseFields() {
        RemoteExecutorConfig base = modalRestBase();
        RemoteSecretsConfig secrets = new RemoteSecretsConfig(
                new SecretRef("env", "modal", "MODAL_TOKEN_ID"),
                new SecretRef("env", "modal", "MODAL_TOKEN_SECRET"),
                null, null);
        Map<String, String> env = Map.of("MODAL_TOKEN_ID", "tid", "MODAL_TOKEN_SECRET", "ts");

        RemoteExecutorConfig result = RemoteExecutorConfigFactory.fromSecrets(base, secrets, env);

        assertEquals(base.modalAppPath(), result.modalAppPath(), "endpoint URL 保留");
        assertEquals(base.modalFunctionName(), result.modalFunctionName(), "functionName 保留");
        assertEquals(base.type(), result.type(), "type 保留");
        assertEquals(base.timeoutSeconds(), result.timeoutSeconds(), "timeout 保留");
    }

    @Test
    @DisplayName("secrets 全 null → 返回的 config token 字段全 null，其余同 base")
    void fromSecrets_nullSecretsReturnsBaseUnchanged() {
        RemoteExecutorConfig base = modalRestBase();

        RemoteExecutorConfig result = RemoteExecutorConfigFactory.fromSecrets(base, RemoteSecretsConfig.empty());

        assertNull(result.modalTokenId());
        assertNull(result.modalTokenSecret());
        assertNull(result.modalWorkspace());
        assertEquals(base.modalAppPath(), result.modalAppPath());
        assertEquals(base.type(), result.type());
        // RemoteSecretsConfig.empty() 不抛 + 返回有效 config
        assertNotNull(result);
    }
}
