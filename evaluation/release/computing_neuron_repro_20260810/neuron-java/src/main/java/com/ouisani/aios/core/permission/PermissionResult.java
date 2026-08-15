package com.ouisani.aios.core.permission;

/**
 * 权限裁决结果 — 镜像 jcode {@code safety.rs:67-74} 的 {@code PermissionResult} 枚举。
 * <p>
 * sealed interface + 4 record 实现，对应 jcode 四变体：
 * <ul>
 *   <li>{@link Approved} — 已批准（jcode {@code Approved{message}}）</li>
 *   <li>{@link Denied} — 已拒绝（jcode {@code Denied{reason}}）</li>
 *   <li>{@link Queued} — 已排队待异步裁决（jcode {@code Queued{request_id}}）</li>
 *   <li>{@link Timeout} — 超时（jcode {@code Timeout}）</li>
 * </ul>
 * <p>
 * <b>仅运行时返回值，不持久化</b> — 规避 Gson 多态序列化风险。
 * 持久化用 {@link Decision} record（普通 record，Gson 原生支持）。
 *
 * @see PrivilegeSyscallFilter#askPermission
 */
public sealed interface PermissionResult
        permits PermissionResult.Approved, PermissionResult.Denied,
                PermissionResult.Queued, PermissionResult.Timeout {

    /** 已批准 — 携带可选消息。 */
    record Approved(String message) implements PermissionResult {}

    /** 已拒绝 — 携带拒绝原因。 */
    record Denied(String reason) implements PermissionResult {}

    /** 已排队待异步裁决 — 携带 requestId 供后续回填。 */
    record Queued(String requestId) implements PermissionResult {}

    /** 超时 — 无字段。 */
    record Timeout() implements PermissionResult {}
}
