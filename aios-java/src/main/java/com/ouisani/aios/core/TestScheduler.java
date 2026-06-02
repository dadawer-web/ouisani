package com.ouisani.aios.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TestScheduler {

    public static void main(String[] args) throws InterruptedException {
        int agentCount = 10_000;
        int loopsPerAgent = 3;
        AtomicInteger aliveCounter = new AtomicInteger(0);
        AtomicInteger completedAgents = new AtomicInteger(0);

        TaskScheduler scheduler = new TaskScheduler();
        scheduler.start();

        System.out.printf("[Test] Spawning %d agents, each running %d loops with sleep...%n",
                agentCount, loopsPerAgent);
        System.out.printf("[Test] Platform thread count before spawn: %d%n",
                Thread.activeCount());

        long startTime = System.currentTimeMillis();
        CountDownLatch allDone = new CountDownLatch(agentCount);
        List<Integer> pids = new ArrayList<>();

        for (int i = 1; i <= agentCount; i++) {
            final int pid = i;
            AgentTask task = new AgentTask(
                    pid,
                    AgentTask.TaskStatus.READY,
                    "cgroup/test",
                    "/dev/stdin/" + pid,
                    "/dev/stdout/" + pid,
                    new ArrayList<>()
            );

            int finalLoopsPerAgent = loopsPerAgent;
            scheduler.spawn(task, () -> {
                int currentAlive = aliveCounter.incrementAndGet();
                if (pid % 1000 == 0) {
                    System.out.printf("[Agent#%d] STARTED | concurrent_alive=%d | thread=%s%n",
                            pid, currentAlive, Thread.currentThread());
                }

                try {
                    for (int j = 0; j < finalLoopsPerAgent; j++) {
                        if (Thread.interrupted()) {
                            break;
                        }
                        Thread.sleep(1);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                aliveCounter.decrementAndGet();
                completedAgents.incrementAndGet();
                allDone.countDown();
            });

            pids.add(pid);
        }

        System.out.printf("[Test] All %d agents spawned in %dms%n",
                agentCount, System.currentTimeMillis() - startTime);
        System.out.printf("[Test] Active agents in PCB: %d%n", scheduler.activeCount());

        boolean finished = allDone.await(60, TimeUnit.SECONDS);

        long elapsed = System.currentTimeMillis() - startTime;

        System.out.println();
        System.out.println("========== TEST RESULTS ==========");
        System.out.printf("  Total spawned:    %d%n", agentCount);
        System.out.printf("  Completed:        %d%n", completedAgents.get());
        System.out.printf("  Finished in time: %s%n", finished);
        System.out.printf("  Total elapsed:    %dms%n", elapsed);
        System.out.printf("  Peak concurrent:  ~%d%n", aliveCounter.get() + completedAgents.get());
        System.out.printf("  Platform threads: %d%n", Thread.activeCount());
        System.out.printf("  Scheduler stats:  %s%n", scheduler.stats());
        System.out.println("==================================");

        System.out.println();
        System.out.println("--- Testing cancel_agent ---");
        testCancel(scheduler);

        scheduler.shutdown();
        System.out.println("[Test] Scheduler shutdown complete.");
    }

    private static void testCancel(TaskScheduler scheduler) throws InterruptedException {
        AgentTask longTask = new AgentTask(
                99999,
                AgentTask.TaskStatus.READY,
                "cgroup/test",
                "/dev/stdin/99999",
                "/dev/stdout/99999",
                new ArrayList<>()
        );

        scheduler.spawn(longTask, () -> {
            try {
                System.out.println("[Agent#99999] Starting long sleep...");
                Thread.sleep(Duration.ofMinutes(10));
            } catch (InterruptedException e) {
                System.out.println("[Agent#99999] Interrupted! Exiting gracefully.");
                Thread.currentThread().interrupt();
            }
        });

        Thread.sleep(50);

        boolean cancelled = scheduler.cancelAgent(99999);
        System.out.printf("[Test] cancel_agent(99999) = %s%n", cancelled);
        System.out.printf("[Test] Agent#99999 status = %s%n", longTask.status());
        System.out.printf("[Test] Agent#99999 isCancelled = %s%n", longTask.isCancelled());
    }

    private static class Duration {
        static java.time.Duration ofMinutes(long minutes) {
            return java.time.Duration.ofMinutes(minutes);
        }
    }
}
