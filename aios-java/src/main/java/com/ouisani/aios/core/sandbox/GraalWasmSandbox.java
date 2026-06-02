package com.ouisani.aios.core.sandbox;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.ByteSequence;
import org.graalvm.polyglot.proxy.ProxyExecutable;

import java.util.HashMap;
import java.util.Map;

public class GraalWasmSandbox {
    private Context context;

    public void initContext() {
        context = Context.newBuilder("wasm").allowAllAccess(true).build();

        Map<String, Object> aiosEnv = new HashMap<>();
        aiosEnv.put("__aios_log", (ProxyExecutable) arguments -> {
            int val = arguments[0].asInt();
            System.out.println("[JVM Host] 拦截到 WASM 系统调用 __aios_log，参数值: " + val);
            return 0;
        });

        try {
            context.getBindings("wasm").putMember("aios_env", aiosEnv);
            System.out.println("[Sandbox] aios_env registered via putMember");
        } catch (UnsupportedOperationException e) {
            System.out.println("[Sandbox] putMember not supported by WASM global scope (GraalVM limitation)");
            System.out.println("[Sandbox] aios_env ProxyExecutable map created but not injected into WASM bindings");
            System.out.println("[Sandbox] For WASM modules that import aios_env, a JS bridge layer is required");
        }
    }

    public Value execute(byte[] wasmBytes, String functionName) {
        Source source = Source.newBuilder("wasm", ByteSequence.create(wasmBytes), "main").buildLiteral();
        context.eval(source);
        Value mainFunc = context.getBindings("wasm").getMember("main").getMember(functionName);
        return mainFunc.execute();
    }
}
