package com.ouisani.aios.core.team;

import com.ouisani.aios.core.network.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 文件原子写入邮箱后端 — 借鉴 OpenHarness 的 TeammateMailbox。
 * <p>
 * OpenHarness 用 {@code .tmp + os.replace()} 实现跨进程原子消息投递，
 * 无需消息中间件。本类将同样的模式引入 AIOS，用于支持多 JVM 实例
 * （SemanticRaftNode 集群模式）的跨进程 Agent 通信。
 * <p>
 * <h3>设计原理</h3>
 * <pre>
 *   Agent A (JVM 1)                    Agent B (JVM 2)
 *      │                                  │
 *      │  1. 写入 .tmp 文件                │
 *      │  2. Files.move(.tmp → .msg,      │
 *      │     ATOMIC_MOVE)                 │
 *      │──────────────────────────────────→│
 *      │                                  │  3. WatchService 检测到 .msg 文件
 *      │                                  │  4. 读取并投递到 AgentMailbox
 *      │                                  │  5. 删除 .msg 文件
 * </pre>
 * <p>
 * <h3>OS 类比</h3>
 * <ul>
 *   <li>Linux Mailbox IPC ({@code /var/spool/mail/}) — 经典的文件邮箱</li>
 *   <li>Redis AOF — 先写临时文件再原子 rename，保证持久化安全</li>
 *   <li>Postfix MTA — 邮件队列基于文件系统，崩溃安全</li>
 * </ul>
 * <p>
 * <h3>使用方式</h3>
 * <pre>{@code
 * // 启用文件邮箱后端（集群模式）
 * FileMailboxBackend backend = FileMailboxBackend.enable();
 *
 * // 投递跨进程消息
 * backend.deliverToFs("agent_002", message);
 *
 * // 启动后台监听线程（虚拟线程）
 * backend.startWatching("agent_001", myMailbox);
 * }</pre>
 * <p>
 * 不修改现有 {@link AgentMailbox} 的任何逻辑，
 * 仅作为可选的跨进程通信后端，与内存队列并行工作。
 *
 * @see AgentMailbox
 * @see MailMessage
 */
public final class FileMailboxBackend {

    private static final Logger log = LoggerFactory.getLogger(FileMailboxBackend.class);

    /** 单例 */
    private static volatile FileMailboxBackend instance;

    /** VFS 邮箱根目录 */
    private static final String MAILBOX_ROOT = "/vfs/mailbox";

    /** 消息文件后缀 */
    private static final String MSG_SUFFIX = ".msg";

    /** 临时文件后缀 — 先写 .tmp 再原子 rename */
    private static final String TMP_SUFFIX = ".tmp";

    /** 已注册的 WatchService 监听线程 */
    private final Map<String, Thread> watchers = new ConcurrentHashMap<>();

    /** 是否已启用 */
    private volatile boolean enabled = false;

    /** 集群节点 ID — 用于区分不同 JVM 实例 */
    private String nodeId = "local";

    private FileMailboxBackend() {}

    public static FileMailboxBackend getInstance() {
        if (instance == null) {
            synchronized (FileMailboxBackend.class) {
                if (instance == null) {
                    instance = new FileMailboxBackend();
                }
            }
        }
        return instance;
    }

    /**
     * 启用文件邮箱后端 — 创建 VFS 邮箱根目录。
     *
     * @param nodeId 当前集群节点 ID
     */
    public void enable(String nodeId) {
        this.nodeId = nodeId != null ? nodeId : "local";
        this.enabled = true;
        try {
            Files.createDirectories(Paths.get(MAILBOX_ROOT));
            log.info("[FileMailbox] 已启用, nodeId={}, root={}", this.nodeId, MAILBOX_ROOT);
        } catch (IOException e) {
            log.error("[FileMailbox] 创建邮箱根目录失败: {}", e.getMessage());
        }
    }

    /** 简化版启用 — 使用默认 nodeId */
    public void enable() {
        enable("local");
    }

