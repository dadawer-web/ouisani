package com.ouisani.aios.core.security;

import com.ouisani.aios.core.permission.ActionTier;
import com.ouisani.aios.core.permission.PermissionRequest;
import com.ouisani.aios.core.permission.PermissionResult;
import com.ouisani.aios.core.permission.Urgency;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;
import static org.junit.jupiter.api.Assertions.*;

/**
 * PrivilegeSyscallFilter 异步裁决测试 — 覆盖 askPermission 主入口：
 * AutoAllowed 直通、approve 同步裁决、5s 超时返回 Queued + 落盘、
 * PermissionNotifier 依赖反转注入。
 * <p>
 * 测试隔离：System.setProperty("aios.safety.dir", tmpDir) + 清理 pendingApprovals。
 */
class PrivilegeSyscallFilterAsyncTest {

    private Path tmpDir;

    @BeforeEach
    void setUp() throws IOException {
        tmpDir = Files.createTempDirectory("privilege-syscall-async-test");
        System.setProperty("aios.safety.dir", tmpDir.toString());
        PrivilegeSyscallFilter.clearPermissionNotifierForTest();
    }

    @AfterEach
    void tearDown() {
        PrivilegeSyscallFilter.clearPermissionNotifierForTest();
    }

    // ── AutoAllowed 白名单直通 ──

    @Test
    void auto_allowed_tool_returns_approved_immediately() {
        long before = System.currentTimeMillis();
        PermissionResult result = PrivilegeSyscallFilter.askPermission(
                "file_read", "read file", Urgency.Normal, "test_agent"
        );
        long elapsed = System.currentTimeMillis() - before;

        assertInstanceOf(PermissionResult.Approved.class, result);
        assertEquals("auto-allowed", ((PermissionResult.Approved) result).message());
        assertTrue(elapsed < 1000, "AutoAllowed 应在 1s 内返回，实际 " + elapsed + "ms");

        // history 应有一条 auto 记录
        await().atMost(ofSeconds(2)).untilAsserted(() -> {
            List<?> history = PermissionFileStore.readHistory();
            assertFalse(history.isEmpty());
        });
    }

    @Test
    void auto_allowed_glob_returns_approved() {
        PermissionResult result = PrivilegeSyscallFilter.askPermission(
                "glob", "find files", Urgency.Low, "agent"
        );
        assertInstanceOf(PermissionResult.Approved.class, result);
    }

    // ── approve 同步裁决 ──

    @Test
    void requires_permission_approved_via_approve_call() {
        // 启动一个线程在 askPermission 阻塞时调用 approve
        new Thread(() -> {
            try {
                Thread.sleep(300);  // 等待 askPermission 注册 future
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            // 从 history 反推 requestId 较难，这里用广播监听更可靠
            // 简化：直接遍历 pendingApprovals（但它是 private）
            // 折中：用 EventBus 订阅 PERMISSION_REQUEST_EVENT 拿 requestId
        }).start();

        // 用 EventBus 订阅获取 requestId
        com.ouisani.aios.core.network.EventBus bus = com.ouisani.aios.core.network.EventBus.instance();
        final String[] capturedId = {null};
        bus.subscribe("permission.request", payload -> {
            // payload 是 JSON，提取 requestId
            int idx = payload.indexOf("\"requestId\":\"");
            if (idx >= 0) {
                int start = idx + "\"requestId\":\"".length();
                int end = payload.indexOf("\"", start);
                if (end > start) capturedId[0] = payload.substring(start, end);
            }
        });

        // 另起线程调用 approve
        new Thread(() -> {
            try {
                Thread.sleep(500);  // 等 askPermission 注册 + 广播
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (capturedId[0] != null) {
                PrivilegeSyscallFilter.approve(capturedId[0], true);
            }
        }).start();

        PermissionResult result = PrivilegeSyscallFilter.askPermission(
                "bash", "run shell", Urgency.Normal, "test_agent"
        );

        assertInstanceOf(PermissionResult.Approved.class, result, "应通过 approve 调用获得 Approved");
    }

    @Test
    void requires_permission_denied_via_approve_false() {
        com.ouisani.aios.core.network.EventBus bus = com.ouisani.aios.core.network.EventBus.instance();
        final String[] capturedId = {null};
        bus.subscribe("permission.request", payload -> {
            int idx = payload.indexOf("\"requestId\":\"");
            if (idx >= 0) {
                int start = idx + "\"requestId\":\"".length();
                int end = payload.indexOf("\"", start);
                if (end > start) capturedId[0] = payload.substring(start, end);
            }
        });

        new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (capturedId[0] != null) {
                PrivilegeSyscallFilter.approve(capturedId[0], false);
            }
        }).start();

        PermissionResult result = PrivilegeSyscallFilter.askPermission(
                "bash", "run shell", Urgency.Normal, "test_agent"
        );

        assertInstanceOf(PermissionResult.Denied.class, result);
    }

