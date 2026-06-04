package com.ouisani.aios.user.apps.devhouse;

import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.syscall.SyscallResponse;
import com.ouisani.aios.user.sdk.AbstractAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Coder Agent — the code monkey of the Auto Dev House.
 * <p>
 * This Agent acts as a genius programmer. It polls the VFS waiting for
 * the PM Agent to finish the PRD, then generates code based on the PRD,
 * and executes it in a Docker sandbox for validation.
 * <p>
 * Lifecycle:
 * <ol>
 *   <li>Spin-wait until /devhouse/status == "PRD_READY"</li>
 *   <li>Read PRD from /devhouse/prd.txt</li>
 *   <li>Call LLM to generate Python code from PRD</li>
 *   <li>Write code to /devhouse/server.py</li>
 *   <li>Execute code in Docker sandbox via sdk.callTool</li>
 *   <li>Write sandbox output to /devhouse/build.log</li>
 *   <li>Update status to "CODE_READY"</li>
 *   <li>Exit</li>
 * </ol>
 */
public class CoderAgent extends AbstractAgent {

    private static final Logger log = LoggerFactory.getLogger(CoderAgent.class);

    private static final int POLL_INTERVAL_MS = 500;
    private static final int MAX_POLL_ATTEMPTS = 60; // 30 seconds max

    public CoderAgent() {
        super("coder_agent", ProcessPriority.NORMAL, 50000);
    }

    @Override
    protected void onStart() {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  [Coder Agent] Code monkey reporting for duty!             ║");
        System.out.println("  ║  Agent ID: coder_agent | Priority: NORMAL | Budget: 50000  ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");
        log.info("[Coder Agent] Starting with priority=NORMAL, budget=50000");

        // Step 1: Spin-wait for PRD_READY
        System.out.println("  ├─ [Coder Agent] Waiting for PRD_READY signal...");
        String status = "";
        int attempts = 0;

        while (attempts < MAX_POLL_ATTEMPTS) {
            status = sdk.readFile(agentId, "/devhouse/status");
            if ("PRD_READY".equals(status.trim())) {
                break;
            }
            attempts++;
            if (attempts % 4 == 0) {
                System.out.printf("  │  [Coder Agent] Still waiting... (status=%s, attempt=%d)%n",
                        status.trim(), attempts);
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("  ├─ [Coder Agent] Interrupted while waiting for PRD");
                return;
            }
        }

        if (!"PRD_READY".equals(status.trim())) {
            System.out.println("  ├─ [Coder Agent] Timeout waiting for PRD_READY! Using fallback.");
            log.warn("[Coder Agent] Timeout waiting for PRD_READY after {} attempts", attempts);
        } else {
            System.out.println("  ├─ [Coder Agent] PRD_READY signal received! Starting coding...");
            log.info("[Coder Agent] PRD_READY signal received after {} polls", attempts);
        }

        // Step 2: Read PRD
        String prdContent = sdk.readFile(agentId, "/devhouse/prd.txt");
        if (prdContent == null || prdContent.isEmpty() || prdContent.startsWith("[SDK Error]")) {
            System.out.println("  ├─ [Coder Agent] Failed to read PRD! Using fallback.");
            log.warn("[Coder Agent] Failed to read PRD, using fallback");
            prdContent = "Write a minimal Python HTTP server that returns 'Hello from AIOS' on port 8080.";
        }
        System.out.printf("  ├─ [Coder Agent] PRD loaded (%d chars)%n", prdContent.length());

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
            // Strip markdown code fences if present
            code = stripMarkdownFences(code);
            System.out.printf("  ├─ [Coder Agent] Code generated (%d chars)%n", code.length());
            log.info("[Coder Agent] Code generated: {} chars", code.length());
        }

        // Step 4: Write code to VFS
        sdk.writeFile(agentId, "/devhouse/server.py", code);
        System.out.println("  ├─ [Coder Agent] Code written to /devhouse/server.py");

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

        // Step 6: Write build log
        sdk.writeFile(agentId, "/devhouse/build.log", sandboxOutput);
        System.out.println("  ├─ [Coder Agent] Build log written to /devhouse/build.log");

        // Step 7: Update status
        sdk.writeFile(agentId, "/devhouse/status", "CODE_READY");
        System.out.println("  ├─ [Coder Agent] Status → CODE_READY");

        // Step 8: Done
        System.out.println("  └─ [Coder Agent] Code compiled and executed in Docker Sandbox. Logs written.");
        log.info("[Coder Agent] Code compiled and executed in Docker Sandbox. Logs written.");

        exit();
    }

    @Override
    protected void onMessage(String msg) {
        log.info("[Coder Agent] Received message (but Coder has already exited): {}", msg);
    }

    /**
     * Strip markdown code fences (```python ... ```) from LLM output.
     */
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

    /**
     * Fallback Python HTTP server code.
     */
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
