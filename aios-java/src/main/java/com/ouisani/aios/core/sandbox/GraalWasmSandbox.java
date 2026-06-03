package com.ouisani.aios.core.sandbox;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.VfsNode;
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

public class GraalWasmSandbox {
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

    public Value execute(byte[] wasmBytes, String functionName) {
        Source source = Source.newBuilder("wasm", ByteSequence.create(wasmBytes), "main").buildLiteral();
        context.eval(source);
        Value mainFunc = context.getBindings("wasm").getMember("main").getMember(functionName);
        return mainFunc.execute();
    }
}
