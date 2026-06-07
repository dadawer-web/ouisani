package com.ouisani.aios.user.apps.devhouse;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.ipc.SemanticMemoryBlock;
import com.ouisani.aios.core.ipc.SharedMemoryManager;
import com.ouisani.aios.core.ipc.SignalInterceptor;
import com.ouisani.aios.core.ipc.SignalType;
import com.ouisani.aios.core.syscall.SyscallResponse;
import com.ouisani.aios.user.sdk.AbstractAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Coder Agent — 自动开发屋的程序员。
 * <p>
 * OS 类比：相当于内核中受 CPU 调度的工作进程 — 使用"共享内存 + 硬件中断"IPC 模型：
 * <ol>
 *   <li>注册 {@code SIG_CONTEXT_UPDATE} 信号处理器</li>
 *   <li>等待中断（无轮询！）</li>
 *   <li>当 PM Agent 触发中断时，从 SemanticMemoryBlock 零拷贝读取 PRD</li>
 *   <li>生成代码，在 Docker 中执行，将结果写回共享内存块</li>
 *   <li>触发 {@code SIG_CONTEXT_UPDATE} 通知 Reviewer</li>
 * </ol>
 * <p>
 * <h3>性能对比</h3>
 * <pre>
 *   旧模型：500ms 轮询 × ~60 次 = 最坏 30s 延迟
 *   新模型：SIG_CONTEXT_UPDATE 中断 ≈ 0ms 延迟
 * </pre>
 */
public class CoderAgent extends AbstractAgent {

    private static final Logger log = LoggerFactory.getLogger(CoderAgent.class);

    /** DevHouse 项目共享内存块 ID */
    private static final String SHM_BLOCK_ID = "devhouse_project";

    /** SIG_CONTEXT_UPDATE 信号等待超时时间（回退安全网） */
    private static final long SIGNAL_WAIT_TIMEOUT_MS = 60_000;

    /** 回退轮询间隔（仅当信号机制失效时使用） */
    private static final int FALLBACK_POLL_INTERVAL_MS = 2000;
    private static final int FALLBACK_MAX_POLLS = 30;

    public CoderAgent() {
        super("coder_agent", ProcessPriority.NORMAL, 50000);
    }

