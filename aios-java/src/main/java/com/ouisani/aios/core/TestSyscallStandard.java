package com.ouisani.aios.core;

import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.llm.LlmRouter;
import com.ouisani.aios.core.security.ObjectManager;
import com.ouisani.aios.core.syscall.SyscallDispatcher;
import com.ouisani.aios.core.syscall.SyscallRequest;
import com.ouisani.aios.core.syscall.SyscallResponse;
import com.ouisani.aios.core.telemetry.EventRecord;
import com.ouisani.aios.core.telemetry.SemanticEtw;

import java.util.List;
import java.util.Map;

/**
 * Integration test: validates that all Agent operations are forced through
 * the Syscall abstraction layer, with ETW observability and kernel logging.
 */
public class TestSyscallStandard {

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║  AIOS Syscall Standard Integration Test                     ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        // ── Step 1: Initialize infrastructure ──
        System.out.println("  [Step 1] Initializing AIOS kernel subsystems...");

        // Mock LLM provider for testing
        LlmProvider mockLlm = new LlmProvider() {
            @Override public String name() { return "mock_model"; }
            @Override public String think(String prompt, String systemPrompt) {
                return "为什么程序员不喜欢户外？因为有太多 bugs！";
            }
            @Override public String think(String prompt) { return think(prompt, ""); }
            @Override public String thinkWithHistory(List<ChatMessage> messages, String systemPrompt) {
                return think(messages.get(messages.size() - 1).content(), systemPrompt);
            }
            @Override public float[] embed(String text) { return mockEmbed(text); }
            @Override public boolean isAvailable() { return true; }
        };

        LlmRouter router = new LlmRouter();
        router.registerProvider("fast_model", mockLlm);
        router.registerProvider("smart_model", mockLlm);

        VfsManager vfs = VfsManager.instance();
        vfs.init();

        ObjectManager objMgr = ObjectManager.instance();

        SyscallDispatcher dispatcher = SyscallDispatcher.getInstance();
        dispatcher.configure(router, vfs, objMgr);

        System.out.println("  [Step 1] Kernel subsystems ready: LlmRouter, VfsManager, ObjectManager, SyscallDispatcher");
        System.out.println();

        // ── Step 2: Agent issues llm.think via Syscall ──
        System.out.println("  [Step 2] Agent 'agent_007' issuing llm.think syscall...");

        SyscallRequest thinkReq = new SyscallRequest("llm.think",
                Map.of("prompt", "请讲一个笑话"));

        SyscallResponse thinkResp = dispatcher.execute("agent_007", thinkReq);

        System.out.printf("  [Step 2] Response: success=%s, data='%s'%n",
                thinkResp.success(), thinkResp.data());
        System.out.println();

        // ── Step 3: Agent writes the joke to VFS via Syscall ──
        System.out.println("  [Step 3] Agent 'agent_007' issuing vfs.write syscall...");

        // Write to /dev/shm/blackboard (no LLM dependency, pure key-value)
        SyscallRequest writeReq = new SyscallRequest("vfs.write",
                Map.of("path", "/dev/shm/blackboard", "payload", "joke=" + thinkResp.data()));

        SyscallResponse writeResp = dispatcher.execute("agent_007", writeReq);

        System.out.printf("  [Step 3] Response: success=%s%n", writeResp.success());
        System.out.println();

        // ── Step 4: Verify via vfs.read ──
        System.out.println("  [Step 4] Verifying: reading back /dev/shm/blackboard via vfs.read syscall...");

        SyscallRequest readReq = new SyscallRequest("vfs.read",
                Map.of("path", "/dev/shm/blackboard"));

        SyscallResponse readResp = dispatcher.execute("agent_007", readReq);

        System.out.printf("  [Step 4] Response: success=%s, dataLen=%d%n",
                readResp.success(), readResp.data() != null ? readResp.data().length() : 0);
        System.out.println();

        // ── Step 5: Verify ETW event buffer ──
        System.out.println("  [Step 5] Checking ETW event buffer...");

        List<EventRecord> recentEvents = SemanticEtw.getInstance().fetchRecent(10);
        long syscallEvents = recentEvents.stream()
                .filter(e -> "SYSCALL".equals(e.component()))
                .count();

        System.out.printf("  [Step 5] ETW buffer: %d total recent events, %d SYSCALL events%n",
                recentEvents.size(), syscallEvents);

        for (EventRecord event : recentEvents) {
            if ("SYSCALL".equals(event.component())) {
                System.out.printf("         → [%s] [%s] %s%n",
                        event.component(), event.eventType(), event.payload());
            }
        }
        System.out.println();

        // ── Final Verification ──
        boolean thinkOk = thinkResp.success() && thinkResp.data() != null;
        boolean writeOk = writeResp.success();
        boolean readOk = readResp.success();
        boolean etwOk = syscallEvents >= 2; // at least ENTER+EXIT for each call

        System.out.println("  ┌─ Test Results ──────────────────────────────────────────┐");
        System.out.printf("  │  llm.think:          %s%n", thinkOk ? "PASS ✓" : "FAIL ✗");
        System.out.printf("  │  vfs.write:          %s%n", writeOk ? "PASS ✓" : "FAIL ✗");
        System.out.printf("  │  vfs.read:           %s%n", readOk ? "PASS ✓" : "FAIL ✗");
        System.out.printf("  │  ETW recorded:       %s (%d SYSCALL events)%n",
                etwOk ? "PASS ✓" : "FAIL ✗", syscallEvents);
        System.out.println("  └─────────────────────────────────────────────────────────┘");
        System.out.println();

        boolean allPassed = thinkOk && writeOk && readOk && etwOk;
        if (allPassed) {
            System.out.println("  [Architecture Upgrade] Syscall abstraction successfully integrated!");
        } else {
            System.out.println("  [Architecture Upgrade] Some tests FAILED!");
        }
    }
}
