package com.ouisani.aios.core.tool;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 不可提升权限的子 Agent 委托凭证。
 *
 * <p>令牌绑定父子 Agent、租户/工作流追踪信息、能力集合、有效期和调用预算。
 * 子令牌只能从父令牌的能力集合中选择能力（{@code Child ⊆ Parent}），且每次工具执行
 * 都会在真正调用工具前重新验证签名、有效期和预算。</p>
 */
public final class DelegationToken {

    public static final long DEFAULT_TTL_MS = 15 * 60 * 1000L;
    public static final int DEFAULT_MAX_CALLS = 100;

    private static final byte[] PROCESS_KEY = createProcessKey();
    /** Monotonic generation used to invalidate tokens across a restore boundary. */
    private static final AtomicLong REVOCATION_GENERATION = new AtomicLong(0L);

    private final String tokenId;
    private final String parentTokenId;
    private final String parentAgentId;
    private final String childAgentId;
    private final String tenantId;
    private final String workflowId;
    private final String traceId;
    private final Set<String> capabilities;
    private final Set<String> forbiddenCapabilities;
    /** Exact memory asset ids the holder may mount/delegate; {@code *} is a signed wildcard. */
    private final Set<String> delegableMemoryAssets;
    private final long issuedAtMs;
    private final long expiresAtMs;
    private final int maxCalls;
    private final AtomicInteger remainingCalls;
    private final long issuanceGeneration;
    private final String signature;

    private DelegationToken(String tokenId,
                            String parentTokenId,
                            String parentAgentId,
                            String childAgentId,
                            String tenantId,
                            String workflowId,
                            String traceId,
                            Set<String> capabilities,
                            Set<String> forbiddenCapabilities,
                            Set<String> delegableMemoryAssets,
                            long issuedAtMs,
                            long expiresAtMs,
                            int maxCalls,
                            String signature) {
        this.tokenId = requireNonBlank(tokenId, "tokenId");
        this.parentTokenId = blankToNull(parentTokenId);
        this.parentAgentId = blankToNull(parentAgentId);
        this.childAgentId = requireNonBlank(childAgentId, "childAgentId");
        this.tenantId = blankToNull(tenantId);
        this.workflowId = blankToNull(workflowId);
        this.traceId = blankToNull(traceId);
        this.capabilities = normalizeCapabilities(capabilities);
        this.forbiddenCapabilities = normalizeCapabilities(forbiddenCapabilities);
        this.delegableMemoryAssets = normalizeMemoryAssets(delegableMemoryAssets);
        this.issuedAtMs = issuedAtMs > 0 ? issuedAtMs : System.currentTimeMillis();
        this.expiresAtMs = expiresAtMs;
        if (expiresAtMs > 0 && expiresAtMs <= this.issuedAtMs) {
            throw new IllegalArgumentException("expiresAtMs must be after issuedAtMs");
        }
        if (maxCalls < 0) throw new IllegalArgumentException("maxCalls must be >= 0");
        this.maxCalls = maxCalls;
        this.remainingCalls = new AtomicInteger(maxCalls);
        this.issuanceGeneration = REVOCATION_GENERATION.get();
        this.signature = signature == null || signature.isBlank()
                ? sign(canonicalPayload()) : signature;
    }

    /** 创建不受能力限制的根凭证。根凭证只用于兼容顶层 Agent，不代表可伪造子令牌。 */
    public static DelegationToken root(String agentId, String tenantId,
                                       String workflowId, String traceId) {
        long now = System.currentTimeMillis();
        DelegationToken token = new DelegationToken(
                "root_" + UUID.randomUUID(), null, null, agentId,
                tenantId, workflowId, traceId, Set.of("*"), Set.of(), Set.of("*"), now,
                0L, 0, null);
        return token;
    }

    public static DelegationToken root(String agentId) {
        return root(agentId, null, null, null);
    }