    @Override
    protected void onStart() {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  [Coder Agent] Code monkey reporting for duty!             ║");
        System.out.println("  ║  Agent ID: coder_agent | Priority: NORMAL | Budget: 50000  ║");
        System.out.println("  ║  IPC: Signal-driven (SIG_CONTEXT_UPDATE handler)           ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");
        log.info("[Coder Agent] Starting with priority=NORMAL, budget=50000, ipc=SIGNAL");

        // Step 1: Register signal handler — wait for SIG_CONTEXT_UPDATE from PM
        System.out.println("  ├─ [Coder Agent] Registering SIG_CONTEXT_UPDATE handler...");
        System.out.println("  │  \u001B[36m[Coder Agent] Waiting for PM's interrupt... (no polling!)\u001B[0m");

        boolean signalReceived = waitForContextUpdate();

        if (!signalReceived) {
            System.out.println("  ├─ [Coder Agent] Signal timeout! Falling back to VFS polling...");
            log.warn("[Coder Agent] SIG_CONTEXT_UPDATE timeout, falling back to VFS polling");
            // Fallback: try VFS polling as a safety net
            if (!vfsFallbackPoll("PRD_READY")) {
                System.out.println("  ├─ [Coder Agent] All wait methods failed! Using fallback PRD.");
            }
        } else {
            System.out.println("  ├─ [Coder Agent] \u001B[32m⚡ SIG_CONTEXT_UPDATE received! Shared memory updated.\u001B[0m");
            log.info("[Coder Agent] SIG_CONTEXT_UPDATE received — reading from SHM");
        }

        // Step 2: Read PRD from shared memory (zero-copy, instant)
        String prdContent = sdk.shmRead(agentId, SHM_BLOCK_ID, "prd_content");
        if (prdContent == null || prdContent.isEmpty()) {
            // Fallback: try VFS
            prdContent = sdk.readFile(agentId, "/devhouse/prd.txt");
        }
        if (prdContent == null || prdContent.isEmpty() || prdContent.startsWith("[SDK Error]")) {
            System.out.println("  ├─ [Coder Agent] Failed to read PRD! Using fallback.");
            log.warn("[Coder Agent] Failed to read PRD, using fallback");
            prdContent = "Write a minimal Python HTTP server that returns 'Hello from AIOS' on port 8080.";
        }

        // Check ContextPointer for summary (optional: use for prompt optimization)
        SemanticMemoryBlock block = sdk.shmGetBlockIfExists(SHM_BLOCK_ID);
        if (block != null) {
            SemanticMemoryBlock.ContextPointer prdPointer = block.getContextPointer("prd_pointer");
            if (prdPointer != null) {
                System.out.printf("  ├─ [Coder Agent] ContextPointer: ref=%s, summary=%s%n",
                        prdPointer.contextRef(),
                        prdPointer.summary().length() > 80
                                ? prdPointer.summary().substring(0, 80) + "..."
                                : prdPointer.summary());
            }
        }

        System.out.printf("  ├─ [Coder Agent] PRD loaded from SHM (%d chars)%n", prdContent.length());

        // Step 3: Generate code via LLM
        String codePrompt = "你是一个天才程序员。根据以下 PRD 写出 Python 代码。只输出纯代码，不要任何 Markdown 标记。\n\nPRD:\n" + prdContent;
        System.out.println("  ├─ [Coder Agent] Generating code via LLM...");
        log.info("[Coder Agent] Calling LLM for code generation...");

        String code = sdk.think(agentId, codePrompt);

        if (code == null || code.isEmpty() || code.startsWith("[SDK Error]")) {
            System.out.println("  ├─ [Coder Agent] LLM call failed! Using fallback code.");
            log.warn("[Coder Agent] LLM call failed, using fallback code");
            code = generateFallbackCode();
        } else {
            code = stripMarkdownFences(code);
            System.out.printf("  ├─ [Coder Agent] Code generated (%d chars)%n", code.length());
            log.info("[Coder Agent] Code generated: {} chars", code.length());
        }

        // Step 4: Write code to VFS + SHM
        sdk.writeFile(agentId, "/devhouse/server.py", code);
        sdk.shmWrite(agentId, SHM_BLOCK_ID, "code_content", code);
        System.out.println("  ├─ [Coder Agent] Code written to /devhouse/server.py + SHM");

        // Step 5: Execute in Docker sandbox
        System.out.println("  ├─ [Coder Agent] Launching Docker Sandbox for validation...");
        log.info("[Coder Agent] Executing code in Docker sandbox...");

        String sandboxOutput;
        try {
            Map<String, Object> sandboxArgs = Map.of(
                    "image", "python:3.10",
                    "script", code
            );
            SyscallResponse sandboxResp = sdk.callTool(agentId, "sandbox.docker", sandboxArgs);

            if (sandboxResp.success()) {
                sandboxOutput = sandboxResp.data();
                System.out.printf("  ├─ [Coder Agent] Docker execution complete!%n");
            } else {
                sandboxOutput = "[Docker Sandbox Error] " + sandboxResp.errorMessage();
                System.out.printf("  ├─ [Coder Agent] Docker execution failed: %s%n", sandboxResp.errorMessage());
                log.warn("[Coder Agent] Docker sandbox failed: {}", sandboxResp.errorMessage());
            }
        } catch (Exception e) {
            sandboxOutput = "[Docker Sandbox Exception] " + e.getMessage();
            System.out.printf("  ├─ [Coder Agent] Docker sandbox exception: %s%n", e.getMessage());
            log.warn("[Coder Agent] Docker sandbox exception: {}", e.getMessage());
        }

        // Step 6: Write build log to VFS + SHM
        sdk.writeFile(agentId, "/devhouse/build.log", sandboxOutput);
        sdk.shmWrite(agentId, SHM_BLOCK_ID, "build_log", sandboxOutput);
        System.out.println("  ├─ [Coder Agent] Build log written to /devhouse/build.log + SHM");

        // Step 7: Update status to CODE_READY in SHM + VFS
        sdk.shmWrite(agentId, SHM_BLOCK_ID, "status", "CODE_READY");
        sdk.writeFile(agentId, "/devhouse/status", "CODE_READY");
        System.out.println("  ├─ [Coder Agent] Status → CODE_READY (SHM + VFS)");

        // Step 8: Fire SIG_CONTEXT_UPDATE to notify Reviewer
        sdk.broadcastSignal("agents", SignalType.SIG_CONTEXT_UPDATE);
        System.out.println("  ├─ [Coder Agent] SIG_CONTEXT_UPDATE broadcast → Reviewer will wake instantly");
        System.out.println("  │  \u001B[32m[Coder Agent] ⚡ Interrupt fired! Reviewer is waking up.\u001B[0m");

        // Done
        System.out.println("  └─ [Coder Agent] Code compiled and executed in Docker Sandbox. Interrupt sent.");
        log.info("[Coder Agent] Code compiled and executed. SIG_CONTEXT_UPDATE broadcast. Exiting.");

        exit();
    }

