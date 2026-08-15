package com.ouisani.aios.core.cgroup;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.audit.UnifiedAuditLog;
import com.ouisani.aios.core.memory.SomWindowController;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token Cgroup 管理器 — AIOS 的资源隔离与 OOM Kill 机制。
 * <p>
 * AIOS 的"内存"消耗本质上是 Token 消耗。CgroupManager 为每个 Agent 进程
 * 分配 Token Quota（配额），一旦某个 Agent 陷入"无限自我反思"的死循环，
 * 在短时间内疯狂消耗 Token，Cgroup 立即触发 OOM Kill。
 *
 * <h3>OS 类比: Linux Cgroup + OOM Killer</h3>
 * <ul>
 *   <li>Linux cgroup → CgroupNode（层级配额树）</li>
 *   <li>memory.limit_in_bytes → tokenQuota（Token 硬限制）</li>
 *   <li>memory.soft_limit_in_bytes → softLimit（Token 软限制，触发压缩）</li>
 *   <li>OOM Killer → {@link #oomKill(String)}（无情 Kill Agent 进程）</li>
 *   <li>memory.oom_control → {@link #disableOomKill(String)}（禁用 OOM Kill）</li>
 * </ul>
 *
 * <h3>Token 消费路径</h3>
 * <pre>
 * Agent → LLM 调用 → CgroupNode.consumeTokens()
 *   → 超过硬限制 → TokenOomException → CgroupManager.oomKill()
 *   → 超过软限制 → TokenSoftOomException → 触发 TokenZram 压缩
 * </pre>
 */
public final class CgroupManager {

    private static final Logger log = LoggerFactory.getLogger(CgroupManager.class);

    public static final ThreadLocal<CgroupNode> CURRENT_CGROUP = ThreadLocal.withInitial(() -> null);

    private static final class Holder {
        static final CgroupManager INSTANCE = new CgroupManager();
    }

    public static CgroupManager instance() {
        return Holder.INSTANCE;
    }

    private final Map<String, CgroupNode> nodes = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;

    /** OOM Kill 开关（per agent） — 设为 true 则不 Kill，只记录 */
    private final Set<String> oomKillDisabled = ConcurrentHashMap.newKeySet();

    /** OOM Kill 统计 */
    private final Map<String, Long> oomKillCount = new ConcurrentHashMap<>();

    private CgroupManager() {
    }

    public void init() {
        if (initialized) return;

        CgroupNode root = new CgroupNode("aios-root", 1_000_000);
        nodes.put("aios-root", root);

        CgroupNode agents = new CgroupNode("agents", 500_000, root);
        nodes.put("agents", agents);

        CgroupNode system = new CgroupNode("system", 200_000, root);
        nodes.put("system", system);

        CgroupNode tools = new CgroupNode("tools", 300_000, agents);
        nodes.put("tools", tools);

        initialized = true;
        log.info("[CgroupManager] Initialized with default hierarchy: root(1M) → agents(500K), system(200K), tools(300K)");
    }

    public CgroupNode createNode(String name, long quota) {
        return createNode(name, quota, null);
    }

    public CgroupNode createNode(String name, long quota, String parentName) {
        CgroupNode parent = parentName != null ? nodes.get(parentName) : null;
        CgroupNode node = new CgroupNode(name, quota, parent);
        nodes.put(name, node);
        log.info("[CgroupManager] Created node: name={}, quota={}, parent={}", name, quota, parentName);
        return node;
    }

    public CgroupNode getNode(String name) {
        return nodes.get(name);
    }

    public CgroupNode getOrCreateAgentCgroup(int agentId) {
        String name = "agent_" + agentId;
        return nodes.computeIfAbsent(name, n -> {
            CgroupNode parent = nodes.getOrDefault("agents", nodes.get("aios-root"));
            long defaultQuota = 50_000;
            log.info("[CgroupManager] Auto-created cgroup for agent_{}: quota={}", agentId, defaultQuota);
            return new CgroupNode(n, defaultQuota, parent);
        });
    }

    /**
     * 为指定 Agent 设置 Token 配额。
     * <p>
     * 等价于 Linux 的 {@code echo 50000 > /sys/fs/cgroup/memory/agent_42/memory.limit_in_bytes}
     *
     * @param agentId Agent 的 PID
     * @param quota   Token 配额
     */
    public void setAgentQuota(int agentId, long quota) {
        CgroupNode node = getOrCreateAgentCgroup(agentId);
        node.setTokenQuota(quota);
        log.info("[CgroupManager] Quota set: agent_{} → {} tokens", agentId, quota);
    }

    /**
     * 获取指定 Agent 的 Token 使用率。
     *
     * @param agentId Agent 的 PID
     * @return 使用率百分比 (0-100)
     */
    public int getAgentUsagePercent(int agentId) {
        CgroupNode node = getOrCreateAgentCgroup(agentId);
        if (node.tokenQuota() == 0) return 0;
        return (int) ((node.tokenConsumed() * 100) / node.tokenQuota());
    }

    // ════════════════════════════════════════════════════════════════
    //  OOM Kill — Token 爆炸时的无情 Kill
    // ════════════════════════════════════════════════════════════════

    /**
     * OOM Kill：当 Agent 的 Token 消耗超过硬限制时，无情地 Kill 该 Agent。
     * <p>
     * 这是 AIOS 的 OOM Killer — 当 Agent 陷入"无限自我反思"死循环，
     * 疯狂消耗 Token 时，Cgroup 立即触发 Kill，保护系统算力。
     * <p>
     * 等价于 Linux 的 OOM Killer：
     * <pre>
     * Out of memory: Kill process 1042 (agent_reflect) score 950 or sacrifice child
     * Killed process 1042 (agent_reflect) total-vm:50000kB, anon-rss:49980kB
     * </pre>
     *
     * @param agentId 要 Kill 的 Agent 标识
     */
    public void oomKill(String agentId) {
        if (oomKillDisabled.contains(agentId)) {
            log.warn("[CgroupManager] Agent 的 OOM Kill 已禁用，仅记录日志，agent={}", agentId);
            SemanticEtw.getInstance().logEvent("CGROUP", "OOM_KILL_DISABLED",
                    "agent=" + agentId + " reason=oom_control_disabled");
            return;
        }

        oomKillCount.merge(agentId, 1L, Long::sum);

        // 审计追踪
        SemanticEtw.getInstance().logEvent("CGROUP", "OOM_KILL",
                "agent=" + agentId + " totalKills=" + oomKillCount.getOrDefault(agentId, 0L));

        // ── P0: 接入 UnifiedAuditLog 跨层联合审计链（按 traceId 与 permission/sandbox 关联）──
        UnifiedAuditLog.append(
                UnifiedAuditLog.LAYER_CGROUP,
                "OOM_KILL",
                agentId,
                agentId,
                "token quota exceeded; totalKills=" + oomKillCount.getOrDefault(agentId, 0L));

        log.error("[CgroupManager] ╔══════════════════════════════════════════════════╗");
        log.error("[CgroupManager] ║  OOM KILL: Agent '{}' exceeded Token quota!      ║", agentId);
        log.error("[CgroupManager] ║  This Agent is being terminated to protect       ║");
        log.error("[CgroupManager] ║  system compute resources.                         ║");
        log.error("[CgroupManager] ╚══════════════════════════════════════════════════╝");

        System.out.printf("  ╔══════════════════════════════════════════════════╗%n");
        System.out.printf("  ║  ☠️ [OOM Killer] Agent '%s' TERMINATED!            ║%n", agentId);
        System.out.printf("  ║  Token quota exceeded. Process killed.          ║%n");
        System.out.printf("  ╚══════════════════════════════════════════════════╝%n");

        // 尝试从 TaskScheduler 中取消该 Agent 的任务
        try {
            TaskScheduler scheduler = VfsManager.instance().getTaskScheduler();
            if (scheduler != null) {
                for (AgentTask task : scheduler.activeTasks().values()) {
                    String taskPayload = task.payload();
                    if (agentId.equals(taskPayload) || agentId.equals("agent_" + task.pid())) {
                        task.cancel();
                        log.info("[CgroupManager] OOM Kill: Agent task pid={} cancelled", task.pid());
                        System.out.printf("  ☠️ [OOM Killer] Agent pid=%d cancelled via TaskScheduler%n", task.pid());
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[CgroupManager] OOM Kill: Failed to cancel task for agent={}: {}", agentId, e.getMessage());
        }
    }

    /**
     * 禁用指定 Agent 的 OOM Kill（等价于 memory.oom_control = 1）。
     * <p>
     * 禁用后，当 Agent 超过配额时只记录日志，不 Kill 进程。
     * 用于系统关键 Agent（如 init daemon）。
     */
    public void disableOomKill(String agentId) {
        oomKillDisabled.add(agentId);
        log.info("[CgroupManager] Agent 的 OOM Kill 已禁用，agent={}", agentId);
    }

    /**
     * 重新启用指定 Agent 的 OOM Kill。
     */
    public void enableOomKill(String agentId) {
        oomKillDisabled.remove(agentId);
        log.info("[CgroupManager] Agent 的 OOM Kill 已重新启用，agent={}", agentId);
    }

    /**
     * 获取 OOM Kill 统计。
     */
    public Map<String, Long> getOomKillStats() {
        return Map.copyOf(oomKillCount);
    }

    // ════════════════════════════════════════════════════════════════
    //  SyscallDispatcher 集成: Token 消费网关
    // ════════════════════════════════════════════════════════════════

    /**
     * 在 Syscall 执行前检查并预扣 Token。
     * <p>
     * 这将 CgroupManager 接入了 SyscallDispatcher 的执行路径：
     * 每次 LLM 调用前，先检查 Token 配额是否足够。
     * 如果不足，直接触发 OOM Kill 而不是等到 LLM 返回后。
     *
     * @param agentId       Agent 标识
     * @param estimatedCost 预估的 Token 消耗
     * @return true 如果配额足够，false 如果配额不足
     */
    public boolean preCheckAndReserve(String agentId, long estimatedCost) {
        CgroupNode node = CURRENT_CGROUP.get();
        if (node == null) return true; // 无 cgroup 绑定，放行

        try {
            node.consumeTokens(estimatedCost, agentId);
            return true;
        } catch (TokenOomException e) {
            // 硬 OOM — 触发 Kill
            oomKill(agentId);
            return false;
        } catch (TokenSoftOomException e) {
            // 软 OOM — 触发 SomWindowController 语义折叠（而非直接 Kill）
            SemanticEtw.getInstance().logEvent("CGROUP", "SOFT_OOM",
                    "agent=" + agentId + " node=" + e.cgroupNode()
                    + " consumed=" + e.consumed() + "/" + e.quota());

            // ── P0: 软限决策也接入联合审计链（资源压力事件，可与后续 permission/sandbox 响应关联）──
            UnifiedAuditLog.append(
                    UnifiedAuditLog.LAYER_CGROUP,
                    "SOFT_OOM",
                    agentId,
                    e.cgroupNode(),
                    "consumed=" + e.consumed() + "/" + e.quota() + " — triggered semantic folding");

            // 尝试语义折叠回收 Token
            AgentTask currentTask = TaskScheduler.CURRENT_TASK.get();
            if (currentTask != null) {
                long saved = SomWindowController.instance().triggerFolding(currentTask, node);
                if (saved > 0) {
                    log.info("[CgroupManager] SOM folding recovered {} tokens for agent={}", saved, agentId);
                    // 折叠后重试消费
                    try {
                        node.consumeTokens(estimatedCost, agentId);
                        return true;
                    } catch (TokenOomException ex) {
                        oomKill(agentId);
                        return false;
                    } catch (TokenSoftOomException ex) {
                        return true; // 仍然软 OOM，但允许继续
                    }
                }
            }
            return true; // 允许继续，但 Agent 应该压缩
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  原有 API
    // ════════════════════════════════════════════════════════════════

    public void bindToCurrentThread(String cgroupName) {
        CgroupNode node = nodes.get(cgroupName);
        if (node != null) {
            CURRENT_CGROUP.set(node);
            log.debug("[CgroupManager] Bound cgroup '{}' to current thread", cgroupName);
        } else {
            log.warn("[CgroupManager] Cgroup '{}' not found, thread unbound", cgroupName);
        }
    }

    public void bindToCurrentThread(CgroupNode node) {
        CURRENT_CGROUP.set(node);
    }

    public void unbindFromCurrentThread() {
        CURRENT_CGROUP.remove();
    }

    public CgroupNode currentCgroup() {
        return CURRENT_CGROUP.get();
    }

    public long consumeTokensForCurrentThread(long amount) {
        CgroupNode node = CURRENT_CGROUP.get();
        if (node == null) return 0;
        try {
            node.consumeTokens(amount);
        } catch (TokenOomException e) {
            // 向上传播，让调用者处理
            String agentId = resolveAgentId();
            oomKill(agentId);
            throw e;
        }
        return amount;
    }

    public long estimateAndConsumeForCurrentThread(String text) {
        CgroupNode node = CURRENT_CGROUP.get();
        if (node == null) return 0;
        return node.estimateAndConsume(text);
    }

    private String resolveAgentId() {
        AgentTask task = TaskScheduler.CURRENT_TASK.get();
        if (task != null) return "agent_" + task.pid();
        return "unknown";
    }

    public boolean removeNode(String name) {
        CgroupNode removed = nodes.remove(name);
        if (removed != null) {
            log.info("[CgroupManager] Removed node: {}", name);
            return true;
        }
        return false;
    }

    public Set<String> nodeNames() {
        return Collections.unmodifiableSet(nodes.keySet());
    }

    public void printHierarchy() {
        System.out.println("  ┌─ Cgroup Hierarchy ──────────────────────────────");
        nodes.values().stream()
                .filter(n -> n.parent() == null)
                .forEach(n -> printNode(n, "  │ ", 0));
        System.out.println("  └─────────────────────────────────────────────────");
    }

    private void printNode(CgroupNode node, String prefix, int depth) {
        String indent = "  ".repeat(depth);
        System.out.printf("%s%s ├─ %s [quota=%d, consumed=%d, remaining=%d]%n",
                prefix, indent, node.name(), node.tokenQuota(), node.tokenConsumed(), node.tokenRemaining());
        nodes.values().stream()
                .filter(n -> n.parent() == node)
                .forEach(n -> printNode(n, prefix, depth + 1));
    }
}
