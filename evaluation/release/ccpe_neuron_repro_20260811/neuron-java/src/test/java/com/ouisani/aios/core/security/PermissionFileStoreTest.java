package com.ouisani.aios.core.security;

import com.ouisani.aios.core.permission.ActionTier;
import com.ouisani.aios.core.permission.Decision;
import com.ouisani.aios.core.permission.PermissionRequest;
import com.ouisani.aios.core.permission.Urgency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PermissionFileStore 持久化测试 — 覆盖 queue/history 读写、recordPermissionViaFile 移除+追加、
 * expireStale 过期清理、空文件回退。
 * <p>
 * 用 System.setProperty("aios.safety.dir", tmpDir) 隔离测试目录。
 */
class PermissionFileStoreTest {

    private Path tmpDir;

    @BeforeEach
    void setUp() throws IOException {
        tmpDir = Files.createTempDirectory("permission-file-store-test");
        System.setProperty("aios.safety.dir", tmpDir.toString());
    }

    // ── queue 读写 ──

    @Test
    void read_queue_returns_empty_when_file_missing() {
        List<PermissionRequest> queue = PermissionFileStore.readQueue();
        assertTrue(queue.isEmpty());
    }

    @Test
    void write_then_read_queue_round_trip() {
        PermissionRequest req = sampleRequest("req_1", "file_read");
        PermissionFileStore.writeQueue(List.of(req));

        List<PermissionRequest> read = PermissionFileStore.readQueue();
        assertEquals(1, read.size());
        assertEquals("req_1", read.get(0).requestId());
        assertEquals("file_read", read.get(0).action());
    }

    @Test
    void enqueue_request_appends_to_existing_queue() {
        PermissionFileStore.enqueueRequest(sampleRequest("req_1", "grep"));
        PermissionFileStore.enqueueRequest(sampleRequest("req_2", "glob"));

        List<PermissionRequest> read = PermissionFileStore.readQueue();
        assertEquals(2, read.size());
        assertEquals("req_1", read.get(0).requestId());
        assertEquals("req_2", read.get(1).requestId());
    }

    @Test
    void enqueue_null_request_is_noop() {
        PermissionFileStore.enqueueRequest(null);
        assertTrue(PermissionFileStore.readQueue().isEmpty());
    }

    // ── history 读写 ──

    @Test
    void read_history_returns_empty_when_file_missing() {
        assertTrue(PermissionFileStore.readHistory().isEmpty());
    }

    @Test
    void append_history_accumulates() {
        Decision d1 = sampleDecision("req_1", true, "auto");
        Decision d2 = sampleDecision("req_2", false, "user_sync");

        PermissionFileStore.appendHistory(d1);
        PermissionFileStore.appendHistory(d2);

        List<Decision> history = PermissionFileStore.readHistory();
        assertEquals(2, history.size());
        assertEquals("req_1", history.get(0).requestId());
        assertTrue(history.get(0).approved());
        assertEquals("req_2", history.get(1).requestId());
        assertFalse(history.get(1).approved());
    }

    @Test
    void append_null_history_is_noop() {
        PermissionFileStore.appendHistory(null);
        assertTrue(PermissionFileStore.readHistory().isEmpty());
    }

    // ── recordPermissionViaFile — 镜像 safety.rs:379-416 ──

    @Test
    void record_permission_via_file_removes_from_queue_and_appends_history() {
        PermissionFileStore.enqueueRequest(sampleRequest("req_42", "bash"));
        assertEquals(1, PermissionFileStore.readQueue().size());

        boolean removed = PermissionFileStore.recordPermissionViaFile(
                "req_42", true, "file_async", "approved via IMAP reply"
        );

        assertTrue(removed);
        assertTrue(PermissionFileStore.readQueue().isEmpty());
        List<Decision> history = PermissionFileStore.readHistory();
        assertEquals(1, history.size());
        assertEquals("req_42", history.get(0).requestId());
        assertTrue(history.get(0).approved());
        assertEquals("file_async", history.get(0).decidedVia());
        assertEquals("approved via IMAP reply", history.get(0).reason());
    }