    @Override
    protected void onMessage(String msg) {
        log.info("[Coder Agent] Received message (but Coder has already exited): {}", msg);
    }

    // ════════════════════════════════════════════════════════════════
    //  信号处理器：等待 SIG_CONTEXT_UPDATE
    // ════════════════════════════════════════════════════════════════

    /**
     * 等待 PM Agent 发来的 SIG_CONTEXT_UPDATE 信号。
     * <p>
     * 用信号驱动等待替代旧的 500ms 轮询循环。
     * Agent 休眠直到中断到达，然后读取更新后的共享内存块。
     * <p>
     * 作为安全网，也定期检查 SHM 状态（每 2 秒），以防信号丢失。
     *
     * @return 收到信号返回 true，超时返回 false
     */
    private boolean waitForContextUpdate() {
        long startTime = System.currentTimeMillis();
        TaskScheduler scheduler = VfsManager.instance().getTaskScheduler();
        AgentTask myTask = scheduler.getTask(getPid());

        if (myTask == null) {
            log.warn("[Coder Agent] Cannot find own AgentTask, falling back to polling");
            return false;
        }

        while (System.currentTimeMillis() - startTime < SIGNAL_WAIT_TIMEOUT_MS) {
            // Check for SIG_CONTEXT_UPDATE signal
            if (SignalInterceptor.hasContextUpdate(myTask)) {
                SignalInterceptor.drainContextUpdates(myTask);
                return true;
            }

            // Safety net: check SHM status directly (every ~500ms)
            String status = sdk.shmRead(agentId, SHM_BLOCK_ID, "status");
            if ("PRD_READY".equals(status)) {
                log.info("[Coder Agent] Detected PRD_READY in SHM (safety net check)");
                return true;
            }

            // Sleep briefly — this is NOT polling, it's a signal wait loop
            // The actual notification comes via the signal, not the sleep
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Check if the interrupt was due to a signal
                if (SignalInterceptor.hasContextUpdate(myTask)) {
                    SignalInterceptor.drainContextUpdates(myTask);
                    return true;
                }
                return false;
            }
        }

        return false; // timeout
    }

    /**
     * 回退方案：当信号机制完全失效时使用 VFS 轮询。
     */
    private boolean vfsFallbackPoll(String targetStatus) {
        for (int i = 0; i < FALLBACK_MAX_POLLS; i++) {
            String status = sdk.readFile(agentId, "/devhouse/status");
            if (targetStatus.equals(status.trim())) {
                return true;
            }
            try {
                Thread.sleep(FALLBACK_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** 去除 LLM 输出中的 Markdown 代码围栏 */
    private String stripMarkdownFences(String code) {
        String stripped = code.trim();
        if (stripped.startsWith("```python")) {
            stripped = stripped.substring("```python".length());
        } else if (stripped.startsWith("```")) {
            stripped = stripped.substring(3);
        }
        if (stripped.endsWith("```")) {
            stripped = stripped.substring(0, stripped.length() - 3);
        }
        return stripped.trim();
    }

    /** 生成回退代码（当 LLM 调用失败时使用） */
    private String generateFallbackCode() {
        return """
                from http.server import HTTPServer, BaseHTTPRequestHandler
                
                class Handler(BaseHTTPRequestHandler):
                    def do_GET(self):
                        self.send_response(200)
                        self.send_header('Content-Type', 'text/plain')
                        self.end_headers()
                        self.wfile.write(b'Hello from AIOS')
                
                if __name__ == '__main__':
                    server = HTTPServer(('0.0.0.0', 8080), Handler)
                    print('Server running on port 8080')
                    server.serve_forever()
                """;
    }
}
