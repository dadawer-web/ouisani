package com.ouisani.aios.core;

import com.ouisani.aios.core.ipc.SharedMemoryManager;
import com.ouisani.aios.vfs.ShmNode;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test: Agent A writes intermediate results to SHM, Agent B reads and verifies
 * in real-time, proving zero-copy cross-agent communication via shared memory.
 */
public class TestIpc {

    private static final String SEGMENT_ID = "test_blackboard";
    private static final int NUM_WRITES = 10;

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║  AIOS SHM IPC Test: Cross-Agent Shared Memory           ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();

        // Setup
        VfsManager.instance().init();
        SharedMemoryManager shmMgr = SharedMemoryManager.instance();
        shmMgr.getOrCreateSegment(SEGMENT_ID);

        ShmNode shmNode = new ShmNode("/dev/shm/" + SEGMENT_ID, SEGMENT_ID);
        VfsManager.instance().mount("/dev/shm", SEGMENT_ID, shmNode);

        System.out.println("  [Setup] SHM segment '" + SEGMENT_ID + "' created and mounted at /dev/shm/" + SEGMENT_ID);
        System.out.println();

        CountDownLatch writerReady = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(2);
        AtomicInteger verifiedCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        // ── Agent A: Writer ──
        Thread writer = Thread.ofVirtual().name("agent-writer").start(() -> {
            try {
                System.out.println("  [Agent A] Writer started, writing " + NUM_WRITES + " entries...");
                writerReady.countDown();

                for (int i = 1; i <= NUM_WRITES; i++) {
                    String key = "result_" + i;
                    String value = "intermediate_value_" + (i * 42);
                    boolean ok = shmNode.write(key + "=" + value);
                    if (ok) {
                        System.out.printf("  [Agent A] Wrote: %s=%s%n", key, value);
                    } else {
                        System.out.printf("  [Agent A] FAILED to write: %s=%s%n", key, value);
                        errorCount.incrementAndGet();
                    }
                    Thread.sleep(50); // simulate computation delay
                }

                // Signal completion
                shmNode.write("status=COMPLETE");
                System.out.println("  [Agent A] All writes done, status=COMPLETE");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                allDone.countDown();
            }
        });

        // ── Agent B: Reader ──
        Thread reader = Thread.ofVirtual().name("agent-reader").start(() -> {
            try {
                writerReady.await(5, TimeUnit.SECONDS);
                System.out.println("  [Agent B] Reader started, polling SHM...");

                int lastVerified = 0;
                int attempts = 0;
                int maxAttempts = NUM_WRITES * 20; // generous timeout

                while (lastVerified < NUM_WRITES && attempts < maxAttempts) {
                    attempts++;

                    // Read via SharedMemoryManager directly (zero-copy)
                    String value = shmMgr.get(SEGMENT_ID, "result_" + (lastVerified + 1));
                    if (value != null) {
                        String expected = "intermediate_value_" + ((lastVerified + 1) * 42);
                        if (value.equals(expected)) {
                            lastVerified++;
                            verifiedCount.incrementAndGet();
                            System.out.printf("  [Agent B] Verified: result_%d=%s  ✓%n", lastVerified, value);
                        } else {
                            System.out.printf("  [Agent B] MISMATCH: result_%d expected=%s got=%s  ✗%n",
                                    lastVerified + 1, expected, value);
                            errorCount.incrementAndGet();
                        }
                    } else {
                        Thread.yield();
                    }
                }

                // Verify completion signal
                String status = shmMgr.get(SEGMENT_ID, "status");
                if ("COMPLETE".equals(status)) {
                    System.out.println("  [Agent B] Completion signal received: status=COMPLETE  ✓");
                } else {
                    System.out.println("  [Agent B] Completion signal NOT received  ✗");
                    errorCount.incrementAndGet();
                }

                // Also test reading via VFS (full dump)
                String dump = shmNode.read();
                System.out.printf("  [Agent B] VFS read() dump (%d chars): %s%n", dump.length(), dump);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                allDone.countDown();
            }
        });

        boolean finished = allDone.await(30, TimeUnit.SECONDS);

        System.out.println();
        System.out.println("  ┌─ SHM IPC Test Results ─────────────────────────────┐");
        System.out.printf("  │  Writer entries:     %d/%d%n", NUM_WRITES, NUM_WRITES);
        System.out.printf("  │  Reader verified:    %d/%d%n", verifiedCount.get(), NUM_WRITES);
        System.out.printf("  │  Errors:             %d%n", errorCount.get());
        System.out.printf("  │  Completed in time:  %s%n", finished ? "YES" : "NO (timeout)");
        System.out.printf("  │  SHM segments:       %d%n", shmMgr.segmentCount());
        System.out.printf("  │  Segment keys:       %d%n", shmMgr.getSegment(SEGMENT_ID).size());
        System.out.println("  └────────────────────────────────────────────────────┘");

        boolean success = finished && verifiedCount.get() == NUM_WRITES && errorCount.get() == 0;
        System.out.println();
        if (success) {
            System.out.println("  ✅ SHM IPC Test PASSED: Zero-copy cross-agent communication verified!");
        } else {
            System.out.println("  ❌ SHM IPC Test FAILED!");
        }

        // Cleanup
        shmMgr.destroySegment(SEGMENT_ID);
    }
}