    @Test
    void record_permission_via_file_returns_false_when_id_not_found() {
        boolean removed = PermissionFileStore.recordPermissionViaFile(
                "nonexistent", true, "file_async", ""
        );
        assertFalse(removed);
        assertTrue(PermissionFileStore.readHistory().isEmpty());
    }

    @Test
    void record_permission_via_file_blank_id_returns_false() {
        assertFalse(PermissionFileStore.recordPermissionViaFile("", true, "x", ""));
        assertFalse(PermissionFileStore.recordPermissionViaFile(null, true, "x", ""));
    }

    // ── expireStale — 镜像 safety.rs:420-470 ──

    @Test
    void expire_stale_removes_expired_and_writes_history() {
        long pastTime = System.currentTimeMillis() - 10_000;  // 10s 前
        PermissionRequest expired = new PermissionRequest(
                "req_old", "bash", "desc", Urgency.High, ActionTier.RequiresPermission,
                pastTime, "agent_1"
        );
        PermissionRequest fresh = new PermissionRequest(
                "req_new", "grep", "desc", Urgency.Normal, ActionTier.AutoAllowed,
                System.currentTimeMillis(), "agent_1"
        );
        PermissionFileStore.writeQueue(List.of(expired, fresh));

        List<String> expiredIds = PermissionFileStore.expireStale(5_000, "timeout");  // 5s 阈值

        assertEquals(1, expiredIds.size());
        assertEquals("req_old", expiredIds.get(0));

        // queue 仅剩 fresh
        List<PermissionRequest> queue = PermissionFileStore.readQueue();
        assertEquals(1, queue.size());
        assertEquals("req_new", queue.get(0).requestId());

        // history 多了一条过期 Decision
        List<Decision> history = PermissionFileStore.readHistory();
        assertEquals(1, history.size());
        assertEquals("req_old", history.get(0).requestId());
        assertFalse(history.get(0).approved());
        assertEquals("timeout", history.get(0).decidedVia());
    }

    @Test
    void expire_stale_no_expired_returns_empty() {
        PermissionRequest fresh = new PermissionRequest(
                "req_new", "grep", "desc", Urgency.Normal, ActionTier.AutoAllowed,
                System.currentTimeMillis(), "agent_1"
        );
        PermissionFileStore.writeQueue(List.of(fresh));

        List<String> expired = PermissionFileStore.expireStale(60_000, "timeout");
        assertTrue(expired.isEmpty());
    }

    @Test
    void expire_stale_empty_queue_returns_empty() {
        List<String> expired = PermissionFileStore.expireStale(60_000, "timeout");
        assertTrue(expired.isEmpty());
    }

    // ── 边界 ──

    @Test
    void write_empty_queue_creates_file() throws IOException {
        PermissionFileStore.writeQueue(List.of());
        assertTrue(Files.exists(PermissionFileStore.queuePath()));
    }

    @Test
    void write_null_queue_treated_as_empty() {
        PermissionFileStore.writeQueue(null);
        assertTrue(PermissionFileStore.readQueue().isEmpty());
    }

    @Test
    void queue_and_history_independent_files() {
        PermissionFileStore.enqueueRequest(sampleRequest("req_1", "grep"));
        PermissionFileStore.appendHistory(sampleDecision("req_2", true, "auto"));

        assertEquals(PermissionFileStore.queuePath(), PermissionFileStore.queuePath());
        assertNotEquals(PermissionFileStore.queuePath(), PermissionFileStore.historyPath());
        assertEquals(1, PermissionFileStore.readQueue().size());
        assertEquals(1, PermissionFileStore.readHistory().size());
    }

    // ── 工具方法 ──

    private PermissionRequest sampleRequest(String id, String action) {
        return new PermissionRequest(
                id, action, "test description", Urgency.Normal,
                ActionTier.RequiresPermission, System.currentTimeMillis(), "test_agent"
        );
    }

    private Decision sampleDecision(String id, boolean approved, String via) {
        return new Decision(
                id, "test_action", approved, System.currentTimeMillis(),
                via, "test reason", Urgency.Normal, ActionTier.RequiresPermission
        );
    }
}
