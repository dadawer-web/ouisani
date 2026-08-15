package com.ouisani.aios.core.network;

import com.ouisani.aios.core.memory.VersionedMemoryStore;
import com.ouisani.aios.core.memory.providers.MemoryDomain;
import com.ouisani.aios.core.memory.providers.MemoryRecord;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Supplier;

/**
 * 记忆查看器 HTTP 路由 — P3「看得见改得了」。
 * <p>
 * 三个端点：
 * <ul>
 *   <li>{@code GET /api/memory/{agentId}} — 列出 agent 的所有当前记忆</li>
 *   <li>{@code PATCH /api/memory/{agentId}/{key}} — 更新单条 confidence/domain</li>
 *   <li>{@code DELETE /api/memory/{agentId}/{key}} — 删除单条记忆</li>
 * </ul>
 * <p>
 * <b>设计原则</b>：
 * <ul>
 *   <li><b>可测试</b>：每个端点的核心逻辑提取为 static handler 方法，返回
 *       {@link RouteResult}（HTTP 状态码 + JSON body），可在不启动 Javalin 的情况下单元测试</li>
 *   <li><b>非侵入式</b>：通过 {@code Supplier<VersionedMemoryStore>} 注入 store，
 *       未注入时返回 503 Service Unavailable，不影响其他路由</li>
 *   <li><b>JSON 手写</b>：与 {@link HitlStateRoutes} 风格一致，不引入 Jackson</li>
 *   <li><b>URL-decode path params</b>：key 可能含特殊字符（如斜杠编码），用
 *       {@link java.net.URLDecoder} 解码</li>
 * </ul>
 * <p>
 * OS 类比：相当于 Linux 的 {@code /proc/<pid>/maps} — 让用户态可查看并修改
 * 内核数据结构（VersionedMemoryStore）的运行时状态。
 */
public final class MemoryViewerRoutes {

    private static final Logger log = LoggerFactory.getLogger(MemoryViewerRoutes.class);

    private MemoryViewerRoutes() {}

    /**
     * 挂载记忆查看器路由到 Javalin 应用。
     * <p>
     * 使用 {@link VersionedMemoryStore#getPrimaryStore()} 作为默认 store 来源，
     * 应用启动时需先调用 {@link VersionedMemoryStore#setPrimaryStore} 注入。
     *
     * @param app Javalin 应用
     */
    public static void attachTo(Javalin app) {
        attachTo(app, VersionedMemoryStore::getPrimaryStore);
    }

    /**
     * 挂载记忆查看器路由 — 可自定义 store supplier（测试用）。
     *
     * @param app            Javalin 应用
     * @param storeSupplier  store 来源；返回 null 时端点返回 503
     */
    public static void attachTo(Javalin app, Supplier<VersionedMemoryStore> storeSupplier) {
        // ── GET /api/memory/{agentId} — 列出当前记忆 ──
        app.get("/api/memory/{agentId}", ctx -> {
            String agentId = ctx.pathParam("agentId");
            RouteResult rr = handleList(storeSupplier, agentId);
            applyResult(ctx, rr);
        });

        // ── PATCH /api/memory/{agentId}/{key} — 更新 confidence/domain ──
        app.patch("/api/memory/{agentId}/{key}", ctx -> {
            String agentId = ctx.pathParam("agentId");
            String key = decodeKey(ctx.pathParam("key"));
            RouteResult rr = handlePatch(storeSupplier, agentId, key, ctx.body());
            applyResult(ctx, rr);
        });

        // ── DELETE /api/memory/{agentId}/{key} — 删除单条 ──
        app.delete("/api/memory/{agentId}/{key}", ctx -> {
            String agentId = ctx.pathParam("agentId");
            String key = decodeKey(ctx.pathParam("key"));
            RouteResult rr = handleDelete(storeSupplier, agentId, key);
            applyResult(ctx, rr);
        });

        // ── OPTIONS 预检（CORS 支持，便于浏览器直接访问） ──
        app.options("/api/memory/{agentId}", ctx -> corsOptions(ctx));
        app.options("/api/memory/{agentId}/{key}", ctx -> corsOptions(ctx));

        log.info("[MemoryViewer] 路由已挂载: GET/PATCH/DELETE /api/memory/{agentId}[/{key}]");
        System.out.println("  ✓ [MemoryViewer] 路由: GET/PATCH/DELETE /api/memory/{agentId}[/{key}]");
    }

    // ════════════════════════════════════════════════════════════════
    //  可测试的 handler — 单元测试直接调用，不依赖 Javalin
    // ════════════════════════════════════════════════════════════════

    /** 路由处理结果 — HTTP 状态码 + JSON body。 */
    public record RouteResult(int status, String body) {}

    /**
     * 处理 GET /api/memory/{agentId} — 列出当前记忆。
     */
    public static RouteResult handleList(Supplier<VersionedMemoryStore> supplier, String agentId) {
        VersionedMemoryStore store = supplier.get();
        if (store == null) {
            return new RouteResult(503, "{\"error\":\"primary store not configured\"}");
        }
        if (agentId == null || agentId.isBlank()) {
            return new RouteResult(400, "{\"error\":\"agentId required\"}");
        }
        List<MemoryRecord> records = store.listCurrent(agentId);
        StringBuilder sb = new StringBuilder();
        sb.append("{\"agentId\":\"").append(escapeJson(agentId)).append("\",");
        sb.append("\"count\":").append(records.size()).append(",");
        sb.append("\"memories\":[");
        for (int i = 0; i < records.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(recordToJson(records.get(i)));
        }
        sb.append("]}");
        return new RouteResult(200, sb.toString());
    }

