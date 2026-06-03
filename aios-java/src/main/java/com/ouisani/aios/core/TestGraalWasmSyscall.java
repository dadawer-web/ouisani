package com.ouisani.aios.core;

import com.ouisani.aios.core.sandbox.GraalWasmSandbox;
import org.graalvm.polyglot.Value;

public class TestGraalWasmSyscall {
    public static void main(String[] args) {
        GraalWasmSandbox sandbox = new GraalWasmSandbox();
        sandbox.initContext();

        byte[] validWasmBytes = new byte[] {
            0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
            0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7f, 0x03,
            0x02, 0x01, 0x00, 0x07, 0x08, 0x01, 0x04, 0x6d,
            0x61, 0x69, 0x6e, 0x00, 0x00, 0x0a, 0x06, 0x01,
            0x04, 0x00, 0x41, 0x2a, 0x0b
        };

        System.out.println("[Test] 正在向 GraalWasm 引擎提交二进制字节码...");
        try {
            Value result = sandbox.execute(validWasmBytes, "main");
            System.out.println("[Test] 沙箱执行成功，WASM 返回值: " + result.asInt());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("[Test] WASM execution interrupted by signal: " + e.getMessage());
        }
    }
}
