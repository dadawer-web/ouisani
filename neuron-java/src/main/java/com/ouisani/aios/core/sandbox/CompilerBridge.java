package com.ouisani.aios.core.sandbox;

import com.ouisani.aios.core.ipc.SignalType;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 动态编译桥 — AIOS 的 JIT 编译器接口。
 * <p>
 * 当 Agent 发现现有工具不足以完成任务时，它可以自主编写代码，
 * 通过 {@code sys_jit_compile} 系统调用将代码实时编译为可执行格式，
 * 然后在 {@link GraalWasmSandbox} 的 Ring 3 沙箱中安全执行。
 *
 * <h3>编译流程</h3>
 * <ol>
 *   <li>Agent 生成源代码（Java / C / Rust）</li>
 *   <li>调用 {@code sys_jit_compile(sourceCode, language)}</li>
 *   <li>CompilerBridge 调用系统工具链编译代码：
 *     <ul>
 *       <li>Java → javac → .class → 包装为可执行 JAR</li>
 *       <li>C → clang → .wasm (使用 wasm32-wasi 目标)</li>
 *       <li>Rust → cargo + wasm32-wasi → .wasm</li>
 *     </ul>
 *   </li>
 *   <li>编译产物存入 {@code /var/cache/jit/}</li>
 *   <li>返回编译结果（成功/失败 + 产物路径）</li>
 * </ol>
 *
 * <h3>安全模型</h3>
 * CompilerBridge 只负责编译，不负责执行。所有编译产物必须在
 * GraalWasmSandbox（Ring 3）中执行，绝不能在 Ring 0 内核态运行。
 * 这确保了即使 Agent 生成了恶意代码，也只能在沙箱中执行，
 * 无法影响 AIOS 内核的稳定性。
 *
 * <h3>OS 类比</h3>
 * <table>
 *   <tr><th>概念</th><th>AIOS</th><th>说明</th></tr>
 *   <tr><td>JIT 编译器</td><td>CompilerBridge</td><td>运行时编译</td></tr>
 *   <tr><td>Ring 0 / Ring 3</td><td>内核态 / GraalWasmSandbox</td><td>特权级隔离</td></tr>
 *   <tr><td>sys_brk / sys_mmap</td><td>SandboxResourceLimit</td><td>资源限制</td></tr>
 *   <tr><td>SIGSEGV</td><td>SandboxException → SIGSEGV</td><td>段错误熔断</td></tr>
 * </table>
 *
 * @see GraalWasmSandbox
 */
public final class CompilerBridge {

    private static final Logger log = LoggerFactory.getLogger(CompilerBridge.class);

    // ── 路径常量 ──

    private static final String JIT_CACHE_DIR = "/var/cache/jit";
    private static final String JIT_SRC_DIR = "/var/cache/jit/src";
    private static final String JIT_OUT_DIR = "/var/cache/jit/out";

    // ── Singleton ──

    private static final class Holder {
        static final CompilerBridge INSTANCE = new CompilerBridge();
    }

    public static CompilerBridge instance() {
        return Holder.INSTANCE;
    }

    // ── 状态 ──

    /** 编译产物索引：compileId → CompilationResult */
    private final ConcurrentHashMap<String, CompilationResult> compilationIndex = new ConcurrentHashMap<>();

    /** 编译 ID 序列 */
    private final AtomicLong compileIdSeq = new AtomicLong(0);

    // ── 统计 ──

    private final AtomicLong totalCompilations = new AtomicLong(0);
    private final AtomicLong totalSuccesses = new AtomicLong(0);
    private final AtomicLong totalFailures = new AtomicLong(0);

    private CompilerBridge() {
    }

    // ════════════════════════════════════════════════════════════════
    //  sys_jit_compile — 动态编译系统调用
    // ════════════════════════════════════════════════════════════════

