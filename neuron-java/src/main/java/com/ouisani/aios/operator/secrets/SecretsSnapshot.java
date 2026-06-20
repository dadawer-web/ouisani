package com.ouisani.aios.operator.secrets;

import java.util.*;

/**
 * 密钥运行时快照 — 对标 OpenClaw 的 PreparedSecretsRuntimeSnapshot。
 * <p>
 * 在 Agent 启动时准备一次，包含所有已解析的密钥和认证配置。
 * 快照是不可变的，运行期间不会改变。
 * <p>
 * OS 类比：相当于进程启动时的 capabilities — 一次性从配置和环境变量中
 * 解析出所有权限和密钥，之后不再修改。
 */
public class SecretsSnapshot {

    private final Map<String, String> resolvedSecrets;
    private final Map<String, AuthProfile> authProfiles;
    private final List<String> warnings;

    private SecretsSnapshot(Map<String, String> resolvedSecrets,
                            Map<String, AuthProfile> authProfiles,
                            List<String> warnings) {
        this.resolvedSecrets = Collections.unmodifiableMap(new LinkedHashMap<>(resolvedSecrets));
        this.authProfiles = Collections.unmodifiableMap(new LinkedHashMap<>(authProfiles));
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
    }

    /** 获取已解析的密钥值 */
    public String getSecret(String key) {
        return resolvedSecrets.get(key);
    }

    /** 获取认证档案 */
    public AuthProfile getAuthProfile(String providerId) {
        return authProfiles.get(providerId);
    }

    /** 获取所有已解析的密钥键集合 */
    public Set<String> secretKeys() {
        return resolvedSecrets.keySet();
    }

    /** 获取所有认证档案 */
    public Collection<AuthProfile> authProfiles() {
        return authProfiles.values();
    }

    /** 获取警告列表 */
    public List<String> warnings() { return warnings; }

    /**
     * 准备密钥快照 — 从环境变量和配置中解析所有密钥。
     *
     * @param env           环境变量
     * @param secretRefs    需要解析的 SecretRef 列表
     * @param authProfiles  认证档案
     * @return 准备好的快照
     */
    public static SecretsSnapshot prepare(Map<String, String> env,
                                           List<SecretRef> secretRefs,
                                           Map<String, AuthProfile> authProfiles) {
        Map<String, String> resolved = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        for (SecretRef ref : secretRefs) {
            try {
                String value = SecretRefResolver.resolve(ref, env);
                resolved.put(ref.toKey(), value);
            } catch (SecretResolutionException e) {
                warnings.add("Failed to resolve " + ref.toKey() + ": " + e.getMessage());
            }
        }

        return new SecretsSnapshot(resolved, authProfiles, warnings);
    }

    /** 便捷方法 — 使用系统环境变量 */
    public static SecretsSnapshot prepare(List<SecretRef> secretRefs,
                                           Map<String, AuthProfile> authProfiles) {
        Map<String, String> env = new HashMap<>();
        System.getenv().forEach(env::put);
        return prepare(env, secretRefs, authProfiles);
    }

    /**
     * 认证档案 — 对标 OpenClaw 的 AuthProfileStore 条目。
     *
     * @param providerId  Provider ID（如 "openai", "anthropic"）
     * @param type        档案类型（api_key / token）
     * @param credential  凭证值
     * @param accountId   账户 ID（可选）
     */
    public record AuthProfile(
            String providerId,
            String type,
            String credential,
            String accountId
    ) {
        public AuthProfile {
            if (providerId == null || providerId.isBlank()) throw new IllegalArgumentException("providerId required");
            if (type == null) type = "api_key";
            if (credential == null || credential.isBlank()) throw new IllegalArgumentException("credential required");
            if (accountId == null) accountId = "";
        }
    }
}
