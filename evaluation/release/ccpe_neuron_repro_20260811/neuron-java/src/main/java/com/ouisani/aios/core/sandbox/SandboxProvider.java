package com.ouisani.aios.core.sandbox;

/**
 * 沙箱执行统一接口 — AIOS 的 Ring 3 代码执行抽象。
 * <p>
 * 不同实现提供不同的执行环境：
 * <ul>
 *   <li>{@link GraalWasmSandbox} — 进程内 WASM 执行（GraalVM Polyglot）</li>
 *   <li>{@link DockerSandboxProvider} — 进程外 Docker 容器执行</li>
 * </ul>
 *
 * @see GraalWasmSandbox
 * @see DockerSandboxProvider
 */
public interface SandboxProvider {

    /**
     * 在沙箱中执行代码。
     *
     * @param code       要执行的源代码（WASM 字节码十六进制串、Python、Bash 等）
     * @param entrypoint 入口点（函数名、脚本路径等）
     * @return 执行结果字符串
     * @throws Exception 执行失败时抛出
     */
    String executeCode(String code, String entrypoint) throws Exception;

    /**
     * 返回沙箱后端名称。
     */
    String providerName();
}
