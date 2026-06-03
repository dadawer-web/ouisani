package com.ouisani.aios.core;

import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.trace.TraceManager;
import com.ouisani.aios.core.trace.TraceMode;
import com.ouisani.aios.core.trace.TraceProxyFactory;

import java.util.List;

public class TestTimeTravel {

    static class MockLlmProvider implements LlmProvider {

        private int callCount = 0;

        @Override
        public String name() {
            return "MockLlm";
        }

        @Override
        public String think(String prompt, String systemPrompt) {
            callCount++;
            String result = "生成的随机数: " + Math.random();
            System.out.printf("    [MockLlm] think() REAL CALL #%d → %s%n", callCount, result);
            return result;
        }

        @Override
        public String thinkWithHistory(List<ChatMessage> messages, String systemPrompt) {
            return think(messages.getLast().content(), systemPrompt);
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public float[] embed(String text) {
            return mockEmbed(text);
        }

        int callCount() {
            return callCount;
        }
    }

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║        TestTimeTravel: Deterministic Replay Engine          ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        MockLlmProvider rawProvider = new MockLlmProvider();
        LlmProvider proxyLlm = TraceProxyFactory.createProxy(rawProvider, LlmProvider.class, "agent_101");
        TraceManager traceManager = TraceManager.instance();

        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  阶段一：真实录制 (RECORD MODE)");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println();

        traceManager.setMode(TraceMode.RECORD);

        String prompt = "给我一个随机数";
        System.out.printf("  → proxyLlm.think(\"%s\") [call #1]%n", prompt);
        String recordResult1 = proxyLlm.think(prompt, "");
        System.out.printf("  ← Result #1: %s%n", recordResult1);
        System.out.println();

        System.out.printf("  → proxyLlm.think(\"%s\") [call #2]%n", prompt);
        String recordResult2 = proxyLlm.think(prompt, "");
        System.out.printf("  ← Result #2: %s%n", recordResult2);
        System.out.println();

        System.out.printf("  MockLlmProvider real call count: %d%n", rawProvider.callCount());
        System.out.printf("  TraceManager recorded events: %d%n", traceManager.recordCount("agent_101"));
        System.out.println();

        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  阶段二：时光倒流与回放 (REPLAY MODE)");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println();

        int callsBeforeReplay = rawProvider.callCount();
        traceManager.setMode(TraceMode.REPLAY);

        System.out.printf("  → proxyLlm.think(\"%s\") [replay #1]%n", prompt);
        String replayResult1 = proxyLlm.think(prompt, "");
        System.out.printf("  ← Result #1: %s%n", replayResult1);
        System.out.println();

        System.out.printf("  → proxyLlm.think(\"%s\") [replay #2]%n", prompt);
        String replayResult2 = proxyLlm.think(prompt, "");
        System.out.printf("  ← Result #2: %s%n", replayResult2);
        System.out.println();

        int callsAfterReplay = rawProvider.callCount();

        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  确定性验证");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println();

        boolean match1 = recordResult1.equals(replayResult1);
        boolean match2 = recordResult2.equals(replayResult2);
        boolean noNewCalls = (callsAfterReplay == callsBeforeReplay);

        System.out.printf("  Record #1:  %s%n", recordResult1);
        System.out.printf("  Replay  #1: %s%n", replayResult1);
        System.out.printf("  Match #1: %s%n", match1 ? "✅ IDENTICAL" : "❌ MISMATCH");
        System.out.println();

        System.out.printf("  Record #2:  %s%n", recordResult2);
        System.out.printf("  Replay  #2: %s%n", replayResult2);
        System.out.printf("  Match #2: %s%n", match2 ? "✅ IDENTICAL" : "❌ MISMATCH");
        System.out.println();

        System.out.printf("  MockLlmProvider calls before replay: %d%n", callsBeforeReplay);
        System.out.printf("  MockLlmProvider calls after  replay: %d%n", callsAfterReplay);
        System.out.printf("  No real calls during replay: %s%n", noNewCalls ? "✅ CONFIRMED" : "❌ LEAKED");
        System.out.println();

        System.out.println("  TraceManager stats:");
        System.out.printf("    %s%n", traceManager.stats());
        System.out.println();

        if (match1 && match2 && noNewCalls) {
            System.out.println("  ╔══════════════════════════════════════════════════════════╗");
            System.out.println("  ║     [SINGULARITY] Deterministic Replay Successful!      ║");
            System.out.println("  ╚══════════════════════════════════════════════════════════╝");
        } else {
            System.out.println("  ❌ [SINGULARITY] Deterministic Replay FAILED!");
        }
        System.out.println();

        traceManager.clearHistory();
        traceManager.setMode(TraceMode.DISABLED);
    }
}
