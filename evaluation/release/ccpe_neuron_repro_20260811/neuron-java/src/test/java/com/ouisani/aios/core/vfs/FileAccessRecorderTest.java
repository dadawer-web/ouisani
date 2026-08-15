package com.ouisani.aios.core.vfs;

import com.ouisani.aios.core.ranking.FileAccessRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FileAccessRecorder 单测 — 验证 ConcurrentHashMap compute 原子更新 + touchRead/touchEdit 行为。
 */
class FileAccessRecorderTest {

    @Test
    void touchRead_newPath_createsRecord() {
        FileAccessRecorder r = new FileAccessRecorder();
        r.touchRead("/foo/bar", 1000L);
        FileAccessRecord rec = r.get("/foo/bar");
        assertNotNull(rec);
        assertEquals("/foo/bar", rec.path());
        assertEquals(1000L, rec.lastReadMs());
        assertEquals(0L, rec.lastEditMs());
        assertEquals(1L, rec.readCount());
        assertEquals(0L, rec.editCount());
    }

    @Test
    void touchRead_existingPath_incrementsCount() {
        FileAccessRecorder r = new FileAccessRecorder();
        r.touchRead("/foo", 1000L);
        r.touchRead("/foo", 2000L);
        FileAccessRecord rec = r.get("/foo");
        assertEquals(2000L, rec.lastReadMs(), "lastReadMs 更新为最新");
        assertEquals(2L, rec.readCount(), "readCount +1");
        assertEquals(0L, rec.editCount(), "editCount 不变");
    }

    @Test
    void touchEdit_separateFromRead() {
        FileAccessRecorder r = new FileAccessRecorder();
        r.touchEdit("/foo", 1000L);
        FileAccessRecord rec = r.get("/foo");
        assertEquals(0L, rec.lastReadMs(), "未读 → lastReadMs=0");
        assertEquals(1000L, rec.lastEditMs(), "lastEditMs 更新");
        assertEquals(0L, rec.readCount());
        assertEquals(1L, rec.editCount());
    }

    @Test
    void touchReadAndEdit_bothTracked() {
        FileAccessRecorder r = new FileAccessRecorder();
        r.touchRead("/foo", 1000L);
        r.touchEdit("/foo", 2000L);
        r.touchRead("/foo", 3000L);
        FileAccessRecord rec = r.get("/foo");
        assertEquals(3000L, rec.lastReadMs());
        assertEquals(2000L, rec.lastEditMs());
        assertEquals(2L, rec.readCount());
        assertEquals(1L, rec.editCount());
        assertEquals(3000L, rec.lastAccessMs(), "lastAccess = max(read, edit)");
    }

    @Test
    void snapshot_returnsAllRecords() {
        FileAccessRecorder r = new FileAccessRecorder();
        r.touchRead("/a", 1000L);
        r.touchEdit("/b", 2000L);
        r.touchRead("/c", 3000L);
        List<FileAccessRecord> snapshot = r.snapshot();
        assertEquals(3, snapshot.size(), "3 条独立路径");
    }

    @Test
    void get_unknownPath_returnsNull() {
        FileAccessRecorder r = new FileAccessRecorder();
        assertNull(r.get("/nonexistent"));
    }

    @Test
    void clear_emptiesAllRecords() {
        FileAccessRecorder r = new FileAccessRecorder();
        r.touchRead("/a", 1000L);
        r.touchEdit("/b", 2000L);
        assertEquals(2, r.snapshot().size());
        r.clear();
        assertTrue(r.snapshot().isEmpty(), "clear 后为空");
        assertNull(r.get("/a"));
    }

    @Test
    void touchRead_nullPath_noOp() {
        FileAccessRecorder r = new FileAccessRecorder();
        r.touchRead(null, 1000L);
        assertTrue(r.snapshot().isEmpty(), "null path 无操作");
    }

    @Test
    void touchEdit_blankPath_noOp() {
        FileAccessRecorder r = new FileAccessRecorder();
        r.touchEdit("   ", 1000L);
        assertTrue(r.snapshot().isEmpty(), "blank path 无操作");
    }
}
