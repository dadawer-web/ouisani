package com.ouisani.aios.core.cluster;

import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 语义 Raft 节点 — AIOS 集群的核心共识引擎。
 * <p>
 * 实现了 Raft 分布式一致性协议的核心逻辑：
 * <ul>
 *   <li><b>Leader 选举</b>：通过心跳超时 + 随机化选举超时实现</li>
 *   <li><b>日志复制</b>：Leader 将记忆条目以 AppendEntries 广播到 Follower</li>
 *   <li><b>全局算力池</b>：Leader 统筹集群任务调度，派发给最空闲的节点</li>
 *   <li><b>记忆状态机</b>：高价值知识通过 Raft 日志复制到全网</li>
 * </ul>
 *
 * <h3>Raft 状态机</h3>
 * <pre>
 *   ┌──────────┐  timeout   ┌──────────┐  majority  ┌──────────┐
 *   │ FOLLOWER │ ──────────→│CANDIDATE │ ──────────→│  LEADER  │
 *   └──────────┘            └──────────┘            └──────────┘
 *        ↑                       │                       │
 *        │    higher term        │                       │
 *        └───────────────────────┴───────────────────────┘
 * </pre>
 *
 * <h3>AIOS 集群扩展</h3>
 * <table>
 *   <tr><th>Raft 原语</th><th>AIOS 扩展</th><th>说明</th></tr>
 *   <tr><td>AppendEntries</td><td>Memory Replicate</td><td>记忆状态机复制</td></tr>
 *   <tr><td>Leader 选举</td><td>算力调度 Master</td><td>Leader 统筹全局任务</td></tr>
 *   <tr><td>Log Commit</td><td>知识刻入潜意识</td><td>半数确认 = 永久记忆</td></tr>
 * </table>
 */
public class SemanticRaftNode {

    private static final Logger log = LoggerFactory.getLogger(SemanticRaftNode.class);

    // ── Raft 配置 ──

    /** 心跳间隔（毫秒）— Leader 发送心跳的频率 */
    private static final long HEARTBEAT_INTERVAL_MS = 2000L;

    /** 选举超时基数（毫秒）— Follower 等待心跳的超时 */
    private static final long ELECTION_TIMEOUT_BASE_MS = 5000L;

    /** 选举超时随机范围（毫秒）— 防止同时选举 */
    private static final long ELECTION_TIMEOUT_JITTER_MS = 3000L;

    // ── Raft 状态 ──

    /** 节点角色 */
    public enum Role {
        FOLLOWER,   // 跟随者 — 接收 Leader 的日志和心跳
        CANDIDATE,  // 候选人 — 发起选举
        LEADER      // 领导者 — 统筹集群
    }

    private final String nodeId;
    private volatile Role role = Role.FOLLOWER;
    private volatile long currentTerm = 0;
    private volatile String votedFor = null;
    private volatile String leaderId = null;

    // ── 日志 ──

    /** Raft 日志条目 */
    private final List<RaftLogEntry> logEntries = new CopyOnWriteArrayList<>();

    /** 已提交的日志索引 */
    private volatile long commitIndex = 0;

    /** 已应用的日志索引 */
    private volatile long lastApplied = 0;

    // ── 集群拓扑 ──

    /** 已知的集群节点：nodeId → ClusterPeer */
    private final ConcurrentHashMap<String, ClusterPeer> peers = new ConcurrentHashMap<>();

    /** Leader 状态：每个节点的下一个日志索引 */
    private final ConcurrentHashMap<String, Long> nextIndex = new ConcurrentHashMap<>();

    /** Leader 状态：每个节点的已匹配索引 */
    private final ConcurrentHashMap<String, Long> matchIndex = new ConcurrentHashMap<>();

    // ── 选举状态 ──

    /** 收到的投票数 */
    private volatile int votesReceived = 0;

    /** 上次收到心跳的时间 */
    private volatile long lastHeartbeatTime = System.currentTimeMillis();

    // ── 全局算力池 ──

    /** 集群节点负载：nodeId → 负载分数 (0=空闲, 100=满载) */
    private final ConcurrentHashMap<String, Integer> nodeLoad = new ConcurrentHashMap<>();

