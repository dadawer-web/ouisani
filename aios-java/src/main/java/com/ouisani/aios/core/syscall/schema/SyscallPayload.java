package com.ouisani.aios.core.syscall.schema;

/**
 * Top-level marker interface for all standardized syscall payloads.
 * <p>
 * Every namespace-specific payload (LLM, Memory, Storage, Tool, etc.)
 * must implement this interface, enabling compile-time type safety
 * and generic constraints across the AIOS kernel ABI.
 * <p>
 * Analogous to POSIX's {@code struct} definitions for syscall arguments —
 * each payload is a strongly-typed contract between user-space Agents
 * and the kernel.
 */
public interface SyscallPayload {
}
