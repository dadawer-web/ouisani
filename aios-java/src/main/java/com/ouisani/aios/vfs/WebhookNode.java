package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsNode;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Webhook 节点 — AIOS 的外部事件接收器。
 * <p>
 * 挂载在 VFS 中，动态注册 HTTP POST 路由（如 {@code /webhook/{webhookId}}），
 * 外部系统通过 HTTP POST 将事件推送到此节点，Agent 通过 VFS read 读取。
 *
 * <h3>OS 类比</h3>
 * 类比 Linux 的 {@code /dev/input} — 外部硬件中断转换为文件可读事件。
 * Webhook 是"网络中断"，将外部 HTTP 请求转换为 VFS 可读数据。
 */
public non-sealed class WebhookNode implements VfsNode {

    private static final Logger log = LoggerFactory.getLogger(WebhookNode.class);

    private final String path;
    private final String webhookId;
    private int ownerUid;
    private int permissions;
    private final ConcurrentLinkedQueue<String> incomingQueue = new ConcurrentLinkedQueue<>();

    public WebhookNode(String path, String webhookId, Javalin app) {
        this.path = path;
        this.webhookId = webhookId;
        this.ownerUid = 0;
        this.permissions = 0666;

        // 动态注册 Javalin 路由
        String route = "/webhook/" + webhookId;
        app.post(route, ctx -> {
            String body = ctx.body();
            incomingQueue.add(body);
            System.out.printf("  🪝 [WebhookNode] 收到 POST /webhook/%s (%d chars)%n", webhookId, body.length());
            log.info("[WebhookNode] Received webhook: id={}, bodyLen={}", webhookId, body.length());
            ctx.status(200);
            ctx.result("{\"status\":\"accepted\"}");
        });

        System.out.printf("  🪝 [WebhookNode] 路由已注册: POST /webhook/%s%n", webhookId);
        log.info("[WebhookNode] Registered route: POST /webhook/{}", webhookId);
    }

    @Override
    public VfsNodeType nodeType() {
        return VfsNodeType.WEBHOOK;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public int ownerUid() {
        return ownerUid;
    }

    @Override
    public void setOwnerUid(int uid) {
        this.ownerUid = uid;
    }

    @Override
    public int permissions() {
        return permissions;
    }

    @Override
    public void setPermissions(int perm) {
        this.permissions = perm;
    }

    @Override
    public String read() {
        // 阻塞等待直到有数据到达（最多 5 秒）
        String payload = incomingQueue.poll();
        if (payload != null) {
            log.debug("WebhookNode.read: path={}, dequeued payload ({} chars)", path, payload.length());
            return payload;
        }

        // 短暂等待新数据
        try {
            CountDownLatch latch = new CountDownLatch(1);
            Thread.ofVirtual().start(() -> {
                while (incomingQueue.isEmpty() && !Thread.currentThread().isInterrupted()) {
                    try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                }
                latch.countDown();
            });
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        payload = incomingQueue.poll();
        if (payload != null) {
            log.debug("WebhookNode.read: path={}, dequeued after wait ({} chars)", path, payload.length());
            return payload;
        }

        return "{\"status\":\"timeout\",\"message\":\"No webhook data received\"}";
    }

    @Override
    public boolean write(String data) {
        // WebhookNode 是外部写入、Agent 读取的模式
        // 但也允许 Agent 手动压入数据（用于测试）
        incomingQueue.add(data);
        log.debug("WebhookNode.write: path={}, manually pushed data ({} chars)", path, data.length());
        return true;
    }
}
