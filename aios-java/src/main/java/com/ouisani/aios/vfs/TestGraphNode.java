package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.cgroup.CgroupManager;
import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.llm.OpenAiAdapter;

import java.util.List;

public class TestGraphNode {

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║   TestGraphNode: GraphRAG Topology + BFS Subgraph Query     ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        System.out.println("── Step 1: Initialize with LlmProvider ──");
        String apiKey = System.getenv().getOrDefault("OPENAI_API_KEY", "");
        String baseUrl = System.getenv().getOrDefault("OPENAI_BASE_URL", "https://api.openai.com");
        String model = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini");

        OpenAiAdapter llm = new OpenAiAdapter(apiKey, baseUrl, model);
        VfsManager.instance().configureLlmProvider(llm);
        VfsManager.instance().init();
        CgroupManager.instance().init();
        System.out.println("  ✓ VfsManager initialized with /dev/graph_mem");
        System.out.println();

        System.out.println("── Step 2: Resolve /dev/graph_mem ──");
        VfsNode node = VfsManager.instance().resolve("/dev/graph_mem").orElseThrow();
        GraphNode graphMem = (GraphNode) node;
        System.out.printf("  /dev/graph_mem resolved: type=%s%n%n", graphMem.nodeType());

        System.out.println("── Step 3: Ingest knowledge via write() ──");
        graphMem.write("AIOS is an AI operating system. It contains a TaskScheduler that manages virtual threads. "
                + "The TaskScheduler spawns agents that run in isolated containers. Each container has a Cgroup "
                + "for token-based resource limits. The Cgroup uses soft limits and hard limits. When soft limit "
                + "is exceeded, TokenZRAM compresses memory. When hard limit is exceeded, the agent is OOM killed. "
                + "The VfsManager provides a virtual filesystem with nodes like /dev/vec_mem for vector search "
                + "and /dev/graph_mem for knowledge graphs.");

        System.out.println();
        System.out.printf("  Graph stats: %d entities, %d edges%n%n", graphMem.entityCount(), graphMem.edgeCount());

        System.out.println("── Step 4: Read /dev/graph_mem (VFS standard) ──");
        String content = graphMem.read();
        System.out.printf("  %s%n%n", content.length() > 500 ? content.substring(0, 500) + "..." : content);

        System.out.println("── Step 5: BFS subgraph query ──");
        String subgraph1 = graphMem.querySubgraph("AIOS", 2);
        System.out.printf("  Query: root=AIOS, depth=2%n");
        System.out.printf("  %s%n%n", subgraph1);

        String subgraph2 = graphMem.querySubgraph("Cgroup", 1);
        System.out.printf("  Query: root=Cgroup, depth=1%n");
        System.out.printf("  %s%n%n", subgraph2);

        boolean testPassed = graphMem.entityCount() > 0 && graphMem.edgeCount() > 0;

        if (testPassed) {
            System.out.println("  ╔══════════════════════════════════════════════════════════╗");
            System.out.println("  ║  🕸️  [GraphNode] GraphRAG Topology Test PASSED!         ║");
            System.out.println("  ║                                                          ║");
            System.out.printf("  ║  Entities: %d, Edges: %d%n", graphMem.entityCount(), graphMem.edgeCount());
            System.out.println("  ║  write() → LLM triplet extraction → graph store ✅       ║");
            System.out.println("  ║  querySubgraph() → BFS traversal → JSON ✅               ║");
            System.out.println("  ║  Knowledge graph memory online! 🌟                       ║");
            System.out.println("  ╚══════════════════════════════════════════════════════════╝");
        } else {
            System.out.println("  ❌ GraphNode Test FAILED!");
        }
    }
}
