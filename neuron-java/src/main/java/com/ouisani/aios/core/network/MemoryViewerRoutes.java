package com.ouisani.aios.core.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ouisani.aios.core.memory.VersionedMemoryStore;
import com.ouisani.aios.core.memory.MemoryLayer;
import com.ouisani.aios.core.memory.providers.MemoryDomain;
import com.ouisani.aios.core.memory.providers.MemoryRecord;
import com.ouisani.aios.core.memory.graph.GroundedAnswerer;
import com.ouisani.aios.core.memory.graph.HybridMemoryRetriever;
import com.ouisani.aios.core.memory.graph.MemoryEdgeType;
import com.ouisani.aios.core.memory.graph.MemoryGraphAccess;
import com.ouisani.aios.core.memory.graph.MemoryNodeType;
import com.ouisani.aios.core.memory.graph.RetrievalQuery;
import com.ouisani.aios.core.memory.graph.RetrievalTrace;
import com.ouisani.aios.core.memory.graph.TypedMemoryGraphStore;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    /** Default viewer wiring keeps one hot graph index per primary VMS. */
    private static final ConcurrentHashMap<VersionedMemoryStore, TypedMemoryGraphStore> GRAPH_STORES
            = new ConcurrentHashMap<>();

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
        attachTo(app, storeSupplier, () -> {
            VersionedMemoryStore store = storeSupplier.get();
            if (store == null) return null;
            return GRAPH_STORES.computeIfAbsent(store, TypedMemoryGraphStore::new);
        });
    }

    /** Attach CRUD endpoints and typed-graph retrieval/grounded-answer endpoints. */
    public static void attachTo(Javalin app,
                                Supplier<VersionedMemoryStore> storeSupplier,
                                Supplier<TypedMemoryGraphStore> graphSupplier) {
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

        app.get("/api/memory/graph/{scope}/retrieve", ctx -> {
            RouteResult rr = handleGraphRetrieve(graphSupplier, ctx.pathParam("scope"),
                    queryParamsAsJson(ctx));
            applyResult(ctx, rr);
        });
        app.post("/api/memory/graph/{scope}/retrieve", ctx -> {
            RouteResult rr = handleGraphRetrieve(graphSupplier, ctx.pathParam("scope"), ctx.body());
            applyResult(ctx, rr);
        });
        app.post("/api/memory/graph/{scope}/answer", ctx -> {
            RouteResult rr = handleGraphAnswer(graphSupplier, ctx.pathParam("scope"), ctx.body());
            applyResult(ctx, rr);
        });
        app.options("/api/memory/graph/{scope}/retrieve", ctx -> corsOptions(ctx));
        app.options("/api/memory/graph/{scope}/answer", ctx -> corsOptions(ctx));

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
     * Body 期望 JSON：{@code {"confidence":0.8,"domain":"USER","layer":"L2"}}
     * （字段都可选，但至少提供一个）。
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
        MemoryLayer newLayer = null;
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
                if (jsonObj.has("layer") && !jsonObj.get("layer").isJsonNull()) {
                    try {
                        newLayer = MemoryLayer.parse(jsonObj.get("layer").getAsString());
                    } catch (IllegalArgumentException e) {
                        return new RouteResult(400,
                                "{\"error\":\"layer must be L0, L1, L2, or L3\"}");
                    }
                }
            } catch (Exception e) {
                return new RouteResult(400, "{\"error\":\"invalid JSON: "
                        + escapeJson(e.getMessage()) + "\"}");
            }
        }

        if (newConfidence == null && newDomain == null && newLayer == null) {
            return new RouteResult(400,
                    "{\"error\":\"at least one of confidence/domain must be provided (or layer)\"}");
        }

        boolean ok = store.updateMetadata(agentId, key, newConfidence, newDomain, newLayer);
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

    /** POST/GET graph retrieval endpoint; returns the complete RetrievalTrace JSON. */
    public static RouteResult handleGraphRetrieve(Supplier<TypedMemoryGraphStore> supplier,
                                                  String scopeId,
                                                  String body) {
        TypedMemoryGraphStore graph = supplier == null ? null : supplier.get();
        if (graph == null) {
            return new RouteResult(503, "{\"error\":\"typed graph store not configured\"}");
        }
        if (scopeId == null || scopeId.isBlank()) {
            return new RouteResult(400, "{\"error\":\"scope required\"}");
        }
        try {
            JsonObject requestJson = parseObject(body);
            RetrievalQuery query = retrievalQuery(scopeId, requestJson);
            hydrateIfNeeded(graph, scopeId);
            RetrievalTrace trace = new HybridMemoryRetriever(graph).retrieve(query);
            return new RouteResult(200, trace.toJson());
        } catch (RuntimeException e) {
            return new RouteResult(400, "{\"error\":\"invalid retrieval request: "
                    + escapeJson(e.getMessage()) + "\"}");
        }
    }

    /** POST grounded-answer endpoint; the response embeds the trace and coverage decisions. */
    public static RouteResult handleGraphAnswer(Supplier<TypedMemoryGraphStore> supplier,
                                                String scopeId,
                                                String body) {
        TypedMemoryGraphStore graph = supplier == null ? null : supplier.get();
        if (graph == null) {
            return new RouteResult(503, "{\"error\":\"typed graph store not configured\"}");
        }
        if (scopeId == null || scopeId.isBlank()) {
            return new RouteResult(400, "{\"error\":\"scope required\"}");
        }
        try {
            JsonObject requestJson = parseObject(body);
            String proposedAnswer = stringValue(requestJson, "answer", "proposedAnswer");
            RetrievalQuery query = retrievalQuery(scopeId, requestJson);
            hydrateIfNeeded(graph, scopeId);
            RetrievalTrace trace = new HybridMemoryRetriever(graph).retrieve(query);
            GroundedAnswerer.GroundedAnswer grounded = new GroundedAnswerer()
                    .ground(proposedAnswer, trace);
            return new RouteResult(200, grounded.toJson());
        } catch (RuntimeException e) {
            return new RouteResult(400, "{\"error\":\"invalid grounded-answer request: "
                    + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private static void hydrateIfNeeded(TypedMemoryGraphStore graph, String scopeId) {
        if (graph.nodeCount(scopeId) == 0) graph.hydrate(scopeId);
    }

    private static RetrievalQuery retrievalQuery(String scopeId, JsonObject json) {
        String tenant = stringValue(json, "tenant", "tenantId");
        boolean includeShared = booleanValue(json, true, "includeShared", "include_shared");
        MemoryGraphAccess access = new MemoryGraphAccess(scopeId, tenant, includeShared);
        Set<MemoryNodeType> nodeTypes = enumSet(json, MemoryNodeType.class,
                "nodeTypes", "node_types", "types");
        Set<MemoryEdgeType> allowedEdges = enumSet(json, MemoryEdgeType.class,
                "allowedEdges", "allowed_edges", "edges");
        Set<String> identities = stringSet(json, "identityIds", "identity_ids", "identities");
        Map<String, String> metadata = metadataMap(json);
        Long validAt = longValue(json, "validAt", "valid_at");
        int seedLimit = intValue(json, 8, "seedLimit", "seed_limit");
        int maxDepth = intValue(json, 2, "maxDepth", "max_depth");
        int evidenceLimit = intValue(json, 20, "evidenceLimit", "evidence_limit");
        double lexicalWeight = doubleValue(json, 0.45, "lexicalWeight", "lexical_weight");
        double vectorWeight = doubleValue(json, 0.35, "vectorWeight", "vector_weight");
        double metadataWeight = doubleValue(json, 0.20, "metadataWeight", "metadata_weight");
        double minScore = doubleValue(json, 0.0, "minScore", "min_score");
        String actionValue = stringValue(json, "insufficientEvidenceAction",
                "insufficient_evidence_action", "onInsufficient", "on_insufficient");
        RetrievalQuery.InsufficientEvidenceAction action = actionValue == null
                ? RetrievalQuery.InsufficientEvidenceAction.OBSERVE
                : RetrievalQuery.InsufficientEvidenceAction.valueOf(actionValue.trim().toUpperCase());
        return new RetrievalQuery(stringValue(json, "query", "q"), access, nodeTypes,
                allowedEdges, validAt, identities, metadata, seedLimit, maxDepth,
                evidenceLimit, lexicalWeight, vectorWeight, metadataWeight, minScore, action);
    }

    private static JsonObject parseObject(String body) {
        if (body == null || body.isBlank()) return new JsonObject();
        JsonElement parsed = JsonParser.parseString(body);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("request body must be a JSON object");
        return parsed.getAsJsonObject();
    }

    private static String stringValue(JsonObject json, String... keys) {
        for (String key : keys) {
            if (json != null && json.has(key) && !json.get(key).isJsonNull()) {
                return json.get(key).getAsString();
            }
        }
        return null;
    }

    private static boolean booleanValue(JsonObject json, boolean fallback, String... keys) {
        String value = stringValue(json, keys);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static int intValue(JsonObject json, int fallback, String... keys) {
        String value = stringValue(json, keys);
        return value == null ? fallback : Integer.parseInt(value);
    }

    private static Long longValue(JsonObject json, String... keys) {
        String value = stringValue(json, keys);
        return value == null ? null : Long.parseLong(value);
    }

    private static double doubleValue(JsonObject json, double fallback, String... keys) {
        String value = stringValue(json, keys);
        return value == null ? fallback : Double.parseDouble(value);
    }

    private static Set<String> stringSet(JsonObject json, String... keys) {
        JsonElement element = firstElement(json, keys);
        if (element == null || element.isJsonNull()) return Set.of();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (!item.isJsonNull() && !item.getAsString().isBlank()) values.add(item.getAsString().trim());
            }
        } else {
            Arrays.stream(element.getAsString().split(","))
                    .map(String::trim).filter(value -> !value.isBlank()).forEach(values::add);
        }
        return Set.copyOf(values);
    }

    private static <E extends Enum<E>> Set<E> enumSet(JsonObject json, Class<E> type,
                                                       String... keys) {
        Set<String> raw = stringSet(json, keys);
        LinkedHashSet<E> values = new LinkedHashSet<>();
        for (String value : raw) {
            try {
                if (type == MemoryNodeType.class) values.add(type.cast(MemoryNodeType.parse(value)));
                else values.add(type.cast(MemoryEdgeType.parse(value)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("invalid " + type.getSimpleName() + ": " + value);
            }
        }
        return Set.copyOf(values);
    }

    private static Map<String, String> metadataMap(JsonObject json) {
        JsonElement element = firstElement(json, "metadata", "metadataFilters", "metadata_filters");
        if (element == null || !element.isJsonObject()) return Map.of();
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            if (!entry.getValue().isJsonNull()) values.put(entry.getKey(), entry.getValue().getAsString());
        }
        return values;
    }

    private static JsonElement firstElement(JsonObject json, String... keys) {
        if (json == null) return null;
        for (String key : keys) if (json.has(key)) return json.get(key);
        return null;
    }

    private static String queryParamsAsJson(io.javalin.http.Context ctx) {
        JsonObject json = new JsonObject();
        String query = ctx.queryParam("query");
        if (query == null) query = ctx.queryParam("q");
        if (query != null) json.addProperty("query", query);
        String tenant = ctx.queryParam("tenant");
        if (tenant != null) json.addProperty("tenant", tenant);
        String edges = ctx.queryParam("edges");
        if (edges != null) json.addProperty("edges", edges);
        String types = ctx.queryParam("types");
        if (types != null) json.addProperty("types", types);
        String identities = ctx.queryParam("identities");
        if (identities != null) json.addProperty("identities", identities);
        String validAt = ctx.queryParam("validAt");
        if (validAt != null) json.addProperty("validAt", validAt);
        String depth = ctx.queryParam("maxDepth");
        if (depth != null) json.addProperty("maxDepth", depth);
        String limit = ctx.queryParam("seedLimit");
        if (limit != null) json.addProperty("seedLimit", limit);
        String action = ctx.queryParam("onInsufficient");
        if (action != null) json.addProperty("onInsufficient", action);
        return json.toString();
    }

    // ── 内部工具 ──

    /** 应用 RouteResult 到 Javalin ctx */
    private static void applyResult(io.javalin.http.Context ctx, RouteResult rr) {
        ctx.status(rr.status());
        ctx.header("Access-Control-Allow-Origin", "*");
        ctx.header("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS");
        ctx.header("Access-Control-Allow-Headers", "Content-Type");
        ctx.contentType("application/json");
        ctx.result(rr.body());
    }

    /** CORS 预检响应 */
    private static void corsOptions(io.javalin.http.Context ctx) {
        ctx.header("Access-Control-Allow-Origin", "*");
        ctx.header("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS");
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
        sb.append("\"layer\":\"").append(r.layer()).append("\",");
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
