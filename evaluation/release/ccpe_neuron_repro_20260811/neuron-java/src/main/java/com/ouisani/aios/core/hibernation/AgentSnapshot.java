package com.ouisani.aios.core.hibernation;

import com.ouisani.aios.core.cache.kvstate.KvCacheRef;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Agent 快照 — Semantic Core Hibernation 的完整状态序列化。
 * <p>
 * 借鉴 LMCache 的"把临时变永久"产品定位：
 * 当关闭工作区时，AIOS 不杀掉 Agent，而是挂起到硬盘。
 * <p>
 * <h3>快照内容</h3>
 * <ul>
 *   <li>{@code workspaceId} — 工作区标识</li>
 *   <li>{@code variablePoolSnapshot} — VariablePool 的所有变量（Agent 脑子里的变量）</li>
 *   <li>{@code taskQueueSnapshot} — 正在处理的任务队列</li>
 *   <li>{@code kvCacheRefs} — 大模型的上下文指针（KV Cache 引用列表）</li>
 *   <li>{@code contextPointers} — SemanticMemoryBlock 的上下文指针</li>
 *   <li>{@code timestamp} — 快照时间戳</li>
 * </ul>
 * <p>
 * <h3>OS 类比</h3>
 * 类比操作系统的 hibernate（挂起到磁盘）：
 * 将内存中的所有状态写入磁盘文件，下次启动时直接恢复。
 * <p>
 * <h3>与 ProcessSnapshot 的区别</h3>
 * {@link com.ouisani.aios.core.snapshot.ProcessSnapshot} 是进程级快照（CRIU），
 * 捕获单个 AgentTask 的寄存器、内存页、文件句柄。
 * <p>
 * {@link AgentSnapshot} 是工作区级快照（Hibernate），捕获整个工作区的
 * 所有 Agent 状态、变量池、任务队列和 KV Cache 引用。
 *
 * @see HibernationManager
 * @see KvCacheRef
 */
public record AgentSnapshot(
        /** 工作区标识 */
        String workspaceId,
        /** 快照时间戳 */
        long timestamp,
        /** VariablePool 的所有变量（Scope → ContextId → Key → Value） */
        Map<String, Map<String, Map<String, String>>> variablePoolSnapshot,
        /** 正在处理的任务队列（每个任务包含 taskId, status, payload） */
        List<TaskState> taskQueueSnapshot,
        /** 大模型的上下文指针（KV Cache 引用列表） */
        List<KvCacheRef> kvCacheRefs,
        /** SemanticMemoryBlock 的上下文指针（blockId → key → ContextPointer JSON） */
        Map<String, Map<String, String>> contextPointers,
        /** 共享内存段快照（segmentId → key → value） */
        Map<String, Map<String, String>> shmSegments,
        /** 快照版本号（用于兼容性检查） */
        int version
) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前快照格式版本 */
    public static final int CURRENT_VERSION = 1;

    /**
     * 任务状态 — 正在处理的任务队列中的一项。
     *
     * @param taskId   任务 ID
     * @param status   任务状态
     * @param payload  任务 Payload
     * @param priority 任务优先级
     */
    public record TaskState(
            String taskId,
            String status,
            String payload,
            int priority
    ) implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    /**
     * 紧凑构造器 — 设置默认版本号。
     */
    public AgentSnapshot {
        if (version == 0) {
            version = CURRENT_VERSION;
        }
    }

    /**
     * 快照大小估算（字节）。
     */
    public long estimatedSizeBytes() {
        long size = 0;
        if (variablePoolSnapshot != null) {
            for (var m1 : variablePoolSnapshot.values()) {
                for (var m2 : m1.values()) {
                    for (var e : m2.entrySet()) {
                        size += e.getKey().length() + (e.getValue() != null ? e.getValue().length() : 0);
                    }
                }
            }
        }
        if (taskQueueSnapshot != null) {
            for (var t : taskQueueSnapshot) {
                size += t.taskId().length() + t.status().length() + t.payload().length();
            }
        }
        if (kvCacheRefs != null) {
            size += kvCacheRefs.stream().mapToLong(r -> r.kvTensorUri().length() + r.contentHash().length()).sum();
        }
        if (contextPointers != null) {
            for (var m : contextPointers.values()) {
                for (var e : m.entrySet()) {
                    size += e.getKey().length() + e.getValue().length();
                }
            }
        }
        return size;
    }

    @Override
    public String toString() {
        return "AgentSnapshot{" +
                "workspace='" + workspaceId + '\'' +
                ", ts=" + timestamp +
                ", vars=" + (variablePoolSnapshot != null ? variablePoolSnapshot.size() : 0) +
                ", tasks=" + (taskQueueSnapshot != null ? taskQueueSnapshot.size() : 0) +
                ", kvRefs=" + (kvCacheRefs != null ? kvCacheRefs.size() : 0) +
                ", ctxPtrs=" + (contextPointers != null ? contextPointers.size() : 0) +
                ", shmSegs=" + (shmSegments != null ? shmSegments.size() : 0) +
                ", ver=" + version +
                '}';
    }
}
