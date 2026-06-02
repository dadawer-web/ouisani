package com.ouisani.aios.core;

import com.ouisani.aios.core.network.SyscallServer;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.vfs.WebSocketNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TestNetworkBus: AIOS 全双工 WebSocket VFS 桥梁端到端测试
 *
 * 测试流程：
 * 1. 启动 Javalin 服务器（含 WebSocket 路由）
 * 2. Spawn 一个死循环 Agent，挂在 /dev/ws/test 上读取数据并回复
 * 3. 通过 Java WebSocket 客户端连接 ws://localhost:8080/ws/dev/test
 * 4. 客户端发送消息 → Agent 读取 → Agent 回复 → 客户端接收
 *
 * 真实浏览器交互测试：
 * 在浏览器控制台中执行：
 *   const ws = new WebSocket('ws://localhost:8080/ws/dev/test');
 *   ws.onmessage = e => console.log('← Agent:', e.data);
 *   ws.onopen = () => { console.log('Connected!'); ws.send('Hello AIOS!'); };
 *   ws.onclose = () => console.log('Disconnected');
 */
public class TestNetworkBus {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║     TestNetworkBus: Full-Duplex WebSocket VFS Bridge        ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        System.out.println("── Step 1: Initialize VfsManager + TaskScheduler ──");
        VfsManager.instance().init();
        TaskScheduler scheduler = new TaskScheduler();
        scheduler.start();
        System.out.println("  ✓ VfsManager initialized");
        System.out.println("  ✓ TaskScheduler started");
        System.out.println();

        System.out.println("── Step 2: Start SyscallServer on port 8080 ──");
        SyscallServer server = new SyscallServer(scheduler);
        server.start(8080);
        System.out.println();

        System.out.println("── Step 3: Spawn echo Agent (reads /dev/ws/test, writes back) ──");
        AtomicInteger agentMsgCount = new AtomicInteger(0);
        CountDownLatch agentReady = new CountDownLatch(1);

        AgentTask agentTask = new AgentTask(1, AgentTask.TaskStatus.READY,
                "/aios/ws-agent", "/dev/null", "/dev/null", new ArrayList<>());
        agentTask.setType(AgentTask.TaskType.TOOL_CALL);

        scheduler.spawn(agentTask, () -> {
            agentReady.countDown();
            System.out.println("  [Agent#1] Echo agent started, waiting for /dev/ws/test ...");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Optional<VfsNode> nodeOpt = VfsManager.instance().resolve("/dev/ws/test");
                    if (nodeOpt.isEmpty()) {
                        Thread.sleep(200);
                        continue;
                    }

                    WebSocketNode wsNode = (WebSocketNode) nodeOpt.get();
                    String incoming = wsNode.read();
                    if (incoming.isEmpty()) continue;

                    int count = agentMsgCount.incrementAndGet();
                    System.out.printf("  [Agent#1] ← Received: '%s' (msg #%d)%n", incoming, count);

                    String reply = "[AIOS Echo] You said: " + incoming + " (reply #" + count + ")";
                    wsNode.write(reply);
                    System.out.printf("  [Agent#1] → Sent: '%s'%n", reply);

                    if (count >= 3) {
                        System.out.println("  [Agent#1] 3 messages processed, exiting loop");
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            System.out.println("  [Agent#1] Echo agent finished");
        });

        agentReady.await(2, TimeUnit.SECONDS);
        System.out.println();

        System.out.println("── Step 4: Connect WebSocket client to ws://localhost:8080/ws/dev/test ──");
        List<String> wsReplies = new ArrayList<>();
        CountDownLatch wsConnected = new CountDownLatch(1);
        CountDownLatch allReplies = new CountDownLatch(3);

        Thread wsClientThread = Thread.ofVirtual().name("ws-client").start(() -> {
            try {
                java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                java.net.http.WebSocket ws = client.newWebSocketBuilder()
                        .buildAsync(URI.create("ws://localhost:8080/ws/dev/test"),
                                new java.net.http.WebSocket.Listener() {
                                    StringBuilder buffer = new StringBuilder();

                                    @Override
                                    public void onOpen(java.net.http.WebSocket webSocket) {
                                        System.out.println("  [WS Client] Connected!");
                                        wsConnected.countDown();
                                        webSocket.request(1);
                                    }

                                    @Override
                                    public CompletionStage<?> onText(java.net.http.WebSocket webSocket,
                                            CharSequence data, boolean last) {
                                        buffer.append(data);
                                        if (last) {
                                            String msg = buffer.toString();
                                            buffer.setLength(0);
                                            synchronized (wsReplies) {
                                                wsReplies.add(msg);
                                                wsReplies.notifyAll();
                                            }
                                            System.out.printf("  [WS Client] ← Received: '%s'%n", msg);
                                            allReplies.countDown();
                                        }
                                        webSocket.request(1);
                                        return null;
                                    }

                                    @Override
                                    public CompletionStage<?> onClose(java.net.http.WebSocket webSocket,
                                            int statusCode, String reason) {
                                        System.out.printf("  [WS Client] Closed: %d %s%n", statusCode, reason);
                                        return null;
                                    }

                                    @Override
                                    public void onError(java.net.http.WebSocket webSocket, Throwable error) {
                                        System.out.printf("  [WS Client] Error: %s%n", error.getMessage());
                                        wsConnected.countDown();
                                    }
                                })
                        .get(5, TimeUnit.SECONDS);

                wsConnected.await(3, TimeUnit.SECONDS);
                Thread.sleep(500);

                String[] messages = {"Hello AIOS!", "VFS WebSocket bridge works!", "Final message!"};
                for (String msg : messages) {
                    ws.sendText(msg, true);
                    System.out.printf("  [WS Client] → Sent: '%s'%n", msg);
                    Thread.sleep(300);
                }

                allReplies.await(10, TimeUnit.SECONDS);
                Thread.sleep(500);
                ws.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "test done");

            } catch (Exception e) {
                System.out.printf("  [WS Client] Exception: %s%n", e.getMessage());
                wsConnected.countDown();
            }
        });

        wsClientThread.join(15_000);
        Thread.sleep(500);
        System.out.println();

        System.out.println("── Step 5: Results ──");
        System.out.printf("  Agent messages processed: %d%n", agentMsgCount.get());
        System.out.printf("  WS client replies received: %d%n", wsReplies.size());
        for (int i = 0; i < wsReplies.size(); i++) {
            System.out.printf("    Reply #%d: %s%n", i + 1, wsReplies.get(i));
        }
        System.out.println();

        System.out.println("── Step 6: VFS tree ──");
        System.out.println(VfsManager.instance().tree());
        System.out.println();

        System.out.println("── Step 7: Scheduler stats ──");
        TaskScheduler.SchedulerStats stats = scheduler.stats();
        System.out.printf("  Spawned: %d, Completed: %d, Cancelled: %d, Active: %d%n",
                stats.totalSpawned(), stats.totalCompleted(), stats.totalCancelled(), stats.activeCount());
        System.out.println();

        server.stop();
        scheduler.shutdown();

        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║              Network Bus Test Complete ✓                    ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
    }
}
