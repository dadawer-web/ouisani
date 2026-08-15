package com.ouisani.aios.core.permission;

import com.google.gson.JsonParser;
import com.ouisani.aios.core.network.EventBus;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolPermissionChannel 单元测试 — 验证 CompletableFuture 审批 + 三态 + 零回归 fallback。
 * <p>
 * 关键不变式：无 EventBus 订阅者时立即返回 ALLOW_ONCE（与 QueryEngine 原「ASK 自动放行」一致），
 * 保证所有不连前端的单测/headless 运行零回归。
 */
class ToolPermissionChannelTest {

    /** 轮询等待条件成立（避免引入 Awaitility 依赖）。 */
    private static void waitFor(java.util.function.Supplier<Boolean> cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.get()) return;
            Thread.sleep(20);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  零回归 fallback
    //════════════════════════════════════════════════════════════════

    @Test
    void requestApproval_noSubscriber_fallsBackToAllowOnce() {
        // 前置：确保本通道无残留订阅者（其他测试可能泄漏）
        assertEquals(0, EventBus.instance().subscriberCount(ToolPermissionChannel.CHANNEL),
                "测试前置：审批通道不应有订阅者");
        ToolPermissionChannel.ApprovalResponse resp = ToolPermissionChannel.requestApproval(
                "agent1", "send_message", "#general", "test");
        assertEquals(ToolPermissionChannel.ApprovalResponse.ALLOW_ONCE, resp,
                "无订阅者时必须 fallback 到 ALLOW_ONCE（零回归）");
    }

    // ════════════════════════════════════════════════════════════════
    //  有订阅者 → respond 决定结果
    //════════════════════════════════════════════════════════════════

    @Test
    void requestApproval_withSubscriber_respondAlwaysTarget_returnsAlwaysTarget() throws Exception {
        AtomicReference<String> capturedId = new AtomicReference<>();
        Consumer<String> handler = payload -> {
            try {
                capturedId.set(JsonParser.parseString(payload).getAsJsonObject().get("requestId").getAsString());
            } catch (Exception ignored) {}
        };
        EventBus.instance().subscribe(ToolPermissionChannel.CHANNEL, handler);
        try {
            CompletableFuture<ToolPermissionChannel.ApprovalResponse> result = CompletableFuture.supplyAsync(() ->
                    ToolPermissionChannel.requestApproval("agent1", "send_message", "#general", "test"));
            waitFor(() -> capturedId.get() != null, 2000);
            assertNotNull(capturedId.get(), "handler 应捕获 requestId");

            boolean ok = ToolPermissionChannel.respond(capturedId.get(),
                    ToolPermissionChannel.ApprovalResponse.ALWAYS_TARGET);
            assertTrue(ok, "respond 应成功命中 pending future");

            assertEquals(ToolPermissionChannel.ApprovalResponse.ALWAYS_TARGET,
                    result.get(3, TimeUnit.SECONDS));
        } finally {
            EventBus.instance().unsubscribe(ToolPermissionChannel.CHANNEL, handler);
        }
    }

    @Test
    void requestApproval_withSubscriber_respondDeny_returnsDeny() throws Exception {
        AtomicReference<String> capturedId = new AtomicReference<>();
        Consumer<String> handler = payload -> {
            try {
                capturedId.set(JsonParser.parseString(payload).getAsJsonObject().get("requestId").getAsString());
            } catch (Exception ignored) {}
        };
        EventBus.instance().subscribe(ToolPermissionChannel.CHANNEL, handler);
        try {
            CompletableFuture<ToolPermissionChannel.ApprovalResponse> result = CompletableFuture.supplyAsync(() ->
                    ToolPermissionChannel.requestApproval("agent1", "bash", null, "dangerous"));
            waitFor(() -> capturedId.get() != null, 2000);

            ToolPermissionChannel.respond(capturedId.get(), ToolPermissionChannel.ApprovalResponse.DENY);
            assertEquals(ToolPermissionChannel.ApprovalResponse.DENY, result.get(3, TimeUnit.SECONDS));
        } finally {
            EventBus.instance().unsubscribe(ToolPermissionChannel.CHANNEL, handler);
        }
    }

    @Test
    void requestApproval_withSubscriber_respondAllowOnce_returnsAllowOnce() throws Exception {
        AtomicReference<String> capturedId = new AtomicReference<>();
        Consumer<String> handler = payload -> capturedId.set(
                JsonParser.parseString(payload).getAsJsonObject().get("requestId").getAsString());
        EventBus.instance().subscribe(ToolPermissionChannel.CHANNEL, handler);
        try {
            CompletableFuture<ToolPermissionChannel.ApprovalResponse> result = CompletableFuture.supplyAsync(() ->
                    ToolPermissionChannel.requestApproval("agent1", "file_write", "/a/b", "test"));
            waitFor(() -> capturedId.get() != null, 2000);

            ToolPermissionChannel.respond(capturedId.get(), ToolPermissionChannel.ApprovalResponse.ALLOW_ONCE);
            assertEquals(ToolPermissionChannel.ApprovalResponse.ALLOW_ONCE, result.get(3, TimeUnit.SECONDS));
        } finally {
            EventBus.instance().unsubscribe(ToolPermissionChannel.CHANNEL, handler);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  超时 → DENY
    //════════════════════════════════════════════════════════════════

    @Test
    void requestApproval_withSubscriber_timeout_returnsDeny() throws Exception {
        Consumer<String> handler = payload -> {};
        EventBus.instance().subscribe(ToolPermissionChannel.CHANNEL, handler);
        try {
            // 1 秒超时，不 respond → 必须 DENY
            ToolPermissionChannel.ApprovalResponse resp = ToolPermissionChannel.requestApproval(
                    "agent1", "send_message", "#general", "test",
                    0, java.util.List.of(), false, 1);
            assertEquals(ToolPermissionChannel.ApprovalResponse.DENY, resp,
                    "超时未 respond 必须返回 DENY");
        } finally {
            EventBus.instance().unsubscribe(ToolPermissionChannel.CHANNEL, handler);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  respond 边界
    //════════════════════════════════════════════════════════════════

    @Test
    void respond_unknownRequestId_returnsFalse() {
        assertFalse(ToolPermissionChannel.respond("perm_nonexistent",
                ToolPermissionChannel.ApprovalResponse.ALLOW_ONCE),
                "未知 requestId 应返回 false");
    }

    @Test
    void safeValueOf_unknownString_returnsAllowOnce() {
        assertEquals(ToolPermissionChannel.ApprovalResponse.ALLOW_ONCE,
                ToolPermissionChannel.ApprovalResponse.safeValueOf("garbage"));
        assertEquals(ToolPermissionChannel.ApprovalResponse.ALLOW_ONCE,
                ToolPermissionChannel.ApprovalResponse.safeValueOf(null));
        assertEquals(ToolPermissionChannel.ApprovalResponse.ALWAYS_TARGET,
                ToolPermissionChannel.ApprovalResponse.safeValueOf("always_target"),
                "大小写不敏感应正确解析");
    }
}
