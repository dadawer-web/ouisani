package com.ouisani.aios.user.bridge.rpa;

/**
 * 安全令牌 — 控制对宿主物理资源（RPA）的访问权限。
 * <p>
 * 只有持有 {@link Capability#SYS_ADMIN} 级别令牌的组件才能调用
 * {@link HostRpaManager} 的物理操作方法。
 * <p>
 * 令牌在签发时记录请求者身份和时间戳，支持过期检查和撤销。
 * 令牌泄露等同于宿主机被完全接管。
 *
 * @see HostRpaManager#issueSysAdminToken(String)
 * @see HostRpaManager#requireSysAdmin(SecurityToken)
 */
public final class SecurityToken {

    /** 权限级别 */
    public enum Capability {
        /** 系统管理员 — 可访问宿主物理资源（RPA） */
        SYS_ADMIN,
        /** 普通用户 — 无物理资源访问权限 */
        USER
    }

    private static final long TOKEN_LIFETIME_MS = 24 * 60 * 60 * 1000L; // 24 hours

    private final String id;
    private final String requester;
    private final Capability capability;
    private final long issuedAt;
    private final long expiresAt;

    SecurityToken(String requester, Capability capability) {
        this.id = "tok_" + System.currentTimeMillis() + "_" + Integer.toHexString(System.identityHashCode(this));
        this.requester = requester;
        this.capability = capability;
        this.issuedAt = System.currentTimeMillis();
        this.expiresAt = this.issuedAt + TOKEN_LIFETIME_MS;
    }

    public String id() { return id; }
    public String requester() { return requester; }
    public Capability capability() { return capability; }
    public long issuedAt() { return issuedAt; }
    public long expiresAt() { return expiresAt; }

    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SecurityToken that)) return false;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "SecurityToken{id=" + id + ", requester=" + requester + ", capability=" + capability + "}";
    }
}