    /** 禁用文件邮箱后端 */
    public void disable() {
        this.enabled = false;
        stopAllWatchers();
        log.info("[FileMailbox] 已禁用");
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ════════════════════════════════════════════════════════════════
    //  核心操作 — 投递 / 读取
    // ════════════════════════════════════════════════════════════════

    /**
     * 投递消息到文件系统 — 原子写入保证跨进程安全。
     * <p>
     * 步骤：
     * <ol>
     *   <li>在收件人的 inbox 目录下创建 .tmp 文件</li>
     *   <li>写入消息 JSON 内容</li>
     *   <li>{@code Files.move(.tmp → .msg, ATOMIC_MOVE)} 原子重命名</li>
     * </ol>
     * 如果收件人是本 JVM 内的 Agent，消息同时投递到内存队列。
     *
     * @param receiverId 收件人 Agent ID
     * @param message    邮件消息
     */
    public void deliverToFs(String receiverId, MailMessage message) {
        if (!enabled) {
            log.debug("[FileMailbox] 未启用，跳过文件投递");
            return;
        }

        Path inboxDir = Paths.get(MAILBOX_ROOT, receiverId, "inbox");
        String fileName = System.currentTimeMillis() + "_" + message.getMessageId() + TMP_SUFFIX;
        Path tmpFile = inboxDir.resolve(fileName);
        Path msgFile = Paths.get(tmpFile.toString().replace(TMP_SUFFIX, MSG_SUFFIX));

        try {
            // 确保目录存在
            Files.createDirectories(inboxDir);

            // 1. 写入临时文件
            String json = serializeMessage(message);
            Files.write(tmpFile, json.getBytes(StandardCharsets.UTF_8));

            // 2. 原子重命名 — .tmp → .msg
            Files.move(tmpFile, msgFile, StandardCopyOption.ATOMIC_MOVE);

            log.debug("[FileMailbox] 消息已投递到文件系统: {} → {}", 
                    message.getMessageId(), msgFile);

            // 3. 广播遥测事件
            try {
                String payload = String.format(
                        "{\"eventType\":\"FS_MAIL_DELIVERED\",\"sender\":\"%s\",\"receiver\":\"%s\","
                                + "\"messageId\":\"%s\",\"nodeId\":\"%s\"}",
                        message.getSenderId(), receiverId,
                        message.getMessageId(), nodeId
                );
                EventBus.instance().broadcast("sys.telemetry.events", payload);
            } catch (Exception ignore) {}

        } catch (IOException e) {
            log.error("[FileMailbox] 投递消息失败: {} → {} ({})",
                    message.getSenderId(), receiverId, e.getMessage());
            // 清理残留的临时文件
            try { Files.deleteIfExists(tmpFile); } catch (IOException ignore) {}
        }
    }

    /**
     * 读取收件人 inbox 中的所有消息 — 非阻塞。
     * <p>
     * 读取后删除文件，防止重复消费。
     *
     * @param receiverId 收件人 Agent ID
     * @return 读取到的消息列表（按时间戳排序），如果没有返回空列表
     */
    public List<MailMessage> readFromFs(String receiverId) {
        if (!enabled) return Collections.emptyList();

        Path inboxDir = Paths.get(MAILBOX_ROOT, receiverId, "inbox");
        if (!Files.exists(inboxDir)) return Collections.emptyList();

        List<MailMessage> messages = new ArrayList<>();
        List<Path> msgFiles = new ArrayList<>();

        try (Stream<Path> stream = Files.list(inboxDir)) {
            stream.filter(p -> p.toString().endsWith(MSG_SUFFIX))
                    .forEach(msgFiles::add);
        } catch (IOException e) {
            log.error("[FileMailbox] 读取 inbox 失败: {}", e.getMessage());
            return messages;
        }

        // 按文件名排序（文件名以时间戳开头）
        msgFiles.sort(Comparator.comparing(p -> p.getFileName().toString()));

        for (Path msgFile : msgFiles) {
            try {
                String json = Files.readString(msgFile, StandardCharsets.UTF_8);
                MailMessage msg = deserializeMessage(json);
                if (msg != null) {
                    messages.add(msg);
                }
                // 读取后删除文件
                Files.deleteIfExists(msgFile);
            } catch (IOException e) {
                log.warn("[FileMailbox] 读取消息文件失败: {} ({})", msgFile, e.getMessage());
            }
        }

        if (!messages.isEmpty()) {
            log.debug("[FileMailbox] 从文件系统读取了 {} 条消息 for {}", messages.size(), receiverId);
        }
        return messages;
    }

    // ════════════════════════════════════════════════════════════════
    //  WatchService — 监听文件系统变化，自动投递到内存邮箱
    // ════════════════════════════════════════════════════════════════

    /**
     * 启动 WatchService 监听 — 当文件系统中有新消息时，自动投递到内存邮箱。
     * <p>
     * 使用虚拟线程执行监听循环，不消耗 OS 线程。
     *
     * @param agentId  Agent ID
     * @param mailbox  对应的内存邮箱
     */
    public void startWatching(String agentId, AgentMailbox mailbox) {
        if (!enabled) {
            log.warn("[FileMailbox] 未启用，无法启动监听");
            return;
        }

        if (watchers.containsKey(agentId)) {
            log.debug("[FileMailbox] {} 的监听线程已在运行", agentId);
            return;
        }

        Path inboxDir = Paths.get(MAILBOX_ROOT, agentId, "inbox");
        try {
            Files.createDirectories(inboxDir);
        } catch (IOException e) {
            log.error("[FileMailbox] 创建 inbox 目录失败: {}", e.getMessage());
            return;
        }

        Thread watcherThread = Thread.startVirtualThread(() -> {
            log.info("[FileMailbox] 启动文件监听: agentId={}, inbox={}", agentId, inboxDir);

            try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
                inboxDir.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY);

                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key = watchService.take();

                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;

                        Path filename = (Path) event.context();
                        if (filename == null || !filename.toString().endsWith(MSG_SUFFIX)) {
                            continue;
                        }

                        Path fullPath = inboxDir.resolve(filename);

                        // 短暂延迟，确保文件写入完成
                        Thread.sleep(50);

                        try {
                            String json = Files.readString(fullPath, StandardCharsets.UTF_8);
                            MailMessage msg = deserializeMessage(json);
                            if (msg != null) {
                                mailbox.deliver(msg);
                                log.debug("[FileMailbox] 文件消息已投递到内存邮箱: {} → {}",
                                        msg.getMessageId(), agentId);
                            }
                            Files.deleteIfExists(fullPath);
                        } catch (IOException e) {
                            log.warn("[FileMailbox] 处理文件消息失败: {} ({})",
                                    fullPath, e.getMessage());
                        }
                    }

                    if (!key.reset()) {
                        break;
                    }
                }
            } catch (InterruptedException e) {
                log.info("[FileMailbox] {} 的监听线程被中断", agentId);
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                log.error("[FileMailbox] WatchService 异常: {}", e.getMessage());
            }
        });

        watchers.put(agentId, watcherThread);
    }

    /**
     * 停止指定 Agent 的文件监听。
     */
    public void stopWatching(String agentId) {
        Thread watcher = watchers.remove(agentId);
        if (watcher != null) {
            watcher.interrupt();
            log.info("[FileMailbox] 停止文件监听: agentId={}", agentId);
        }
    }

    /**
     * 停止所有文件监听。
     */
    public void stopAllWatchers() {
        for (String agentId : new ArrayList<>(watchers.keySet())) {
            stopWatching(agentId);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  消息序列化 / 反序列化
    // ════════════════════════════════════════════════════════════════

    /**
     * 序列化 MailMessage 为 JSON 字符串。
     * <p>
     * 格式：
     * <pre>{@code
     * {
     *   "messageId": "uuid",
     *   "senderId": "Architect_01",
     *   "receiverId": "Coder_02",
     *   "type": "TASK_ASSIGN",
     *   "payload": "请实现登录功能",
     *   "timestamp": 1234567890,
     *   "traceId": "trace-xxx",
     *   "priority": "NORMAL",
     *   "sourceNode": "node_1"
     * }
     * }</pre>
     */
    String serializeMessage(MailMessage msg) {
        return String.format(
                "{\"messageId\":\"%s\",\"senderId\":\"%s\",\"receiverId\":\"%s\","
                        + "\"type\":\"%s\",\"payload\":%s,"
                        + "\"timestamp\":%d,\"traceId\":\"%s\",\"priority\":\"%s\","
                        + "\"sourceNode\":\"%s\"}",
                escape(msg.getMessageId()),
                escape(msg.getSenderId()),
                escape(msg.getReceiverId()),
                msg.getType().name(),
                payloadToJson(msg.getPayload()),
                msg.getTimestamp(),
                escape(msg.getTraceId()),
                msg.getPriority().name(),
                escape(nodeId)
        );
    }

    /**
     * 反序列化 JSON 字符串为 MailMessage。
     */
    MailMessage deserializeMessage(String json) {
        try {
            String messageId = extractJsonField(json, "messageId");
            String senderId = extractJsonField(json, "senderId");
            String receiverId = extractJsonField(json, "receiverId");
            String typeStr = extractJsonField(json, "type");
            String payloadStr = extractJsonFieldRaw(json, "payload");
            String traceId = extractJsonField(json, "traceId");
            String priorityStr = extractJsonField(json, "priority");

            MailMessage.MessageType type = MailMessage.MessageType.valueOf(typeStr);
            MailMessage.Priority priority = MailMessage.Priority.valueOf(priorityStr);

            // 去除 payload 的引号（如果是字符串）
            Object payload = payloadStr;
            if (payloadStr != null && payloadStr.startsWith("\"") && payloadStr.endsWith("\"")) {
                payload = payloadStr.substring(1, payloadStr.length() - 1)
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\");
            }

            MailMessage msg = new MailMessage(senderId, receiverId, type, payload, priority);
            return msg;

        } catch (Exception e) {
            log.error("[FileMailbox] 反序列化消息失败: {} ({})", 
                    json.length() > 100 ? json.substring(0, 100) + "..." : json, 
                    e.getMessage());
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  辅助方法
    // ════════════════════════════════════════════════════════════════

    /** 获取邮箱根目录 */
    public String getMailboxRoot() {
        return MAILBOX_ROOT;
    }

    /** 获取当前节点 ID */
    public String getNodeId() {
        return nodeId;
    }

    /**
     * 获取指定 Agent 的 inbox 路径。
     */
    public Path getInboxPath(String agentId) {
        return Paths.get(MAILBOX_ROOT, agentId, "inbox");
    }

    /**
     * 清理指定 Agent 的 inbox — 删除所有消息文件。
     */
    public int clearInbox(String agentId) {
        Path inboxDir = getInboxPath(agentId);
        if (!Files.exists(inboxDir)) return 0;

        int cleared = 0;
        try (Stream<Path> stream = Files.list(inboxDir)) {
            var it = stream.iterator();
            while (it.hasNext()) {
                Files.deleteIfExists(it.next());
                cleared++;
            }
        } catch (IOException e) {
            log.error("[FileMailbox] 清理 inbox 失败: {}", e.getMessage());
        }
        return cleared;
    }

    /**
     * 获取指定 Agent inbox 中的消息数量。
     */
    public int getInboxSize(String agentId) {
        Path inboxDir = getInboxPath(agentId);
        if (!Files.exists(inboxDir)) return 0;

        try (Stream<Path> stream = Files.list(inboxDir)) {
            return (int) stream.filter(p -> p.toString().endsWith(MSG_SUFFIX)).count();
        } catch (IOException e) {
            return 0;
        }
    }

    // ── JSON 辅助 ──

    private static String payloadToJson(Object payload) {
        if (payload == null) return "null";
        String s = payload.toString();
        return "\"" + escape(s) + "\"";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 提取 JSON 字段值（字符串类型字段，带引号）。
     */
    private static String extractJsonField(String json, String field) {
        String pattern = "\"" + field + "\":\"";
        int start = json.indexOf(pattern);
        if (start < 0) return "";
        start += pattern.length();
        int end = start;
        boolean escaped = false;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break;
            }
            end++;
        }
        return json.substring(start, end)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }

    /**
     * 提取 JSON 字段原始值（可能是字符串、数字、null 等）。
     */
    private static String extractJsonFieldRaw(String json, String field) {
        String pattern = "\"" + field + "\":";
        int start = json.indexOf(pattern);
        if (start < 0) return null;
        start += pattern.length();

        // 跳过空白
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        if (start >= json.length()) return null;

        char first = json.charAt(start);
        if (first == '"') {
            // 字符串值
            int end = start + 1;
            boolean escaped = false;
            while (end < json.length()) {
                char c = json.charAt(end);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    break;
                }
                end++;
            }
            return json.substring(start, end + 1);
        } else {
            // 非字符串值（数字、true、false、null）
            int end = start;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
                end++;
            }
            return json.substring(start, end).trim();
        }
    }
}
