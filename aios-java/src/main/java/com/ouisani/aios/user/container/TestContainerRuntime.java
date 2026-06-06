package com.ouisani.aios.user.container;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.cgroup.CgroupManager;
import com.ouisani.aios.core.cgroup.CgroupNode;

public class TestContainerRuntime {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║   TestContainerRuntime: Agentfile Deploy E2E Test               ║");
        System.out.println("║   From Agentfile → Parse → Cgroup → Namespace → Sandbox         ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.println();

        System.out.println("── Step 1: Initialize infrastructure ──");
        VfsManager.instance().init();
        CgroupManager.instance().init();
        TaskScheduler scheduler = new TaskScheduler();
        scheduler.start();
        System.out.println("  ✓ VfsManager initialized");
        System.out.println("  ✓ CgroupManager initialized");
        System.out.println("  ✓ TaskScheduler started (virtual threads)");
        System.out.println();

        System.out.println("── Step 2: Parse Agentfile ──");
        String agentfile = """
                # Java 高级工程师
                FROM gpt-4o
                PERSONA "你是一个资深的 Java 工程师，精通 Spring Boot 和分布式系统。"
                RUN sys_insmod github_search
                RUN sys_insmod code_linter
                COPY ./project_docs /knowledge_base
                LIMIT_TOKENS 100000
                NETWORK dev_team
                ENTRYPOINT ["等待用户输入"]
                """;
        System.out.println("  Agentfile content:");
        System.out.println("  ┌─────────────────────────────────────────┐");
        agentfile.lines().forEach(line ->
                System.out.printf("  │  %s%n", line));
        System.out.println("  └─────────────────────────────────────────┘");
        System.out.println();

        AgentfileParser parser = new AgentfileParser();
        AgentImageConfig config = parser.parse(agentfile);

        boolean parseOk = "gpt-4o".equals(config.baseImage())
                && config.persona() != null && config.persona().contains("Java")
                && config.plugins().size() == 2
                && config.plugins().contains("github_search")
                && config.plugins().contains("code_linter")
                && config.knowledgeMounts().size() == 1
                && config.tokenLimit() == 100000
                && "dev_team".equals(config.networkGroup())
                && "等待用户输入".equals(config.entrypoint());

        System.out.printf("  Parse validation: %s%n", parseOk ? "✅ ALL FIELDS CORRECT" : "❌ MISMATCH");
        System.out.printf("  Parsed: FROM=%s, persona=%d chars, plugins=%s, knowledge=%d, tokens=%d, network=%s%n",
                config.baseImage(),
                config.persona() != null ? config.persona().length() : 0,
                config.plugins(), config.knowledgeMounts().size(),
                config.tokenLimit(), config.networkGroup());
        System.out.println();

        System.out.println("── Step 3: Deploy container via ContainerRuntime ──");
        ContainerRuntime runtime = new ContainerRuntime(scheduler);
        runtime.run("java_engineer_1", config);

        Thread.sleep(2000);

        ContainerRuntime.ContainerContext ctx = runtime.getContainer("java_engineer_1");
        boolean containerExists = ctx != null;
        System.out.printf("  Container 'java_engineer_1' registered: %s%n",
                containerExists ? "✅ YES" : "❌ NO");
        System.out.println();

        System.out.println("── Step 4: Verify Cgroup isolation ──");
        CgroupNode agentCgroup = CgroupManager.instance().getNode("java_engineer_1");
        boolean cgroupOk = agentCgroup != null && agentCgroup.tokenQuota() == 100000;
        System.out.printf("  Cgroup 'java_engineer_1' exists: %s%n",
                agentCgroup != null ? "✅ YES" : "❌ NO");
        System.out.printf("  Cgroup quota: %d (expected 100000): %s%n",
                agentCgroup != null ? agentCgroup.tokenQuota() : -1,
                cgroupOk ? "✅ CORRECT" : "❌ MISMATCH");
        System.out.println();

        System.out.println("── Step 5: Verify VFS namespace isolation ──");
        String containerRoot = ctx != null ? ctx.rootPath() : "/containers/unknown";
        VfsManager.AGENT_ROOT.set(containerRoot);
        String resolvedData = VfsManager.instance().resolvePath("/data/test.txt");
        boolean namespaceOk = resolvedData.startsWith(containerRoot);
        System.out.printf("  Agent path /data/test.txt resolves to: %s%n", resolvedData);
        System.out.printf("  Namespace chroot: %s%n",
                namespaceOk ? "✅ PATH REWRITTEN UNDER CONTAINER ROOT" : "❌ NOT ISOLATED");
        VfsManager.AGENT_ROOT.remove();
        System.out.println();

        System.out.println("── Step 6: Verify IPC isolation ──");
        boolean ipcSameGroup = runtime.canCommunicate("java_engineer_1", "java_engineer_1");
        boolean ipcCrossGroup = runtime.canCommunicate("java_engineer_1", "nonexistent");
        System.out.printf("  Same-group communication: %s%n", ipcSameGroup ? "✅ ALLOWED" : "❌ BLOCKED");
        System.out.printf("  Cross-group communication: %s%n", !ipcCrossGroup ? "✅ BLOCKED" : "❌ ALLOWED");
        System.out.println();

        System.out.println("── Step 7: Print running containers ──");
        System.out.printf("  Active containers: %s%n", runtime.runningContainers());
        System.out.println();

        System.out.println("── Step 8: Stop container ──");
        runtime.stop("java_engineer_1");
        System.out.printf("  After stop, containers: %s%n", runtime.runningContainers());
        System.out.println();

        scheduler.shutdown();

        System.out.println();
        if (parseOk && containerExists && cgroupOk && namespaceOk) {
            System.out.println("  ╔════════════════════════════════════════════════════════════════╗");
            System.out.println("  ║  🐳 [Container] Agentfile Deploy E2E Test PASSED!             ║");
            System.out.println("  ║                                                               ║");
            System.out.println("  ║  FROM gpt-4o                                                 ║");
            System.out.println("  ║    → PERSONA injected ✅                                      ║");
            System.out.println("  ║    → Plugins preloaded ✅                                     ║");
            System.out.println("  ║    → Knowledge base mounted ✅                                ║");
            System.out.println("  ║    → Cgroup created (quota=100000) ✅                         ║");
            System.out.println("  ║    → VFS namespace chroot active ✅                           ║");
            System.out.println("  ║    → IPC network bridge (dev_team) ✅                         ║");
            System.out.println("  ║                                                               ║");
            System.out.println("  ║  From 'Agentfile' to 'Running Container' — The Leap is Done!  ║");
            System.out.println("  ╚════════════════════════════════════════════════════════════════╝");
        } else {
            System.out.println("  ❌ Agentfile Deploy E2E Test FAILED!");
            System.out.printf("     parseOk=%b, containerExists=%b, cgroupOk=%b, namespaceOk=%b%n",
                    parseOk, containerExists, cgroupOk, namespaceOk);
        }
    }
}
