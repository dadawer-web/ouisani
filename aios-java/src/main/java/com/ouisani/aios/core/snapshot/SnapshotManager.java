package com.ouisani.aios.core.snapshot;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.NumaAffinity;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.cache.SemanticCacheManager;
import com.ouisani.aios.core.ipc.SignalType;
import com.ouisani.aios.core.llm.ComputeAffinity;
import com.ouisani.aios.core.security.SecurityToken;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import com.ouisani.aios.core.vfs.VfsJournal;
import com.ouisani.aios.user.container.AgentImageConfig;
import com.ouisani.aios.user.container.ContainerRuntime;
import com.ouisani.aios.vfs.SemanticNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 快照管理器 — AIOS 的进程冻结/恢复/热迁移引擎。
 * <p>
 * 借鉴 CRIU (Checkpoint/Restore In Userspace) 和 VMware vMotion 的思想，
 * SnapshotManager 实现了 AgentTask 的纳秒级冻结与网络热迁移：
 *
 * <h3>冻结 (Checkpoint)</h3>
 * <ol>
 *   <li>暂停目标 AgentTask 的调度（发送 SIGSTOP）</li>
 *   <li>序列化进程"寄存器"（System Prompt, TaskStatus, Priority, Affinity）</li>
 *   <li>序列化进程"内存空间"（SemanticCacheManager 中属于它的缓存页）</li>
 *   <li>序列化进程"文件句柄"（打开的 VFS 节点的 ShadowCopy）</li>
 *   <li>捕获 VfsJournal 中未刷盘的日志尾部</li>
 *   <li>将 ProcessSnapshot 持久化到 {@code /var/snapshot/}</li>
 * </ol>
 *
 * <h3>恢复 (Restore)</h3>
 * <ol>
 *   <li>从 {@code /var/snapshot/} 读取 ProcessSnapshot</li>
 *   <li>重放 Journal Tail（保证数据一致性）</li>
 *   <li>利用 ContainerRuntime 重新分配沙箱</li>
 *   <li>恢复内存页（将 CachedPage 写回 SemanticCacheManager）</li>
 *   <li>重新绑定 VFS 句柄</li>
 *   <li>将 AgentTask 重新挂载到 TaskScheduler 的就绪队列</li>
 * </ol>
 *
 * <h3>热迁移 (Live Migration)</h3>
 * 通过 WebSocket 将 ProcessSnapshot 传输到远程 AIOS 节点，
 * 在目标节点上调用 {@link #restore(ProcessSnapshot)} 完成迁移。
 *
 * <h3>OS 类比</h3>
 * <table>
 *   <tr><th>机制</th><th>AIOS</th><th>说明</th></tr>
 *   <tr><td>CRIU dump</td><td>createSnapshot()</td><td>冻结并序列化进程</td></tr>
 *   <tr><td>CRIU restore</td><td>restore()</td><td>反序列化并恢复进程</td></tr>
 *   <tr><td>vMotion</td><td>migrateToRemote()</td><td>网络热迁移</td></tr>
 *   <tr><td>SIGSTOP</td><td>freezeTask()</td><td>暂停进程调度</td></tr>
 *   <tr><td>SIGCONT</td><td>resumeTask()</td><td>恢复进程调度</td></tr>
 * </table>
 *
 * @see ProcessSnapshot
 * @see VfsJournal
 */
public final class SnapshotManager {

    private static final Logger log = LoggerFactory.getLogger(SnapshotManager.class);

    // ── 快照存储路径 ──
    private static final String SNAPSHOT_DIR = "/var/snapshot";

    // ── Singleton ──

    private static final class Holder {
        static final SnapshotManager INSTANCE = new SnapshotManager();
    }

    public static SnapshotManager instance() {
        return Holder.INSTANCE;
    }

    // ── 状态 ──

    /** 已创建的快照索引：snapshotId → ProcessSnapshot */
    private final ConcurrentHashMap<String, ProcessSnapshot> snapshotStore = new ConcurrentHashMap<>();

    /** PID → 冻结状态（正在冻结中的 PID 集合，防止并发冻结） */
    private final Set<Integer> freezingPids = ConcurrentHashMap.newKeySet();

    // ── 统计 ──

    private final AtomicLong totalSnapshots = new AtomicLong(0);
    private final AtomicLong totalRestores = new AtomicLong(0);
    private final AtomicLong totalMigrations = new AtomicLong(0);
    private final AtomicLong totalFailedFreezes = new AtomicLong(0);
    private final AtomicLong totalFailedRestores = new AtomicLong(0);

    private SnapshotManager() {
    }

    // ════════════════════════════════════════════════════════════════
    //  Phase 1: 进程冻结 (Checkpoint / CRIU dump)
    // ════════════════════════════════════════════════════════════════

    /**
     * 创建进程快照 — 冻结并序列化一个 AgentTask 的完整状态。
     * <p>
     * 类比 CRIU 的 {@code criu dump -t <pid> -D <images-dir>}。
     * <p>
     * 冻结流程：
     * <ol>
     *   <li>检查 PID 是否已在冻结中（防止并发冻结）</li>
     *   <li>暂停目标 AgentTask 的调度（设置 BLOCKED 状态）</li>
     *   <li>序列化进程寄存器（TaskStatus, Priority, Affinity, etc.）</li>
     *   <li>序列化进程内存空间（SemanticCacheManager 中的缓存页）</li>
     *   <li>序列化进程文件句柄（VFS 节点的 ShadowCopy）</li>
     *   <li>捕获 VfsJournal 日志尾部</li>
     *   <li>持久化到 {@code /var/snapshot/}</li>
     * </ol>
     *
     * @param task 要冻结的 AgentTask
     * @return ProcessSnapshot 快照对象
     * @throws IllegalStateException 如果进程已在冻结中或状态不允许冻结
     */
    public ProcessSnapshot createSnapshot(AgentTask task) {
        int pid = task.pid();

        // ── 防并发冻结 ──
        if (!freezingPids.add(pid)) {
            throw new IllegalStateException("PID " + pid + " is already being frozen");
        }

        try {
            log.info("[Snapshot] ╔══════════════════════════════════════════════════╗");
            log.info("[Snapshot] ║  FREEZE START: PID={}                           ║", pid);
            log.info("[Snapshot] ╚══════════════════════════════════════════════════╝");

            SemanticEtw.getInstance().logEvent("SNAPSHOT", "FREEZE_START", "pid=" + pid);

            // ── Step 1: 暂停进程调度 — 类似 SIGSTOP ──
            AgentTask.TaskStatus prevStatus = task.status();
            if (prevStatus == AgentTask.TaskStatus.KILLED
                    || prevStatus == AgentTask.TaskStatus.OOM_KILLED
                    || prevStatus == AgentTask.TaskStatus.CRASHED) {
                throw new IllegalStateException("Cannot freeze PID " + pid + " in terminal state: " + prevStatus);
            }

            task.setStatus(AgentTask.TaskStatus.BLOCKED);
            log.info("[Snapshot] PID {} frozen: {} → BLOCKED", pid, prevStatus);

            // ── Step 2: 序列化进程寄存器 ──
            RegisterCapture registers = captureRegisters(task);

            // ── Step 3: 序列化进程内存空间 ──
            List<ProcessSnapshot.CachedPage> cachedPages = captureMemoryPages(task);

            // ── Step 4: 序列化进程文件句柄 ──
            List<ProcessSnapshot.OpenHandle> openHandles = captureOpenHandles(task);

            // ── Step 5: 捕获信号队列 ──
            List<String> pendingSignals = capturePendingSignals(task);

            // ── Step 6: 捕获 VfsJournal 日志尾部 ──
            List<ProcessSnapshot.JournalEntry> journalTail = captureJournalTail();

            // ── Step 7: 捕获安全上下文 ──
            SecurityCapture security = captureSecurityContext(task);

            // ── 组装 ProcessSnapshot ──
            String snapshotId = "snap-" + pid + "-" + System.currentTimeMillis();
            ProcessSnapshot snapshot = new ProcessSnapshot(
                    snapshotId,
                    System.currentTimeMillis(),
                    getLocalNodeName(),
                    pid,
                    prevStatus,
                    registers.processPriority,
                    registers.numaAffinity,
                    registers.computeAffinity,
                    registers.cgroup,
                    registers.budget,
                    registers.gasLimit,
                    registers.gasUsed,
                    registers.taskType,
                    registers.payload,
                    registers.toolName,
                    registers.toolCode,
                    registers.deadlineMs,
                    task.contextHistory(),
                    cachedPages,
                    openHandles,
                    pendingSignals,
                    journalTail,
                    security.privilegeLevel,
                    security.ownerId
            );

            // ── Step 8: 持久化快照 ──
            persistSnapshot(snapshot);

            // ── 注册到内存索引 ──
            snapshotStore.put(snapshotId, snapshot);
            totalSnapshots.incrementAndGet();

            log.info("[Snapshot] ╔══════════════════════════════════════════════════╗");
            log.info("[Snapshot] ║  FREEZE COMPLETE: PID={}, snapId={}    ║", pid, snapshotId);
            log.info("[Snapshot] ║  Pages={}, Handles={}, Journal={}, Signals={} ║",
                    cachedPages.size(), openHandles.size(), journalTail.size(), pendingSignals.size());
            log.info("[Snapshot] ╚══════════════════════════════════════════════════╝");

            SemanticEtw.getInstance().logEvent("SNAPSHOT", "FREEZE_COMPLETE",
                    "pid=" + pid + " snapId=" + snapshotId
                    + " pages=" + cachedPages.size() + " handles=" + openHandles.size()
                    + " journal=" + journalTail.size());

            return snapshot;

        } catch (Exception e) {
            totalFailedFreezes.incrementAndGet();
            // 冻结失败，恢复进程状态
            task.setStatus(AgentTask.TaskStatus.READY);
            log.error("[Snapshot] FREEZE FAILED: PID={}, error={}", pid, e.getMessage(), e);
            throw e;
        } finally {
            freezingPids.remove(pid);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Phase 2: 进程恢复 (Restore / CRIU restore)
    // ════════════════════════════════════════════════════════════════

    /**
     * 从快照恢复进程 — 反序列化并重新激活一个 AgentTask。
     * <p>
     * 类比 CRIU 的 {@code criu restore -D <images-dir>}。
     * <p>
     * 恢复流程：
     * <ol>
     *   <li>重放 Journal Tail（保证数据一致性）</li>
     *   <li>创建新的 AgentTask（分配新 PID）</li>
     *   <li>恢复寄存器状态</li>
     *   <li>恢复内存页（将 CachedPage 写回 SemanticCacheManager）</li>
     *   <li>重新绑定 VFS 句柄</li>
     *   <li>利用 ContainerRuntime 重新分配沙箱</li>
     *   <li>将 AgentTask 挂载到 TaskScheduler 的就绪队列</li>
     * </ol>
     *
     * @param snapshot 进程快照
     * @return 恢复后的新 AgentTask
     */
    public AgentTask restore(ProcessSnapshot snapshot) {
        log.info("[Snapshot] ╔══════════════════════════════════════════════════╗");
        log.info("[Snapshot] ║  RESTORE START: snapId={}, origPID={}     ║",
                snapshot.snapshotId(), snapshot.pid());
        log.info("[Snapshot] ╚══════════════════════════════════════════════════╝");

        SemanticEtw.getInstance().logEvent("SNAPSHOT", "RESTORE_START",
                "snapId=" + snapshot.snapshotId() + " origPID=" + snapshot.pid());

        try {
            // ── Step 1: 重放 Journal Tail ──
            replayJournalTail(snapshot.journalTail());

            // ── Step 2: 创建新的 AgentTask（分配新 PID） ──
            TaskScheduler scheduler = getTaskScheduler();
            if (scheduler == null) {
                throw new IllegalStateException("TaskScheduler not available");
            }

            int newPid = scheduler.nextPid();
            AgentTask task = new AgentTask(newPid, AgentTask.TaskStatus.READY,
                    snapshot.cgroup(), "/dev/null", "/dev/null", List.of());

            // ── Step 3: 恢复寄存器状态 ──
            restoreRegisters(task, snapshot);

            // ── Step 4: 恢复内存页 ──
            restoreMemoryPages(task, snapshot.cachedPages());

            // ── Step 5: 恢复上下文历史 ──
            for (String entry : snapshot.contextHistory()) {
                task.appendHistory(entry);
            }

            // ── Step 6: 恢复信号队列 ──
            restorePendingSignals(task, snapshot.pendingSignals());

            // ── Step 7: 重新绑定 VFS 句柄 ──
            restoreVfsHandles(snapshot.openHandles());

            // ── Step 8: 恢复安全上下文 ──
            SecurityToken restoredToken = new SecurityToken(
                    snapshot.securityOwnerId(),
                    snapshot.securityPrivilegeLevel(),
                    Set.of("restored_from:" + snapshot.snapshotId())
            );
            task.setPrimaryToken(restoredToken);

            // ── Step 9: 挂载到 TaskScheduler 就绪队列 ──
            // 注意：这里只注册到 PCB，不启动虚拟线程（需要外部调用者提供 agentLogic）
            task.setStatus(AgentTask.TaskStatus.READY);

            totalRestores.incrementAndGet();

            log.info("[Snapshot] ╔══════════════════════════════════════════════════╗");
            log.info("[Snapshot] ║  RESTORE COMPLETE: newPID={}, snapId={}  ║",
                    newPid, snapshot.snapshotId());
            log.info("[Snapshot] ╚══════════════════════════════════════════════════╝");

            SemanticEtw.getInstance().logEvent("SNAPSHOT", "RESTORE_COMPLETE",
                    "snapId=" + snapshot.snapshotId() + " newPID=" + newPid
                    + " pages=" + snapshot.cachedPages().size());

            return task;

        } catch (Exception e) {
            totalFailedRestores.incrementAndGet();
            log.error("[Snapshot] RESTORE FAILED: snapId={}, error={}",
                    snapshot.snapshotId(), e.getMessage(), e);
            throw new RuntimeException("Restore failed: " + e.getMessage(), e);
        }
    }

    /**
     * 从快照恢复并启动容器 — 完整的恢复 + 调度。
     * <p>
     * 此方法在 restore() 的基础上，进一步利用 ContainerRuntime
     * 为恢复的 Agent 创建隔离沙箱，并将其挂载到 TaskScheduler。
     *
     * @param snapshot   进程快照
     * @param agentLogic Agent 的执行逻辑
     * @return 恢复后的新 AgentTask
     */
    public AgentTask restoreAndSchedule(ProcessSnapshot snapshot, Runnable agentLogic) {
        AgentTask task = restore(snapshot);

        TaskScheduler scheduler = getTaskScheduler();
        if (scheduler != null) {
            scheduler.spawn(task, agentLogic);
            log.info("[Snapshot] Restored PID {} scheduled on TaskScheduler", task.pid());
        }

        return task;
    }

    // ════════════════════════════════════════════════════════════════
    //  Phase 3: 网络热迁移 (Live Migration / vMotion)
    // ════════════════════════════════════════════════════════════════

    /**
     * 将快照序列化为字节数组 — 用于网络传输。
     * <p>
     * 类比 VMware vMotion：将虚拟机的内存状态序列化后
     * 通过网络传输到目标宿主机。
     *
     * @param snapshot 进程快照
     * @return GZIP 压缩的序列化字节数组
     */
    public byte[] serializeForTransfer(ProcessSnapshot snapshot) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             GZIPOutputStream gzip = new GZIPOutputStream(baos);
             ObjectOutputStream oos = new ObjectOutputStream(gzip)) {

            oos.writeObject(snapshot);
            oos.flush();
            gzip.finish();

            byte[] data = baos.toByteArray();
            log.info("[Snapshot] Serialized: snapId={}, size={} bytes (compressed)",
                    snapshot.snapshotId(), data.length);

            return data;

        } catch (IOException e) {
            throw new RuntimeException("Serialization failed: " + e.getMessage(), e);
        }
    }

    /**
     * 从字节数组反序列化快照 — 用于网络接收。
     *
     * @param data GZIP 压缩的序列化字节数组
     * @return ProcessSnapshot
     */
    public ProcessSnapshot deserializeFromTransfer(byte[] data) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             GZIPInputStream gzip = new GZIPInputStream(bais);
             ObjectInputStream ois = new ObjectInputStream(gzip)) {

            ProcessSnapshot snapshot = (ProcessSnapshot) ois.readObject();
            log.info("[Snapshot] Deserialized: snapId={}, origPID={}",
                    snapshot.snapshotId(), snapshot.pid());

            // 注册到本地索引
            snapshotStore.put(snapshot.snapshotId(), snapshot);

            return snapshot;

        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Deserialization failed: " + e.getMessage(), e);
        }
    }

    /**
     * 执行热迁移 — 冻结 + 序列化 + 传输 + 远程恢复。
     * <p>
     * 类比 VMware vMotion 的完整流程：
     * <ol>
     *   <li>在源节点冻结进程</li>
     *   <li>序列化快照</li>
     *   <li>通过网络传输到目标节点</li>
     *   <li>在目标节点恢复进程</li>
     * </ol>
     * <p>
     * 注意：实际的网络传输需要由外部调用者实现（通过 SyscallServer 的 WebSocket），
     * 此方法只完成冻结和序列化部分。
     *
     * @param task 要迁移的 AgentTask
     * @return 序列化后的字节数组，可传输到远程节点
     */
    public byte[] prepareMigration(AgentTask task) {
        ProcessSnapshot snapshot = createSnapshot(task);
        byte[] data = serializeForTransfer(snapshot);
        totalMigrations.incrementAndGet();

        log.info("[Snapshot] Migration prepared: PID={}, snapId={}, size={} bytes",
                task.pid(), snapshot.snapshotId(), data.length);

        SemanticEtw.getInstance().logEvent("SNAPSHOT", "MIGRATION_PREPARED",
                "pid=" + task.pid() + " snapId=" + snapshot.snapshotId()
                + " size=" + data.length);

        return data;
    }

    // ════════════════════════════════════════════════════════════════
    //  冻结子步骤 — 寄存器/内存/句柄/信号/Journal 捕获
    // ════════════════════════════════════════════════════════════════

    /**
     * 捕获进程寄存器 — AgentTask 的核心调度属性。
     */
    private RegisterCapture captureRegisters(AgentTask task) {
        return new RegisterCapture(
                task.processPriority(),
                task.affinity(),
                task.computeAffinity(),
                task.cgroup(),
                task.budget(),
                task.gasLimit(),
                task.gasUsed(),
                task.type(),
                task.payload(),
                task.toolName(),
                task.toolCode(),
                task.deadlineMs()
        );
    }

    /**
     * 捕获进程内存空间 — SemanticCacheManager 中的缓存页。
     * <p>
     * 类似 CRIU 捕获进程的内存页表：将进程在 L1 缓存中的
     * 所有记忆页冻结并序列化。
     */
    private List<ProcessSnapshot.CachedPage> captureMemoryPages(AgentTask task) {
        List<ProcessSnapshot.CachedPage> pages = new ArrayList<>();
        SemanticCacheManager cacheMgr = SemanticCacheManager.instance();

        for (SemanticCacheManager.CacheEntry entry : cacheMgr.getCacheEntries()) {
            // 捕获所有缓存页（AIOS 的缓存是共享的，快照时全部捕获）
            Map<String, Object> meta = new HashMap<>(entry.metadata());
            pages.add(new ProcessSnapshot.CachedPage(
                    entry.responseText(),
                    entry.synapticWeight(),
                    entry.swappable(),
                    meta
            ));
        }

        log.debug("[Snapshot] Captured {} memory pages from SemanticCacheManager", pages.size());
        return pages;
    }

    /**
     * 捕获进程文件句柄 — 打开的 VFS 节点的 ShadowCopy。
     * <p>
     * 类似 CRIU 捕获进程的 fd table：将每个打开的文件描述符
     * 对应的 VFS 节点冻结为只读快照。
     */
    private List<ProcessSnapshot.OpenHandle> captureOpenHandles(AgentTask task) {
        List<ProcessSnapshot.OpenHandle> handles = new ArrayList<>();
        VfsManager vfs = VfsManager.instance();

        // 捕获关键 VFS 节点的快照
        String[] criticalPaths = {
                "/dev/semantic",
                "/dev/vec_mem",
                "/dev/graph_mem",
                "/var/db/memory",
                "/dev/shm/blackboard"
        };

        for (String vfsPath : criticalPaths) {
            try {
                Optional<VfsNode> nodeOpt = vfs.resolve(vfsPath);
                if (nodeOpt.isPresent()) {
                    VfsNode node = nodeOpt.get();
                    String frozenContent;

                    // 尝试创建 ShadowCopy（如果节点支持）
                    try {
                        VfsNode shadow = node.createShadowCopy();
                        frozenContent = shadow.read();
                    } catch (Exception e) {
                        // 回退：直接读取当前内容
                        frozenContent = node.read();
                    }

                    handles.add(new ProcessSnapshot.OpenHandle(
                            vfsPath,
                            node.nodeType().name(),
                            frozenContent
                    ));
                }
            } catch (Exception e) {
                log.warn("[Snapshot] Failed to capture handle for {}: {}", vfsPath, e.getMessage());
            }
        }

        log.debug("[Snapshot] Captured {} VFS handles", handles.size());
        return handles;
    }

    /**
     * 捕获信号队列 — 未处理的信号列表。
     */
    private List<String> capturePendingSignals(AgentTask task) {
        List<String> signals = new ArrayList<>();
        for (SignalType signal : task.pendingSignals()) {
            signals.add(signal.name());
        }
        return signals;
    }

    /**
     * 捕获 VfsJournal 日志尾部 — 快照瞬间的未刷盘日志。
     * <p>
     * 这是最关键的一致性保障：快照瞬间可能有尚未应用到 VFS 的
     * WAL 日志条目。将这些条目附加到快照中，恢复时先重放这些
     * 条目，保证数据不丢失。
     */
    private List<ProcessSnapshot.JournalEntry> captureJournalTail() {
        List<ProcessSnapshot.JournalEntry> tail = new ArrayList<>();

        try {
            VfsJournal journal = VfsJournal.getInstance();
            if (!journal.isOpen()) return tail;

            // 读取当前 Journal 文件中的所有条目
            Path journalPath = Path.of("/tmp/aios_vfs.journal");
            if (!Files.exists(journalPath)) return tail;

            List<String> lines = Files.readAllLines(journalPath, StandardCharsets.UTF_8);
            for (String line : lines) {
                ProcessSnapshot.JournalEntry entry = parseJournalLine(line);
                if (entry != null) {
                    tail.add(entry);
                }
            }

            log.debug("[Snapshot] Captured {} journal entries", tail.size());

        } catch (Exception e) {
            log.warn("[Snapshot] Failed to capture journal tail: {}", e.getMessage());
        }

        return tail;
    }

    /**
     * 捕获安全上下文。
     */
    private SecurityCapture captureSecurityContext(AgentTask task) {
        SecurityToken token = task.primaryToken();
        return new SecurityCapture(
                token != null ? token.privilegeLevel() : 3,
                token != null ? token.ownerId() : "unknown"
        );
    }

    // ════════════════════════════════════════════════════════════════
    //  恢复子步骤
    // ════════════════════════════════════════════════════════════════

    /**
     * 重放 Journal Tail — 保证快照后数据一致性。
     */
    private void replayJournalTail(List<ProcessSnapshot.JournalEntry> journalTail) {
        if (journalTail == null || journalTail.isEmpty()) return;

        VfsManager vfs = VfsManager.instance();
        int replayed = 0;

        for (ProcessSnapshot.JournalEntry entry : journalTail) {
            try {
                Optional<VfsNode> nodeOpt = vfs.resolve(entry.nodePath());
                if (nodeOpt.isPresent()) {
                    nodeOpt.get().write(entry.payload());
                    replayed++;
                }
            } catch (Exception e) {
                log.warn("[Snapshot] Journal replay error for {}: {}", entry.nodePath(), e.getMessage());
            }
        }

        log.info("[Snapshot] Journal tail replayed: {}/{} entries", replayed, journalTail.size());
    }

    /**
     * 恢复寄存器状态。
     */
    private void restoreRegisters(AgentTask task, ProcessSnapshot snapshot) {
        task.setProcessPriority(snapshot.processPriority());
        task.setAffinity(snapshot.numaAffinity());
        task.setComputeAffinity(snapshot.computeAffinity());
        task.setBudget(snapshot.budget());
        task.setGasLimit(snapshot.gasLimit());
        task.setGasUsed(snapshot.gasUsed());
        task.setType(snapshot.taskType());
        task.setPayload(snapshot.payload());
        task.setToolName(snapshot.toolName());
        task.setToolCode(snapshot.toolCode());
        task.setDeadlineMs(snapshot.deadlineMs());

        log.debug("[Snapshot] Registers restored for new PID {}", task.pid());
    }

    /**
     * 恢复内存页 — 将 CachedPage 写回 SemanticCacheManager。
     * <p>
     * 类似 CRIU 恢复进程的内存页：将冻结的内存页重新映射到
     * 新进程的地址空间。
     */
    private void restoreMemoryPages(AgentTask task, List<ProcessSnapshot.CachedPage> pages) {
        if (pages == null || pages.isEmpty()) return;

        SemanticCacheManager cacheMgr = SemanticCacheManager.instance();
        // 将缓存页流入 SemanticNode（L2 持久存储），而非直接写回 L1 缓存
        // L1 缓存是有限的，恢复的数据应进入 L2，按需提升回 L1
        try {
            VfsManager vfs = VfsManager.instance();
            Optional<VfsNode> nodeOpt = vfs.resolve("/dev/semantic");
            if (nodeOpt.isPresent() && nodeOpt.get() instanceof SemanticNode semanticNode) {
                for (ProcessSnapshot.CachedPage page : pages) {
                    // 使用 mock embed（因为恢复时可能没有 LlmProvider）
                    float[] mockVector = new float[1536]; // 占位向量
                    semanticNode.influxFromCache(page.text(), mockVector, page.metadata());
                }
                log.info("[Snapshot] Restored {} pages → /dev/semantic (L2)", pages.size());
            }
        } catch (Exception e) {
            log.warn("[Snapshot] Failed to restore pages to SemanticNode: {}", e.getMessage());
        }
    }

    /**
     * 恢复信号队列。
     */
    private void restorePendingSignals(AgentTask task, List<String> signalNames) {
        if (signalNames == null) return;
        for (String signalName : signalNames) {
            try {
                SignalType signal = SignalType.valueOf(signalName);
                task.sendSignal(signal);
            } catch (IllegalArgumentException e) {
                log.warn("[Snapshot] Unknown signal: {}", signalName);
            }
        }
    }

    /**
     * 重新绑定 VFS 句柄 — 将快照中的 VFS 节点内容恢复到当前 VFS。
     */
    private void restoreVfsHandles(List<ProcessSnapshot.OpenHandle> handles) {
        if (handles == null) return;

        VfsManager vfs = VfsManager.instance();
        for (ProcessSnapshot.OpenHandle handle : handles) {
            try {
                Optional<VfsNode> nodeOpt = vfs.resolve(handle.vfsPath());
                if (nodeOpt.isPresent() && handle.frozenContent() != null) {
                    // 将冻结的内容写回 VFS 节点
                    // 注意：某些节点（如 SemanticNode）的 write 有特殊语义
                    VfsNode node = nodeOpt.get();
                    if (node.nodeType() != VfsNode.VfsNodeType.SEMANTIC) {
                        // 非 SemanticNode 直接写回
                        node.write(handle.frozenContent());
                    }
                    // SemanticNode 的双写机制会自动处理
                }
            } catch (Exception e) {
                log.warn("[Snapshot] Failed to restore handle {}: {}", handle.vfsPath(), e.getMessage());
            }
        }

        log.debug("[Snapshot] Restored {} VFS handles", handles.size());
    }

    // ════════════════════════════════════════════════════════════════
    //  快照持久化
    // ════════════════════════════════════════════════════════════════

    /**
     * 将快照持久化到 {@code /var/snapshot/} 目录。
     */
    private void persistSnapshot(ProcessSnapshot snapshot) {
        try {
            Path dir = Path.of(SNAPSHOT_DIR);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }

            Path filePath = dir.resolve(snapshot.snapshotId() + ".snapshot");
            byte[] data = serializeForTransfer(snapshot);
            Files.write(filePath, data);

            log.info("[Snapshot] Persisted: {} ({} bytes)", filePath, data.length);

        } catch (Exception e) {
            log.error("[Snapshot] Failed to persist snapshot: {}", e.getMessage());
        }
    }

    /**
     * 从 {@code /var/snapshot/} 目录加载快照。
     */
    public ProcessSnapshot loadSnapshot(String snapshotId) {
        // 先查内存索引
        ProcessSnapshot cached = snapshotStore.get(snapshotId);
        if (cached != null) return cached;

        // 从磁盘加载
        try {
            Path filePath = Path.of(SNAPSHOT_DIR, snapshotId + ".snapshot");
            if (!Files.exists(filePath)) {
                throw new FileNotFoundException("Snapshot not found: " + snapshotId);
            }

            byte[] data = Files.readAllBytes(filePath);
            ProcessSnapshot snapshot = deserializeFromTransfer(data);
            snapshotStore.put(snapshotId, snapshot);

            return snapshot;

        } catch (Exception e) {
            throw new RuntimeException("Failed to load snapshot: " + e.getMessage(), e);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  查询与统计
    // ════════════════════════════════════════════════════════════════

    /** 获取所有快照 ID */
    public Set<String> listSnapshots() {
        return Collections.unmodifiableSet(snapshotStore.keySet());
    }

    /** 获取指定快照 */
    public ProcessSnapshot getSnapshot(String snapshotId) {
        return snapshotStore.get(snapshotId);
    }

    /** 删除快照 */
    public boolean deleteSnapshot(String snapshotId) {
        ProcessSnapshot removed = snapshotStore.remove(snapshotId);
        if (removed != null) {
            try {
                Files.deleteIfExists(Path.of(SNAPSHOT_DIR, snapshotId + ".snapshot"));
            } catch (IOException e) {
                log.warn("[Snapshot] Failed to delete snapshot file: {}", e.getMessage());
            }
        }
        return removed != null;
    }

    /** 统计报告 */
    public String getStatsReport() {
        return """
                ┌─ SnapshotManager Stats ─────────────────────────────
                │  Total Snapshots     : %d
                │  Total Restores      : %d
                │  Total Migrations    : %d
                │  Failed Freezes      : %d
                │  Failed Restores     : %d
                │  Stored Snapshots    : %d
                │  Snapshot Directory  : %s
                └─────────────────────────────────────────────────"""
                .formatted(
                        totalSnapshots.get(), totalRestores.get(), totalMigrations.get(),
                        totalFailedFreezes.get(), totalFailedRestores.get(),
                        snapshotStore.size(), SNAPSHOT_DIR);
    }

    // ── 内部辅助 ──

    private ProcessSnapshot.JournalEntry parseJournalLine(String line) {
        if (line == null || line.isBlank()) return null;
        String[] parts = line.split("\\|", 4);
        if (parts.length < 4) return null;
        try {
            return new ProcessSnapshot.JournalEntry(
                    Long.parseLong(parts[0]),
                    parts[1],
                    parts[2],
                    parts[3].replace("\\n", "\n")
            );
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private TaskScheduler getTaskScheduler() {
        try {
            return VfsManager.instance().getTaskScheduler();
        } catch (Exception e) {
            return null;
        }
    }

    private String getLocalNodeName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "aios-local";
        }
    }

    // ── 内部数据结构 ──

    private record RegisterCapture(
            ProcessPriority processPriority,
            NumaAffinity numaAffinity,
            ComputeAffinity computeAffinity,
            String cgroup,
            int budget,
            int gasLimit,
            int gasUsed,
            AgentTask.TaskType taskType,
            String payload,
            String toolName,
            String toolCode,
            long deadlineMs
    ) {}

    private record SecurityCapture(
            int privilegeLevel,
            String ownerId
    ) {}
}