    /**
     * 处理 PATCH /api/memory/{agentId}/{key} — 更新 confidence/domain。
     * <p>
     * Body 期望 JSON：{@code {"confidence":0.8,"domain":"USER"}}（两字段都可选）。
     */
    public static RouteResult handlePatch(Supplier<VersionedMemoryStore> supplier,
                                            String agentId, String key, String body) {
        VersionedMemoryStore store = supplier.get();
        if (store == null) {
            return new RouteResult(503, "{\"error\":\"primary store not configured\"}");
        }
        if (agentId == null || agentId.isBlank()) {
            return new RouteResult(400, "{\"error\":\"agentId required\"}");
        }
        if (key == null || key.isBlank()) {
            return new RouteResult(400, "{\"error\":\"key required\"}");
        }

        // 解析 body — 提取 confidence 与 domain（容错，缺失视为不更新）
        Double newConfidence = null;
        MemoryDomain newDomain = null;
        if (body != null && !body.isBlank()) {
            try {
                var jsonObj = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
                if (jsonObj.has("confidence") && !jsonObj.get("confidence").isJsonNull()) {
                    newConfidence = jsonObj.get("confidence").getAsDouble();
                    if (newConfidence < 0.0 || newConfidence > 1.0) {
                        return new RouteResult(400,
                                "{\"error\":\"confidence must be in [0.0, 1.0]\"}");
                    }
                }
                if (jsonObj.has("domain") && !jsonObj.get("domain").isJsonNull()) {
                    String domStr = jsonObj.get("domain").getAsString();
                    try {
                        newDomain = MemoryDomain.valueOf(domStr.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        return new RouteResult(400,
                                "{\"error\":\"domain must be USER or AGENT\"}");
                    }
                }
            } catch (Exception e) {
                return new RouteResult(400, "{\"error\":\"invalid JSON: "
                        + escapeJson(e.getMessage()) + "\"}");
            }
        }

        if (newConfidence == null && newDomain == null) {
            return new RouteResult(400,
                    "{\"error\":\"at least one of confidence/domain must be provided\"}");
        }

        boolean ok = store.updateMetadata(agentId, key, newConfidence, newDomain);
        if (!ok) {
            return new RouteResult(404, "{\"error\":\"key not found\",\"key\":\""
                    + escapeJson(key) + "\"}");
        }

        // 返回更新后的记录
        MemoryRecord updated = store.current(agentId, key);
        String recordJson = updated != null ? recordToJson(updated) : "{}";
        return new RouteResult(200, "{\"ok\":true,\"record\":" + recordJson + "}");
    }

    /**
     * 处理 DELETE /api/memory/{agentId}/{key} — 删除单条记忆。
     */
    public static RouteResult handleDelete(Supplier<VersionedMemoryStore> supplier,
                                             String agentId, String key) {
        VersionedMemoryStore store = supplier.get();
        if (store == null) {
            return new RouteResult(503, "{\"error\":\"primary store not configured\"}");
        }
        if (agentId == null || agentId.isBlank()) {
            return new RouteResult(400, "{\"error\":\"agentId required\"}");
        }
        if (key == null || key.isBlank()) {
            return new RouteResult(400, "{\"error\":\"key required\"}");
        }

        boolean ok = store.delete(agentId, key);
        if (!ok) {
            return new RouteResult(404, "{\"error\":\"key not found\",\"key\":\""
                    + escapeJson(key) + "\"}");
        }
        return new RouteResult(200, "{\"ok\":true,\"deletedKey\":\""
                + escapeJson(key) + "\"}");
    }

    // ── 内部工具 ──

    /** 应用 RouteResult 到 Javalin ctx */
    private static void applyResult(io.javalin.http.Context ctx, RouteResult rr) {
        ctx.status(rr.status());
        ctx.header("Access-Control-Allow-Origin", "*");
        ctx.header("Access-Control-Allow-Methods", "GET, PATCH, DELETE, OPTIONS");
        ctx.header("Access-Control-Allow-Headers", "Content-Type");
        ctx.contentType("application/json");
        ctx.result(rr.body());
    }

    /** CORS 预检响应 */
    private static void corsOptions(io.javalin.http.Context ctx) {
        ctx.header("Access-Control-Allow-Origin", "*");
        ctx.header("Access-Control-Allow-Methods", "GET, PATCH, DELETE, OPTIONS");
        ctx.header("Access-Control-Allow-Headers", "Content-Type");
        ctx.result("");
    }

    /** URL-decode path param — key 可能被编码（如 %2F 表示 /） */
    private static String decodeKey(String raw) {
        if (raw == null) return null;
        try {
            return java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return raw; // 解码失败返回原值
        }
    }

    /** 序列化 MemoryRecord 为 JSON */
    static String recordToJson(MemoryRecord r) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"key\":\"").append(escapeJson(r.key() == null ? "" : r.key())).append("\",");
        sb.append("\"content\":\"").append(escapeJson(truncate(r.content(), 500))).append("\",");
        sb.append("\"source\":\"").append(escapeJson(r.source() == null ? "" : r.source())).append("\",");
        sb.append("\"timestamp\":").append(r.timestamp()).append(",");
        sb.append("\"confidence\":").append(r.confidence()).append(",");
        sb.append("\"domain\":\"").append(r.domain()).append("\",");
        sb.append("\"version\":").append(r.version());
        sb.append("}");
        return sb.toString();
    }

    static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }

    static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