    /** 远程任务结果：taskId → CompletableFuture */
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingRemoteTasks = new ConcurrentHashMap<>();

    // ── 记忆状态机 ──

    /** 记忆应用回调 — 当 Raft 日志被提交时调用 */
    private Consumer<RaftLogEntry> memoryApplyCallback;

    // ── 线程 ──

    private ScheduledExecutorService raftExecutor;
    private ServerSocket serverSocket;
    private volatile boolean running = false;

    // ── 统计 ──

    private final AtomicLong totalElections = new AtomicLong(0);
    private final AtomicLong totalLogEntries = new AtomicLong(0);
    private final AtomicLong totalTasksDispatched = new AtomicLong(0);
    private final AtomicLong totalMemoryReplicated = new AtomicLong(0);

    // ════════════════════════════════════════════════════════════════
    //  构造与启动
    // ════════════════════════════════════════════════════════════════

    public SemanticRaftNode(String nodeId) {
        this.nodeId = nodeId;
    }

    /**
     * 启动 Raft 节点 — 开启心跳/选举定时器 + 接受连接。
     *
     * @param clusterPort 集群通信端口
     */
    public void start(int clusterPort) {
        if (running) return;
        running = true;

        // Raft 定时器
        raftExecutor = Executors.newScheduledThreadPool(3, r -> {
            Thread t = new Thread(r, "aios-raft-" + nodeId);
            t.setDaemon(true);
            return t;
        });

        // 心跳/选举定时器
        raftExecutor.scheduleAtFixedRate(this::raftTick, 0, 500, TimeUnit.MILLISECONDS);

        // Leader 心跳发送（仅 Leader 执行）
        raftExecutor.scheduleAtFixedRate(this::leaderHeartbeat, 0, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // 日志应用定时器
        raftExecutor.scheduleAtFixedRate(this::applyCommittedEntries, 0, 1000, TimeUnit.MILLISECONDS);

        // 接受集群连接
        startServer(clusterPort);

        log.info("[Raft] Node {} started on port {}, role={}", nodeId, clusterPort, role);
        System.out.printf("  ✓ [Raft] Node %s started (port=%d, role=%s)%n", nodeId, clusterPort, role);
    }

    /**
     * 停止 Raft 节点。
     */
    public void stop() {
        running = false;

        if (raftExecutor != null) {
            raftExecutor.shutdownNow();
        }

        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
        }

        for (ClusterPeer peer : peers.values()) {
            peer.disconnect();
        }

        log.info("[Raft] Node {} stopped", nodeId);
    }

    // ════════════════════════════════════════════════════════════════
    //  集群拓扑管理
    // ════════════════════════════════════════════════════════════════

    /**
     * 添加集群节点。
     */
    public void addPeer(String nodeId, String host, int port) {
        ClusterPeer peer = new ClusterPeer(nodeId, host, port);
        peers.put(nodeId, peer);

        // 尝试连接
        if (peer.connect()) {
            peer.startReceiving(this::handleMessage);
            log.info("[Raft] Peer connected: {} ({}:{})", nodeId, host, port);
        }

        // 初始化 Leader 状态
        nextIndex.put(nodeId, (long) logEntries.size() + 1);
        matchIndex.put(nodeId, 0L);
        nodeLoad.put(nodeId, 0);
    }

    /**
     * 获取集群节点列表。
     */
    public Set<String> getPeerNodeIds() {
        return Collections.unmodifiableSet(peers.keySet());
    }

    /**
     * 获取集群大小（含自身）。
     */
    public int clusterSize() {
        return peers.size() + 1;
    }

    // ════════════════════════════════════════════════════════════════
    //  Raft 核心逻辑
    // ════════════════════════════════════════════════════════════════

    /**
     * Raft Tick — 每 500ms 执行一次，检查选举超时。
     */
    private void raftTick() {
        if (!running) return;

        if (role == Role.FOLLOWER) {
            long elapsed = System.currentTimeMillis() - lastHeartbeatTime;
            long electionTimeout = ELECTION_TIMEOUT_BASE_MS
                    + ThreadLocalRandom.current().nextLong(ELECTION_TIMEOUT_JITTER_MS);

            if (elapsed > electionTimeout && !peers.isEmpty()) {
                startElection();
            }
        }
    }

