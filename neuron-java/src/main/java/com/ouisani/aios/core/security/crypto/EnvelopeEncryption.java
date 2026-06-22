package com.ouisani.aios.core.security.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 信封加密 (Envelope Encryption) — DEK/KEK 两层密钥体系。
 * <p>
 * 借鉴 n8n 的 keyId:ciphertext 格式和 AWS KMS 的信封加密设计：
 * <ul>
 *   <li>DEK (Data Encryption Key) — 加密实际数据（如 API Key），每次写入生成新 DEK</li>
 *   <li>KEK (Key Encryption Key) — 加密 DEK，由主密码或环境变量保护</li>
 *   <li>轮转时只需重新包装 DEK，无需重新加密所有数据</li>
 *   <li>AES-256-GCM 认证加密保证完整性和机密性</li>
 * </ul>
 * <p>
 * <h3>密文格式</h3>
 * <pre>
 *   keyId:v1:base64(iv|ciphertext|authTag|wrappedDek)
 *   ─────  ─  ───────────────────────────────────────
 *    KEK ID  版本    GCM 加密包 + 包装的 DEK
 * </pre>
 * <p>
 * <h3>工作流程</h3>
 * <pre>
 *   写入密钥:
 *     1. 生成随机 DEK (256-bit AES)
 *     2. 用 DEK 加密明文 (AES-256-GCM)
 *     3. 用 KEK 加密 DEK (AES-256-GCM)
 *     4. 存储 keyId:v1:base64(iv|ciphertext|authTag|wrappedDek)
 *
 *   读取密钥:
 *     1. 解析 keyId 和密文
 *     2. 用 KEK 解密 DEK
 *     3. 用 DEK 解密明文
 *     4. 返回明文
 *
 *   密钥轮转:
 *     1. 生成新 KEK (keyId 递增)
 *     2. 遍历所有密文，用旧 KEK 解密 DEK
 *     3. 用新 KEK 重新包装 DEK
 *     4. 更新密文格式中的 keyId
 *     (无需重新加密实际数据！)
 * </pre>
 * <p>
 * OS 类比：相当于 Linux 的 dm-crypt + LUKS — DEK 是数据卷密钥，
 * KEK 是主密钥，轮转时只需重新包装数据卷密钥。
 *
 * @see EncryptedSecretVault
 */
public class EnvelopeEncryption {

    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final String AES = "AES";
    private static final int GCM_IV_LENGTH = 12;       // 96-bit IV (GCM 推荐)
    private static final int GCM_TAG_LENGTH = 128;      // 128-bit 认证标签
    private static final int DEK_LENGTH_BITS = 256;     // AES-256
    private static final String VERSION = "v1";

    /** KEK 注册表: keyId → KEK 密钥字节 */
    private final Map<String, SecretKey> kekRegistry = new ConcurrentHashMap<>();

    /** 当前活跃的 KEK ID */
    private volatile String activeKekId;

    /** 已退役的 KEK ID 列表（用于解密旧密文） */
    private final Map<String, SecretKey> retiredKeks = new ConcurrentHashMap<>();

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 用主密码初始化 KEK。
     * <p>
     * 使用 HKDF-SHA256 从主密码派生 256-bit KEK。
     *
     * @param masterPassword 主密码（来自环境变量或用户输入）
     * @return KEK ID
     */
    public String initializeKek(String masterPassword) {
        String kekId = "kek_" + System.currentTimeMillis();
        byte[] kekBytes = hkdfSha256(
                masterPassword.getBytes(StandardCharsets.UTF_8),
                kekId.getBytes(StandardCharsets.UTF_8),
                DEK_LENGTH_BITS / 8
        );
        SecretKey kek = new SecretKeySpec(kekBytes, AES);
        kekRegistry.put(kekId, kek);
        this.activeKekId = kekId;
        return kekId;
    }

