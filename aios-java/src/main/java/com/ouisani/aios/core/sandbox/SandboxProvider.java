package com.ouisani.aios.core.sandbox;

/**
 * Unified sandbox execution interface for AIOS.
 * <p>
 * Implementations provide different execution environments:
 * <ul>
 *   <li>{@link GraalWasmSandbox} — in-process WASM via GraalVM Polyglot</li>
 *   <li>{@link DockerSandboxProvider} — out-of-process Docker container</li>
 * </ul>
 */
public interface SandboxProvider {

    /**
     * Execute code in this sandbox.
     *
     * @param code       the source code to execute (WASM bytecode as string, Python, Bash, etc.)
     * @param entrypoint the entry point (function name, script path, etc.)
     * @return the execution result as a string
     * @throws Exception if execution fails
     */
    String executeCode(String code, String entrypoint) throws Exception;

    /**
     * Return the name of this sandbox provider.
     */
    String providerName();
}
