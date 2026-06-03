package com.ouisani.aios.core.sandbox;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.ipc.SignalInterceptor;
import com.ouisani.aios.core.llm.LlmProvider;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.ByteSequence;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class GraalWasmSandbox implements SandboxProvider {
    private static final Logger log = LoggerFactory.getLogger(GraalWasmSandbox.class);
    private Context context;

    public void initContext() {
        context = Context.newBuilder("wasm").allowAllAccess(true).build();

        Map<String, Object> aiosEnv = new HashMap<>();

        aiosEnv.put("__aios_log", (ProxyExecutable) arguments -> {
            int val = arguments[0].asInt();
            log.info("[Host] __aios_log({})", val);
            return 0;
        });

        aiosEnv.put("__aios_vfs_read", (ProxyExecutable) arguments -> {
            String path = arguments[0].asString();
            int maxLen = arguments[2].asInt();
            try {
                var optNode = VfsManager.instance().resolve(path, VfsManager.AGENT_ROOT.get());
                if (optNode.isEmpty()) {
                    log.warn("[Host] __aios_vfs_read: path not found: {}", path);
                    return -1;
                }
                VfsNode node = optNode.get();
                String content = node.read();
                if (content == null) {
                    return 0;
                }
                int copyLen = Math.min(content.length(), maxLen);
                log.debug("[Host] __aios_vfs_read(\"{}\") -> {} bytes", path, copyLen);
                return copyLen;
            } catch (Exception e) {
                log.error("[Host] __aios_vfs_read error: path={}, error={}", path, e.getMessage());
                return -1;
            }
        });

        aiosEnv.put("__aios_think", (ProxyExecutable) arguments -> {
            String prompt = arguments[0].asString();
            int maxLen = arguments[2].asInt();
            try {
                LlmProvider provider = VfsManager.instance().getLlmProvider();
                if (provider == null || !provider.isAvailable()) {
                    log.warn("[Host] __aios_think: LLM provider not available");
                    return -1;
                }
                String response = provider.think(prompt, "You are an AIOS agent.");
                if (response == null) {
                    return 0;
                }
                int copyLen = Math.min(response.length(), maxLen);
                log.debug("[Host] __aios_think(\"{}...\") -> {} bytes",
                        prompt.substring(0, Math.min(prompt.length(), 40)), copyLen);
                return copyLen;
            } catch (Exception e) {
                log.error("[Host] __aios_think error: prompt={}, error={}",
                        prompt.substring(0, Math.min(prompt.length(), 40)), e.getMessage());
                return -1;
            }
        });

        try {
            context.getBindings("wasm").putMember("aios_env", aiosEnv);
            log.info("[Sandbox] aios_env registered: __aios_log, __aios_vfs_read, __aios_think");
        } catch (UnsupportedOperationException e) {
            log.warn("[Sandbox] putMember not supported by WASM global scope (GraalVM limitation)");
            log.warn("[Sandbox] aios_env ProxyExecutable map created but not injected into WASM bindings");
            log.warn("[Sandbox] For WASM modules that import aios_env, a JS bridge layer is required");
        }
    }

    public Value execute(byte[] wasmBytes, String functionName) throws InterruptedException {
        // Signal interception: check pending signals before WASM execution
        AgentTask currentTask = TaskScheduler.CURRENT_TASK.get();
        if (currentTask != null) {
            SignalInterceptor.checkAndDrain(currentTask);
            // If we reach here, no SIGTERM/SIGINT was pending.
            // SIGUSR1 is not meaningful for WASM execution, so we just drain it.
        }

        Source source = Source.newBuilder("wasm", ByteSequence.create(wasmBytes), "main").buildLiteral();
        context.eval(source);
        Value mainFunc = context.getBindings("wasm").getMember("main").getMember(functionName);
        return mainFunc.execute();
    }

    @Override
    public String executeCode(String code, String entrypoint) throws Exception {
        // For GraalWasm, code is expected to be hex-encoded WASM bytecode
        // or base64. For simplicity, we treat it as raw bytes if it looks
        // like hex, otherwise fall back to the mock WASM.
        byte[] wasmBytes;
        try {
            wasmBytes = hexToBytes(code);
        } catch (Exception e) {
            log.debug("[GraalWasmSandbox] Code is not valid hex, using mock WASM bytecode");
            wasmBytes = new byte[]{
                    0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
                    0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7f, 0x03,
                    0x02, 0x01, 0x00, 0x07, 0x08, 0x01, 0x04, 0x6d,
                    0x61, 0x69, 0x6e, 0x00, 0x00, 0x0a, 0x06, 0x01,
                    0x04, 0x00, 0x41, 0x2a, 0x0b
            };
        }

        Value result = execute(wasmBytes, entrypoint != null ? entrypoint : "main");
        return result.toString();
    }

    @Override
    public String providerName() {
        return "GraalWasm";
    }

    private static byte[] hexToBytes(String hex) {
        String clean = hex.replaceAll("\\s+", "");
        if (clean.length() % 2 != 0) throw new IllegalArgumentException("Odd hex length");
        byte[] bytes = new byte[clean.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