    /**
     * 发起选举 — Follower 超时后转变为 Candidate。
     */
    private void startElection() {
        role = Role.CANDIDATE;
        currentTerm++;
        votedFor = nodeId;
        votesReceived = 1; // 投自己一票
        leaderId = null;

        totalElections.incrementAndGet();

        log.info("[Raft] ╔══════════════════════════════════════════════════╗");
        log.info("[Raft] ║  ELECTION STARTED: node={}, term={}            ║", nodeId, currentTerm);
        log.info("[Raft] ╚══════════════════════════════════════════════════╝");

        SemanticEtw.getInstance().logEvent("RAFT", "ELECTION_START",
                "node=" + nodeId + " term=" + currentTerm);

        // 向所有节点请求投票
        for (ClusterPeer peer : peers.values()) {
            if (!peer.isConnected()) continue;

            String payload = "{\"term\":" + currentTerm
                    + ",\"candidateId\":\"" + nodeId + "\""
                    + ",\"lastLogIndex\":" + logEntries.size()
                    + ",\"lastLogTerm\":" + (logEntries.isEmpty() ? 0 : logEntries.get(logEntries.size() - 1).term)
                    + "}";

            peer.sendAsync(RaftMessage.requestVote(nodeId, peer.nodeId(), currentTerm, payload));
        }

        // 检查是否已经获得多数票（单节点集群）
        checkElectionWin();
    }

    /**
     * 处理收到的 Raft 消息。
     */
    private void handleMessage(RaftMessage msg) {
        if (msg == null || !running) return;

        // 如果消息的 term 比自己高，回退为 Follower
        if (msg.term() > currentTerm) {
            currentTerm = msg.term();
            role = Role.FOLLOWER;
            votedFor = null;
            leaderId = null;
        }

        switch (msg.type()) {
            case REQUEST_VOTE -> handleRequestVote(msg);
            case VOTE_RESPONSE -> handleVoteResponse(msg);
            case APPEND_ENTRIES -> handleAppendEntries(msg);
            case APPEND_ENTRIES_RESPONSE -> handleAppendEntriesResponse(msg);
            case TASK_DISPATCH -> handleTaskDispatch(msg);
            case TASK_RESULT -> handleTaskResult(msg);
            case MEMORY_REPLICATE -> handleMemoryReplicate(msg);
            case NODE_JOIN -> handleNodeJoin(msg);
            case CLUSTER_STATUS -> handleClusterStatus(msg);
            default -> log.warn("[Raft] Unknown message type: {}", msg.type());
        }
    }

    /**
     * 处理 RequestVote — 投票给候选人。
     */
    private void handleRequestVote(RaftMessage msg) {
        long candidateTerm = msg.term();
        String candidateId = msg.fromNodeId();

        // 投票条件：term >= 当前 term，且本轮未投票或已投给该候选人
        boolean grantVote = false;
        if (candidateTerm >= currentTerm) {
            if (votedFor == null || votedFor.equals(candidateId)) {
                votedFor = candidateId;
                grantVote = true;
                role = Role.FOLLOWER;
                leaderId = null;
            }
        }

        log.debug("[Raft] Vote request from {}: granted={}", candidateId, grantVote);

        ClusterPeer peer = peers.get(candidateId);
        if (peer != null && peer.isConnected()) {
            peer.sendAsync(RaftMessage.voteResponse(nodeId, candidateId, currentTerm, grantVote));
        }
    }

    /**
     * 处理 VoteResponse — 统计投票。
     */
    private void handleVoteResponse(RaftMessage msg) {
        if (role != Role.CANDIDATE) return;

        boolean granted = msg.payload().contains("\"granted\":true");
        if (granted) {
            votesReceived++;
            checkElectionWin();
        }
    }

