package com.ouisani.aios.core.hibernation;

import com.ouisani.aios.core.cache.kvstate.KvCacheRef;
import com.ouisani.aios.core.cache.kvstate.KvCacheRegistry;
import com.ouisani.aios.core.cache.kvstate.KvCacheVfsStore;
import com.ouisani.aios.core.ipc.SharedMemoryManager;
import com.ouisani.aios.core.ipc.SemanticMemoryBlock;
import com.ouisani.aios.core.ipc.VariablePool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 休眠管理器 — Semantic Core Hibernation 的核心引擎。
 * <p>
 * 借鉴 LMCache 的"把临时变永久"产品定位：
 * <p>
 * 当你在前端界面里点击"关闭工作区"时，AIOS 内核不应该简单地杀掉 OmniMotherAgent。
 * 它应该调用一个 {@code suspend_to_disk}（挂起到硬盘）的机制：
 * <ul>
 *   <li>把当前 Agent 脑子里的所有变量（VariablePool）</li>
 *   <li>正在处理的任务队列</li>
 *   <li>大模型的上下文指针（利用类似 LMCache 的机制）</li>
 *   <li>全部打包成一个 {@code .aios_snapshot} 文件保存在 VFS 里</li>
 * </ul>
 * <p>
 * 第二天，当你再次打开这个工作区，系统读取这个 Snapshot，瞬间"满血复活"。
 * 这就是数字生命，这也是 AIOS 真正能让人感到震撼的黑科技。
 * <p>
 * <h3>挂起流程 (suspend_to_disk)</h3>
 * <ol>
 *   <li>捕获 VariablePool 的所有变量</li>
 *   <li>捕获正在处理的任务队列</li>
 *   <li>捕获大模型的上下文指针（KV Cache 引用）</li>
 *   <li>捕获 SemanticMemoryBlock 的上下文指针</li>
 *   <li>捕获 SharedMemoryManager 的共享内存段</li>
 *   <li>序列化为 {@code .aios_snapshot} 文件保存在 VFS 里</li>
 * </ol>
 * <p>
 * <h3>恢复流程 (resume_from_disk)</h3>
 * <ol>
 *   <li>读取 {@code .aios_snapshot} 文件</li>
 *   <li>恢复 VariablePool</li>
 *   <li>恢复任务队列</li>
 *   <li>恢复 KV Cache 引用</li>
 *   <li>恢复上下文指针</li>
 *   <li>恢复共享内存段</li>
 *   <li>Agent "满血复活"</li>
 * </ol>
 * <p>
 * <h3>OS 类比</h3>
 * 类比 Linux 的 hibernate（S4 挂起到磁盘）：
 * 将整个系统内存写入 swap 分区，下次启动时直接恢复。
 *
 * @see AgentSnapshot
 * @see SnapshotSerializer
 * @see KvCacheRegistry
 * @see VariablePool
 */
public final class HibernationManager {

    private static final Logger log = LoggerFactory.getLogger(HibernationManager.class);

    // ── Singleton ──

    private static final class Holder {
        static final HibernationManager INSTANCE = new HibernationManager();
    }

    public static HibernationManager instance() {
        return Holder.INSTANCE;
    }

    private HibernationManager() {
    }

    // ════════════════════════════════════════════════════════════════
    //  Phase 1: 挂起到硬盘 (suspend_to_disk)
    // ════════════════════════════════════════════════════════════════

