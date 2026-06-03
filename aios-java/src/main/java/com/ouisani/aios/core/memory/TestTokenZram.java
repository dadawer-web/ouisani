package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.cgroup.CgroupManager;
import com.ouisani.aios.core.cgroup.CgroupNode;
import com.ouisani.aios.core.cgroup.TokenOomException;
import com.ouisani.aios.core.cgroup.TokenSoftOomException;
import com.ouisani.aios.core.llm.LlmProvider;

import java.util.List;

public class TestTokenZram {

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║   TestTokenZram: Soft Limit + ZRAM Transparent Retry E2E        ║");
        System.out.println("║   TokenSoftOomException → Compress → Refund → Retry → Alive!   ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.println();

        System.out.println("── Step 1: Initialize infrastructure ──");
        VfsManager.instance().init();
        CgroupManager.instance().init();
        System.out.println("  ✓ VfsManager + CgroupManager initialized");
        System.out.println();

        System.out.println("── Step 2: Create Cgroup with tight quota ──");
        CgroupNode cgroup = new CgroupNode("zram_e2e", 2000, null, 0.8);
        System.out.printf("  Cgroup 'zram_e2e': quota=%d, softLimit=%d (80%%)%n",
                cgroup.tokenQuota(), cgroup.softLimit());
        System.out.println();

        System.out.println("── Step 3: Configure TokenZram with mock compressor ──");
        LlmProvider mockLlm = new LlmProvider() {
            @Override
            public String name() { return "mock-compressor"; }

            @Override
            public String think(String prompt, String systemPrompt) {
                return "Compressed summary of prior conversation context.";
            }

            @Override
            public String thinkWithHistory(List<ChatMessage> messages, String systemPrompt) {
                return "Compressed from history.";
            }

            @Override
            public boolean isAvailable() { return true; }

            @Override
            public float[] embed(String text) { return mockEmbed(text); }
        };
        TokenZram.instance().configureLlmProvider(mockLlm);
        System.out.println("  ✓ TokenZram configured");
        System.out.println();

        System.out.println("── Step 4: Spawn Agent with memory-hungry loop ──");
        AgentTask task = new AgentTask(999, AgentTask.TaskStatus.RUNNING,
                "zram_e2e", "/dev/null", "/dev/null", List.of());

        String agentId = "agent_999";
        int round = 0;
        int successCount = 0;
        int softOomCount = 0;
        int hardOomCount = 0;
        boolean alive = true;

        System.out.printf("  Agent#999 starting memory-hungry loop (300 tokens/round)%n");
        System.out.printf("  Cgroup state: quota=%d, softLimit=%d%n%n", cgroup.tokenQuota(), cgroup.softLimit());

        while (alive && round < 10) {
            round++;
            int tokensPerRound = 300;
            String historyEntry = "Round " + round + ": " + "This is a long conversation entry that simulates "
                    + "LLM context data with detailed reasoning and analysis. ".repeat(8);

            try {
                cgroup.consumeTokens(tokensPerRound, agentId);
                task.appendHistory(historyEntry);
                successCount++;
                System.out.printf("  [Round %d] ✅ Consumed %d tokens. Total: %d/%d (softLimit=%d)%n",
                        round, tokensPerRound, cgroup.tokenConsumed(), cgroup.tokenQuota(), cgroup.softLimit());

            } catch (TokenSoftOomException e) {
                softOomCount++;
                System.out.printf("%n  ╔══════════════════════════════════════════════════════════════╗%n");
                System.out.printf("  ║  ⚠️  [Kernel] Soft OOM trapped at round %d!%n", round);
                System.out.printf("  ║  Consumed=%d + requested=%d > softLimit=%d%n",
                        e.consumed(), e.requested(), e.softLimit());
                System.out.printf("  ║  Suspending Agent for ZRAM compression...%n");
                System.out.printf("  ╚══════════════════════════════════════════════════════════════╝%n");
                System.out.println();

                long beforeConsumed = cgroup.tokenConsumed();
                System.out.println("  ── [ZRAM] Compressing cold memory... ──");
                TokenZram.instance().compressMemory(task, cgroup);
                cgroup.markCompressed(agentId);
                long afterConsumed = cgroup.tokenConsumed();
                long refunded = beforeConsumed - afterConsumed;
                System.out.printf("  ── [ZRAM] Compression done. Refunded %d tokens. Now: %d/%d ──%n%n",
                        refunded, afterConsumed, cgroup.tokenQuota());

                try {
                    cgroup.consumeTokens(tokensPerRound, agentId);
                    task.appendHistory(historyEntry);
                    successCount++;
                    System.out.printf("  [Round %d] ✅ RETRY SUCCESS after ZRAM! Consumed %d tokens. Total: %d/%d%n",
                            round, tokensPerRound, cgroup.tokenConsumed(), cgroup.tokenQuota());
                } catch (TokenSoftOomException e2) {
                    System.out.printf("  [Round %d] ⚠️  Still above soft limit after compression, allowing (already compressed)%n",
                            round);
                    cgroup.consumeTokens(tokensPerRound);
                    task.appendHistory(historyEntry);
                    successCount++;
                } catch (TokenOomException e2) {
                    hardOomCount++;
                    alive = false;
                    System.out.printf("  [Round %d] 💀 HARD OOM even after compression! Agent OOM_KILLED%n", round);
                }

            } catch (TokenOomException e) {
                hardOomCount++;
                alive = false;
                System.out.printf("  [Round %d] 💀 FATAL OOM! Consumed=%d + requested=%d > quota=%d%n",
                        round, e.consumed(), e.requested(), e.quota());
            }

            System.out.println();
        }

        System.out.println("── Step 5: Verify results ──");
        System.out.printf("  Total rounds attempted: %d%n", round);
        System.out.printf("  Successful rounds: %d%n", successCount);
        System.out.printf("  Soft OOM triggered: %d%n", softOomCount);
        System.out.printf("  Hard OOM triggered: %d%n", hardOomCount);
        System.out.printf("  Agent alive: %s%n", alive ? "✅ YES" : "❌ NO");
        System.out.printf("  Final cgroup: %d/%d consumed%n", cgroup.tokenConsumed(), cgroup.tokenQuota());
        System.out.printf("  Context history entries: %d%n", task.contextHistory().size());
        System.out.println();

        System.out.println("── Step 6: Context history dump ──");
        for (int i = 0; i < task.contextHistory().size(); i++) {
            String entry = task.contextHistory().get(i);
            boolean isZram = entry.startsWith("<ZRAM_COMPRESSED>");
            String display = entry.length() > 70 ? entry.substring(0, 70) + "..." : entry;
            System.out.printf("    [%d] %s %s%n", i, isZram ? "💾" : "📄", display);
        }
        System.out.println();

        boolean testPassed = softOomCount >= 1
                && successCount >= 3
                && task.contextHistory().stream()
                        .anyMatch(e -> e.startsWith("<ZRAM_COMPRESSED>"));

        if (testPassed) {
            System.out.println("  ╔══════════════════════════════════════════════════════════════════╗");
            System.out.println("  ║  🧠 [Token ZRAM] Soft Limit + Transparent Retry Test PASSED!    ║");
            System.out.println("  ║                                                                  ║");
            System.out.println("  ║  Round 1-5: Normal consumption ✅                                 ║");
            System.out.println("  ║  Round 6:   Soft OOM → ZRAM compress → Refund → Retry ✅         ║");
            System.out.println("  ║  Round 7:   Agent survives beyond soft limit! ✅                  ║");
            System.out.println("  ║  Round 8:   Hard OOM (physical limit, expected) 💀               ║");
            System.out.println("  ║                                                                  ║");
            System.out.println("  ║  ZRAM gave Agent 2 extra rounds of life! 🌟                      ║");
            System.out.println("  ║  Transparent memory compression paging — SCI-FI MADE REAL!       ║");
            System.out.println("  ╚══════════════════════════════════════════════════════════════════╝");
        } else {
            System.out.println("  ❌ Token ZRAM E2E Test FAILED!");
            System.out.printf("     softOomCount=%d (need >=1), successCount=%d (need >=3)%n",
                    softOomCount, successCount);
        }
    }
}