    /**
     * 检查是否赢得选举。
     */
    private void checkElectionWin() {
        int majority = (clusterSize() / 2) + 1;
        if (votesReceived >= majority && role == Role.CANDIDATE) {
            role = Role.LEADER;
            leaderId = nodeId;

            log.info("[Raft] ╔══════════════════════════════════════════════════╗");
            log.info("[Raft] ║  LEADER ELECTED: node={}, term={}             ║", nodeId, currentTerm);
            log.info("[Raft] ║  This node now controls the cluster.          ║");
            log.info("[Raft] ╚══════════════════════════════════════════════════╝");

            SemanticEtw.getInstance().logEvent("RAFT", "LEADER_ELECTED",
                    "node=" + nodeId + " term=" + currentTerm + " clusterSize=" + clusterSize());

            // 初始化 Leader 状态
            for (String peerId : peers.keySet()) {
                nextIndex.put(peerId, (long) logEntries.size() + 1);
                matchIndex.put(peerId, 0L);
            }

            // 立即发送心跳
            leaderHeartbeat();
        }
    }

    /**
     * 处理 AppendEntries — Follower 接收 Leader 的日志/心跳。
     */
    private void handleAppendEntries(RaftMessage msg) {
        // 重置选举超时
        lastHeartbeatTime = System.currentTimeMillis();

        if (msg.term() < currentTerm) {
            // 拒绝过期 term 的请求
            ClusterPeer peer = peers.get(msg.fromNodeId());
            if (peer != null) {
                peer.sendAsync(RaftMessage.appendEntriesResponse(
                        nodeId, msg.fromNodeId(), currentTerm, false));
            }
            return;
        }

        // 认可 Leader
        if (role != Role.FOLLOWER) {
            role = Role.FOLLOWER;
        }
        leaderId = msg.fromNodeId();

        // 解析日志条目并追加
        boolean success = true;
        // (简化实现：直接接受所有日志)

        ClusterPeer peer = peers.get(msg.fromNodeId());
        if (peer != null) {
            peer.sendAsync(RaftMessage.appendEntriesResponse(
                    nodeId, msg.fromNodeId(), currentTerm, success));
        }
    }

    /**
     * 处理 AppendEntriesResponse — Leader 更新复制进度。
     */
    private void handleAppendEntriesResponse(RaftMessage msg) {
        boolean success = msg.payload().contains("\"success\":true");
        String peerId = msg.fromNodeId();

        if (success) {
            // 更新 matchIndex 和 nextIndex
            long currentNext = nextIndex.getOrDefault(peerId, 1L);
            matchIndex.put(peerId, currentNext - 1);
            nextIndex.put(peerId, currentNext + 1);

            // 检查是否可以推进 commitIndex
            advanceCommitIndex();
        } else {
            // 回退 nextIndex
            long currentNext = nextIndex.getOrDefault(peerId, 1L);
            if (currentNext > 1) {
                nextIndex.put(peerId, currentNext - 1);
            }
        }
    }

    /**
     * 推进 commitIndex — 当半数以上节点确认时提交日志。
     */
    private void advanceCommitIndex() {
        // 从高到低查找可以提交的索引
        for (long n = logEntries.size(); n > commitIndex; n--) {
            int replications = 1; // 自己
            for (Long mi : matchIndex.values()) {
                if (mi >= n) replications++;
            }

            if (replications >= (clusterSize() / 2) + 1) {
                commitIndex = n;
                log.debug("[Raft] Commit index advanced to {}", commitIndex);
                break;
            }
        }
    }

