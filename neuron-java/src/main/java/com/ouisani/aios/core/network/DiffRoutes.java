package com.ouisani.aios.core.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ouisani.aios.core.review.DiffTimelineManager;
import io.javalin.Javalin;

import java.util.Map;

/** Review and explicit undo endpoints for the IDE diff timeline. */
public final class DiffRoutes {
    private DiffRoutes() {}

    public static void attachTo(Javalin app) {
        app.get("/api/diffs", ctx -> {
            if (!authorized(ctx)) return;
            ctx.json(DiffTimelineManager.instance().list(ctx.queryParam("requestId"), ctx.queryParam("agentId")));
        });
        app.post("/api/diffs/{diffId}/review", ctx -> {
            if (!authorized(ctx)) return;
            JsonObject body = parse(ctx.body());
            var result = DiffTimelineManager.instance().review(ctx.pathParam("diffId"), string(body, "decision"));
            if (result.isEmpty()) { ctx.status(404).json(Map.of("error", "diff_not_found")); return; }
            ctx.json(result.get());
        });
        app.post("/api/diffs/{diffId}/revert", ctx -> {
            if (!authorized(ctx)) return;
            var result = DiffTimelineManager.instance().revert(ctx.pathParam("diffId"));
            if (result.isEmpty()) { ctx.status(409).json(Map.of("error", "diff_revert_denied")); return; }
            ctx.json(result.get());
        });
        app.options("/api/diffs", DiffRoutes::cors);
        app.options("/api/diffs/{diffId}/review", DiffRoutes::cors);
        app.options("/api/diffs/{diffId}/revert", DiffRoutes::cors);
    }

    private static JsonObject parse(String raw) {
        try { var e = JsonParser.parseString(raw == null ? "{}" : raw); return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject(); }
        catch (Exception ignored) { return new JsonObject(); }
    }
    private static String string(JsonObject body, String key) {
        try { return body.has(key) && !body.get(key).isJsonNull() ? body.get(key).getAsString() : null; }
        catch (Exception ignored) { return null; }
    }
    private static boolean authorized(io.javalin.http.Context ctx) {
        String token = ctx.queryParam("token"); String h = ctx.header("Authorization");
        if (token == null && h != null && h.startsWith("Bearer ")) token = h.substring(7);
        if (!AuthManager.instance().verifyToken(token)) { ctx.status(401).json(Map.of("error", "unauthorized")); return false; }
        return true;
    }
    private static void cors(io.javalin.http.Context ctx) {
        ctx.header("Access-Control-Allow-Origin", "*");
        ctx.header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
        ctx.result("");
    }
}
