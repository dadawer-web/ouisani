package com.ouisani.aios.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * MCP Client Registry — the universal tool bus for hot-plugging external
 * capabilities into the AIOS kernel via the Model Context Protocol.
 * <p>
 * Manages a registry of external MCP server processes, each identified by
 * a logical name (e.g. "weather", "github"). Servers are launched as
 * background child processes via {@link ProcessBuilder}, and communication
 * follows the MCP STDIO transport: JSON-RPC 2.0 messages are written to
 * the process's stdin and read from its stdout.
 * <p>
 * <h3>Lifecycle:</h3>
 * <ol>
 *   <li>{@link #registerServer(String, String)} — spawn the external process</li>
 *   <li>{@link #initializeServer(String)} — perform MCP handshake</li>
 *   <li>{@link #callTool(String, String, Map)} — invoke a tool on the server</li>
 *   <li>{@link #unregisterServer(String)} — terminate the process</li>
 * </ol>
 */
public final class McpClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpClientRegistry.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final class Holder {
        static final McpClientRegistry INSTANCE = new McpClientRegistry();
    }

    public static McpClientRegistry getInstance() {
        return Holder.INSTANCE;
    }

    private final AtomicLong requestIdCounter = new AtomicLong(0);
    private final ConcurrentHashMap<String, McpClientConnection> connections = new ConcurrentHashMap<>();

    private McpClientRegistry() {
        log.info("[MCP Subsystem] Universal Tool Bus initialized. Ready to hot-plug external capabilities.");
        System.out.println("[MCP Subsystem] Universal Tool Bus initialized. Ready to hot-plug external capabilities.");
    }

    // ════════════════════════════════════════════════════════════════
    //  Server Registration (Discovery)
    // ════════════════════════════════════════════════════════════════

    /**
     * Register and spawn an external MCP server as a background child process.
     * <p>
     * Example: {@code registerServer("weather", "npx -y @modelcontextprotocol/server-weather")}
     * <p>
     * The command is split by whitespace and passed to {@link ProcessBuilder}.
     * The process's stdin/stdout are captured for JSON-RPC communication.
     * The process's stderr is redirected to AIOS's own stderr for debugging.
     *
     * @param serverName logical name for the server (used as lookup key)
     * @param command    the shell command to launch the MCP server
     * @throws IllegalStateException if a server with the same name is already registered
     */
    public void registerServer(String serverName, String command) {
        if (connections.containsKey(serverName)) {
            throw new IllegalStateException("MCP server '" + serverName + "' is already registered");
        }

        log.info("[MCP Registry] Spawning server '{}': {}", serverName, command);

        String[] commandParts = command.split("\\s+");
        ProcessBuilder pb = new ProcessBuilder(commandParts)
                .redirectErrorStream(false);

        try {
            Process process = pb.start();

            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream()));
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));

            // Drain stderr in a background thread to prevent buffer deadlock
            Thread stderrDrainer = new Thread(() -> {
                try (BufferedReader errReader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = errReader.readLine()) != null) {
                        log.debug("[MCP stderr|{}] {}", serverName, line);
                    }
                } catch (IOException e) {
                    // Process terminated, that's fine
                }
            }, "mcp-stderr-" + serverName);
            stderrDrainer.setDaemon(true);
            stderrDrainer.start();

            McpClientConnection conn = new McpClientConnection(
                    serverName, command, process, writer, reader, stderrDrainer);

            connections.put(serverName, conn);

            log.info("[MCP Registry] Server '{}' spawned successfully (pid={})",
                    serverName, process.pid());
            System.out.println("  + [MCP Registry] Server '" + serverName + "' mounted: " + command);

            // Perform MCP handshake automatically
            initializeServer(serverName);

        } catch (IOException e) {
            log.error("[MCP Registry] Failed to spawn server '{}': {}", serverName, e.getMessage());
            throw new RuntimeException("Failed to spawn MCP server '" + serverName + "': " + e.getMessage(), e);
        }
    }

    /**
     * Unregister and terminate an external MCP server.
     *
     * @param serverName the logical name of the server
     */
    public void unregisterServer(String serverName) {
        McpClientConnection conn = connections.remove(serverName);
        if (conn == null) {
            log.warn("[MCP Registry] Server '{}' not found for unregistration", serverName);
            return;
        }

        conn.close();
        log.info("[MCP Registry] Server '{}' unregistered and terminated", serverName);
        System.out.println("  - [MCP Registry] Server '" + serverName + "' unmounted.");
    }

    /**
     * Check if a server is registered.
     */
    public boolean hasServer(String serverName) {
        return connections.containsKey(serverName);
    }

    /**
     * Get all registered server names.
     */
    public java.util.Set<String> serverNames() {
        return connections.keySet();
    }

    // ════════════════════════════════════════════════════════════════
    //  MCP Protocol Handshake
    // ════════════════════════════════════════════════════════════════

    /**
     * Perform the MCP initialization handshake with an external server.
     * <p>
     * Sends an {@code "initialize"} JSON-RPC request and waits for the
     * server's response confirming protocol compatibility.
     *
     * @param serverName the server to initialize
     */
    public void initializeServer(String serverName) {
        McpClientConnection conn = connections.get(serverName);
        if (conn == null) {
            throw new IllegalStateException("MCP server '" + serverName + "' not registered");
        }

        long reqId = requestIdCounter.incrementAndGet();

        Map<String, Object> initParams = Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(),
                "clientInfo", Map.of(
                        "name", "aios-kernel",
                        "version", "0.1.0"
                )
        );

        try {
            Object response = sendJsonRpc(conn, reqId, "initialize", initParams);
            conn.initialized = true;

            // Send initialized notification (no id, no response expected)
            sendNotification(conn, "notifications/initialized", Map.of());

            log.info("[MCP Registry] Server '{}' initialized: {}", serverName, response);
        } catch (Exception e) {
            log.error("[MCP Registry] Failed to initialize server '{}': {}", serverName, e.getMessage());
            throw new RuntimeException("MCP handshake failed for '" + serverName + "': " + e.getMessage(), e);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Tool Execution (JSON-RPC 2.0 over STDIO)
    // ════════════════════════════════════════════════════════════════

    /**
     * Call a tool on an external MCP server via JSON-RPC 2.0.
     * <p>
     * The request is formatted as:
     * <pre>
     * {
     *   "jsonrpc": "2.0",
     *   "id": &lt;auto-incremented&gt;,
     *   "method": "tools/call",
     *   "params": {
     *     "name": "&lt;toolName&gt;",
     *     "arguments": { ... }
     *   }
     * }
     * </pre>
     * The JSON is written to the server process's stdin, and the response
     * is read from its stdout.
     *
     * @param serverName the logical name of the MCP server
     * @param toolName   the tool to invoke on that server
     * @param arguments  the tool arguments
     * @return the parsed result from the MCP server's response
     * @throws IllegalStateException if the server is not registered
     * @throws RuntimeException     if the JSON-RPC call fails
     */
    public Object callTool(String serverName, String toolName, Map<String, Object> arguments) {
        McpClientConnection conn = connections.get(serverName);
        if (conn == null) {
            throw new IllegalStateException("MCP server '" + serverName + "' not registered");
        }

        if (!conn.initialized) {
            throw new IllegalStateException("MCP server '" + serverName + "' not initialized — call initializeServer() first");
        }

        long reqId = requestIdCounter.incrementAndGet();

        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments != null ? arguments : Map.of());

        log.info("[MCP Client] Calling tool '{}/{}' with args: {}", serverName, toolName, arguments != null ? arguments.keySet() : "[]");

        try {
            Object response = sendJsonRpc(conn, reqId, "tools/call", params);
            log.info("[MCP Client] Tool '{}/{}' executed successfully", serverName, toolName);
            return response;
        } catch (Exception e) {
            log.error("[MCP Client] Tool '{}/{}' call failed: {}", serverName, toolName, e.getMessage());
            throw new RuntimeException("MCP tool call failed: " + e.getMessage(), e);
        }
    }

    /**
     * List all tools available on a registered MCP server.
     *
     * @param serverName the server to query
     * @return the parsed tools/list result
     */
    public Object listTools(String serverName) {
        McpClientConnection conn = connections.get(serverName);
        if (conn == null) {
            throw new IllegalStateException("MCP server '" + serverName + "' not registered");
        }

        long reqId = requestIdCounter.incrementAndGet();

        try {
            return sendJsonRpc(conn, reqId, "tools/list", Map.of());
        } catch (Exception e) {
            throw new RuntimeException("MCP tools/list failed: " + e.getMessage(), e);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  JSON-RPC Transport Layer (STDIO)
    // ════════════════════════════════════════════════════════════════

    /**
     * Send a JSON-RPC 2.0 request and block until the response is received.
     * <p>
     * MCP STDIO transport: each JSON message is written as a single line
     * to the process's stdin, and responses are read line-by-line from stdout.
     */
    private Object sendJsonRpc(McpClientConnection conn, long id, String method, Map<String, Object> params) throws Exception {
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.put("params", params);

        String jsonRequest = objectMapper.writeValueAsString(request);

        conn.writeLock.lock();
        try {
            conn.writer.write(jsonRequest);
            conn.writer.newLine();
            conn.writer.flush();

            log.debug("[MCP Transport] TX → server='{}': {}", conn.serverName, jsonRequest);
        } finally {
            conn.writeLock.unlock();
        }

        // Read response (blocking)
        String responseLine;
        try {
            responseLine = conn.reader.readLine();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read response from MCP server '" + conn.serverName + "': " + e.getMessage(), e);
        }

        if (responseLine == null) {
            throw new RuntimeException("MCP server '" + conn.serverName + "' closed stdout (process may have crashed)");
        }

        log.debug("[MCP Transport] RX ← server='{}': {}", conn.serverName, responseLine);

        // Parse the JSON-RPC response
        @SuppressWarnings("unchecked")
        Map<String, Object> responseMap = objectMapper.readValue(responseLine, Map.class);

        // Check for JSON-RPC error
        if (responseMap.containsKey("error")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) responseMap.get("error");
            int code = error.get("code") instanceof Number n ? n.intValue() : -1;
            String message = error.getOrDefault("message", "Unknown error").toString();
            throw new RuntimeException("MCP server error (code=" + code + "): " + message);
        }

        return responseMap.get("result");
    }

    /**
     * Send a JSON-RPC notification (no id, no response expected).
     */
    private void sendNotification(McpClientConnection conn, String method, Map<String, Object> params) throws Exception {
        Map<String, Object> notification = new java.util.LinkedHashMap<>();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.put("params", params);

        String jsonNotification = objectMapper.writeValueAsString(notification);

        conn.writeLock.lock();
        try {
            conn.writer.write(jsonNotification);
            conn.writer.newLine();
            conn.writer.flush();

            log.debug("[MCP Transport] NOTIFY → server='{}': {}", conn.serverName, jsonNotification);
        } finally {
            conn.writeLock.unlock();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ════════════════════════════════════════════════════════════════

    /**
     * Shutdown all registered MCP servers.
     */
    public void shutdownAll() {
        for (Map.Entry<String, McpClientConnection> entry : connections.entrySet()) {
            entry.getValue().close();
            log.info("[MCP Registry] Server '{}' terminated during shutdown", entry.getKey());
        }
        connections.clear();
        log.info("[MCP Registry] All servers shut down");
    }

    // ════════════════════════════════════════════════════════════════
    //  Internal: MCP Client Connection
    // ════════════════════════════════════════════════════════════════

    /**
     * Represents a live STDIO connection to an external MCP server process.
     */
    static final class McpClientConnection {

        final String serverName;
        final String command;
        final Process process;
        final BufferedWriter writer;
        final BufferedReader reader;
        final Thread stderrDrainer;
        final ReentrantLock writeLock = new ReentrantLock();
        volatile boolean initialized = false;

        McpClientConnection(String serverName, String command, Process process,
                            BufferedWriter writer, BufferedReader reader, Thread stderrDrainer) {
            this.serverName = serverName;
            this.command = command;
            this.process = process;
            this.writer = writer;
            this.reader = reader;
            this.stderrDrainer = stderrDrainer;
        }

        void close() {
            try { writer.close(); } catch (IOException ignored) {}
            try { reader.close(); } catch (IOException ignored) {}
            process.destroyForcibly();
            stderrDrainer.interrupt();
        }
    }
}