    /** Create a root token bounded by a caller-supplied effective permission set. */
    public static DelegationToken rootWithCapabilities(String agentId, String tenantId,
                                                       String workflowId, String traceId,
                                                       Set<String> capabilities) {
        long now = System.currentTimeMillis();
        Set<String> normalized = normalizeCapabilities(capabilities);
        DelegationToken token = new DelegationToken(
                "root_" + UUID.randomUUID(), null, null, agentId,
                tenantId, workflowId, traceId, normalized, Set.of(), Set.of("*"), now,
                0L, 0, null);
        return token;
    }

    /** Root capability boundary with an exact memory asset set. */
    public static DelegationToken rootWithCapabilities(String agentId, String tenantId,
                                                       String workflowId, String traceId,
                                                       Set<String> capabilities,
                                                       Set<String> memoryAssetIds) {
        return rootWithMemoryAssets(agentId, tenantId, workflowId, traceId,
                capabilities, memoryAssetIds);
    }

    /** Create a root token with an explicit memory-asset delegation boundary. */
    public static DelegationToken rootWithMemoryAssets(String agentId, String tenantId,
                                                       String workflowId, String traceId,
                                                       Set<String> capabilities,
                                                       Set<String> memoryAssetIds) {
        long now = System.currentTimeMillis();
        return new DelegationToken(
                "root_" + UUID.randomUUID(), null, null, agentId,
                tenantId, workflowId, traceId, normalizeCapabilities(capabilities), Set.of(),
                memoryAssetIds, now, 0L, 0, null);
    }

    /** 从父令牌签发子令牌，使用默认 15 分钟/100 次工具调用预算。 */
    public static DelegationToken issueChild(DelegationToken parent,
                                             String childAgentId,
                                             Set<String> requestedCapabilities) {
        return issueChild(parent, childAgentId, requestedCapabilities,
                DEFAULT_TTL_MS, DEFAULT_MAX_CALLS);
    }

    /**
     * 从父令牌签发子令牌。
     * @param ttlMs 小于等于 0 表示继承父令牌有效期（根令牌则使用默认 TTL）
     * @param maxCalls 0 表示不限制；有父预算时子预算不能超过父预算
     */
    public static DelegationToken issueChild(DelegationToken parent,
                                             String childAgentId,
                                             Set<String> requestedCapabilities,
                                             long ttlMs,
                                             int maxCalls) {
        return issueChild(parent, childAgentId, requestedCapabilities, null,
                ttlMs, maxCalls, false);
    }

    /**
     * Issue a child token with an explicit memory loadout.  The loadout is
     * checked against the parent's signed asset set before the child token is
     * signed.  A {@code null} set inherits the parent's asset boundary for
     * backwards compatibility; an empty set is an explicit empty loadout.
     */
    public static DelegationToken issueChildWithMemoryAssets(DelegationToken parent,
                                                              String childAgentId,
                                                              Set<String> requestedCapabilities,
                                                              Set<String> requestedMemoryAssets,
                                                              long ttlMs,
                                                              int maxCalls) {
        return issueChild(parent, childAgentId, requestedCapabilities, requestedMemoryAssets,
                ttlMs, maxCalls, true);
    }

    /** Naming-friendly overload for callers that already use issueChild. */
    public static DelegationToken issueChild(DelegationToken parent, String childAgentId,
                                             Set<String> requestedCapabilities,
                                             Set<String> requestedMemoryAssets,
                                             long ttlMs, int maxCalls) {
        return issueChildWithMemoryAssets(parent, childAgentId, requestedCapabilities,
                requestedMemoryAssets, ttlMs, maxCalls);
    }

