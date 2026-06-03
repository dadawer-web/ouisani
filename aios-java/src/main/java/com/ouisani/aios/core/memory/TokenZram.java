package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.cgroup.CgroupNode;
import com.ouisani.aios.core.llm.LlmProvider;

import java.util.List;

public class TokenZram {

    private static final class Holder {
        static final TokenZram INSTANCE = new TokenZram();
    }

    private volatile LlmProvider llmProvider;

    private TokenZram() {}

    public static TokenZram instance() {
        return Holder.INSTANCE;
    }

    public void configureLlmProvider(LlmProvider provider) {
        this.llmProvider = provider;
    }

    public void compressMemory(AgentTask task, CgroupNode cgroup) {
        List<String> history = task.contextHistory();
        if (history.size() < 2) {
            System.out.println("  [Token ZRAM] Agent#" + task.pid()
                    + " has insufficient history (" + history.size() + " entries), skip compression");
            return;
        }

        int splitPoint = history.size() / 2;

        StringBuilder coldData = new StringBuilder();
        for (int i = 0; i < splitPoint; i++) {
            coldData.append(history.get(i));
            if (i < splitPoint - 1) coldData.append("\n");
        }

        long coldTokens = Math.max(1, coldData.length() / 4);

        String compressed;
        if (llmProvider != null && llmProvider.isAvailable()) {
            System.out.printf("  [Token ZRAM] Compressing Agent#%d cold memory (%d entries, ~%d tokens)...%n",
                    task.pid(), splitPoint, coldTokens);
            try {
                compressed = llmProvider.think(
                        "Please summarize the following context compactly, preserving all key information:\n" + coldData,
                        "System: Compression Engine");
            } catch (Exception e) {
                System.out.printf("  [Token ZRAM] LLM compression failed, using truncation: %s%n", e.getMessage());
                compressed = truncateCompress(coldData.toString());
            }
        } else {
            System.out.printf("  [Token ZRAM] No LLM available, using truncation compression for Agent#%d%n", task.pid());
            compressed = truncateCompress(coldData.toString());
        }

        String zramBlock = "<ZRAM_COMPRESSED>" + compressed + "</ZRAM_COMPRESSED>";
        long compressedTokens = Math.max(1, zramBlock.length() / 4);
        long savedTokens = coldTokens - compressedTokens;

        task.replaceHistoryRange(0, splitPoint, zramBlock);

        if (savedTokens > 0 && cgroup != null) {
            long refunded = cgroup.refundTokens(savedTokens);
            System.out.printf("  ╔══════════════════════════════════════════════════════════════╗%n");
            System.out.printf("  ║  [Token ZRAM] Compressed Agent#%d memory.%n", task.pid());
            System.out.printf("  ║  Cold data: %d entries (~%d tokens)%n", splitPoint, coldTokens);
            System.out.printf("  ║  Compressed: ~%d tokens%n", compressedTokens);
            System.out.printf("  ║  Refunded %d tokens to Cgroup '%s'!%n", refunded, cgroup.name());
            System.out.printf("  ╚══════════════════════════════════════════════════════════════╝%n");
        } else {
            System.out.printf("  [Token ZRAM] Agent#%d compressed but no tokens saved (cold=%d, compressed=%d)%n",
                    task.pid(), coldTokens, compressedTokens);
        }
    }

    private String truncateCompress(String text) {
        if (text.length() <= 200) return text;
        return text.substring(0, 100) + "...[TRUNCATED]..." + text.substring(text.length() - 100);
    }
}
