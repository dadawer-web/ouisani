package com.ouisani.aios.core.a2a;

/**
 * A2A 协议定义 — 借鉴 Agent Zero 的 A2A 通信协议。
 * <p>
 * 定义跨节点 Agent 间通信的消息格式和协议常量。
 * <p>
 * 协议设计原则：
 * - 基于 JSON-RPC 2.0 格式
 * - 支持 WebSocket 和 HTTP 两种传输
 * - 与内部 MailMessage 协议桥接
 * - 支持请求-响应和发布-订阅两种模式
 */
public final class A2aProtocol {

    private A2aProtocol() {}

    /** 协议版本 */
    public static final String PROTOCOL_VERSION = "1.0";

    /** A2A WebSocket 端点路径 */
    public static final String WS_ENDPOINT = "/api/a2a/channel";

    /** A2A HTTP 端点路径 */
    public static final String HTTP_ENDPOINT = "/api/a2a/message";

    /** A2A 节点发现端点 */
    public static final String DISCOVERY_ENDPOINT = "/api/a2a/discovery";

    /** A2A 消息类型 */
    public enum MessageType {
        /** 任务委派 — 请求远程 Agent 执行任务 */
        TASK_DELEGATE,
        /** 任务结果 — 远程 Agent 返回任务结果 */
        TASK_RESULT,
        /** 状态查询 — 查询远程 Agent 状态 */
        STATUS_QUERY,
        /** 状态响应 — 返回 Agent 状态 */
        STATUS_RESPONSE,
        /** 广播 — 向所有连接的节点广播消息 */
        BROADCAST,
        /** 心跳 — 保持连接活跃 */
        HEARTBEAT,
        /** 节点注册 — 新节点加入联邦 */
        NODE_REGISTER,
        /** 节点注销 — 节点离开联邦 */
        NODE_DEREGISTER
    }

    /** 节点能力描述 */
    public enum Capability {
        /** 可以接受任务委派 */
        ACCEPT_TASKS,
        /** 可以提供工具调用 */
        PROVIDE_TOOLS,
        /** 可以提供 LLM 推理 */
        PROVIDE_LLM,
        /** 可以提供向量检索 */
        PROVIDE_VECTOR_SEARCH,
        /** 全功能节点 */
        FULL
    }
}
