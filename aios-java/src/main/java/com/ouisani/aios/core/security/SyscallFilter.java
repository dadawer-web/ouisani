package com.ouisani.aios.core.security;

import com.ouisani.aios.core.syscall.SyscallRequest;

/**
 * Syscall firewall filter — inspired by Linux Seccomp/eBPF.
 * <p>
 * Every syscall passes through a chain of {@link SyscallFilter}s
 * before reaching the kernel dispatcher. A filter that throws
 * {@link SecurityException} blocks the syscall entirely.
 *
 * @see RateLimitSyscallFilter
 * @see PrivilegeSyscallFilter
 */
@FunctionalInterface
public interface SyscallFilter {

    /**
     * Pre-filter a syscall request.
     *
     * @param agentId the agent issuing the syscall
     * @param request the syscall request to inspect
     * @throws SecurityException if the syscall should be blocked
     */
    void preFilter(String agentId, SyscallRequest request) throws SecurityException;
}
