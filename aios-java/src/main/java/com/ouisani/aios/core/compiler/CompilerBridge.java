package com.ouisani.aios.core.compiler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class CompilerBridge {

    private static final Logger log = LoggerFactory.getLogger(CompilerBridge.class);

    private static final class Holder {
        static final CompilerBridge INSTANCE = new CompilerBridge();
    }

    private CompilerBridge() {}

    public static CompilerBridge instance() {
        return Holder.INSTANCE;
    }

    /**
     * JIT 编译 C 源码为 WebAssembly 字节码
     *
     * @param cSourceCode C 语言源码字符串
     * @return 编译后的 WASM 字节数组
     * @throws RuntimeException 编译失败时抛出，包含 clang 的错误输出
     */
    public byte[] compileCtoWasm(String cSourceCode) {
        System.out.println("  ⚙️ [Compiler Bridge] JIT compiling C code to WebAssembly...");
        log.info("[Compiler Bridge] Starting C → WASM compilation (source: {} chars)", cSourceCode.length());

        Path cFile = null;
        Path wasmFile = null;

        try {
            // 写入临时 .c 文件
            cFile = Files.createTempFile("aios_temp", ".c");
            wasmFile = Path.of(cFile.toString().replace(".c", ".wasm"));
            Files.writeString(cFile, cSourceCode);
            log.debug("[Compiler Bridge] C source written to: {}", cFile);

            // 调用 clang 编译
            ProcessBuilder pb = new ProcessBuilder(
                    "clang",
                    "--target=wasm32",
                    "-nostdlib",
                    "-Wl,--no-entry",
                    "-Wl,--export-all",
                    "-o", wasmFile.toString(),
                    cFile.toString()
            );
            pb.redirectErrorStream(true);

            System.out.printf("  ⚙️ [Compiler Bridge] clang --target=wasm32 -nostdlib -Wl,--no-entry -Wl,--export-all -o %s %s%n",
                    wasmFile.getFileName(), cFile.getFileName());

            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                System.err.printf("  ❌ [Compiler Bridge] Compilation FAILED (exit code %d)%n", exitCode);
                System.err.printf("  ❌ [Compiler Bridge] clang output:%n%s%n", output);
                log.error("[Compiler Bridge] Compilation failed (exit={}): {}", exitCode, output);
                throw new RuntimeException("C → WASM compilation failed (exit code " + exitCode + "):\n" + output);
            }

            // 读取 .wasm 字节码
            byte[] wasmBytes = Files.readAllBytes(wasmFile);
            System.out.printf("  ✅ [Compiler Bridge] Compilation successful! WASM binary: %d bytes%n", wasmBytes.length);
            log.info("[Compiler Bridge] Compilation successful, output: {} bytes", wasmBytes.length);
            return wasmBytes;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Compiler Bridge interrupted", e);
        } catch (IOException e) {
            throw new RuntimeException("Compiler Bridge I/O error: " + e.getMessage(), e);
        } finally {
            // 清理临时文件
            try {
                if (cFile != null) Files.deleteIfExists(cFile);
                if (wasmFile != null) Files.deleteIfExists(wasmFile);
            } catch (IOException e) {
                log.warn("[Compiler Bridge] Failed to cleanup temp files: {}", e.getMessage());
            }
        }
    }
}
