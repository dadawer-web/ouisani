package com.ouisani.aios.user.apps.devhouse;

import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.user.sdk.AbstractAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reviewer Agent — the chief architect / QA of the Auto Dev House.
 * <p>
 * This Agent holds the highest privilege (REALTIME) and acts as the
 * final gatekeeper. It polls until the Coder Agent finishes, then
 * reviews the code and build logs, writes a final report, and shuts
 * down the entire Dev House pipeline.
 * <p>
 * Lifecycle:
 * <ol>
 *   <li>Spin-wait until /devhouse/status == "CODE_READY"</li>
 *   <li>Read /devhouse/server.py and /devhouse/build.log</li>
 *   <li>Call LLM for a sharp one-liner review</li>
 *   <li>Write review to /devhouse/final_report.txt</li>
 *   <li>Update status to "FINISHED"</li>
 *   <li>Exit — Dev House shuts down</li>
 * </ol>
 */
public class ReviewerAgent extends AbstractAgent {

    private static final Logger log = LoggerFactory.getLogger(ReviewerAgent.class);

    private static final int POLL_INTERVAL_MS = 500;
    private static final int MAX_POLL_ATTEMPTS = 120; // 60 seconds max

    public ReviewerAgent() {
        super("reviewer_agent", ProcessPriority.REALTIME, 20000);
    }

    @Override
    protected void onStart() {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  [Reviewer Agent] Chief Architect reporting for duty!      ║");
        System.out.println("  ║  Agent ID: reviewer_agent | Priority: REALTIME | Budget: 20k║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");
        log.info("[Reviewer Agent] Starting with priority=REALTIME, budget=20000");

        // Step 1: Spin-wait for CODE_READY
        System.out.println("  ├─ [Reviewer Agent] Waiting for CODE_READY signal...");
        String status = "";
        int attempts = 0;

        while (attempts < MAX_POLL_ATTEMPTS) {
            status = sdk.readFile(agentId, "/devhouse/status");
            if ("CODE_READY".equals(status.trim())) {
                break;
            }
            attempts++;
            if (attempts % 6 == 0) {
                System.out.printf("  │  [Reviewer Agent] Still waiting... (status=%s, attempt=%d)%n",
                        status.trim(), attempts);
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("  ├─ [Reviewer Agent] Interrupted while waiting for CODE_READY");
                return;
            }
        }

        if (!"CODE_READY".equals(status.trim())) {
            System.out.println("  ├─ [Reviewer Agent] Timeout waiting for CODE_READY! Using fallback review.");
            log.warn("[Reviewer Agent] Timeout waiting for CODE_READY after {} attempts", attempts);
        } else {
            System.out.println("  ├─ [Reviewer Agent] CODE_READY signal received! Starting review...");
            log.info("[Reviewer Agent] CODE_READY signal received after {} polls", attempts);
        }

        // Step 2: Read code and build log
        String code = sdk.readFile(agentId, "/devhouse/server.py");
        if (code == null || code.isEmpty() || code.startsWith("[SDK Error]")) {
            code = "(code unavailable)";
        }
        System.out.printf("  ├─ [Reviewer Agent] Code loaded (%d chars)%n", code.length());

        String buildLog = sdk.readFile(agentId, "/devhouse/build.log");
        if (buildLog == null || buildLog.isEmpty() || buildLog.startsWith("[SDK Error]")) {
            buildLog = "(build log unavailable)";
        }
        System.out.printf("  ├─ [Reviewer Agent] Build log loaded (%d chars)%n", buildLog.length());

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

        // Step 4: Write final report
        sdk.writeFile(agentId, "/devhouse/final_report.txt", review);
        System.out.println("  ├─ [Reviewer Agent] Final report written to /devhouse/final_report.txt");

        // Step 5: Update status to FINISHED
        sdk.writeFile(agentId, "/devhouse/status", "FINISHED");
        System.out.println("  ├─ [Reviewer Agent] Status → FINISHED");

        // Step 6: Shutdown
        System.out.println("  └─ [Reviewer Agent] Final report published. Dev House shutting down.");
        log.info("[Reviewer Agent] Final report published. Dev House shutting down.");

        exit();
    }

    @Override
    protected void onMessage(String msg) {
        log.info("[Reviewer Agent] Received message (but Reviewer has already exited): {}", msg);
    }

    /**
     * Fallback review in case LLM is unavailable.
     */
    private String generateFallbackReview() {
        return "代码能跑，但架构品味堪忧——这就像用牛刀杀鸡，鸡死了，刀也钝了。";
    }
}