    /**
     * 应用已提交的日志条目到状态机。
     */
    private void applyCommittedEntries() {
        while (lastApplied < commitIndex && lastApplied < logEntries.size()) {
            int idx = (int) lastApplied;
            if (idx < logEntries.size()) {
                RaftLogEntry entry = logEntries.get(idx);
                if (memoryApplyCallback != null) {
                    try {
                        memoryApplyCallback.accept(entry);
                    } catch (Exception e) {
                        log.warn("[Raft] Memory apply error: {}", e.getMessage());
                    }
                }
                lastApplied++;
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Leader 心跳
    // ════════════════════════════════════════════════════════════════

    /**
     * Leader 心跳 — 定期向所有 Follower 发送 AppendEntries。
     */
    private void leaderHeartbeat() {
        if (role != Role.LEADER || !running) return;

        String payload = "{\"leaderId\":\"" + nodeId + "\""
                + ",\"term\":" + currentTerm
                + ",\"commitIndex\":" + commitIndex
                + ",\"logSize\":" + logEntries.size()
                + ",\"load\":" + nodeLoad.getOrDefault(nodeId, 0)
                + "}";

        for (ClusterPeer peer : peers.values()) {
            if (peer.isConnected()) {
                peer.sendAsync(RaftMessage.appendEntries(
                        nodeId, peer.nodeId(), currentTerm, payload));
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  全局算力池 (Global Compute Pool)
    // ════════════════════════════════════════════════════════════════

    /**
     * 派发任务到集群 — Leader 选择最空闲的节点执行。
     * <p>
     * 类比分布式任务队列：当本机 TaskQueue 爆满或算力不足时，
     * 将 AgentTask 派发给集群中最空闲的节点。
     *
     * @param taskPayload 任务描述（JSON）
     * @return 任务 ID，用于获取结果
     */
    public String dispatchTask(String taskPayload) {
        String taskId = "task-" + System.nanoTime();

        if (role == Role.LEADER) {
            // Leader：选择最空闲的节点派发
            String targetNode = selectLeastLoadedNode();

            if (targetNode != null && !targetNode.equals(nodeId)) {
                ClusterPeer peer = peers.get(targetNode);
                if (peer != null && peer.isConnected()) {
                    String payload = "{\"taskId\":\"" + taskId + "\",\"task\":" + taskPayload + "}";
                    peer.sendAsync(RaftMessage.taskDispatch(nodeId, targetNode, payload));
                    totalTasksDispatched.incrementAndGet();

                    log.info("[Raft] Task dispatched: taskId={}, target={}", taskId, targetNode);
                } else {
                    // 目标节点不可用，本地执行
                    log.warn("[Raft] Target node {} unavailable, executing locally", targetNode);
                }
            }
            // 如果 Leader 是最空闲的或无可用节点，本地执行
        } else if (leaderId != null) {
            // Follower：转发给 Leader
            ClusterPeer leaderPeer = peers.get(leaderId);
            if (leaderPeer != null && leaderPeer.isConnected()) {
                String payload = "{\"taskId\":\"" + taskId + "\",\"source\":\"" + nodeId + "\",\"task\":" + taskPayload + "}";
                leaderPeer.sendAsync(RaftMessage.taskDispatch(nodeId, leaderId, payload));
                totalTasksDispatched.incrementAndGet();
            }
        }

        return taskId;
    }

    /**
     * 选择最空闲的节点。
     */
    private String selectLeastLoadedNode() {
        String leastLoaded = nodeId;
        int minLoad = nodeLoad.getOrDefault(nodeId, 0);

        for (Map.Entry<String, Integer> entry : nodeLoad.entrySet()) {
            if (entry.getValue() < minLoad) {
                minLoad = entry.getValue();
                leastLoaded = entry.getKey();
            }
        }

        return leastLoaded;
    }

    /**
     * 更新本节点负载。
     */
    public void updateLocalLoad(int load) {
        nodeLoad.put(nodeId, load);
    }

    /**
     * 获取远程任务结果。
     */
    public CompletableFuture<String> getTaskResult(String taskId) {
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingRemoteTasks.put(taskId, future);
        return future;
    }

    /**
     * 处理任务派发消息。
     */
    private void handleTaskDispatch(RaftMessage msg) {
        log.info("[Raft] Task dispatch received from {}", msg.fromNodeId());

        if (role == Role.LEADER) {
            // Leader 收到 Follower 的任务请求，重新派发
            dispatchTask(msg.payload());
        } else {
            // Follower 收到 Leader 的任务派发，执行任务
            // (实际执行由 TaskScheduler 回调处理)
            log.info("[Raft] Task assigned to this node: {}", msg.payload());
        }
    }

    /**
     * 处理任务结果消息。
     */
    private void handleTaskResult(RaftMessage msg) {
        // 解析 taskId 并完成 CompletableFuture
        String payload = msg.payload();
        String taskId = extractField(payload, "taskId");
        String result = extractField(payload, "result");

        CompletableFuture<String> future = pendingRemoteTasks.remove(taskId);
        if (future != null) {
            future.complete(result);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  记忆状态机复制 (Semantic State Machine Replication)
    // ════════════════════════════════════════════════════════════════

    /**
     * 复制记忆到集群 — 将高价值知识以 Raft 日志形式广播。
     * <p>
     * 类比 Raft 的 AppendEntries RPC：当任何一个节点学到了
     * 新的高价值知识，它以 Raft 日志的形式向全网广播。
     * 一旦半数以上节点确认，这块"记忆"就永远刻入了整个
     * AIOS 集群的潜意识中。
     *
     * @param query     知识的查询键
     * @param response  知识的内容
     * @param metadata  元数据（情绪标签等）
     */
    public void replicateMemory(String query, String response, Map<String, Object> metadata) {
        if (role != Role.LEADER) {
            // Follower 转发给 Leader
            if (leaderId != null) {
                ClusterPeer leaderPeer = peers.get(leaderId);
                if (leaderPeer != null && leaderPeer.isConnected()) {
                    String payload = "{\"query\":\"" + escape(query) + "\""
                            + ",\"response\":\"" + escape(response) + "\""
                            + ",\"source\":\"" + nodeId + "\"}";
                    leaderPeer.sendAsync(RaftMessage.memoryReplicate(nodeId, currentTerm, payload));
                }
            }
            return;
        }

        // Leader：追加日志条目
        RaftLogEntry entry = new RaftLogEntry(
                currentTerm, logEntries.size() + 1,
                RaftLogEntry.Type.MEMORY, query, response,
                metadata != null ? new HashMap<>(metadata) : new HashMap<>(),
                System.currentTimeMillis()
        );

        logEntries.add(entry);
        totalLogEntries.incrementAndGet();
        totalMemoryReplicated.incrementAndGet();

        log.info("[Raft] Memory replicated: idx={}, term={}, query={}", entry.index, entry.term,
                query.length() > 50 ? query.substring(0, 50) + "..." : query);

        // 广播到 Follower
        String payload = "{\"index\":" + entry.index
                + ",\"term\":" + entry.term
                + ",\"query\":\"" + escape(query) + "\""
                + ",\"response\":\"" + escape(response) + "\""
                + "}";

        for (ClusterPeer peer : peers.values()) {
            if (peer.isConnected()) {
                peer.sendAsync(RaftMessage.appendEntries(
                        nodeId, peer.nodeId(), currentTerm, payload));
            }
        }

        // 本地立即应用
        if (memoryApplyCallback != null) {
            memoryApplyCallback.accept(entry);
        }
    }

    /**
     * 处理记忆复制消息。
     */
    private void handleMemoryReplicate(RaftMessage msg) {
        if (role == Role.LEADER) {
            // Leader 收到 Follower 的记忆复制请求
            String query = extractField(msg.payload(), "query");
            String response = extractField(msg.payload(), "response");
            if (query != null && response != null) {
                replicateMemory(query, response, null);
            }
        } else {
            // Follower 收到 Leader 的记忆复制
            log.info("[Raft] Memory replication received from Leader");
        }
    }

    /**
     * 设置记忆应用回调 — 当 Raft 日志被提交时调用。
     */
    public void setMemoryApplyCallback(Consumer<RaftLogEntry> callback) {
        this.memoryApplyCallback = callback;
    }

    // ════════════════════════════════════════════════════════════════
    //  节点加入与集群状态
    // ════════════════════════════════════════════════════════════════

    private void handleNodeJoin(RaftMessage msg) {
        log.info("[Raft] Node join request from {}", msg.fromNodeId());

        if (role == Role.LEADER) {
            // Leader 处理节点加入请求
            String payload = "{\"leaderId\":\"" + nodeId + "\""
                    + ",\"term\":" + currentTerm
                    + ",\"clusterSize\":" + clusterSize()
                    + ",\"logSize\":" + logEntries.size()
                    + "}";

            ClusterPeer peer = peers.get(msg.fromNodeId());
            if (peer != null && peer.isConnected()) {
                peer.sendAsync(new RaftMessage(RaftMessage.Type.NODE_JOIN_RESPONSE,
                        currentTerm, nodeId, msg.fromNodeId(), payload, System.currentTimeMillis()));
            }
        }
    }

    private void handleClusterStatus(RaftMessage msg) {
        // 更新节点负载信息
        String fromNode = msg.fromNodeId();
        String loadStr = extractField(msg.payload(), "load");
        if (loadStr != null && !loadStr.isEmpty()) {
            try {
                nodeLoad.put(fromNode, Integer.parseInt(loadStr));
            } catch (NumberFormatException ignored) {}
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  网络服务
    // ════════════════════════════════════════════════════════════════

    private void startServer(int port) {
        try {
            serverSocket = new ServerSocket(port);

            Thread acceptThread = new Thread(() -> {
                while (running && !serverSocket.isClosed()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        String remoteId = clientSocket.getInetAddress().getHostAddress()
                                + ":" + clientSocket.getPort();

                        ClusterPeer peer = new ClusterPeer(remoteId, clientSocket);
                        peers.put(remoteId, peer);
                        peer.startReceiving(this::handleMessage);

                        log.info("[Raft] Accepted connection from {}", remoteId);
                    } catch (IOException e) {
                        if (running) {
                            log.warn("[Raft] Accept error: {}", e.getMessage());
                        }
                    }
                }
            }, "aios-raft-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();

        } catch (IOException e) {
            log.error("[Raft] Failed to start server on port {}: {}", port, e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  统计与报告
    // ════════════════════════════════════════════════════════════════

    public String nodeId() { return nodeId; }
    public Role role() { return role; }
    public long currentTerm() { return currentTerm; }
    public String leaderId() { return leaderId; }
    public boolean isLeader() { return role == Role.LEADER; }
    public long commitIndex() { return commitIndex; }
    public int logSize() { return logEntries.size(); }
    public long totalElections() { return totalElections.get(); }
    public long totalTasksDispatched() { return totalTasksDispatched.get(); }
    public long totalMemoryReplicated() { return totalMemoryReplicated.get(); }

    public String getClusterReport() {
        return """
                ┌─ SemanticRaftNode Cluster Report ───────────────────
                │  Node ID             : %s
                │  Role                : %s
                │  Current Term        : %d
                │  Leader              : %s
                │  Cluster Size        : %d
                │  Log Entries         : %d
                │  Commit Index        : %d
                │  Last Applied        : %d
                │  Total Elections     : %d
                │  Tasks Dispatched    : %d
                │  Memory Replicated   : %d
                │  Connected Peers     : %d
                │  Node Load Map       : %s
                └─────────────────────────────────────────────────"""
                .formatted(nodeId, role, currentTerm, leaderId != null ? leaderId : "none",
                        clusterSize(), logEntries.size(), commitIndex, lastApplied,
                        totalElections.get(), totalTasksDispatched.get(),
                        totalMemoryReplicated.get(),
                        peers.values().stream().filter(ClusterPeer::isConnected).count(),
                        nodeLoad);
    }

    // ── 内部辅助 ──

    private String extractField(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int start = json.indexOf(pattern);
        if (start < 0) {
            // 尝试无引号格式
            pattern = "\"" + key + "\":";
            start = json.indexOf(pattern);
            if (start < 0) return "";
            start += pattern.length();
            int end = json.indexOf(",", start);
            if (end < 0) end = json.indexOf("}", start);
            return end > start ? json.substring(start, end).strip() : "";
        }
        start += pattern.length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : "";
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    // ════════════════════════════════════════════════════════════════
    //  数据结构
    // ════════════════════════════════════════════════════════════════

    /**
     * Raft 日志条目 — 记录一次状态机变更。
     */
    public record RaftLogEntry(
            /** 任期号 */
            long term,
            /** 日志索引 */
            long index,
            /** 条目类型 */
            Type type,
            /** 查询键（记忆的索引） */
            String query,
            /** 响应内容（记忆的值） */
            String response,
            /** 元数据 */
            Map<String, Object> metadata,
            /** 时间戳 */
            long timestamp
    ) {
        /** 日志条目类型 */
        public enum Type {
            /** 记忆复制 — 新知识广播 */
            MEMORY,
            /** 配置变更 — 集群拓扑变更 */
            CONFIG,
            /** 任务日志 — 远程任务记录 */
            TASK
        }
    }
}
