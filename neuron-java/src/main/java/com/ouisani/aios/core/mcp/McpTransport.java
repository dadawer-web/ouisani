package com.ouisani.aios.core.mcp;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * MCP 传输层接口 — 抽象不同的通信机制。
 * <p>
 * OS 类比: 设备驱动的总线接口——无论是 PCI、USB 还是网络，
 * 内核只关心 "发送/接收" 两个基本操作，不关心底层物理介质。
 * <p>
 * 当前实现：
 * <ul>
 *   <li>{@link McpStdioTransport} — 本地子进程 stdin/stdout 通信</li>
 *   <li>{@link McpHttpTransport} — 远程 HTTP/SSE 通信</li>
 * </ul>
 */
public interface McpTransport {

    /**
     * 启动传输层。
     *
     * @param onMessageReceived 接收到消息时的回调
     * @throws IOException 启动失败
     */
    void start(Consumer<String> onMessageReceived) throws IOException;

    /**
     * 发送 JSON-RPC 消息。
     *
     * @param jsonMessage JSON 格式的消息
     * @throws IOException 发送失败
     */
    void send(String jsonMessage) throws IOException;

    /**
     * 关闭传输层，释放资源。
     */
    void close();
}
