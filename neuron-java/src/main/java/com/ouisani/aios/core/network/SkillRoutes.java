package com.ouisani.aios.core.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ouisani.aios.core.skill.SkillCatalogManager;
import io.javalin.Javalin;

import java.util.Map;

/** HTTP read/write boundary for the governed Skill capability catalog. */
public final class SkillRoutes {
    private SkillRoutes() {}

    public static void attachTo(Javalin app) {
        app.get("/api/skills/catalog", ctx -> {
            if (!authorized(ctx)) return;
            ctx.json(SkillCatalogManager.instance().catalog());
        });
        app.post("/api/skills/install", ctx -> {
            if (!authorized(ctx)) return;
            JsonObject body = parse(ctx.body());
            var result = SkillCatalogManager.instance().install(string(body, "name"),
                    string(body, "source"), string(body, "version"));
            if (result.isEmpty()) {
                ctx.status(400).json(Map.of("error", "skill_install_denied"));
                return;
            }
            ctx.status(201).json(result.get());
        });
        app.post("/api/skills/{skillId}/enable", ctx -> setEnabled(ctx, true));
        app.post("/api/skills/{skillId}/disable", ctx -> setEnabled(ctx, false));
        app.options("/api/skills/catalog", SkillRoutes::cors);
        app.options("/api/skills/install", SkillRoutes::cors);
        app.options("/api/skills/{skillId}/enable", SkillRoutes::cors);
        app.options("/api/skills/{skillId}/disable", SkillRoutes::cors);
    }

    private static void setEnabled(io.javalin.http.Context ctx, boolean enabled) {
        if (!authorized(ctx)) return;
        boolean ok = SkillCatalogManager.instance().setEnabled(ctx.pathParam("skillId"), enabled);
        if (!ok) {
            ctx.status(404).json(Map.of("error", "skill_not_found_or_unchanged"));
            return;
        }
        var entry = SkillCatalogManager.instance().get(ctx.pathParam("skillId"));
        if (entry.isEmpty()) { ctx.status(404).json(Map.of("error", "skill_not_found")); return; }
        ctx.json(entry.get());
    }

    private static JsonObject parse(String raw) {
        try {
            var e = JsonParser.parseString(raw == null ? "{}" : raw);
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (Exception ignored) { return new JsonObject(); }
    }

    private static String string(JsonObject body, String field) {
        try { return body.has(field) && !body.get(field).isJsonNull() ? body.get(field).getAsString() : null; }
        catch (Exception ignored) { return null; }
    }

    private static boolean authorized(io.javalin.http.Context ctx) {
        String token = ctx.queryParam("token");
        String header = ctx.header("Authorization");
        if (token == null && header != null && header.startsWith("Bearer ")) token = header.substring(7);
        if (!AuthManager.instance().verifyToken(token)) {
            ctx.status(401).json(Map.of("error", "unauthorized"));
            return false;
        }
        return true;
    }

    private static void cors(io.javalin.http.Context ctx) {
        ctx.header("Access-Control-Allow-Origin", "*");
        ctx.header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
        ctx.result("");
    }
}
