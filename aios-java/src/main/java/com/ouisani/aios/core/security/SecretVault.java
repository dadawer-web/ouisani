package com.ouisani.aios.core.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 密钥保险库 — Agent 只拿句柄，不拿明文。
 * <p>
 * 核心原则：
 * <ul>
 *   <li>API Key 在内存中只存在于 SecretVault 内部，Agent 永远无法直接读取</li>
 *   <li>Agent 通过句柄（Handle）引用密钥，如 {@code handle:llm:openai}</li>
 *   <li>只有在最终发出 HTTP 请求前，才由 SecretVault 在内部完成密钥组装</li>
 *   <li>日志中绝不输出密钥值，只输出句柄 ID</li>
 * </ul>
 * <p>
 * OS 类比：相当于 Linux 的 {@code /etc/shadow} + Keyring —
 * 普通用户只能通过 PAM 认证，无法直接读取密码哈希。
 * <p>
 * 与 SecretRefResolver 的关系：
 * SecretRefResolver 负责从环境变量/文件中解析密钥引用（{@code env:llm:OPENAI_API_KEY}），
 * SecretVault 负责将解析后的密钥值安全存储并只通过句柄暴露给 Agent。
 *
 * @see com.ouisani.aios.operator.secrets.SecretRefResolver
 */
public final class SecretVault {

    private static final Logger log = LoggerFactory.getLogger(SecretVault.class);
    private static final SecretVault INSTANCE = new SecretVault();

    /** 密钥存储：handleId → 加密/混淆后的密钥值 */
    private final ConcurrentHashMap<String, char[]> secrets = new ConcurrentHashMap<>();

    /** 句柄注册表：逻辑引用 → handleId */
    private final ConcurrentHashMap<String, String> handleRegistry = new ConcurrentHashMap<>();

    /** 句柄计数器 */
    private final AtomicInteger handleCounter = new AtomicInteger(0);

    /** 密钥访问审计日志 */
    private final ConcurrentHashMap<String, AccessRecord> accessLog = new ConcurrentHashMap<>();

    public static SecretVault instance() {
        return INSTANCE;
    }

    private SecretVault() {
        log.info("[SecretVault] 已初始化，所有 API Key 将仅通过句柄访问");
    }

    // ════════════════════════════════════════════════════════════════
    //  密钥注册 — 只在启动时调用
    // ════════════════════════════════════════════════════════════════

    /**
     * 注册密钥 — 将明文密钥存入保险库，返回句柄 ID。
     * <p>
     * 调用场景：系统启动时，由 AiosShell 或 InitDaemon 从环境变量读取密钥后调用。
     * Agent 永远不会调用此方法。
     *
     * @param category  密钥类别（如 "llm", "search", "gateway"）
     * @param keyId     密钥标识（如 "openai", "serper"）
     * @param secretValue 密钥明文值
     * @return 句柄 ID（如 "hdl_7"）
     */
    public String registerSecret(String category, String keyId, String secretValue) {
        if (secretValue == null || secretValue.isEmpty()) {
            log.warn("[SecretVault] 空密钥已忽略: {}/{}", category, keyId);
            return "hdl_empty";
        }

        String logicalRef = category + ":" + keyId;

        // 如果已注册，先清除旧密钥
        String existingHandle = handleRegistry.get(logicalRef);
        if (existingHandle != null) {
            char[] oldSecret = secrets.remove(existingHandle);
            if (oldSecret != null) {
                // 安全擦除旧密钥
                java.util.Arrays.fill(oldSecret, '\0');
            }
        }

        // 生成新句柄
        String handleId = "hdl_" + handleCounter.incrementAndGet();

        // 存储为 char[]（而非 String），避免字符串常量池残留
        secrets.put(handleId, secretValue.toCharArray());
        handleRegistry.put(logicalRef, handleId);

        // 立即清除传入的 String 引用（尽力而为，String 不可变无法真正擦除）
        accessLog.put(handleId, new AccessRecord(category, keyId, System.currentTimeMillis()));

        log.info("[SecretVault] 密钥已注册: {}/{} → 句柄={} (保险库大小={})",
                category, keyId, handleId, secrets.size());

        return handleId;
    }

    // ════════════════════════════════════════════════════════════════
    //  句柄查询 — Agent 只能拿到句柄
    // ════════════════════════════════════════════════════════════════

    /**
     * 获取密钥句柄 — Agent 安全地引用密钥。
     * <p>
     * 返回格式：{@code handle:category:keyId}
     * Agent 将此句柄传递给需要密钥的组件，由组件在内部调用
     * {@link #resolveHandle(String)} 获取明文。
     *
     * @param category 密钥类别
     * @param keyId    密钥标识
     * @return 句柄引用字符串，如 "handle:llm:openai"
     */
    public String getHandle(String category, String keyId) {
        String logicalRef = category + ":" + keyId;
        String handleId = handleRegistry.get(logicalRef);
        if (handleId == null) {
            log.warn("[SecretVault] 未注册的密钥: {}", logicalRef);
            return "handle:NOT_FOUND:" + logicalRef;
        }
        return "handle:" + logicalRef;
    }

