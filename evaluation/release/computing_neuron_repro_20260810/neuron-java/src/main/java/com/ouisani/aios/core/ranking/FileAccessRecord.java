package com.ouisani.aios.core.ranking;

/**
 * 文件访问记录 — 镜像 jcode {@code SessionLocation}（repo_ranking.rs）的数据载体。
 * <p>
 * 不可变 record，由 {@link com.ouisani.aios.core.vfs.FileAccessRecorder} 在 VFS
 * 读写时通过 compute 原子更新创建。
 *
 * @param path        VFS 路径
 * @param lastReadMs  最近读取时间戳（毫秒）
 * @param lastEditMs  最近编辑时间戳（毫秒）
 * @param readCount   累计读取次数
 * @param editCount   累计编辑次数
 */
public record FileAccessRecord(
        String path,
        long lastReadMs, long lastEditMs,
        long readCount, long editCount
) {
    public FileAccessRecord {
        if (path == null || path.isBlank()) path = "<unknown>";
        if (lastReadMs < 0) lastReadMs = 0;
        if (lastEditMs < 0) lastEditMs = 0;
        if (readCount < 0) readCount = 0;
        if (editCount < 0) editCount = 0;
    }

    /** 最近活动时间戳：max(lastRead, lastEdit) */
    public long lastAccessMs() {
        return Math.max(lastReadMs, lastEditMs);
    }

    /**
     * 总访问次数（编辑权重 ×2，因编辑比读取更"热"）。
     * 镜像 jcode {@code session_count} 的频度信号。
     */
    public long weightedAccessCount() {
        return readCount + 2 * editCount;
    }
}
