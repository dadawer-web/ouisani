package com.ouisani.aios.operator.secrets;

import com.ouisani.aios.core.remote.RemoteExecutorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 远程执行器配置工厂 — 从基础 config + {@link RemoteSecretsConfig} 解析凭据，装配出可执行的
 * {@link RemoteExecutorConfig}。
 * <p>
 * <b>职责边界</b>：把"SecretRef → 明文凭据 → 填入 config"的桥接逻辑收敛在此处，让调用方一行
 * 构造 config，不直接碰 {@link SecretRefResolver}。core/remote/ 的 {@code RemoteExecutorConfig}
 * 保持为纯数据 record，不耦合 secrets 概念。
 * <p>
 * <b>失败语义</b>：单个 SecretRef 解析失败（env var 不存在 / 文件不在受信目录 / exec 超时）不抛异常 —
 * 对应字段保持 null（让 executor 走 env 回退或 configError），仅 log.warn。这与
 * {@link SecretsSnapshot#prepare} 的"收集 warnings 不抛"模式一致。
 * <p>
 * <b>放置位置</b>：在 {@code operator/secrets/}（不在 {@code core/remote/}）— 向下依赖
 * core/remote/RemoteExecutorConfig，方向正确，避免 core→operator 倒置。
 */
public final class RemoteExecutorConfigFactory {

    private static final Logger log = LoggerFactory.getLogger(RemoteExecutorConfigFactory.class);

    private RemoteExecutorConfigFactory() {}

    /**
     * 从基础 config + secrets config 解析凭据，返回填好 modalTokenId/Secret/Workspace 的新 config。
     * <p>
     * 基础 config 的非敏感字段（host/port/user/modalAppPath 等）原样保留；仅 token 相关字段被
     * 解析出的明文覆盖。解析失败的 SecretRef 对应字段保持 null。
     *
     * @param base    基础 config（含连接参数、endpoint URL 等，token 字段通常为 null）
     * @param secrets 密钥引用配置（全 null 合法 — 此时返回的 config token 字段全 null）
     * @param env     环境变量（用于 env 类型 SecretRef 解析）
     * @return 装配好的新 config（base 字段保留 + 解析出的 token 字段）
     */
    public static RemoteExecutorConfig fromSecrets(RemoteExecutorConfig base,
                                                    RemoteSecretsConfig secrets,
                                                    Map<String, String> env) {
        if (base == null) {
            throw new IllegalArgumentException("base config required");
        }
        if (secrets == null) {
            // 全 null secrets → 返回 base 的 token 字段清零版（保证 token 字段为 null，其余同 base）
            return withTokens(base, null, null, null);
        }

        String tokenId = resolveQuietly(secrets.modalTokenId(), env, "modalTokenId");
        String tokenSecret = resolveQuietly(secrets.modalTokenSecret(), env, "modalTokenSecret");
        String workspace = resolveQuietly(secrets.modalWorkspace(), env, "modalWorkspace");
        // sshPassphrase 当前预留未用（SshExecutor 走 ssh-agent），解析后暂不注入 config
        // R4.2 启用 passphrase 交互时再接入
        if (secrets.sshPassphrase() != null) {
            resolveQuietly(secrets.sshPassphrase(), env, "sshPassphrase"); // 仅触发解析/警告，不使用返回值
        }

        return withTokens(base, tokenId, tokenSecret, workspace);
    }

    /** 便捷重载 — 用系统环境变量。 */
    public static RemoteExecutorConfig fromSecrets(RemoteExecutorConfig base, RemoteSecretsConfig secrets) {
        Map<String, String> env = new HashMap<>();
        System.getenv().forEach(env::put);
        return fromSecrets(base, secrets, env);
    }

    // ── 内部 ──

    /** 解析单个 SecretRef，失败返回 null + log.warn（不抛）。 */
    private static String resolveQuietly(SecretRef ref, Map<String, String> env, String fieldName) {
        if (ref == null) return null;
        try {
            String value = SecretRefResolver.resolve(ref, env);
            log.debug("[RemoteExecutorConfigFactory] 解析成功: {}", fieldName);
            return value;
        } catch (Exception e) {
            log.warn("[RemoteExecutorConfigFactory] 解析 {} 失败（保持 null，executor 走 env 回退）: {}",
                    fieldName, e.getMessage());
            return null;
        }
    }

    /**
     * 构造新 config — 保留 base 全部字段，仅覆盖 3 个 modal token 字段。
     * <p>
     * RemoteExecutorConfig 是 record（无 with），需全 19 字段构造。字段顺序与
     * {@link RemoteExecutorConfig} 的 record header 一致。
     */
    private static RemoteExecutorConfig withTokens(RemoteExecutorConfig base,
                                                    String tokenId, String tokenSecret, String workspace) {
        return new RemoteExecutorConfig(
                base.type(),
                base.host(), base.port(), base.user(), base.privateKeyPath(), base.knownHostsPath(),
                base.slurmLoginHost(), base.partition(), base.timeLimitMinutes(),
                base.cpus(), base.gpus(), base.remoteWorkDir(),
                base.modalAppPath(), base.modalFunctionName(),
                tokenId, tokenSecret, workspace,
                base.timeoutSeconds(), base.env());
    }
}