    private static DelegationToken issueChild(DelegationToken parent,
                                             String childAgentId,
                                             Set<String> requestedCapabilities,
                                             Set<String> requestedMemoryAssets,
                                             long ttlMs,
                                             int maxCalls,
                                             boolean explicitMemoryAssets) {
        Objects.requireNonNull(parent, "parent token must not be null");
        if (!parent.isValid()) throw new IllegalArgumentException("parent delegation token is invalid");
        String child = requireNonBlank(childAgentId, "childAgentId");
        Set<String> requested = normalizeCapabilities(requestedCapabilities);
        if (requested.isEmpty()) requested = parent.capabilities;
        for (String capability : requested) {
            if (!parent.allowsCapability(capability)) {
                throw new IllegalArgumentException("capability exceeds parent delegation: " + capability);
            }
        }
        if (maxCalls < 0) throw new IllegalArgumentException("maxCalls must be >= 0");
        if (parent.maxCalls > 0 && (maxCalls == 0 || maxCalls > parent.maxCalls)) {
            throw new IllegalArgumentException("call budget exceeds parent delegation");
        }

        Set<String> requestedAssets;
        if (!explicitMemoryAssets || requestedMemoryAssets == null) {
            requestedAssets = parent.delegableMemoryAssets;
        } else {
            requestedAssets = normalizeMemoryAssets(requestedMemoryAssets);
            for (String assetId : requestedAssets) {
                if (!parent.allowsMemoryAsset(assetId)) {
                    throw new IllegalArgumentException(
                            "memory asset exceeds parent delegation: " + assetId);
                }
            }
        }

        long now = System.currentTimeMillis();
        long requestedExpiry = ttlMs > 0 ? safeAdd(now, ttlMs) : 0L;
        long expiry = parent.expiresAtMs > 0 && requestedExpiry > 0
                ? Math.min(parent.expiresAtMs, requestedExpiry)
                : (parent.expiresAtMs > 0 ? parent.expiresAtMs : requestedExpiry);
        if (expiry > 0 && expiry <= now) throw new IllegalArgumentException("delegation ttl already expired");

        DelegationToken token = new DelegationToken(
                "del_" + UUID.randomUUID(), parent.tokenId, parent.childAgentId, child,
                parent.tenantId, parent.workflowId, parent.traceId,
                requested, parent.forbiddenCapabilities, requestedAssets,
                now, expiry, maxCalls, null);
        return token;
    }

    public String tokenId() { return tokenId; }
    public String parentTokenId() { return parentTokenId; }
    public String parentAgentId() { return parentAgentId; }
    public String childAgentId() { return childAgentId; }
    public String tenantId() { return tenantId; }
    public String workflowId() { return workflowId; }
    public String traceId() { return traceId; }
    public Set<String> capabilities() { return capabilities; }
    public Set<String> forbiddenCapabilities() { return forbiddenCapabilities; }
    public Set<String> delegableMemoryAssets() { return delegableMemoryAssets; }

    /** Alias used by loadout adapters. */
    public Set<String> memoryAssetIds() { return delegableMemoryAssets; }
    public long issuedAtMs() { return issuedAtMs; }
    public long expiresAtMs() { return expiresAtMs; }
    public int maxCalls() { return maxCalls; }
    public int remainingCalls() { return maxCalls == 0 ? Integer.MAX_VALUE : remainingCalls.get(); }
    public String signature() { return signature; }

    public boolean isExpired() { return isExpired(System.currentTimeMillis()); }

    public boolean isExpired(long nowMs) {
        return expiresAtMs > 0 && nowMs >= expiresAtMs;
    }

    /** 验证进程内 HMAC 签名和有效期；调用预算由 {@link #consumeCall()} 单独消耗。 */
    public boolean isValid() { return isValid(System.currentTimeMillis()); }

    public boolean isValid(long nowMs) {
        if (issuanceGeneration != REVOCATION_GENERATION.get()) return false;
        if (isExpired(nowMs)) return false;
        return MessageDigest.isEqual(signature.getBytes(StandardCharsets.UTF_8),
                sign(canonicalPayload()).getBytes(StandardCharsets.UTF_8));
    }

