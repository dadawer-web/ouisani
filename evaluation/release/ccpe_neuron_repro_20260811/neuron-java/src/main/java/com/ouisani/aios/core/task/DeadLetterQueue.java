package com.ouisani.aios.core.task;

import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 死信队列 (Dead Letter Queue) — 借鉴 n8n 的队列模式与 RabbitMQ 的 DLX 机制。
 * <p>
 * 当一个任务在 WorkflowEngine 中重试/自愈超过最大阈值（默认 3 次）依然失败时：
 * <ol>
 *   <li>系统将其从主队列踢出，放入 DLQ</li>
 *   <li>通过 EventBus 广播红色报警："节点 X 彻底坏死，已进入死信队列"</li>
 *   <li>后台进程继续执行其他不相关的并发 DAG 分支</li>
 *   <li>坚决不让一个老鼠屎阻塞整个 AIOS 操作系统的运行</li>
 * </ol>
 * <p>
 * <h3>OS 类比</h3>
 * <ul>
 *   <li>Linux OOM Killer — 选中"不可救药"的进程，杀掉并记录</li>
 *   <li>Linux hung_task_timeout — 挂起超时的任务被标记并隔离</li>
 *   <li>Kubernetes Eviction — Pod 被驱逐到单独的命名空间</li>
 * </ul>
 * <p>
 * <h3>与现有自愈机制的关系</h3>
 * <ul>
 *   <li>RecoveryOrchestrator — 11 层自愈策略链（华佗自愈）</li>
 *   <li>WorkflowEngine.resumeNode() — 热重启（SIGCONT）</li>
 *   <li>DeadLetterQueue — 华佗救了 3 次都救不活时的最终归宿</li>
 * </ul>
 * <p>
 * 设计原则：不破坏现有逻辑，仅作为"兜底收容所"。
 * WorkflowEngine 和 RecoveryOrchestrator 的代码不做任何修改，
 * 仅在它们判定任务彻底失败时调用 {@code DeadLetterQueue.instance().offer()}。
 *
 * @see DlqEntry
 * @see com.ouisani.aios.core.recovery.RecoveryOrchestrator
 * @see com.ouisani.aios.user.apps.omnifactory.WorkflowEngine
 */
