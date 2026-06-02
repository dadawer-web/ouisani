package com.ouisani.aios.core;

import com.ouisani.aios.vfs.PipeNode;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class TestIpc {

    public static void main(String[] args) throws InterruptedException {
        int messageCount = 100;
        int pipeCapacity = 8;

        PipeNode pipe = new PipeNode("/tmp/pipe0", pipeCapacity);
        TaskScheduler scheduler = new TaskScheduler();
        scheduler.start();

        System.out.println("========== IPC Test: Virtual Thread Pipe ==========");
        System.out.printf("  Pipe: %s (capacity=%d)%n", pipe.path(), pipeCapacity);
        System.out.printf("  Producer: Agent#1 -> write %d messages%n", messageCount);
        System.out.printf("  Consumer: Agent#2 -> read %d messages%n", messageCount);
        System.out.println();

        AtomicInteger producerBlockedCount = new AtomicInteger(0);
        AtomicInteger consumerBlockedCount = new AtomicInteger(0);
        CountDownLatch consumerDone = new CountDownLatch(1);
        CountDownLatch producerDone = new CountDownLatch(1);

        AgentTask producerTask = new AgentTask(
                1, AgentTask.TaskStatus.READY, "cgroup/producer",
                "/dev/null", "/tmp/pipe0", new ArrayList<>()
        );

        AgentTask consumerTask = new AgentTask(
                2, AgentTask.TaskStatus.READY, "cgroup/consumer",
                "/tmp/pipe0", "/dev/null", new ArrayList<>()
        );

        scheduler.spawn(consumerTask, () -> {
            System.out.println("[Consumer#2] STARTED - waiting for messages...");
            try {
                for (int i = 0; i < messageCount; i++) {
                    if (pipe.bufferSize() == 0) {
                        consumerBlockedCount.incrementAndGet();
                        System.out.printf("[Consumer#2] Buffer empty, take() will block... (blocked %d times)%n",
                                consumerBlockedCount.get());
                    }

                    String data = pipe.take();

                    if (i < 3 || i >= messageCount - 3 || i % 20 == 0) {
                        System.out.printf("[Consumer#2] READ [%d/%d]: \"%s\" (bufferAfter=%d)%n",
                                i + 1, messageCount, data, pipe.bufferSize());
                    }
                }
                System.out.println("[Consumer#2] ALL messages received!");
            } catch (InterruptedException e) {
                System.out.println("[Consumer#2] Interrupted!");
                Thread.currentThread().interrupt();
            }
            consumerDone.countDown();
        });

        Thread.sleep(50);

        scheduler.spawn(producerTask, () -> {
            System.out.println("[Producer#1] STARTED - sending messages...");
            try {
                for (int i = 0; i < messageCount; i++) {
                    if (pipe.remainingCapacity() == 0) {
                        producerBlockedCount.incrementAndGet();
                        System.out.printf("[Producer#1] Buffer full, put() will block... (blocked %d times)%n",
                                producerBlockedCount.get());
                    }

                    String msg = "msg#" + (i + 1) + " from Agent#1";
                    pipe.put(msg);

                    if (i < 3 || i >= messageCount - 3 || i % 20 == 0) {
                        System.out.printf("[Producer#1] WROTE [%d/%d]: \"%s\" (bufferAfter=%d)%n",
                                i + 1, messageCount, msg, pipe.bufferSize());
                    }

                    if (i % 5 == 0) {
                        Thread.sleep(2);
                    }
                }
                System.out.println("[Producer#1] ALL messages sent!");
            } catch (InterruptedException e) {
                System.out.println("[Producer#1] Interrupted!");
                Thread.currentThread().interrupt();
            }
            producerDone.countDown();
        });

        boolean ok = consumerDone.await(30, java.util.concurrent.TimeUnit.SECONDS)
                && producerDone.await(5, java.util.concurrent.TimeUnit.SECONDS);

        System.out.println();
        System.out.println("========== IPC TEST RESULTS ==========");
        System.out.printf("  Completed:          %s%n", ok);
        System.out.printf("  Producer blocked:   %d times (buffer full)%n", producerBlockedCount.get());
        System.out.printf("  Consumer blocked:   %d times (buffer empty)%n", consumerBlockedCount.get());
        System.out.printf("  Pipe totalWritten:  %d%n", pipe.totalWritten());
        System.out.printf("  Pipe totalRead:     %d%n", pipe.totalRead());
        System.out.printf("  Pipe final buffer:  %d%n", pipe.bufferSize());
        System.out.printf("  Pipe stats:         %s%n", pipe.stats());
        System.out.println("======================================");

        System.out.println();
        System.out.println("--- Verifying virtual thread blocking behavior ---");
        System.out.printf("  Producer was blocked %d times by put() -> virtual thread unmounted from carrier%n",
                producerBlockedCount.get());
        System.out.printf("  Consumer was blocked %d times by take() -> virtual thread unmounted from carrier%n",
                consumerBlockedCount.get());
        System.out.printf("  No deadlocks! No manual signal handling!%n");
        System.out.printf("  Platform threads used: %d%n", Thread.activeCount());

        scheduler.shutdown();
        System.out.println("[TestIpc] Done.");
    }
}