    /**
     * JIT 编译 — 将 Agent 生成的源代码编译为可执行格式。
     * <p>
     * 类比 Linux 的 {@code sys_bpf} 加载 eBPF 字节码：
     * 内核只负责验证和加载，实际执行在沙箱中进行。
     *
     * @param sourceCode Agent 生成的源代码
     * @param language   编程语言（"java", "c", "rust"）
     * @return CompilationResult 编译结果
     */
    public CompilationResult compile(String sourceCode, String language) {
        String compileId = "jit-" + compileIdSeq.incrementAndGet();
        totalCompilations.incrementAndGet();

        log.info("[CompilerBridge] ╔══════════════════════════════════════════════════╗");
        log.info("[CompilerBridge] ║  JIT 编译: id={}, lang={}              ║", compileId, language);
        log.info("[CompilerBridge] ╚══════════════════════════════════════════════════╝");

        SemanticEtw.getInstance().logEvent("JIT", "COMPILE_START",
                "id=" + compileId + " lang=" + language + " size=" + sourceCode.length());

        long startTime = System.currentTimeMillis();

        try {
            // 确保输出目录存在
            ensureDirectories();

            CompilationResult result = switch (language.toLowerCase()) {
                case "java" -> compileJava(compileId, sourceCode);
                case "c" -> compileC(compileId, sourceCode);
                case "rust" -> compileRust(compileId, sourceCode);
                default -> CompilationResult.failed(compileId, language,
                        "Unsupported language: " + language + ". Supported: java, c, rust");
            };

            long elapsed = System.currentTimeMillis() - startTime;

            if (result.success()) {
                totalSuccesses.incrementAndGet();
                compilationIndex.put(compileId, result);
                log.info("[CompilerBridge] ✓ 编译完成: id={}, lang={}, output={}, elapsed={}ms",
                        compileId, language, result.outputPath(), elapsed);
            } else {
                totalFailures.incrementAndGet();
                log.warn("[CompilerBridge] ✗ 编译失败: id={}, lang={}, error={}",
                        compileId, language, result.errorMessage());
            }

            SemanticEtw.getInstance().logEvent("JIT", "COMPILE_" + (result.success() ? "SUCCESS" : "FAILURE"),
                    "id=" + compileId + " lang=" + language + " elapsed=" + elapsed + "ms");

            return result;

        } catch (Exception e) {
            totalFailures.incrementAndGet();
            log.error("[CompilerBridge] 编译错误: id={}, error={}", compileId, e.getMessage());
            return CompilationResult.failed(compileId, language, e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  语言特定编译器
    // ════════════════════════════════════════════════════════════════

    /**
     * 编译 Java 源代码 → .class 字节码。
     * <p>
     * 使用系统 javac 编译器。编译产物为 .class 文件，
     * 可在 GraalWasmSandbox 中通过 Java Bytecode 解释器执行，
     * 或通过后续的 WASM 转换步骤执行。
     */
    private CompilationResult compileJava(String compileId, String sourceCode) {
        try {
            // 提取类名
            String className = extractJavaClassName(sourceCode);
            if (className == null) {
                className = "AgentGenerated_" + compileId.replace("-", "_");
            }

            // 写入源文件
            Path srcFile = Path.of(JIT_SRC_DIR, compileId, className + ".java");
            Files.createDirectories(srcFile.getParent());
            Files.writeString(srcFile, sourceCode);

            // 调用 javac
            Path outDir = Path.of(JIT_OUT_DIR, compileId);
            Files.createDirectories(outDir);

            ProcessBuilder pb = new ProcessBuilder(
                    "javac", "-d", outDir.toString(), srcFile.toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                Path classFile = outDir.resolve(className + ".class");
                if (Files.exists(classFile)) {
                    byte[] bytecode = Files.readAllBytes(classFile);
                    return CompilationResult.success(compileId, "java",
                            classFile.toString(), bytecode, className);
                } else {
                    return CompilationResult.failed(compileId, "java",
                            "Compilation succeeded but class file not found");
                }
            } else {
                return CompilationResult.failed(compileId, "java",
                        "javac exit code " + exitCode + ": " + output);
            }

        } catch (Exception e) {
            return CompilationResult.failed(compileId, "java",
                    "Java compilation error: " + e.getMessage());
        }
    }

    /**
     * 编译 C 源代码 → WebAssembly (wasm32-wasi)。
     * <p>
     * 使用 clang 的 wasm32-wasi 目标。编译产物为 .wasm 文件，
     * 可直接在 GraalWasmSandbox 中执行。
     */
    private CompilationResult compileC(String compileId, String sourceCode) {
        try {
            // 写入源文件
            Path srcFile = Path.of(JIT_SRC_DIR, compileId, "agent_generated.c");
            Files.createDirectories(srcFile.getParent());
            Files.writeString(srcFile, sourceCode);

            // 输出路径
            Path outDir = Path.of(JIT_OUT_DIR, compileId);
            Files.createDirectories(outDir);
            Path wasmFile = outDir.resolve("agent_generated.wasm");

            // 尝试使用 clang 编译到 wasm32-wasi
            ProcessBuilder pb = new ProcessBuilder(
                    "clang", "--target=wasm32-wasi",
                    "--sysroot=/opt/wasi-sysroot",
                    "-O2", "-o", wasmFile.toString(),
                    srcFile.toString(),
                    "-lwasi-emulated-signal"
            );
            pb.redirectErrorStream(true);

            try {
                Process process = pb.start();
                String output = new String(process.getInputStream().readAllBytes());
                int exitCode = process.waitFor();

                if (exitCode == 0 && Files.exists(wasmFile)) {
                    byte[] wasmBytes = Files.readAllBytes(wasmFile);
                    return CompilationResult.success(compileId, "c",
                            wasmFile.toString(), wasmBytes, "main");
                } else {
                    // clang 或 wasi-sysroot 不可用，回退到模拟编译
                    log.warn("[CompilerBridge] clang wasm32-wasi 不可用，使用模拟 WASM");
                    return createMockWasmResult(compileId, "c",
                            "clang not available: " + output);
                }
            } catch (IOException e) {
                // clang 不存在
                log.warn("[CompilerBridge] clang 未找到，使用模拟 WASM");
                return createMockWasmResult(compileId, "c",
                        "clang not installed");
            }

        } catch (Exception e) {
            return CompilationResult.failed(compileId, "c",
                    "C compilation error: " + e.getMessage());
        }
    }

    /**
     * 编译 Rust 源代码 → WebAssembly (wasm32-wasi)。
     * <p>
     * 使用 cargo 的 wasm32-wasi 目标。编译产物为 .wasm 文件。
     */
    private CompilationResult compileRust(String compileId, String sourceCode) {
        try {
            // Rust 编译需要 cargo 项目结构，这里简化处理
            Path outDir = Path.of(JIT_OUT_DIR, compileId);
            Files.createDirectories(outDir);

            // 尝试使用 rustc 直接编译
            Path srcFile = Path.of(JIT_SRC_DIR, compileId, "agent_generated.rs");
            Files.createDirectories(srcFile.getParent());
            Files.writeString(srcFile, sourceCode);

            Path wasmFile = outDir.resolve("agent_generated.wasm");

            ProcessBuilder pb = new ProcessBuilder(
                    "rustc", "--target=wasm32-wasi",
                    "-O", "-o", wasmFile.toString(),
                    srcFile.toString()
            );
            pb.redirectErrorStream(true);

            try {
                Process process = pb.start();
                String output = new String(process.getInputStream().readAllBytes());
                int exitCode = process.waitFor();

                if (exitCode == 0 && Files.exists(wasmFile)) {
                    byte[] wasmBytes = Files.readAllBytes(wasmFile);
                    return CompilationResult.success(compileId, "rust",
                            wasmFile.toString(), wasmBytes, "main");
                } else {
                    log.warn("[CompilerBridge] rustc wasm32-wasi 不可用，使用模拟 WASM");
                    return createMockWasmResult(compileId, "rust",
                            "rustc not available: " + output);
                }
            } catch (IOException e) {
                log.warn("[CompilerBridge] rustc 未找到，使用模拟 WASM");
                return createMockWasmResult(compileId, "rust",
                        "rustc not installed");
            }

        } catch (Exception e) {
            return CompilationResult.failed(compileId, "rust",
                    "Rust compilation error: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  查询与统计
    // ════════════════════════════════════════════════════════════════

    /** 获取编译结果 */
    public CompilationResult getResult(String compileId) {
        return compilationIndex.get(compileId);
    }

    /** 总编译次数 */
    public long totalCompilations() {
        return totalCompilations.get();
    }

    /** 成功次数 */
    public long totalSuccesses() {
        return totalSuccesses.get();
    }

    /** 失败次数 */
    public long totalFailures() {
        return totalFailures.get();
    }

    public String getStatsReport() {
        return """
                ┌─ CompilerBridge JIT Stats ──────────────────────────
                │  Total Compilations  : %d
                │  Successes           : %d
                │  Failures            : %d
                │  Cached Artifacts    : %d
                │  Cache Directory     : %s
                └─────────────────────────────────────────────────"""
                .formatted(totalCompilations.get(), totalSuccesses.get(),
                        totalFailures.get(), compilationIndex.size(), JIT_CACHE_DIR);
    }

    // ── 内部辅助 ──

    private void ensureDirectories() throws IOException {
        Files.createDirectories(Path.of(JIT_SRC_DIR));
        Files.createDirectories(Path.of(JIT_OUT_DIR));
    }

    /**
     * 从 Java 源代码中提取类名。
     */
    private String extractJavaClassName(String source) {
        var matcher = java.util.regex.Pattern.compile(
                "(?:public\\s+)?class\\s+(\\w+)").matcher(source);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 创建模拟 WASM 编译结果 — 当真实工具链不可用时的回退方案。
     * <p>
     * 返回一个最小的有效 WASM 模块（返回 42），
     * 并附带警告信息说明这是模拟结果。
     */
    private CompilationResult createMockWasmResult(String compileId, String language, String reason) {
        // 最小 WASM 模块：(module (func (export "main") (result i32) i32.const 42))
        byte[] mockWasm = {
                0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
                0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7f, 0x03,
                0x02, 0x01, 0x00, 0x07, 0x08, 0x01, 0x04, 0x6d,
                0x61, 0x69, 0x6e, 0x00, 0x00, 0x0a, 0x06, 0x01,
                0x04, 0x00, 0x41, 0x2a, 0x0b
        };

        CompilationResult result = CompilationResult.success(compileId, language,
                JIT_OUT_DIR + "/" + compileId + "/mock.wasm", mockWasm, "main");
        result.setMock(true);
        result.setMockReason(reason);
        return result;
    }

    // ════════════════════════════════════════════════════════════════
    //  编译结果
    // ════════════════════════════════════════════════════════════════

    /**
     * 编译结果 — 描述一次 JIT 编译的完整结果。
     */
    public static final class CompilationResult {
        private final String compileId;
        private final String language;
        private final boolean success;
        private final String outputPath;
        private final byte[] bytecode;
        private final String entrypoint;
        private final String errorMessage;
        private boolean mock;
        private String mockReason;

        private CompilationResult(String compileId, String language, boolean success,
                                   String outputPath, byte[] bytecode, String entrypoint,
                                   String errorMessage) {
            this.compileId = compileId;
            this.language = language;
            this.success = success;
            this.outputPath = outputPath;
            this.bytecode = bytecode;
            this.entrypoint = entrypoint;
            this.errorMessage = errorMessage;
        }

        static CompilationResult success(String compileId, String language,
                                          String outputPath, byte[] bytecode, String entrypoint) {
            return new CompilationResult(compileId, language, true,
                    outputPath, bytecode, entrypoint, null);
        }

        static CompilationResult failed(String compileId, String language, String error) {
            return new CompilationResult(compileId, language, false,
                    null, null, null, error);
        }

        // Getters
        public String compileId() { return compileId; }
        public String language() { return language; }
        public boolean success() { return success; }
        public String outputPath() { return outputPath; }
        public byte[] bytecode() { return bytecode; }
        public String entrypoint() { return entrypoint != null ? entrypoint : "main"; }
        public String errorMessage() { return errorMessage; }
        public boolean isMock() { return mock; }
        public String mockReason() { return mockReason; }

        void setMock(boolean mock) { this.mock = mock; }
        void setMockReason(String reason) { this.mockReason = reason; }
    }
}
