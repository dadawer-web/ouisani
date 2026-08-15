package com.ouisani.aios.operator.secrets;

/**
 * 远程执行密钥引用配置 — 把远程执行器（Modal/SSH）所需的凭据表达为 {@link SecretRef}。
 * <p>
 * <b>为何用 SecretRef 而非明文</b>：SecretRef（{@code source:provider:id}）把凭据来源
 * （env/file/exec）与凭据值解耦 — 配置文件只存引用，明文由 {@link SecretRefResolver} 在运行时解析。
 * 这与 OpenClaw 的 PreparedSecretsRuntimeSnapshot 模式一致，避免明文泄露到日志/配置仓库。
 * <p>
 * <b>放置位置</b>：本类在 {@code operator/secrets/}（不在 {@code core/remote/}）—
 * 密钥解析是 operator 层职责。{@link RemoteExecutorConfigFactory} 在同包内向下依赖
 * {@code core/remote/RemoteExecutorConfig}，方向正确（operator → core）。
 * <p>
 * <b>字段可空性</b>：全 null 合法（纯 SSH key 无 passphrase、无 Modal 凭据的场景）。
 * 仅 Modal/ModalRest 执行器需要 modalTokenId/Secret/Workspace；sshPassphrase 当前预留
 * （{@code SshExecutor} 走 ssh-agent，R4.2 若支持 passphrase 交互输入再启用）。
 *
 * @param modalTokenId      Modal token id 引用（可空 — 仅 modal/modal-rest 用）
 * @param modalTokenSecret  Modal token secret 引用（可空）
 * @param modalWorkspace    Modal workspace 引用（可空）
 * @param sshPassphrase     SSH 私钥 passphrase 引用（可空 — 预留 R4.2，当前未用）
 */
public record RemoteSecretsConfig(
        SecretRef modalTokenId,
        SecretRef modalTokenSecret,
        SecretRef modalWorkspace,
        SecretRef sshPassphrase
) {
    public RemoteSecretsConfig {
        // 全 null 合法 — compact constructor 仅做存在性记录，不强制非空
    }

    /** 全空便捷工厂（纯 SSH key 无 passphrase 场景）。 */
    public static RemoteSecretsConfig empty() {
        return new RemoteSecretsConfig(null, null, null, null);
    }
}