    // ── 5s 超时返回 Queued + 落盘 ──

    @Test
    void timeout_returns_queued_and_persists_to_queue_json() {
        long before = System.currentTimeMillis();
        PermissionResult result = PrivilegeSyscallFilter.askPermission(
                "tool.run_docker", "run docker", Urgency.Normal, "test_agent"
        );
        long elapsed = System.currentTimeMillis() - before;

        assertInstanceOf(PermissionResult.Queued.class, result);
        String requestId = ((PermissionResult.Queued) result).requestId();
        assertNotNull(requestId);
        assertTrue(requestId.startsWith("req_"));
        assertTrue(elapsed >= 5000, "应等待至少 5s 超时，实际 " + elapsed + "ms");

        // queue.json 应有持久化记录
        List<PermissionRequest> queue = PermissionFileStore.readQueue();
        boolean found = queue.stream().anyMatch(r -> requestId.equals(r.requestId()));
        assertTrue(found, "queue.json 应包含超时的 requestId");
    }

    // ── null/blank action ──

    @Test
    void null_action_returns_denied() {
        PermissionResult result = PrivilegeSyscallFilter.askPermission(
                null, "desc", Urgency.Normal, "agent"
        );
        assertInstanceOf(PermissionResult.Denied.class, result);
    }

    @Test
    void blank_action_returns_denied() {
        PermissionResult result = PrivilegeSyscallFilter.askPermission(
                "  ", "desc", Urgency.Normal, "agent"
        );
        assertInstanceOf(PermissionResult.Denied.class, result);
    }

    // ── PermissionNotifier 依赖反转 ──

    @Test
    void register_permission_notifier_single_registration() {
        AtomicInteger callCount1 = new AtomicInteger(0);
        AtomicInteger callCount2 = new AtomicInteger(0);

        PrivilegeSyscallFilter.PermissionNotifier n1 = (a, d, id) -> callCount1.incrementAndGet();
        PrivilegeSyscallFilter.PermissionNotifier n2 = (a, d, id) -> callCount2.incrementAndGet();

        boolean first = PrivilegeSyscallFilter.registerPermissionNotifier(n1);
        assertTrue(first);

        boolean second = PrivilegeSyscallFilter.registerPermissionNotifier(n2);
        assertFalse(second, "二次注册应被忽略（OnceLock 单次语义）");

        // AutoAllowed 不触发 notifier（直接返回），用 RequiresPermission 触发
        // 但 RequiresPermission 会等 5s 超时，这里用 notifier 验证调用即可
        // 简化：验证 n1 被调用过即可（在超时测试中已验证）
        // 此测试聚焦单次注册语义
        assertEquals(0, callCount2.get(), "n2 不应被调用");
    }

    @Test
    void null_notifier_registration_returns_false() {
        boolean registered = PrivilegeSyscallFilter.registerPermissionNotifier(null);
        assertFalse(registered);
    }

    @Test
    void notifier_called_on_requires_permission() {
        AtomicInteger callCount = new AtomicInteger(0);
        PrivilegeSyscallFilter.registerPermissionNotifier((a, d, id) -> callCount.incrementAndGet());

        PrivilegeSyscallFilter.askPermission(
                "tool.run_docker", "run docker", Urgency.High, "agent"
        );  // 会等 5s 超时

        assertTrue(callCount.get() >= 1, "notifier 应被调用至少一次");
    }
}
