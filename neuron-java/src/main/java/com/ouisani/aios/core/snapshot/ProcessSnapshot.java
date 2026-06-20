package com.ouisani.aios.core.snapshot;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.NumaAffinity;
import com.ouisani.aios.core.llm.ComputeAffinity;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 进程快照 — AgentTask 的完全序列化状态。
 * <p>
 * 类比操作系统中的进程 Checkpoint：将一个运行中进程的完整状态
 * （寄存器、内存空间、文件句柄、信号队列）冻结并序列化，
 * 使得该进程可以在另一台机器上被精确恢复。
 *
 * <h3>OS 类比</h3>
 * <table>
 *   <tr><th>Linux Checkpoint</th><th>AIOS ProcessSnapshot</th><th>说明</th></tr>
 *   <tr><td>CPU 寄存器</td><td>RegisterState</td><td>进程的执行上下文</td></tr>
 *   <tr><td>内存页表</td><td>MemoryPages</td><td>进程的语义缓存页</td></tr>
 *   <tr><td>fd table</td><td>OpenHandles</td><td>打开的 VFS 节点</td></tr>
 *   <tr><td>pending signals</td><td>PendingSignals</td><td>未处理的信号队列</td></tr>
 *   <tr><td>WAL / journal</td><td>JournalTail</td><td>快照瞬间的未刷盘日志</td></tr>
 * </table>
 *
 * <h3>网络传输</h3>
 * ProcessSnapshot 实现 {@link Serializable}，可通过 WebSocket
 * 传输到远程 AIOS 节点，实现 Agent 的热迁移 (Live Migration)。
 *
 * @see SnapshotManager
 */
public record ProcessSnapshot(

        // ── 快照元数据 ──
        /** 快照 ID（UUID） */
        String snapshotId,
        /** 快照创建时间戳 */
        long createdAt,
        /** 源节点标识（如 "aios-node-1"） */
        String sourceNode,

        // ── 进程寄存器 (Register State) ──
        /** 进程 PID */
        int pid,
        /** 进程状态 */
        AgentTask.TaskStatus taskStatus,
        /** 进程优先级 */
        ProcessPriority processPriority,
        /** NUMA 亲和性 */
        NumaAffinity numaAffinity,
        /** 算力亲和性 */
        ComputeAffinity computeAffinity,
        /** Cgroup 名称 */
        String cgroup,
        /** Token 预算 */
        int budget,
        /** Gas Limit */
        int gasLimit,
        /** Gas Used */
        int gasUsed,
        /** 任务类型 */
        AgentTask.TaskType taskType,
        /** 任务 Payload */
        String payload,
        /** 工具名称 */
        String toolName,
        /** 工具代码 */
        String toolCode,
        /** Deadline (ms) */
        long deadlineMs,

        // ── 内存空间 (Memory Pages) ──
        /** 上下文历史（Agent 的"工作记忆"） */
        List<String> contextHistory,
        /** 语义缓存页 — 属于该进程的 CacheEntry 快照 */
        List<CachedPage> cachedPages,

        // ── 文件句柄 (Open Handles) ──
        /** 打开的 VFS 节点路径及其冻结内容 */
        List<OpenHandle> openHandles,

        // ── 信号队列 (Pending Signals) ──
        /** 未处理的信号列表 */
        List<String> pendingSignals,

        // ── 日志尾部 (Journal Tail) ──
        /** 快照瞬间的未刷盘 WAL 日志条目 */
        List<JournalEntry> journalTail,

        // ── 安全上下文 ──
        /** SecurityToken 的特权级别 */
        int securityPrivilegeLevel,
        /** SecurityToken 的 ownerId */
        String securityOwnerId

) implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 缓存页 — 语义缓存中属于该进程的一条记忆。
     *
     * @param text      缓存的响应文本
     * @param weight    快照时的突触权重
     * @param swappable 是否可交换
     * @param metadata  元数据
     */
    public record CachedPage(
            String text,
            double weight,
            boolean swappable,
            Map<String, Object> metadata
    ) implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    /**
     * 打开的 VFS 句柄 — 快照时该进程打开的文件/设备。
     *
     * @param vfsPath       VFS 路径
     * @param nodeType      节点类型名称
     * @param frozenContent 冻结的内容快照
     */
    public record OpenHandle(
            String vfsPath,
            String nodeType,
            String frozenContent
    ) implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    /**
     * 日志条目 — 快照瞬间的 WAL 记录。
     *
     * @param timestamp 时间戳
     * @param nodePath  VFS 节点路径
     * @param operation 操作类型
     * @param payload   操作数据
     */
    public record JournalEntry(
            long timestamp,
            String nodePath,
            String operation,
            String payload
    ) implements Serializable {
        private static final long serialVersionUID = 1L;
    }
}
