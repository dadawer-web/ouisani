package com.ouisani.aios.core.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * 传输接口 — 对标 Claude Code 的 Transport.ts。
 * <p>
 * 定义 CLI 与后端通信的统一接口：
 * - connect/close — 连接管理
 * - send — 发送数据
 * - onData/onClose — 接收回调
 * <p>
 * OS 类比：相当于 Linux 的 socket 抽象 — 统一的 I/O 接口。
 */
public interface Transport {

    /**
     * 连接到远端。
     */
    void connect();

    /**
     * 关闭连接。
     */
    void close();

    /**
     * 发送数据。
     */
    void send(String data);

    /**
     * 注册数据接收回调。
     */
    void onData(Consumer<String> handler);

    /**
     * 注册关闭回调。
     */
    void onClose(Consumer<String> handler);

    /**
     * 是否已连接。
     */
    boolean isConnected();
}
