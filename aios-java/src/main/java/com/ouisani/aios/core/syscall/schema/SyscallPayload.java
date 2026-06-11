package com.ouisani.aios.core.syscall.schema;

/**
 * Syscall 载荷顶层标记接口 — 所有标准化 syscall 载荷的公共基类。
 * <p>
 * 每个命名空间的载荷（LLM、Memory、Storage、Tool 等）必须实现此接口，
 * 实现编译时类型安全和泛型约束，跨越 AIOS 内核 ABI。
 * <p>
 * OS 类比: POSIX 的 {@code struct} 定义——每个载荷是用户空间 Agent 与内核之间的
 * 强类型契约，等价于 Linux syscall 的参数结构体。
 */
public interface SyscallPayload {
}
