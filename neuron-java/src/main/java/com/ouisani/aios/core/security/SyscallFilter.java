package com.ouisani.aios.core.security;

import com.ouisani.aios.core.syscall.SyscallRequest;

/**
 * 系统调用防火墙过滤器 — 灵感来自 Linux Seccomp/eBPF。
 * <p>
 * 每个系统调用在到达内核调度器之前，都会经过一条 {@link SyscallFilter} 链。
 * 如果某个过滤器抛出 {@link SecurityException}，该系统调用将被完全阻止。
 *
 * <h3>OS 类比: Linux Seccomp-BPF</h3>
 * Linux 的 Seccomp 允许进程在进入内核前安装 BPF 过滤器，
 * 对系统调用号进行白名单/黑名单检查。AIOS 的 SyscallFilter
 * 将此模型提升到语义级别：不仅检查调用号，还检查 Payload 内容。
 *
 * @see RateLimitSyscallFilter
 * @see PrivilegeSyscallFilter
 * @see BpfManager
 */
@FunctionalInterface
public interface SyscallFilter {

    /**
     * 系统调用预过滤。
     *
     * @param agentId 发起系统调用的 Agent 标识
     * @param request 待检查的系统调用请求
     * @throws SecurityException 如果该系统调用应被阻止
     */
    void preFilter(String agentId, SyscallRequest request) throws SecurityException;
}
