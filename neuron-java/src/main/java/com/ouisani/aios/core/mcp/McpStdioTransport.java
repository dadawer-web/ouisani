package com.ouisani.aios.core.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.List;
import java.util.function.Consumer;

/**
 * MCP Stdio 传输层 — 通过标准输入输出与 MCP 服务器子进程通信。
 * <p>
 * OS 类比: 设备驱动程序的底层 I/O 管道——内核通过 stdin/stdout 与用户态驱动进程交换数据。
 * <p>
 * MCP 协议规定：每行一个 JSON-RPC 2.0 消息，以换行符分隔。
 */
public class McpStdioTransport implements McpTransport {
    private static final Logger log = LoggerFactory.getLogger(McpStdioTransport.class);

    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private Thread readThread;
    private Consumer<String> messageHandler; // 接收到消息时的回调
    private List<String> pendingCommand; // 待启动的命令

    public void start(List<String> command, Consumer<String> onMessageReceived) throws IOException {
        this.pendingCommand = command;
        this.messageHandler = onMessageReceived;
        doStart();
    }

    /**
     * 实现 McpTransport 接口。
     * 需要先通过 setCommand() 设置启动命令，再调用 start()。
     */
    @Override
    public void start(Consumer<String> onMessageReceived) throws IOException {
        this.messageHandler = onMessageReceived;
        if (pendingCommand == null) {
            throw new IOException("No command set. Use start(List<String>, Consumer) or setCommand() first.");
        }
        doStart();
    }

    /**
     * 设置启动命令（用于 McpTransport 接口模式）。
     */
    public void setCommand(List<String> command) {
        this.pendingCommand = command;
    }

    private void doStart() throws IOException {

        log.info("[MCP Transport] 启动 Stdio 进程: {}", String.join(" ", pendingCommand));
        ProcessBuilder pb = new ProcessBuilder(pendingCommand);
        // 极其重要：合并错误流，防止子进程因为 stderr 写满而阻塞死锁
        pb.redirectErrorStream(true);
        this.process = pb.start();

        this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
        this.reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

        // 在独立的虚拟线程中持续监听子进程的输出
        this.readThread = Thread.startVirtualThread(this::readLoop);
    }

    private void readLoop() {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                // MCP 协议规定：每行一个 JSON 对象
                if (!line.trim().isEmpty()) {
                    log.debug("[MCP Stdio] RECV: {}", line);
                    if (messageHandler != null) {
                        messageHandler.accept(line);
                    }
                }
            }
        } catch (IOException e) {
            log.error("[MCP Stdio] 读取循环意外终止", e);
        } finally {
            log.warn("[MCP Stdio] 进程流已关闭");
        }
    }

    public synchronized void send(String jsonMessage) throws IOException {
        if (writer == null) throw new IOException("传输层未启动，请先调用 start()");
        log.debug("[MCP Stdio] SEND: {}", jsonMessage);
        writer.write(jsonMessage);
        writer.newLine(); // 必须发送换行符
        writer.flush();   // 必须刷新缓冲区
    }

    public void close() {
        if (process != null) {
            process.destroy();
        }
        if (readThread != null) {
            readThread.interrupt();
        }
    }
}
