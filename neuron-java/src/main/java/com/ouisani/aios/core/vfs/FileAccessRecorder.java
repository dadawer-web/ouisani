package com.ouisani.aios.core.vfs;

import com.ouisani.aios.core.ranking.FileAccessRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件访问记录器 — 内存态，记录 VFS 路径的读取/编辑时间戳与次数。
 * <p>
 * 重启丢失（P3 验证概念阶段；持久化延后到独立任务，参考 {@code VersionedPlanPersistence} 模式）。
 * <p>
 * 线程安全：{@link ConcurrentHashMap} + {@code compute} 原子更新，
 * 每条记录的 read/edit 时间戳与计数在单次 compute 闭包内一致更新。
 * <p>
 * 数据消费方：{@link com.ouisani.aios.core.ranking.FileHeatResolver} 实现者
 * 可基于 {@link #snapshot()} 计算热度分数后注入到 {@code CompactCutoffGuard}。
 */
public final class FileAccessRecorder {
    private final ConcurrentHashMap<String, FileAccessRecord> records = new ConcurrentHashMap<>();

    /** 记录一次读取：更新 lastReadMs + readCount+1；保留既有 edit 信息 */
    public void touchRead(String path, long nowMs) {
        if (path == null || path.isBlank()) return;
        records.compute(path, (k, r) -> r == null
                ? new FileAccessRecord(k, nowMs, 0, 1, 0)
                : new FileAccessRecord(k, nowMs, r.lastEditMs(), r.readCount() + 1, r.editCount()));
    }

    /** 记录一次编辑：更新 lastEditMs + editCount+1；保留既有 read 信息 */
    public void touchEdit(String path, long nowMs) {
        if (path == null || path.isBlank()) return;
        records.compute(path, (k, r) -> r == null
                ? new FileAccessRecord(k, 0, nowMs, 0, 1)
                : new FileAccessRecord(k, r.lastReadMs(), nowMs, r.readCount(), r.editCount() + 1));
    }

    /** 全量快照（用于排名计算） */
    public List<FileAccessRecord> snapshot() {
        return new ArrayList<>(records.values());
    }

    /** 单条查询 */
    public FileAccessRecord get(String path) {
        return records.get(path);
    }

    /** 清空所有记录（测试 / 重置用） */
    public void clear() {
        records.clear();
    }
}