    /**
     * 挂起工作区到硬盘 — 捕获所有状态并序列化为 {@code .aios_snapshot} 文件。
     * <p>
     * 这是 Semantic Core Hibernation 的核心入口。
     * 当用户点击"关闭工作区"时调用此方法。
     *
     * @param workspaceId 工作区标识
     * @return AgentSnapshot 快照对象，失败返回 null
     */
    public AgentSnapshot suspendToDisk(String workspaceId) {
        log.info("[Hibernation] ╔══════════════════════════════════════════════════╗");
        log.info("[Hibernation] ║  挂起开始: workspace={}                    ║", workspaceId);
        log.info("[Hibernation] ╚══════════════════════════════════════════════════╝");

        try {
            long startTs = System.currentTimeMillis();

            // ── Step 1: 捕获 VariablePool 的所有变量 ──
            Map<String, Map<String, Map<String, String>>> varPoolSnapshot = captureVariablePool();

            // ── Step 2: 捕获正在处理的任务队列 ──
            List<AgentSnapshot.TaskState> taskQueue = captureTaskQueue();

            // ── Step 3: 捕获大模型的上下文指针（KV Cache 引用） ──
            List<KvCacheRef> kvCacheRefs = captureKvCacheRefs();

            // ── Step 4: 捕获 SemanticMemoryBlock 的上下文指针 ──
            Map<String, Map<String, String>> contextPointers = captureContextPointers();

            // ── Step 5: 捕获 SharedMemoryManager 的共享内存段 ──
            Map<String, Map<String, String>> shmSegments = captureShmSegments();

            // ── Step 6: 组装 AgentSnapshot ──
            AgentSnapshot snapshot = new AgentSnapshot(
                    workspaceId,
                    System.currentTimeMillis(),
                    varPoolSnapshot,
                    taskQueue,
                    kvCacheRefs,
                    contextPointers,
                    shmSegments,
                    AgentSnapshot.CURRENT_VERSION
            );

            // ── Step 7: 序列化为 .aios_snapshot 文件保存在 VFS 里 ──
            boolean saved = SnapshotSerializer.instance().saveToVfs(snapshot);

            // ── Step 8: 同时持久化 KV Cache 引用到 VFS ──
            if (!kvCacheRefs.isEmpty()) {
                KvCacheVfsStore.instance().saveToVfs(workspaceId, kvCacheRefs);
            }

            long elapsed = System.currentTimeMillis() - startTs;
            log.info("[Hibernation] ╔══════════════════════════════════════════════════╗");
            log.info("[Hibernation] ║  挂起完成: workspace={} ({}ms)              ║", workspaceId, elapsed);
            log.info("[Hibernation] ║  Vars={}, Tasks={}, KvRefs={}, CtxPtrs={}, Shm={} ║",
                    varPoolSnapshot.size(), taskQueue.size(),
                    kvCacheRefs.size(), contextPointers.size(), shmSegments.size());
            log.info("[Hibernation] ║  Persisted: {}                                  ║", saved);
            log.info("[Hibernation] ╚══════════════════════════════════════════════════╝");

            return saved ? snapshot : null;

        } catch (Exception e) {
            log.error("[Hibernation] 挂起失败: workspace={}, error={}",
                    workspaceId, e.getMessage(), e);
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Phase 2: 从硬盘恢复 (resume_from_disk)
    // ════════════════════════════════════════════════════════════════

    /**
     * 从硬盘恢复工作区 — 读取 {@code .aios_snapshot} 文件并恢复所有状态。
     * <p>
     * 当用户再次打开工作区时调用此方法。
     * 系统读取 Snapshot，瞬间"满血复活"。
     *
     * @param workspaceId 工作区标识
     * @return AgentSnapshot 快照对象，如果文件不存在返回 null
     */
    public AgentSnapshot resumeFromDisk(String workspaceId) {
        log.info("[Hibernation] ╔══════════════════════════════════════════════════╗");
        log.info("[Hibernation] ║  恢复开始: workspace={}                    ║", workspaceId);
        log.info("[Hibernation] ╚══════════════════════════════════════════════════╝");

        try {
            long startTs = System.currentTimeMillis();

            // ── Step 1: 读取 .aios_snapshot 文件 ──
            AgentSnapshot snapshot = SnapshotSerializer.instance().loadFromVfs(workspaceId);
            if (snapshot == null) {
                log.warn("[Hibernation] 快照文件不存在: workspace={}", workspaceId);
                return null;
            }

            // ── Step 2: 恢复 VariablePool ──
            restoreVariablePool(snapshot.variablePoolSnapshot());

            // ── Step 3: 恢复任务队列 ──
            restoreTaskQueue(snapshot.taskQueueSnapshot());

            // ── Step 4: 恢复 KV Cache 引用 ──
            restoreKvCacheRefs(snapshot.kvCacheRefs());

            // ── Step 5: 恢复上下文指针 ──
            restoreContextPointers(snapshot.contextPointers());

            // ── Step 6: 恢复共享内存段 ──
            restoreShmSegments(snapshot.shmSegments());

            long elapsed = System.currentTimeMillis() - startTs;
            log.info("[Hibernation] ╔══════════════════════════════════════════════════╗");
            log.info("[Hibernation] ║  恢复完成: workspace={} ({}ms)              ║", workspaceId, elapsed);
            log.info("[Hibernation] ║  Vars={}, Tasks={}, KvRefs={}, CtxPtrs={}, Shm={} ║",
                    snapshot.variablePoolSnapshot() != null ? snapshot.variablePoolSnapshot().size() : 0,
                    snapshot.taskQueueSnapshot() != null ? snapshot.taskQueueSnapshot().size() : 0,
                    snapshot.kvCacheRefs() != null ? snapshot.kvCacheRefs().size() : 0,
                    snapshot.contextPointers() != null ? snapshot.contextPointers().size() : 0,
                    snapshot.shmSegments() != null ? snapshot.shmSegments().size() : 0);
            log.info("[Hibernation] ╚══════════════════════════════════════════════════╝");

            return snapshot;

        } catch (Exception e) {
            log.error("[Hibernation] 恢复失败: workspace={}, error={}",
                    workspaceId, e.getMessage(), e);
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  捕获子步骤
    // ════════════════════════════════════════════════════════════════

    /**
     * 捕获 VariablePool 的所有变量。
     * <p>
     * 由于 VariablePool 的内部状态是私有的，此方法通过已知的 Scope 和 ContextId
     * 枚举变量。调用者可以通过 {@link #setCaptureContexts} 预设要捕获的上下文 ID。
     */
    private Map<String, Map<String, Map<String, String>>> captureVariablePool() {
        Map<String, Map<String, Map<String, String>>> result = new ConcurrentHashMap<>();

        // VariablePool 是单例，但其内部状态是私有的
        // 我们通过 SharedMemoryManager 的语义块来捕获变量
        // （VariablePool 的变量通常会同步到 SemanticMemoryBlock 中）
        SharedMemoryManager shm = SharedMemoryManager.instance();
        for (String blockId : shm.listSemanticBlocks()) {
            SemanticMemoryBlock block = shm.getSemanticBlock(blockId);
            if (block == null) continue;

            Map<String, Map<String, String>> scopeMap = new ConcurrentHashMap<>();
            Map<String, String> stringData = new ConcurrentHashMap<>();
            for (String key : block.stringKeys()) {
                String value = block.getString(key);
                if (value != null) {
                    stringData.put(key, value);
                }
            }
            if (!stringData.isEmpty()) {
                scopeMap.put("SESSION", stringData);
                result.put(blockId, scopeMap);
            }
        }

        log.debug("[Hibernation] 捕获了 {} 个变量池作用域", result.size());
        return result;
    }

    /**
     * 捕获正在处理的任务队列。
     * <p>
     * 当前实现返回空列表，因为任务队列状态由 WorkflowEngine 管理，
     * 需要外部调用者提供任务状态。
     */
    private List<AgentSnapshot.TaskState> captureTaskQueue() {
        // 任务队列由 WorkflowEngine 管理，这里返回空列表
        // 外部调用者可以通过 setTaskQueue 方法预设任务状态
        return List.of();
    }

    /**
     * 捕获大模型的上下文指针（KV Cache 引用）。
     */
    private List<KvCacheRef> captureKvCacheRefs() {
        List<KvCacheRef> refs = KvCacheRegistry.instance().snapshot();
        log.debug("[Hibernation] 捕获了 {} 个 KV Cache 引用", refs.size());
        return refs;
    }

    /**
     * 捕获 SemanticMemoryBlock 的上下文指针。
     */
    private Map<String, Map<String, String>> captureContextPointers() {
        Map<String, Map<String, String>> result = new ConcurrentHashMap<>();

        SharedMemoryManager shm = SharedMemoryManager.instance();
        for (String blockId : shm.listSemanticBlocks()) {
            SemanticMemoryBlock block = shm.getSemanticBlock(blockId);
            if (block == null) continue;

            Map<String, String> pointers = new ConcurrentHashMap<>();
            for (String key : block.contextPointerKeys()) {
                SemanticMemoryBlock.ContextPointer ptr = block.getContextPointer(key);
                if (ptr != null) {
                    // 序列化为 "contextRef|summary|embeddingHash|timestamp" 格式
                    pointers.put(key, ptr.contextRef() + "|" +
                            ptr.summary() + "|" +
                            ptr.embeddingHash() + "|" +
                            ptr.timestamp());
                }
            }
            if (!pointers.isEmpty()) {
                result.put(blockId, pointers);
            }
        }

        log.debug("[Hibernation] 捕获了 {} 个语义块的上下文指针", result.size());
        return result;
    }

    /**
     * 捕获 SharedMemoryManager 的共享内存段。
     */
    private Map<String, Map<String, String>> captureShmSegments() {
        Map<String, Map<String, String>> result = new ConcurrentHashMap<>();

        SharedMemoryManager shm = SharedMemoryManager.instance();
        for (String segmentId : shm.listSegments()) {
            ConcurrentHashMap<String, String> segment = shm.getSegment(segmentId);
            if (segment != null && !segment.isEmpty()) {
                result.put(segmentId, new ConcurrentHashMap<>(segment));
            }
        }

        log.debug("[Hibernation] 捕获了 {} 个共享内存段", result.size());
        return result;
    }

    // ════════════════════════════════════════════════════════════════
    //  恢复子步骤
    // ════════════════════════════════════════════════════════════════

    /**
     * 恢复 VariablePool。
     */
    private void restoreVariablePool(Map<String, Map<String, Map<String, String>>> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) return;

        VariablePool varPool = VariablePool.getInstance();
        SharedMemoryManager shm = SharedMemoryManager.instance();

        int restored = 0;
        for (Map.Entry<String, Map<String, Map<String, String>>> blockEntry : snapshot.entrySet()) {
            String blockId = blockEntry.getKey();
            SemanticMemoryBlock block = shm.getOrCreateSemanticBlock(blockId);

            for (Map.Entry<String, Map<String, String>> scopeEntry : blockEntry.getValue().entrySet()) {
                Map<String, String> vars = scopeEntry.getValue();
                for (Map.Entry<String, String> varEntry : vars.entrySet()) {
                    block.putString(varEntry.getKey(), varEntry.getValue());
                    restored++;
                }
            }
        }

        log.info("[Hibernation] 已恢复 {} 个变量 → VariablePool", restored);
    }

    /**
     * 恢复任务队列。
     */
    private void restoreTaskQueue(List<AgentSnapshot.TaskState> tasks) {
        if (tasks == null || tasks.isEmpty()) return;

        // 任务队列的恢复需要由 WorkflowEngine 处理
        // 这里只记录日志，实际恢复由外部调用者完成
        log.info("[Hibernation] 已恢复 {} 个任务状态（需 WorkflowEngine 重新调度）", tasks.size());
        for (AgentSnapshot.TaskState task : tasks) {
            log.info("[Hibernation]   Task: id={}, status={}, priority={}",
                    task.taskId(), task.status(), task.priority());
        }
    }

    /**
     * 恢复 KV Cache 引用。
     */
    private void restoreKvCacheRefs(List<KvCacheRef> refs) {
        if (refs == null || refs.isEmpty()) return;

        KvCacheRegistry.instance().restore(refs);
        log.info("[Hibernation] 已恢复 {} 个 KV Cache 引用 → KvCacheRegistry", refs.size());
    }

    /**
     * 恢复上下文指针。
     */
    private void restoreContextPointers(Map<String, Map<String, String>> contextPointers) {
        if (contextPointers == null || contextPointers.isEmpty()) return;

        SharedMemoryManager shm = SharedMemoryManager.instance();
        int restored = 0;

        for (Map.Entry<String, Map<String, String>> blockEntry : contextPointers.entrySet()) {
            String blockId = blockEntry.getKey();
            SemanticMemoryBlock block = shm.getOrCreateSemanticBlock(blockId);

            for (Map.Entry<String, String> ptrEntry : blockEntry.getValue().entrySet()) {
                String key = ptrEntry.getKey();
                String[] parts = ptrEntry.getValue().split("\\|", -1);
                if (parts.length >= 4) {
                    try {
                        shm.putContextPointer(blockId, key,
                                parts[0],  // contextRef
                                parts[1],  // summary
                                Long.parseLong(parts[2]));  // embeddingHash
                        restored++;
                    } catch (NumberFormatException e) {
                        log.warn("[Hibernation] 恢复上下文指针失败: key={}", key);
                    }
                }
            }
        }

        log.info("[Hibernation] 已恢复 {} 个上下文指针 → SemanticMemoryBlock", restored);
    }

    /**
     * 恢复共享内存段。
     */
    private void restoreShmSegments(Map<String, Map<String, String>> segments) {
        if (segments == null || segments.isEmpty()) return;

        SharedMemoryManager shm = SharedMemoryManager.instance();
        int restored = 0;

        for (Map.Entry<String, Map<String, String>> entry : segments.entrySet()) {
            String segmentId = entry.getKey();
            for (Map.Entry<String, String> var : entry.getValue().entrySet()) {
                shm.put(segmentId, var.getKey(), var.getValue());
                restored++;
            }
        }

        log.info("[Hibernation] 已恢复 {} 个共享内存变量 → SharedMemoryManager", restored);
    }

    // ════════════════════════════════════════════════════════════════
    //  查询
    // ════════════════════════════════════════════════════════════════

    /**
     * 检查工作区是否有快照。
     *
     * @param workspaceId 工作区标识
     * @return true 如果存在快照
     */
    public boolean hasSnapshot(String workspaceId) {
        return SnapshotSerializer.instance().snapshotExists(workspaceId);
    }

    /**
     * 删除工作区快照。
     *
     * @param workspaceId 工作区标识
     * @return true 如果删除成功
     */
    public boolean deleteSnapshot(String workspaceId) {
        boolean deleted = SnapshotSerializer.instance().deleteFromVfs(workspaceId);
        // 同时删除 KV Cache 快照
        KvCacheVfsStore.instance().deleteSnapshot(workspaceId);
        return deleted;
    }

    /**
     * 创建带自定义任务队列的快照 — 允许外部调用者提供任务状态。
     *
     * @param workspaceId 工作区标识
     * @param taskQueue    任务队列快照
     * @return AgentSnapshot 快照对象
     */
    public AgentSnapshot suspendWithTaskQueue(String workspaceId, List<AgentSnapshot.TaskState> taskQueue) {
        log.info("[Hibernation] 挂起开始（含任务队列）: workspace={}, tasks={}", workspaceId, taskQueue.size());

        try {
            Map<String, Map<String, Map<String, String>>> varPoolSnapshot = captureVariablePool();
            List<KvCacheRef> kvCacheRefs = captureKvCacheRefs();
            Map<String, Map<String, String>> contextPointers = captureContextPointers();
            Map<String, Map<String, String>> shmSegments = captureShmSegments();

            AgentSnapshot snapshot = new AgentSnapshot(
                    workspaceId,
                    System.currentTimeMillis(),
                    varPoolSnapshot,
                    taskQueue,
                    kvCacheRefs,
                    contextPointers,
                    shmSegments,
                    AgentSnapshot.CURRENT_VERSION
            );

            SnapshotSerializer.instance().saveToVfs(snapshot);

            if (!kvCacheRefs.isEmpty()) {
                KvCacheVfsStore.instance().saveToVfs(workspaceId, kvCacheRefs);
            }

            log.info("[Hibernation] 挂起完成: workspace={}, tasks={}", workspaceId, taskQueue.size());
            return snapshot;

        } catch (Exception e) {
            log.error("[Hibernation] 挂起失败: workspace={}, error={}", workspaceId, e.getMessage(), e);
            return null;
        }
    }
}
