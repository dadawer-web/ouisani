package com.ouisani.aios.core;

import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.network.SyscallServer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TestSyscallServer {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║     TestSyscallServer: AIOS Syscall Gateway E2E Test        ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        System.out.println("── Step 1: Initialize TaskScheduler ──");
        TaskScheduler scheduler = new TaskScheduler();
        scheduler.start();
        System.out.println("  ✓ TaskScheduler started");
        System.out.println();

        System.out.println("── Step 2: Start SyscallServer on port 8080 ──");
        SyscallServer server = new SyscallServer(scheduler);
        server.start(8080);
        System.out.println();

        System.out.println("── Step 3: Connect SSE client to /kernel/stream ──");
        List<String> sseEvents = new ArrayList<>();
        CountDownLatch sseReady = new CountDownLatch(1);

        Thread sseThread = Thread.ofVirtual().name("sse-reader").start(() -> {
            try {
                Socket socket = new Socket("localhost", 8080);
                var out = socket.getOutputStream();
                out.write((
                        "GET /kernel/stream HTTP/1.1\r\n" +
                        "Host: localhost:8080\r\n" +
                        "Accept: text/event-stream\r\n" +
                        "Connection: keep-alive\r\n" +
                        "\r\n"
                ).getBytes());
                out.flush();

                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                String line;
                boolean headersDone = false;
                while ((line = reader.readLine()) != null) {
                    if (!headersDone) {
                        if (line.isEmpty()) {
                            headersDone = true;
                            sseReady.countDown();
                        }
                        continue;
                    }
                    synchronized (sseEvents) {
                        sseEvents.add(line);
                        sseEvents.notifyAll();
                    }
                    System.out.printf("  [SSE] ← %s%n", line);
                }
            } catch (Exception e) {
                System.out.printf("  SSE reader ended: %s%n", e.getMessage());
                sseReady.countDown();
            }
        });

        sseReady.await(5, TimeUnit.SECONDS);
        Thread.sleep(300);

        synchronized (sseEvents) {
            while (sseEvents.size() < 2) {
                sseEvents.wait(2000);
            }
        }
        System.out.printf("  EventBus active clients: %d%n", EventBus.instance().activeClientCount());
        System.out.println();

        System.out.println("── Step 4: Direct EventBus broadcast test ──");
        EventBus.instance().broadcast("kernel_heartbeat",
                "{\"ts\":" + System.currentTimeMillis() + ",\"cpu\":0.42,\"mem\":\"128MB\",\"agents\":1}");
        EventBus.instance().broadcast("wasm_result",
                "{\"module\":\"main\",\"function\":\"addTwo\",\"result\":42}");
        System.out.println("  ✓ Broadcasted kernel_heartbeat + wasm_result events");
        System.out.println();

        Thread.sleep(500);

        System.out.println("── Step 5: Send POST /syscall/spawn (triggers SSE broadcast) ──");
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            String jsonPayload = """
                    {
                        "prompt": "Hello AIOS from syscall gateway!",
                        "type": "LLM_CHAT",
                        "cgroup": "/aios/test",
                        "priority": 1,
                        "gas_limit": 5000
                    }
                    """;

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:8080/syscall/spawn"))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            System.out.printf("  HTTP Status: %d%n", response.statusCode());
            System.out.printf("  Response Body: %s%n", response.body());
        } catch (Exception e) {
            System.out.printf("  ✗ HTTP request failed: %s%n", e.getMessage());
        }
        System.out.println();

        Thread.sleep(500);

        System.out.println("── Step 6: SSE events received ──");
        synchronized (sseEvents) {
            System.out.printf("  Total SSE events received: %d%n", sseEvents.size());
            for (String event : sseEvents) {
                System.out.printf("    → %s%n", event);
            }
        }
        System.out.println();

        System.out.println("── Step 7: Scheduler stats ──");
        TaskScheduler.SchedulerStats stats = scheduler.stats();
        System.out.printf("  Spawned: %d, Completed: %d, Cancelled: %d, Active: %d%n",
                stats.totalSpawned(), stats.totalCompleted(), stats.totalCancelled(), stats.activeCount());
        System.out.println();

        server.stop();
        scheduler.shutdown();
        sseThread.interrupt();

        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║              Syscall Gateway Test Complete ✓                ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
    }
}