public final class DeadLetterQueue {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterQueue.class);

    /** 单例 */
    private static final DeadLetterQueue INSTANCE = new DeadLetterQueue();

    /** 最大重试/自愈阈值 — 超过此值后进入 DLQ */
    private static final int DEFAULT_MAX_RETRIES = 3;

    /** 死信队列存储 — entryId → DlqEntry */
    private final Map<String, DlqEntry> entries = new ConcurrentHashMap<>();

    /** 按 nodeId 索引 — 快速查找某节点是否已在 DLQ 中 */
    private final Map<String, String> nodeIdToEntryId = new ConcurrentHashMap<>();

    /** 按 workflowId 索引 — 快速查找某工作流的所有死信条目 */
    private final Map<String, Set<String>> workflowIndex = new ConcurrentHashMap<>();

    /** 统计计数器 */
    private final AtomicInteger totalOffered = new AtomicInteger(0);
    private final AtomicInteger totalResolved = new AtomicInteger(0);
    private final AtomicInteger totalDismissed = new AtomicInteger(0);

    /** 最大重试阈值（可配置） */
    private volatile int maxRetries = DEFAULT_MAX_RETRIES;

    /** DLQ 是否启用 */
    private volatile boolean enabled = true;

    private DeadLetterQueue() {
        log.info("[DLQ] Dead Letter Queue 已初始化 (maxRetries={})", maxRetries);
    }

    public static DeadLetterQueue instance() {
        return INSTANCE;
    }

    // ════════════════════════════════════════════════════════════════
    //  核心操作 — Offer / Peek / Acknowledge
    // ════════════════════════════════════════════════════════════════

    /**
     * 将一个彻底失败的任务放入死信队列。
     * <p>
     * 此操作会：
     * <ol>
     *   <li>创建 DlqEntry 并存入队列</li>
     *   <li>通过 EventBus 广播 "sys.dlq.entry_added" 红色报警</li>
     *   <li>通过 SemanticEtw 记录遥测事件</li>
     *   <li>在日志中打印醒目的告警</li>
     * </ol>
     *
     * @param nodeId       失败的节点 ID
     * @param workflowId   工作流 ID
     * @param role         节点角色
     * @param executor     执行器类型
     * @param errorMessage 最终错误消息
     * @param retryCount   重试/自愈次数
     * @param dumpPath     Core Dump 文件路径
     * @return 创建的 DlqEntry
     */
    public DlqEntry offer(String nodeId, String workflowId, String role,
                          String executor, String errorMessage,
                          int retryCount, String dumpPath) {
        if (!enabled) {
            log.warn("[DLQ] 已禁用，拒绝接收条目: nodeId={}", nodeId);
            return null;
        }

        // 如果该节点已在 DLQ 中，不重复添加
        if (nodeIdToEntryId.containsKey(nodeId)) {
            log.debug("[DLQ] 节点 '{}' 已在 DLQ 中，跳过重复添加", nodeId);
            return entries.get(nodeIdToEntryId.get(nodeId));
        }

        DlqEntry entry = DlqEntry.create(
                nodeId, workflowId, role, executor,
                errorMessage, retryCount, dumpPath
        );

        entries.put(entry.entryId(), entry);
        nodeIdToEntryId.put(nodeId, entry.entryId());
        workflowIndex.computeIfAbsent(workflowId, k -> ConcurrentHashMap.newKeySet())
                .add(entry.entryId());
        totalOffered.incrementAndGet();

        // ── 广播红色报警到 EventBus ──
        // 前端 UI 订阅 "sys.dlq.entry_added" 频道，收到后弹出红色告警
        String alertPayload = buildAlertPayload(entry);
        EventBus.instance().broadcast("sys.dlq.entry_added", alertPayload);

        // ── 记录遥测事件 ──
        SemanticEtw.getInstance().logEvent("DLQ", "ENTRY_ADDED",
                "nodeId=" + nodeId + " workflowId=" + workflowId
                        + " retries=" + retryCount + " error=" + truncate(errorMessage, 200));

        // ── 醒目的日志告警 ──
        log.error("╔══════════════════════════════════════════════════╗");
        log.error("║ [DLQ] 节点彻底坏死，已进入死信队列               ║");
        log.error("║   节点: {} ", pad(nodeId, 44));
        log.error("║   工作流: {} ", pad(workflowId, 42));
        log.error("║   重试次数: {} ", pad(String.valueOf(retryCount), 38));
        log.error("║   错误: {} ", pad(truncate(errorMessage, 44), 44));
        log.error("╚══════════════════════════════════════════════════╝");

        System.out.printf("%n⚠️  [DLQ] 节点 '%s' 彻底坏死（重试 %d 次），已进入死信队列%n",
                nodeId, retryCount);
        System.out.printf("    工作流: %s | 错误: %s%n",
                workflowId, truncate(errorMessage, 100));
        System.out.printf("    其他并发 DAG 分支将继续执行，不受影响%n%n");

        return entry;
    }

    /**
     * 检查节点是否已在死信队列中。
     *
     * @param nodeId 节点 ID
     * @return true 如果节点在 DLQ 中
     */
    public boolean contains(String nodeId) {
        return nodeIdToEntryId.containsKey(nodeId);
    }

    /**
     * 获取指定节点的 DLQ 条目。
     */
    public DlqEntry get(String nodeId) {
        String entryId = nodeIdToEntryId.get(nodeId);
        return entryId != null ? entries.get(entryId) : null;
    }

    /**
     * 获取所有死信条目（按时间排序）。
     */
    public List<DlqEntry> getAll() {
        return entries.values().stream()
                .sorted(Comparator.comparingLong(DlqEntry::timestamp))
                .collect(Collectors.toList());
    }

    /**
     * 获取指定工作流的所有死信条目。
     */
    public List<DlqEntry> getByWorkflow(String workflowId) {
        Set<String> entryIds = workflowIndex.get(workflowId);
        if (entryIds == null || entryIds.isEmpty()) {
            return Collections.emptyList();
        }
        return entryIds.stream()
                .map(entries::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingLong(DlqEntry::timestamp))
                .collect(Collectors.toList());
    }

    /**
     * 获取所有 PENDING 状态的条目 — 等待人类处理的条目。
     */
    public List<DlqEntry> getPending() {
        return entries.values().stream()
                .filter(e -> e.status() == DlqEntry.DlqStatus.PENDING)
                .sorted(Comparator.comparingLong(DlqEntry::timestamp))
                .collect(Collectors.toList());
    }

    // ════════════════════════════════════════════════════════════════
    //  人类干预接口 — Retry / Dismiss / Resolve
    // ════════════════════════════════════════════════════════════════

    /**
     * 标记条目为"请求重试" — 人类决定手动重试该节点。
     * <p>
     * 前端 UI 调用此方法后，DLQ 将条目状态改为 RETRY_REQUESTED，
     * 并广播事件。WorkflowEngine 或 RecoveryOrchestrator 可以订阅此事件，
     * 在适当时机重新执行节点。
     *
     * @param nodeId 节点 ID
     * @return true 如果操作成功
     */
    public boolean requestRetry(String nodeId) {
        DlqEntry entry = get(nodeId);
        if (entry == null) {
            log.warn("[DLQ] requestRetry: 节点 '{}' 不在 DLQ 中", nodeId);
            return false;
        }

        DlqEntry updated = entry.withStatus(DlqEntry.DlqStatus.RETRY_REQUESTED);
        entries.put(entry.entryId(), updated);

        EventBus.instance().broadcast("sys.dlq.retry_requested",
                String.format("{\"nodeId\":\"%s\",\"workflowId\":\"%s\",\"entryId\":\"%s\"}",
                        escape(nodeId), escape(entry.workflowId()), escape(entry.entryId())));

        SemanticEtw.getInstance().logEvent("DLQ", "RETRY_REQUESTED",
                "nodeId=" + nodeId + " workflowId=" + entry.workflowId());

        log.info("[DLQ] 节点 '{}' 被标记为 RETRY_REQUESTED（人类请求重试）", nodeId);
        return true;
    }

    /**
     * 标记条目为"已放弃" — 人类决定放弃该节点。
     *
     * @param nodeId 节点 ID
     * @return true 如果操作成功
     */
    public boolean dismiss(String nodeId) {
        DlqEntry entry = get(nodeId);
        if (entry == null) {
            log.warn("[DLQ] dismiss: 节点 '{}' 不在 DLQ 中", nodeId);
            return false;
        }

        DlqEntry updated = entry.withStatus(DlqEntry.DlqStatus.DISMISSED);
        entries.put(entry.entryId(), updated);
        totalDismissed.incrementAndGet();

        EventBus.instance().broadcast("sys.dlq.dismissed",
                String.format("{\"nodeId\":\"%s\",\"workflowId\":\"%s\"}",
                        escape(nodeId), escape(entry.workflowId())));

        SemanticEtw.getInstance().logEvent("DLQ", "DISMISSED",
                "nodeId=" + nodeId + " workflowId=" + entry.workflowId());

        log.info("[DLQ] 节点 '{}' 被标记为 DISMISSED（人类放弃）", nodeId);
        return true;
    }

    /**
     * 标记条目为"已解决" — 节点重试后成功，从 DLQ 移出。
     * <p>
     * 当 WorkflowEngine.resumeNode() 成功恢复一个之前在 DLQ 中的节点时调用此方法。
     *
     * @param nodeId 节点 ID
     * @return true 如果操作成功
     */
    public boolean resolve(String nodeId) {
        DlqEntry entry = get(nodeId);
        if (entry == null) {
            return false;
        }

        DlqEntry updated = entry.withStatus(DlqEntry.DlqStatus.RESOLVED);
        entries.put(entry.entryId(), updated);
        totalResolved.incrementAndGet();

        // 从索引中移除
        nodeIdToEntryId.remove(nodeId);
        Set<String> wfEntries = workflowIndex.get(entry.workflowId());
        if (wfEntries != null) {
            wfEntries.remove(entry.entryId());
            if (wfEntries.isEmpty()) {
                workflowIndex.remove(entry.workflowId());
            }
        }

        EventBus.instance().broadcast("sys.dlq.resolved",
                String.format("{\"nodeId\":\"%s\",\"workflowId\":\"%s\"}",
                        escape(nodeId), escape(entry.workflowId())));

        SemanticEtw.getInstance().logEvent("DLQ", "RESOLVED",
                "nodeId=" + nodeId + " workflowId=" + entry.workflowId());

        log.info("[DLQ] 节点 '{}' 已从死信队列移出（RESOLVED — 重试成功）", nodeId);
        return true;
    }

    /**
     * 从 DLQ 中彻底移除条目（不区分状态）。
     * 用于清理已过期的条目。
     */
    public boolean remove(String nodeId) {
        String entryId = nodeIdToEntryId.remove(nodeId);
        if (entryId == null) return false;

        DlqEntry entry = entries.remove(entryId);
        if (entry != null) {
            Set<String> wfEntries = workflowIndex.get(entry.workflowId());
            if (wfEntries != null) {
                wfEntries.remove(entryId);
                if (wfEntries.isEmpty()) {
                    workflowIndex.remove(entry.workflowId());
                }
            }
        }
        return true;
    }

    /**
     * 清空所有已解决和已放弃的条目。
     */
    public int cleanup() {
        int removed = 0;
        Iterator<Map.Entry<String, DlqEntry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, DlqEntry> e = it.next();
            DlqEntry.DlqStatus status = e.getValue().status();
            if (status == DlqEntry.DlqStatus.RESOLVED
                    || status == DlqEntry.DlqStatus.DISMISSED) {
                nodeIdToEntryId.remove(e.getValue().nodeId());
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.info("[DLQ] 清理了 {} 个已处理的条目", removed);
        }
        return removed;
    }

    // ════════════════════════════════════════════════════════════════
    //  判定辅助 — shouldDeadLetter
    // ════════════════════════════════════════════════════════════════

    /**
     * 判断一个节点是否应该进入死信队列。
     * <p>
     * 当节点的重试/自愈次数超过最大阈值，且不在 DLQ 中时返回 true。
     * WorkflowEngine 和 RecoveryOrchestrator 可以调用此方法进行判断。
     *
     * @param retryCount 当前重试次数
     * @param nodeId     节点 ID
     * @return true 如果节点应该进入 DLQ
     */
    public boolean shouldDeadLetter(int retryCount, String nodeId) {
        if (!enabled) return false;
        if (retryCount < maxRetries) return false;
        return !contains(nodeId);
    }

    /**
     * 判断一个节点是否应该进入死信队列（不考虑当前重试次数）。
     * 用于 RecoveryOrchestrator 在所有策略都失败后的最终判定。
     *
     * @param nodeId 节点 ID
     * @return true 如果节点不在 DLQ 中（即可以放入）
     */
    public boolean canDeadLetter(String nodeId) {
        return enabled && !contains(nodeId);
    }

    // ════════════════════════════════════════════════════════════════
    //  统计与报告
    // ════════════════════════════════════════════════════════════════

    /** DLQ 统计信息 */
    public record DlqStats(
            int totalOffered,
            int totalResolved,
            int totalDismissed,
            int pendingCount,
            int activeEntries,
            int maxRetries,
            boolean enabled
    ) {
        @Override
        public String toString() {
            return String.format(
                    "DlqStats{offered=%d, resolved=%d, dismissed=%d, pending=%d, active=%d, maxRetries=%d, enabled=%s}",
                    totalOffered, totalResolved, totalDismissed, pendingCount, activeEntries, maxRetries, enabled
            );
        }
    }

    public DlqStats stats() {
        int pending = (int) entries.values().stream()
                .filter(e -> e.status() == DlqEntry.DlqStatus.PENDING)
                .count();
        int active = (int) entries.values().stream()
                .filter(e -> e.status() == DlqEntry.DlqStatus.PENDING
                        || e.status() == DlqEntry.DlqStatus.RETRY_REQUESTED)
                .count();
        return new DlqStats(
                totalOffered.get(),
                totalResolved.get(),
                totalDismissed.get(),
                pending,
                active,
                maxRetries,
                enabled
        );
    }

    /**
     * 生成 DLQ 报告 — 供前端 UI 展示。
     */
    public String getReport() {
        DlqStats s = stats();
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"stats\":{");
        sb.append("\"totalOffered\":").append(s.totalOffered()).append(",");
        sb.append("\"totalResolved\":").append(s.totalResolved()).append(",");
        sb.append("\"totalDismissed\":").append(s.totalDismissed()).append(",");
        sb.append("\"pendingCount\":").append(s.pendingCount()).append(",");
        sb.append("\"activeEntries\":").append(s.activeEntries()).append(",");
        sb.append("\"maxRetries\":").append(s.maxRetries()).append(",");
        sb.append("\"enabled\":").append(s.enabled());
        sb.append("},");
        sb.append("\"entries\":[");
        boolean first = true;
        for (DlqEntry entry : getAll()) {
            if (!first) sb.append(",");
            sb.append(entry.toJson());
            first = false;
        }
        sb.append("]}");
        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════════
    //  配置
    // ════════════════════════════════════════════════════════════════

    /**
     * 设置最大重试阈值。
     */
    public void setMaxRetries(int max) {
        this.maxRetries = max;
        log.info("[DLQ] 最大重试阈值已设置为 {}", max);
    }

    /**
     * 获取最大重试阈值。
     */
    public int getMaxRetries() {
        return maxRetries;
    }

    /**
     * 启用/禁用 DLQ。
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        log.info("[DLQ] 已{}", enabled ? "启用" : "禁用");
    }

    /**
     * 检查 DLQ 是否启用。
     */
    public boolean isEnabled() {
        return enabled;
    }

    // ════════════════════════════════════════════════════════════════
    //  内部工具方法
    // ════════════════════════════════════════════════════════════════

    private String buildAlertPayload(DlqEntry entry) {
        return String.format(
                "{\"eventType\":\"DLQ_ENTRY_ADDED\","
                        + "\"severity\":\"CRITICAL\","
                        + "\"message\":\"节点彻底坏死，已进入死信队列\","
                        + "\"entry\":%s}",
                entry.toJson()
        );
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    private static String pad(String s, int width) {
        if (s == null) s = "";
        if (s.length() > width) return s.substring(0, width);
        return String.format("%-" + width + "s", s);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
