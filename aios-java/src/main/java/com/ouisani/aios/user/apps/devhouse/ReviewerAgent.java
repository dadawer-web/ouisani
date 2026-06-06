package com.ouisani.aios.user.apps.devhouse;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.ipc.SemanticMemoryBlock;
import com.ouisani.aios.core.ipc.SignalInterceptor;
import com.ouisani.aios.core.ipc.SignalType;
import com.ouisani.aios.user.sdk.AbstractAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reviewer Agent — the chief architect / QA of the Auto Dev House.
 * <p>
 * This Agent holds the highest privilege (REALTIME) and acts as the
 * final gatekeeper. Instead of polling the VFS, it uses the
 * <b>"shared memory + hardware interrupt"</b> IPC model:
 * <ol>
 *   <li>Waits for SIG_CONTEXT_UPDATE from the Coder agent</li>
 *   <li>Reads code and build log from the SemanticMemoryBlock</li>
 *   <li>Calls LLM for a sharp review</li>
 *   <li>Writes the final report to SHM + VFS</li>
 *   <li>Updates status to FINISHED</li>
 * </ol>
 */
public class ReviewerAgent extends AbstractAgent {

    private static final Logger log = LoggerFactory.getLogger(ReviewerAgent.class);

    private static final String SHM_BLOCK_ID = "devhouse_project";
    private static final long SIGNAL_WAIT_TIMEOUT_MS = 120_000;
    private static final int FALLBACK_POLL_INTERVAL_MS = 2000;
    private static final int FALLBACK_MAX_POLLS = 60;

    public ReviewerAgent() {
        super("reviewer_agent", ProcessPriority.REALTIME, 20000);
    }

    @Override
    protected void onStart() {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  [Reviewer Agent] Chief Architect reporting for duty!      ║");
        System.out.println("  ║  Agent ID: reviewer_agent | Priority: REALTIME | Budget: 20k║");
        System.out.println("  ║  IPC: Signal-driven (SIG_CONTEXT_UPDATE handler)           ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");
        log.info("[Reviewer Agent] Starting with priority=REALTIME, budget=20000, ipc=SIGNAL");

        // Step 1: Wait for SIG_CONTEXT_UPDATE from Coder
        System.out.println("  ├─ [Reviewer Agent] Registering SIG_CONTEXT_UPDATE handler...");
        System.out.println("  │  \u001B[36m[Reviewer Agent] Waiting for Coder's interrupt... (no polling!)\u001B[0m");

        boolean signalReceived = waitForContextUpdate();

        if (!signalReceived) {
            System.out.println("  ├─ [Reviewer Agent] Signal timeout! Falling back to VFS polling...");
            log.warn("[Reviewer Agent] SIG_CONTEXT_UPDATE timeout, falling back to VFS polling");
            if (!vfsFallbackPoll("CODE_READY")) {
                System.out.println("  ├─ [Reviewer Agent] All wait methods failed! Using fallback review.");
            }
        } else {
            System.out.println("  ├─ [Reviewer Agent] \u001B[32m⚡ SIG_CONTEXT_UPDATE received! Code is ready.\u001B[0m");
            log.info("[Reviewer Agent] SIG_CONTEXT_UPDATE received — reading from SHM");
        }

        // Step 2: Read code and build log from SHM (zero-copy)
        String code = sdk.shmRead(agentId, SHM_BLOCK_ID, "code_content");
        if (code == null || code.isEmpty()) {
            code = sdk.readFile(agentId, "/devhouse/server.py");
        }
        if (code == null || code.isEmpty() || code.startsWith("[SDK Error]")) {
            code = "(code unavailable)";
        }
        System.out.printf("  ├─ [Reviewer Agent] Code loaded from SHM (%d chars)%n", code.length());

        String buildLog = sdk.shmRead(agentId, SHM_BLOCK_ID, "build_log");
        if (buildLog == null || buildLog.isEmpty()) {
            buildLog = sdk.readFile(agentId, "/devhouse/build.log");
        }
        if (buildLog == null || buildLog.isEmpty() || buildLog.startsWith("[SDK Error]")) {
            buildLog = "(build log unavailable)";
        }
        System.out.printf("  ├─ [Reviewer Agent] Build log loaded from SHM (%d chars)%n", buildLog.length());

        // Step 3: Call LLM for review
        String reviewPrompt = "作为首席架构师，请根据代码和运行日志，给出一段犀利的一句话验收点评。\n\n"
                + "=== 代码 ===\n" + code + "\n\n"
                + "=== 运行日志 ===\n" + buildLog;

        System.out.println("  ├─ [Reviewer Agent] Generating review via LLM...");
        log.info("[Reviewer Agent] Calling LLM for final review...");

        String review = sdk.think(agentId, reviewPrompt);

        if (review == null || review.isEmpty() || review.startsWith("[SDK Error]")) {
            System.out.println("  ├─ [Reviewer Agent] LLM call failed! Using fallback review.");
            log.warn("[Reviewer Agent] LLM call failed, using fallback review");
            review = generateFallbackReview();
        } else {
            System.out.printf("  ├─ [Reviewer Agent] Review generated (%d chars)%n", review.length());
            log.info("[Reviewer Agent] Review generated: {} chars", review.length());
        }

        // Step 4: Write final report to SHM + VFS
        sdk.writeFile(agentId, "/devhouse/final_report.txt", review);
        sdk.shmWrite(agentId, SHM_BLOCK_ID, "final_report", review);
        System.out.println("  ├─ [Reviewer Agent] Final report written to /devhouse/final_report.txt + SHM");

        // Step 5: Update status to FINISHED
        sdk.shmWrite(agentId, SHM_BLOCK_ID, "status", "FINISHED");
        sdk.writeFile(agentId, "/devhouse/status", "FINISHED");
        System.out.println("  ├─ [Reviewer Agent] Status → FINISHED (SHM + VFS)");

        // Step 6: Shutdown
        System.out.println("  └─ [Reviewer Agent] Final report published. Dev House shutting down.");
        log.info("[Reviewer Agent] Final report published. Dev House shutting down.");

        exit();
    }

    @Override
    protected void onMessage(String msg) {
        log.info("[Reviewer Agent] Received message (but Reviewer has already exited): {}", msg);
    }

    private boolean waitForContextUpdate() {
        long startTime = System.currentTimeMillis();
        TaskScheduler scheduler = VfsManager.instance().getTaskScheduler();
        AgentTask myTask = scheduler.getTask(getPid());

        if (myTask == null) {
            log.warn("[Reviewer Agent] Cannot find own AgentTask, falling back to polling");
            return false;
        }

        while (System.currentTimeMillis() - startTime < SIGNAL_WAIT_TIMEOUT_MS) {
            if (SignalInterceptor.hasContextUpdate(myTask)) {
                SignalInterceptor.drainContextUpdates(myTask);
                return true;
            }

            String status = sdk.shmRead(agentId, SHM_BLOCK_ID, "status");
            if ("CODE_READY".equals(status)) {
                log.info("[Reviewer Agent] Detected CODE_READY in SHM (safety net check)");
                return true;
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (SignalInterceptor.hasContextUpdate(myTask)) {
                    SignalInterceptor.drainContextUpdates(myTask);
                    return true;
                }
                return false;
            }
        }

        return false;
    }

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

    private String generateFallbackReview() {
        return "代码能跑，但架构品味堪忧——这就像用牛刀杀鸡，鸡死了，刀也钝了。";
    }
}