    /**
     * 检查密钥是否已注册。
     */
    public boolean hasSecret(String category, String keyId) {
        return handleRegistry.containsKey(category + ":" + keyId);
    }

    // ════════════════════════════════════════════════════════════════
    //  密钥解析 — 只在 HTTP 请求发出前调用
    // ════════════════════════════════════════════════════════════════

    /**
     * 解析句柄获取密钥明文 — 仅限内核内部使用。
     * <p>
     * 此方法应该只被以下组件调用：
     * <ul>
     *   <li>{@code OpenAiAdapter} — 在构建 HTTP 请求头时</li>
     *   <li>{@code WebSearchTool} — 在构建 HTTP 请求头时</li>
     *   <li>{@code GatewayClient} — 在构建 HTTP 请求头时</li>
     *   <li>{@code Mem0Provider} — 在构建 HTTP 请求头时</li>
     * </ul>
     * <p>
     * 绝不应该被 Agent 代码或 Tool 实现直接调用！
     *
     * @param handleRef 句柄引用（如 "handle:llm:openai"）
     * @return 密钥明文，未找到时返回空字符串
     */
    public String resolveHandle(String handleRef) {
        if (handleRef == null || !handleRef.startsWith("handle:")) {
            return "";
        }

        String logicalRef = handleRef.substring("handle:".length());
        String handleId = handleRegistry.get(logicalRef);
        if (handleId == null) {
            log.warn("[SecretVault] 句柄解析失败: {}", handleRef);
            return "";
        }

        char[] secretChars = secrets.get(handleId);
        if (secretChars == null) {
            log.warn("[SecretVault] 句柄对应的密钥数据未找到: {}", handleId);
            return "";
        }

        // 记录访问审计
        AccessRecord record = accessLog.get(handleId);
        if (record != null) {
            record.recordAccess();
        }

        return new String(secretChars);
    }

    /**
     * 从环境变量注册密钥 — 便捷方法。
     * <p>
     * 如果环境变量存在，注册到保险库并返回句柄；
     * 如果不存在，返回空句柄。
     *
     * @param category  密钥类别
     * @param keyId     密钥标识
     * @param envVarName 环境变量名
     * @return 句柄引用
     */
    public String registerFromEnv(String category, String keyId, String envVarName) {
        String value = System.getenv(envVarName);
        if (value == null || value.isEmpty()) {
            log.info("[SecretVault] 环境变量 '{}' 未设置，跳过 {}/{}", envVarName, category, keyId);
            return getHandle(category, keyId);
        }
        return registerSecret(category, keyId, value);
    }

    /**
     * 从 Map 注册密钥 — 便捷方法（用于 .env 文件加载）。
     */
    public String registerFromMap(String category, String keyId, Map<String, String> envMap, String envKey) {
        String value = envMap.get(envKey);
        if (value == null || value.isEmpty()) {
            log.info("[SecretVault] 环境映射中未找到键 '{}'，跳过 {}/{}", envKey, category, keyId);
            return getHandle(category, keyId);
        }
        return registerSecret(category, keyId, value);
    }

    // ════════════════════════════════════════════════════════════════
    //  安全清理
    // ════════════════════════════════════════════════════════════════

    /**
     * 清除所有密钥 — 系统关闭时调用。
     */
    public void clearAll() {
        for (char[] secret : secrets.values()) {
            java.util.Arrays.fill(secret, '\0');
        }
        secrets.clear();
        handleRegistry.clear();
        log.info("[SecretVault] 所有密钥已清除");
    }

    /**
     * 获取保险库状态（不含密钥值）。
     */
    public String getVaultStatus() {
        StringBuilder sb = new StringBuilder("SecretVault 状态:\n");
        for (Map.Entry<String, AccessRecord> entry : accessLog.entrySet()) {
            AccessRecord r = entry.getValue();
            sb.append("  ").append(r.category).append("/").append(r.keyId)
                    .append(" → ").append(entry.getKey())
                    .append(" (访问 ").append(r.accessCount).append(" 次)\n");
        }
        sb.append("密钥总数: ").append(secrets.size());
        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════════
    //  内部类
    // ════════════════════════════════════════════════════════════════

    private static class AccessRecord {
        final String category;
        final String keyId;
        final long registeredAt;
        volatile int accessCount = 0;

        AccessRecord(String category, String keyId, long registeredAt) {
            this.category = category;
            this.keyId = keyId;
            this.registeredAt = registeredAt;
        }

        void recordAccess() {
            accessCount++;
        }
    }
}
