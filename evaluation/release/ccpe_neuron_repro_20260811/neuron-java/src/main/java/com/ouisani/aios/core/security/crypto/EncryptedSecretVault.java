package com.ouisani.aios.core.security.crypto;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 加密密钥保险库 — 在 SecretVault 的句柄机制之上增加 DEK/KEK 信封加密。
 * <p>
 * <h3>与 SecretVault 的关系</h3>
 * <pre>
 *   SecretVault (现有)          — 句柄机制，Agent 只拿 handle 不拿明文
 *   EncryptedSecretVault (新增)  — 在存储层增加 DEK/KEK 信封加密
 * </pre>
 * <p>
 * SecretVault 解决的是"Agent 不应看到明文"的问题（访问控制层），
 * EncryptedSecretVault 解决的是"即使 VFS 被提权读取，密文也无法解密"的问题（数据加密层）。
 * 两者互补，不替代。
 * <p>
 * <h3>安全边界</h3>
 * <ul>
 *   <li>所有写入 /vfs/secrets/ 的凭证均通过 EncryptedSecretVault 加密</li>
 *   <li>业务 Agent 和 VFS 视图永远只能看到密文</li>
 *   <li>仅内核底层解密管道（OpenAiAdapter 等）能获取明文</li>
 *   <li>支持无缝密钥轮转：重新包装 DEK，无需重新加密数据</li>
 * </ul>
 * <p>
 * OS 类比：相当于 Linux 的 keyutils + dm-crypt —
 * keyring 管理密钥句柄，dm-crypt 负责实际加密。
 *
 * @see EnvelopeEncryption
 * @see com.ouisani.aios.core.security.SecretVault
 */
public class EncryptedSecretVault {

    /** 加密后的密钥存储: handleId → 密文 */
    private final Map<String, String> encryptedSecrets = new ConcurrentHashMap<>();

    /** 句柄注册表: category:keyId → handleId */
    private final Map<String, String> handleRegistry = new ConcurrentHashMap<>();

    /** 信封加密引擎 */
    private final EnvelopeEncryption envelope;

    /** 单例 */
    private static EncryptedSecretVault instance;

    /**
     * 获取单例实例。
     *
     * @param masterPassword 主密码（来自环境变量 AIOS_MASTER_KEY）
     */
    public static synchronized EncryptedSecretVault getInstance(String masterPassword) {
        if (instance == null) {
            instance = new EncryptedSecretVault(masterPassword);
        }
        return instance;
    }

    /**
     * 获取已初始化的单例实例。
     */
    public static EncryptedSecretVault getInstance() {
        if (instance == null) {
            throw new IllegalStateException("EncryptedSecretVault not initialized. "
                    + "Call getInstance(masterPassword) first.");
        }
        return instance;
    }

    private EncryptedSecretVault(String masterPassword) {
        this.envelope = new EnvelopeEncryption();
        this.envelope.initializeKek(masterPassword);
    }

    /**
     * 注册密钥 — 加密后存储，返回句柄。
     *
     * @param category    密钥分类 (如 "llm", "github", "aws")
     * @param keyId       密钥标识 (如 "OPENAI_API_KEY")
     * @param secretValue 明文密钥值
     * @return 句柄引用 (如 "enc:llm:OPENAI_API_KEY")
     */
    public String registerSecret(String category, String keyId, String secretValue) {
        String handleId = "enc:" + category + ":" + keyId;
        String ciphertext = envelope.encrypt(secretValue);
        encryptedSecrets.put(handleId, ciphertext);
        handleRegistry.put(category + ":" + keyId, handleId);
        return handleId;
    }

    /**
     * 从环境变量注册密钥。
     *
     * @param category    密钥分类
     * @param keyId       密钥标识
     * @param envVarName  环境变量名
     * @return 句柄引用，环境变量不存在则返回 null
     */
    public String registerFromEnv(String category, String keyId, String envVarName) {
        String value = System.getenv(envVarName);
        if (value == null || value.isBlank()) {
            return null;
        }
        return registerSecret(category, keyId, value);
    }

    /**
     * 解密并获取密钥明文 — 仅限内核内部使用。
     * <p>
     * 当 OpenAiAdapter 等模块需要读取 Token 时，通过此方法获取明文。
     * 业务 Agent 永远不应调用此方法。
     *
     * @param handleRef 句柄引用
     * @return 明文密钥值
     */
    public String resolveHandle(String handleRef) {
        String ciphertext = encryptedSecrets.get(handleRef);
        if (ciphertext == null) {
            throw new IllegalArgumentException("Unknown handle: " + handleRef);
        }
        return envelope.decrypt(ciphertext);
    }

    /**
     * 通过 category 和 keyId 获取密钥明文 — 仅限内核内部使用。
     */
    public String resolveSecret(String category, String keyId) {
        String handleId = handleRegistry.get(category + ":" + keyId);
        if (handleId == null) {
            throw new IllegalArgumentException("Unknown secret: " + category + ":" + keyId);
        }
        return resolveHandle(handleId);
    }

    /**
     * 检查密钥是否已注册。
     */
    public boolean hasSecret(String category, String keyId) {
        return handleRegistry.containsKey(category + ":" + keyId);
    }

    /**
     * 获取密钥的密文形式 — 供 VFS 存储使用。
     * <p>
     * 当 VFS 写入 /vfs/secrets/ 路径时，实际存储的是密文而非明文。
     *
     * @param handleRef 句柄引用
     * @return 密文字符串 (keyId:v1:base64(...))
     */
    public String getCiphertext(String handleRef) {
        return encryptedSecrets.get(handleRef);
    }

    /**
     * 密钥轮转 — 生成新 KEK 并重新包装所有 DEK。
     * <p>
     * 轮转流程：
     * <ol>
     *   <li>生成新 KEK</li>
     *   <li>遍历所有密文，用旧 KEK 解密 DEK</li>
     *   <li>用新 KEK 重新包装 DEK</li>
     *   <li>更新密文格式中的 keyId</li>
     * </ol>
     * <p>
     * 注意：实际数据（API Key 明文）不会被重新加密，只有 DEK 被重新包装。
     *
     * @param newMasterPassword 新主密码
     * @return 受影响的密钥数量
     */
    public int rotateKeys(String newMasterPassword) {
        String oldKekId = envelope.getActiveKekId();
        envelope.rotateKek(newMasterPassword);

        int count = 0;
        // 重新加密所有密钥（解密后用新 KEK 重新加密）
        Map<String, String> reencrypted = new ConcurrentHashMap<>();
        for (Map.Entry<String, String> entry : encryptedSecrets.entrySet()) {
            String plaintext = envelope.decrypt(entry.getValue());
            String newCiphertext = envelope.encrypt(plaintext);
            reencrypted.put(entry.getKey(), newCiphertext);
            count++;
        }
        encryptedSecrets.putAll(reencrypted);

        return count;
    }

    /**
     * 获取保险库状态报告。
     */
    public String getVaultStatus() {
        return String.format(
                "EncryptedSecretVault{secrets=%d, activeKek=%s, retiredKeks=%d, totalKeks=%d}",
                encryptedSecrets.size(),
                envelope.getActiveKekId(),
                envelope.retiredKekCount(),
                envelope.getAllKekIds().size()
        );
    }

    /**
     * 安全清理 — 清除所有内存中的密钥和密文。
     */
    public void clearAll() {
        encryptedSecrets.clear();
        handleRegistry.clear();
    }

    /**
     * 获取信封加密引擎（用于高级操作）。
     */
    public EnvelopeEncryption getEnvelope() {
        return envelope;
    }
}