    /**
     * 生成新 KEK 并设为活跃 — 用于密钥轮转。
     * <p>
     * 旧 KEK 保留在 retiredKeks 中，用于解密历史密文。
     *
     * @param masterPassword 新主密码
     * @return 新 KEK ID
     */
    public String rotateKek(String masterPassword) {
        if (activeKekId != null) {
            SecretKey oldKek = kekRegistry.get(activeKekId);
            if (oldKek != null) {
                retiredKeks.put(activeKekId, oldKek);
            }
        }
        return initializeKek(masterPassword);
    }

    /**
     * 加密数据 — 使用 DEK/KEK 双层信封加密。
     *
     * @param plaintext 明文数据
     * @return 密文格式: keyId:v1:base64(iv|ciphertext|authTag|wrappedDek)
     */
    public String encrypt(String plaintext) {
        if (activeKekId == null) {
            throw new IllegalStateException("KEK not initialized. Call initializeKek() first.");
        }
        if (plaintext == null) {
            throw new IllegalArgumentException("Plaintext cannot be null");
        }

        try {
            // 1. 生成随机 DEK
            KeyGenerator keyGen = KeyGenerator.getInstance(AES);
            keyGen.init(DEK_LENGTH_BITS, secureRandom);
            SecretKey dek = keyGen.generateKey();

            // 2. 用 DEK 加密明文 (AES-256-GCM)
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher dataCipher = Cipher.getInstance(AES_GCM);
            dataCipher.init(Cipher.ENCRYPT_MODE, dek, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = dataCipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // 3. 用 KEK 加密 DEK (wrap)
            SecretKey kek = kekRegistry.get(activeKekId);
            byte[] kekIv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(kekIv);

            Cipher kekCipher = Cipher.getInstance(AES_GCM);
            kekCipher.init(Cipher.ENCRYPT_MODE, kek, new GCMParameterSpec(GCM_TAG_LENGTH, kekIv));
            byte[] wrappedDek = kekCipher.doFinal(dek.getEncoded());

            // 4. 组装密文: iv | ciphertext | kekIv | wrappedDek
            ByteBuffer buffer = ByteBuffer.allocate(
                    GCM_IV_LENGTH + ciphertext.length + GCM_IV_LENGTH + wrappedDek.length
            );
            buffer.put(iv);
            buffer.put(ciphertext);
            buffer.put(kekIv);
            buffer.put(wrappedDek);

            String encoded = Base64.getEncoder().encodeToString(buffer.array());
            return activeKekId + ":" + VERSION + ":" + encoded;

        } catch (Exception e) {
            throw new RuntimeException("Encryption failed: " + e.getMessage(), e);
        }
    }

    /**
     * 解密数据 — 使用 DEK/KEK 双层信封解密。
     *
     * @param ciphertext 密文格式: keyId:v1:base64(...)
     * @return 明文数据
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            throw new IllegalArgumentException("Ciphertext cannot be null or blank");
        }

        String[] parts = ciphertext.split(":", 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid ciphertext format. Expected: keyId:version:base64data");
        }

        String kekId = parts[0];
        String version = parts[1];
        String encoded = parts[2];

        if (!VERSION.equals(version)) {
            throw new IllegalArgumentException("Unsupported version: " + version);
        }

        // 查找 KEK（先在活跃 KEK 中找，再在退役 KEK 中找）
        SecretKey kek = kekRegistry.getOrDefault(kekId, retiredKeks.get(kekId));
        if (kek == null) {
            throw new IllegalStateException("Unknown KEK ID: " + kekId + " — key may have been purged");
        }

        try {
            byte[] data = Base64.getDecoder().decode(encoded);
            ByteBuffer buffer = ByteBuffer.wrap(data);

            // 1. 提取 IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);

            // 2. 提取密文（剩余部分减去 kekIv 和 wrappedDek）
            int remaining = buffer.remaining();
            int kekIvStart = remaining - GCM_IV_LENGTH - (DEK_LENGTH_BITS / 8 + GCM_TAG_LENGTH / 8);
            // wrappedDek 长度 = DEK 字节 + GCM 标签字节
            int wrappedDekLength = DEK_LENGTH_BITS / 8 + GCM_TAG_LENGTH / 8;

            byte[] ciphertextBytes = new byte[remaining - GCM_IV_LENGTH - wrappedDekLength];
            buffer.get(ciphertextBytes);

            // 3. 提取 KEK IV
            byte[] kekIv = new byte[GCM_IV_LENGTH];
            buffer.get(kekIv);

            // 4. 提取 wrappedDek
            byte[] wrappedDek = new byte[wrappedDekLength];
            buffer.get(wrappedDek);

            // 5. 用 KEK 解密 DEK (unwrap)
            Cipher kekCipher = Cipher.getInstance(AES_GCM);
            kekCipher.init(Cipher.DECRYPT_MODE, kek, new GCMParameterSpec(GCM_TAG_LENGTH, kekIv));
            byte[] dekBytes = kekCipher.doFinal(wrappedDek);
            SecretKey dek = new SecretKeySpec(dekBytes, AES);

            // 6. 用 DEK 解密明文
            Cipher dataCipher = Cipher.getInstance(AES_GCM);
            dataCipher.init(Cipher.DECRYPT_MODE, dek, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] plaintext = dataCipher.doFinal(ciphertextBytes);

            return new String(plaintext, StandardCharsets.UTF_8);

        } catch (Exception e) {
            throw new RuntimeException("Decryption failed: " + e.getMessage(), e);
        }
    }

    /**
     * 检查密文是否使用指定 KEK 加密。
     *
     * @param ciphertext 密文
     * @param kekId      KEK ID
     * @return true 如果密文使用指定 KEK
     */
    public boolean isEncryptedWith(String ciphertext, String kekId) {
        if (ciphertext == null || kekId == null) return false;
        return ciphertext.startsWith(kekId + ":");
    }

    /**
     * 检查密文是否使用当前活跃 KEK 加密。
     */
    public boolean isCurrentKek(String ciphertext) {
        return isEncryptedWith(ciphertext, activeKekId);
    }

    /**
     * 获取当前活跃 KEK ID。
     */
    public String getActiveKekId() {
        return activeKekId;
    }

    /**
     * 获取所有 KEK ID（含退役）。
     */
    public java.util.Set<String> getAllKekIds() {
        java.util.Set<String> ids = new java.util.HashSet<>(kekRegistry.keySet());
        ids.addAll(retiredKeks.keySet());
        return ids;
    }

    /**
     * 清除退役 KEK — 确保旧密文无法再被解密。
     * <p>
     * 调用前应确保所有密文已用新 KEK 重新包装。
     */
    public void purgeRetiredKeks() {
        retiredKeks.clear();
    }

    /**
     * 获取退役 KEK 数量。
     */
    public int retiredKekCount() {
        return retiredKeks.size();
    }

    // ════════════════════════════════════════════════════════════════
    //  HKDF-SHA256 密钥派生
    // ════════════════════════════════════════════════════════════════

    /**
     * HKDF-SHA256 — 从主密码派生密钥。
     * <p>
     * RFC 5869 实现：Extract + Expand 两阶段。
     */
    private static byte[] hkdfSha256(byte[] ikm, byte[] salt, int length) {
        try {
            // Extract
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            byte[] prk = md.digest(ikm);

            // Expand
            byte[] okm = new byte[length];
            byte[] t = new byte[0];
            int pos = 0;
            int counter = 1;

            while (pos < length) {
                md = MessageDigest.getInstance("SHA-256");
                md.update(prk);
                md.update(t);
                md.update((byte) counter);
                t = md.digest();

                int copyLen = Math.min(t.length, length - pos);
                System.arraycopy(t, 0, okm, pos, copyLen);
                pos += copyLen;
                counter++;
            }

            return okm;
        } catch (Exception e) {
            throw new RuntimeException("HKDF derivation failed: " + e.getMessage(), e);
        }
    }
}
