package com.ouisani.aios.core;

import com.ouisani.aios.core.mcp.McpServer;
import com.ouisani.aios.core.network.SyscallServer;
import com.ouisani.aios.core.sandbox.GraalWasmSandbox;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TestMcpProtocol {

    private static final byte[] WASM_RETURN_42 = {
            0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
            0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7f, 0x03,
            0x02, 0x01, 0x00, 0x07, 0x08, 0x01, 0x04, 0x6d,
            0x61, 0x69, 0x6e, 0x00, 0x00, 0x0a, 0x06, 0x01,
            0x04, 0x00, 0x41, 0x2a, 0x0b
    };

    public static void main(String[] args) throws InterruptedException {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║     TestMcpProtocol: Cross-Ecosystem MCP Integration        ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        System.out.println("── Step 1: Initialize AIOS kernel ──");
        VfsManager.instance().init();
        TaskScheduler scheduler = new TaskScheduler();
        scheduler.start();

        GraalWasmSandbox wasmSandbox = new GraalWasmSandbox();
        wasmSandbox.initContext();
        System.out.println("  ✓ GraalWasmSandbox initialized");

        McpServer mcpServer = new McpServer(wasmSandbox);
        System.out.println("  ✓ McpServer initialized");

        SyscallServer server = new SyscallServer(scheduler, mcpServer);
        server.start(8080);
        System.out.println();

        System.out.println("── Step 2: Connect MCP SSE client ──");
        List<String> sseEvents = new java.util.ArrayList<>();
        CountDownLatch endpointReady = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(1);
        String[] sessionIdHolder = new String[1];

        Thread sseThread = Thread.ofVirtual().name("mcp-sse-reader").start(() -> {
            try {
                Socket socket = new java.net.Socket("localhost", 8080);
                var out = socket.getOutputStream();
                out.write((
                        "GET /mcp/sse HTTP/1.1\r\n" +
                        "Host: localhost:8080\r\n" +
                        "Accept: text/event-stream\r\n" +
                        "Connection: keep-alive\r\n" +
                        "\r\n"
                ).getBytes());
                out.flush();

                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                String line;
                boolean headersDone = false;
                String currentEvent = null;
                while ((line = reader.readLine()) != null) {
                    if (!headersDone) {
                        if (line.isEmpty()) {
                            headersDone = true;
                        }
                        continue;
                    }

                    if (line.startsWith("event:")) {
                        currentEvent = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        String data = line.substring(5).trim();
                        synchronized (sseEvents) {
                            sseEvents.add("[" + currentEvent + "] " + data);
                            sseEvents.notifyAll();
                        }
                        System.out.printf("  [MCP/SSE] ← event=%s data=%s%n", currentEvent, data);

                        if ("endpoint".equals(currentEvent)) {
                            String endpoint = data;
                            int idx = endpoint.indexOf("sessionId=");
                            if (idx >= 0) {
                                sessionIdHolder[0] = endpoint.substring(idx + "sessionId=".length());
                            }
                            endpointReady.countDown();
                        }

                        if ("message".equals(currentEvent) && sseEvents.size() >= 4) {
                            allDone.countDown();
                        }
                    } else if (line.isEmpty()) {
                        currentEvent = null;
                    }
                }
            } catch (Exception e) {
                System.out.printf("  [MCP/SSE] Reader ended: %s%n", e.getMessage());
                endpointReady.countDown();
                allDone.countDown();
            }
        });

        endpointReady.await(5, TimeUnit.SECONDS);
        String sessionId = sessionIdHolder[0];
        System.out.printf("  ✓ SSE connected, sessionId=%s%n", sessionId);
        System.out.println();

        HttpClient httpClient = HttpClient.newHttpClient();

        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  阶段一：MCP 握手 (initialize)");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println();

        String initRequest = """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "method": "initialize",
                  "params": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {},
                    "clientInfo": { "name": "aios-test-client", "version": "0.1.0" }
                  }
                }
                """;

        sendMcpMessage(httpClient, sessionId, initRequest);
        Thread.sleep(1000);

        synchronized (sseEvents) {
            sseEvents.stream().filter(e -> e.contains("protocolVersion")).forEach(e ->
                    System.out.printf("  ✓ Initialize response received: %s%n", e.substring(e.indexOf("{"))));
        }
        System.out.println();

        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  阶段二：工具调用 (tools/call → execute_wasm_sandbox)");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println();

        String wasmBase64 = Base64.getEncoder().encodeToString(WASM_RETURN_42);
        System.out.printf("  WASM binary (base64): %s...%n", wasmBase64.substring(0, 40));

        String toolCallRequest = """
                {
                  "jsonrpc": "2.0",
                  "id": 2,
                  "method": "tools/call",
                  "params": {
                    "name": "execute_wasm_sandbox",
                    "arguments": {
                      "wasm_base64": "%s",
                      "function_name": "main"
                    }
                  }
                }
                """.formatted(wasmBase64);

        sendMcpMessage(httpClient, sessionId, toolCallRequest);
        Thread.sleep(2000);

        System.out.println();
        System.out.println("── Step 3: Verify results ──");

        boolean foundResult42 = false;
        synchronized (sseEvents) {
            System.out.printf("  Total SSE events received: %d%n", sseEvents.size());
            for (String event : sseEvents) {
                System.out.printf("    → %s%n", event);
                if (event.contains("42")) {
                    foundResult42 = true;
                }
            }
        }
        System.out.println();

        if (foundResult42) {
            System.out.println("  ╔══════════════════════════════════════════════════════════╗");
            System.out.println("  ║  [MCP Gateway] Cross-Ecosystem Sandbox Execution        ║");
            System.out.println("  ║  Successful! Result: 42                                  ║");
            System.out.println("  ╚══════════════════════════════════════════════════════════╝");
        } else {
            System.out.println("  ❌ [MCP Gateway] Test FAILED - result 42 not found in SSE events");
        }
        System.out.println();

        server.stop();
        scheduler.shutdown();
        sseThread.interrupt();
    }

    private static void sendMcpMessage(HttpClient client, String sessionId, String jsonBody) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8080/mcp/message?sessionId=" + sessionId))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.printf("  → POST /mcp/message → HTTP %d: %s%n", response.statusCode(), response.body());
        } catch (Exception e) {
            System.out.printf("  ✗ POST failed: %s%n", e.getMessage());
        }
    }
}
