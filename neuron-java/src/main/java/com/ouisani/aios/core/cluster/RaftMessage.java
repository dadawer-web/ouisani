package com.ouisani.aios.core.cluster;

import java.io.Serial;
import java.io.Serializable;

/**
 * Raft 协议消息 — AIOS 集群节点间的通信协议。
 * <p>
 * 类比 Raft 论文中的 RPC 消息：
 * <ul>
 *   <li>{@code RequestVote} — 请求投票（Leader 选举）</li>
 *   <li>{@code AppendEntries} — 追加日志（状态机复制 + 心跳）</li>
 *   <li>{@code TaskDispatch} — 任务派发（全局算力池）</li>
 *   <li>{@code TaskResult} — 任务结果（RPC 返回）</li>
 * </ul>
 *
 * <h3>消息格式</h3>
 * <pre>
 * ┌──────────┬──────────┬──────────┬──────────┬──────────┐
 * │ msgType  │ term     │ from     │ to       │ payload  │
 * │ (1 byte) │ (8 byte) │ (string) │ (string) │ (JSON)   │
 * └──────────┴──────────┴──────────┴──────────┴──────────┘
 * </pre>
 */
public record RaftMessage(
        /** 消息类型 */
        Type type,
        /** 当前任期号 */
        long term,
        /** 发送者节点 ID */
        String fromNodeId,
        /** 接收者节点 ID */
        String toNodeId,
        /** 消息载荷（JSON 格式） */
        String payload,
        /** 时间戳 */
        long timestamp
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Raft 消息类型 */
    public enum Type {
        // ── Raft 核心协议 ──
        /** 请求投票 — 候选人发起选举 */
        REQUEST_VOTE,
        /** 投票响应 — 节点回复投票结果 */
        VOTE_RESPONSE,
        /** 追加日志 — Leader 复制日志 + 心跳 */
        APPEND_ENTRIES,
        /** 追加日志响应 — Follower 确认日志 */
        APPEND_ENTRIES_RESPONSE,

        // ── AIOS 集群扩展 ──
        /** 任务派发 — Leader 向 Follower 分发任务 */
        TASK_DISPATCH,
        /** 任务结果 — Follower 返回任务执行结果 */
        TASK_RESULT,
        /** 记忆广播 — 新知识的 Raft 日志条目 */
        MEMORY_REPLICATE,
        /** 节点加入 — 新节点请求加入集群 */
        NODE_JOIN,
        /** 节点加入确认 */
        NODE_JOIN_RESPONSE,
        /** 集群状态查询 */
        CLUSTER_STATUS
    }

    // ── 工厂方法 ──

    public static RaftMessage requestVote(String from, String to, long term, String payload) {
        return new RaftMessage(Type.REQUEST_VOTE, term, from, to, payload, System.currentTimeMillis());
    }

    public static RaftMessage voteResponse(String from, String to, long term, boolean granted) {
        return new RaftMessage(Type.VOTE_RESPONSE, term, from, to,
                "{\"granted\":" + granted + "}", System.currentTimeMillis());
    }

    public static RaftMessage appendEntries(String from, String to, long term, String payload) {
        return new RaftMessage(Type.APPEND_ENTRIES, term, from, to, payload, System.currentTimeMillis());
    }

    public static RaftMessage appendEntriesResponse(String from, String to, long term, boolean success) {
        return new RaftMessage(Type.APPEND_ENTRIES_RESPONSE, term, from, to,
                "{\"success\":" + success + "}", System.currentTimeMillis());
    }

    public static RaftMessage taskDispatch(String from, String to, String taskPayload) {
        return new RaftMessage(Type.TASK_DISPATCH, 0, from, to, taskPayload, System.currentTimeMillis());
    }

    public static RaftMessage taskResult(String from, String to, String resultPayload) {
        return new RaftMessage(Type.TASK_RESULT, 0, from, to, resultPayload, System.currentTimeMillis());
    }

    public static RaftMessage memoryReplicate(String from, long term, String memoryPayload) {
        return new RaftMessage(Type.MEMORY_REPLICATE, term, from, "ALL", memoryPayload, System.currentTimeMillis());
    }

    public static RaftMessage nodeJoin(String from, String payload) {
        return new RaftMessage(Type.NODE_JOIN, 0, from, "ALL", payload, System.currentTimeMillis());
    }

    public static RaftMessage clusterStatus(String from, String payload) {
        return new RaftMessage(Type.CLUSTER_STATUS, 0, from, "ALL", payload, System.currentTimeMillis());
    }

    @Override
    public String toString() {
        return "RaftMessage{type=%s, term=%d, from=%s, to=%s, payloadLen=%d}".formatted(
                type, term, fromNodeId, toNodeId, payload != null ? payload.length() : 0);
    }
}
