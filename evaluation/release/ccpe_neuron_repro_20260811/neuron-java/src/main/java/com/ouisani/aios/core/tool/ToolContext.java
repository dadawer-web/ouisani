package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.sandbox.BackendBase;
import com.ouisani.aios.core.sandbox.LocalBackend;

/**
 * 工具执行上下文 — 在工具执行期间提供对 AIOS 内核服务的访问。
 * <p>
 * 对标 Claude Code 的 ToolUseContext，但映射到 AIOS 的 SDK 体系。
 * <p>
 * OS 类比：相当于内核栈帧 — 保存当前系统调用的上下文信息。
 *
 * @param agentId  调用该工具的 Agent ID
 * @param sdk      工具层 SDK 契约（{@link ToolSdk}），用于 LLM 调用与文件写入
 * @param workingDir 当前工作目录
 * @param backend  执行后端（{@link BackendBase}）；{@code null} 时通过 {@link #backend()} 懒加载 {@link LocalBackend#instance()}
 * @param tenantId 调用者归属租户 ID；用于 {@link com.ouisani.aios.core.permission.PermissionChecker}
 *                 的显式跨租户所有权校验（{@code caller.tenantId == target.ownerTenantId}）。
 *                 {@code null} 表示 legacy 调用者（未声明租户），所有权校验对 null 一律 skip 以保持向后兼容。
 */
public record ToolContext(
        String agentId,
        ToolSdk sdk,
        String workingDir,
        BackendBase backend,
        String tenantId
) {
    /**
     * 向后兼容的 3 参构造器 — 不指定后端，{@link #backend()} 访问时懒加载 {@link LocalBackend#instance()}。
     * <p>
     * 现有 32+ 个调用点（{@code new ToolContext(agentId, sdk, workingDir)}）零改动继续工作，
     * 默认路由到 LocalBackend；未来容器化执行时由 QueryEngine 注入 DockerBackend/E2BBackend。
     * {@code tenantId} 默认 null（legacy，不参与租户所有权校验）。
     */
    public ToolContext(String agentId, ToolSdk sdk, String workingDir) {
        this(agentId, sdk, workingDir, null, null);
    }

    /**
     * 向后兼容的 4 参构造器 — 承接现有 4 参调用点，{@code tenantId} 默认 null（legacy）。
     */
    public ToolContext(String agentId, ToolSdk sdk, String workingDir, BackendBase backend) {
        this(agentId, sdk, workingDir, backend, null);
    }

    /**
     * backend 访问器 — 懒加载 LocalBackend 单例。
     * <p>
     * 覆盖 record 自动生成的 accessor，使 {@code new ToolContext(a, s, w).backend()}
     * 返回非 null 的 {@link LocalBackend#instance()}，避免 NPE 并实现「工具代码不感知后端类型」。
     */
    @Override
    public BackendBase backend() {
        BackendBase b = this.backend;
        return b == null ? LocalBackend.instance() : b;
    }
}