    /** Revoke all currently issued tokens, used when restoring a snapshot. */
    public static void revokeAll() {
        REVOCATION_GENERATION.incrementAndGet();
    }

    /** Clear the process-wide revocation marker for isolated tests/bootstrap. */
    public static void clearRevocationForTest() {
        REVOCATION_GENERATION.set(0L);
    }


    /** 工具能力匹配：支持精确能力、能力前缀通配符和全局 {@code *}。 */
    public boolean allowsTool(String toolName) {
        if (toolName == null || toolName.isBlank()) return false;
        return allowsCapability("tool:" + toolName) || allowsCapability(toolName);
    }

    /** Namespace permission used by governed shared memory. */
    public boolean allowsMemory(String namespace) {
        if (namespace == null || namespace.isBlank()) return false;
        String normalized = namespace.trim().toLowerCase();
        return allowsCapability("memory:*") || allowsCapability("memory:" + normalized);
    }

    /** Whether a signed token allows this exact memory asset id. */
    public boolean allowsMemoryAsset(String assetId) {
        if (assetId == null || assetId.isBlank() || !isValid()) return false;
        String normalized = assetId.trim().toLowerCase(Locale.ROOT);
        for (String allowed : delegableMemoryAssets) {
            if ("*".equals(allowed) || allowed.equals(normalized)
                    || (allowed.endsWith("*")
                    && normalized.startsWith(allowed.substring(0, allowed.length() - 1)))) {
                return true;
            }
        }
        return false;
    }

    public boolean allowsCapability(String capability) {
        String normalized = normalizeCapability(capability);
        if (normalized.isEmpty() || isForbidden(normalized)) return false;
        for (String allowed : capabilities) {
            if (wildcardMatches(allowed, normalized)) return true;
        }
        return false;
    }

    /** 原子消耗一次工具调用预算，过期或签名失效时始终失败。 */
    public boolean consumeCall() {
        if (!isValid()) return false;
        if (maxCalls == 0) return true;
        while (true) {
            int current = remainingCalls.get();
            if (current <= 0) return false;
            if (remainingCalls.compareAndSet(current, current - 1)) return true;
        }
    }

    private boolean isForbidden(String capability) {
        for (String denied : forbiddenCapabilities) {
            if (wildcardMatches(denied, capability)) return true;
        }
        return false;
    }

    private String canonicalPayload() {
        return field(tokenId) + field(parentTokenId) + field(parentAgentId) + field(childAgentId)
                + field(tenantId) + field(workflowId) + field(traceId)
                + field(String.join(",", capabilities))
                + field(String.join(",", forbiddenCapabilities))
                + field(String.join(",", delegableMemoryAssets))
                + field(Long.toString(issuedAtMs)) + field(Long.toString(expiresAtMs))
                + field(Integer.toString(maxCalls));
    }

    private static boolean wildcardMatches(String pattern, String value) {
        if ("*".equals(pattern) || pattern.equals(value)) return true;
        return pattern.endsWith("*") && value.startsWith(pattern.substring(0, pattern.length() - 1));
    }

    private static Set<String> normalizeCapabilities(Set<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String item = normalizeCapability(value);
                if (!item.isEmpty()) normalized.add(item);
            }
        }
        return Set.copyOf(normalized);
    }

    private static Set<String> normalizeMemoryAssets(Set<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value == null || value.isBlank()) continue;
                normalized.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(normalized);
    }

    private static String normalizeCapability(String capability) {
        if (capability == null) return "";
        String value = capability.trim().toLowerCase();
        return value;
    }

    private static String field(String value) {
        String s = value == null ? "" : value;
        return s.length() + ":" + s;
    }

    private static String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(PROCESS_KEY, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    private static byte[] createProcessKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    private static long safeAdd(long left, long right) {
        if (right <= 0 || Long.MAX_VALUE - left < right) return Long.MAX_VALUE;
        return left + right;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
