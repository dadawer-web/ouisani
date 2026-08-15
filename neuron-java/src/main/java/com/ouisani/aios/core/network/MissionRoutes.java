package com.ouisani.aios.core.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ouisani.aios.core.mission.MissionManager;
import io.javalin.Javalin;

import java.util.Map;

/** HTTP API for the lightweight continuous-task (Mission) home view. */
public final class MissionRoutes {
    private static final Gson JSON = new Gson();
    private MissionRoutes() {}

    public static void attachTo(Javalin app) {
        app.get("/api/missions", ctx -> {
            if (!authorized(ctx)) return;
            ctx.contentType("application/json");
            ctx.json(MissionManager.instance().list());
        });
        app.get("/api/missions/{missionId}", ctx -> {
            if (!authorized(ctx)) return;
            var mission = MissionManager.instance().get(ctx.pathParam("missionId"));
            if (mission.isEmpty()) {
                ctx.status(404);
                ctx.json(Map.of("error", "mission_not_found"));
                return;
            }
            ctx.contentType("application/json");
            ctx.json(mission.get());
        });
        app.post("/api/missions", ctx -> {
            if (!authorized(ctx)) return;
            JsonObject body = parse(ctx.body());
            MissionManager.MissionStatus status = status(body, "status");
            var mission = MissionManager.instance().create(string(body, "goal"),
                    string(body, "currentState"), string(body, "nextStep"), status);
            ctx.status(201);
            ctx.contentType("application/json");
            ctx.json(mission);
        });
        app.patch("/api/missions/{missionId}", ctx -> {
            if (!authorized(ctx)) return;
            JsonObject body = parse(ctx.body());
            var mission = MissionManager.instance().update(ctx.pathParam("missionId"),
                    string(body, "goal"), string(body, "currentState"), string(body, "nextStep"),
                    status(body, "status"), longValue(body, "nextTriggerAt"),
                    string(body, "nextTriggerEvent"), string(body, "completionReport"));
            if (mission.isEmpty()) {
                ctx.status(404);
                ctx.json(Map.of("error", "mission_not_found"));
                return;
            }
            ctx.contentType("application/json");
            ctx.json(mission.get());
        });
        app.post("/api/missions/{missionId}/runs", ctx -> {
            if (!authorized(ctx)) return;
            JsonObject body = parse(ctx.body());
            var mission = MissionManager.instance().attachRun(ctx.pathParam("missionId"), string(body, "runId"));
            respondMission(ctx, mission);
        });
        app.post("/api/missions/{missionId}/knowledge", ctx -> {
            if (!authorized(ctx)) return;
            JsonObject body = parse(ctx.body());
            var mission = MissionManager.instance().addKnowledge(ctx.pathParam("missionId"),
                    string(body, "kind"), string(body, "title"), string(body, "summary"), string(body, "source"));
            respondMission(ctx, mission);
        });
        app.post("/api/missions/{missionId}/approvals", ctx -> {
            if (!authorized(ctx)) return;
            JsonObject body = parse(ctx.body());
            var mission = MissionManager.instance().addApproval(ctx.pathParam("missionId"),
                    string(body, "requestId"), string(body, "action"), string(body, "toolName"),
                    string(body, "target"), string(body, "workflowId"), string(body, "traceId"));
            respondMission(ctx, mission);
        });
        app.post("/api/missions/approvals/{requestId}/resolve", ctx -> {
            if (!authorized(ctx)) return;
            boolean resolved = MissionManager.instance().resolveApproval(ctx.pathParam("requestId"));
            ctx.status(resolved ? 200 : 404);
            ctx.contentType("application/json");
            ctx.json(Map.of("resolved", resolved, "requestId", ctx.pathParam("requestId")));
        });
        app.post("/api/missions/{missionId}/complete", ctx -> {
            if (!authorized(ctx)) return;
            JsonObject body = parse(ctx.body());
            var mission = MissionManager.instance().complete(ctx.pathParam("missionId"), string(body, "completionReport"));
            respondMission(ctx, mission);
        });
        app.options("/api/missions", ctx -> cors(ctx));
        app.options("/api/missions/{missionId}", ctx -> cors(ctx));
        app.options("/api/missions/{missionId}/runs", ctx -> cors(ctx));
        app.options("/api/missions/{missionId}/knowledge", ctx -> cors(ctx));
        app.options("/api/missions/{missionId}/approvals", ctx -> cors(ctx));
        app.options("/api/missions/approvals/{requestId}/resolve", ctx -> cors(ctx));
        app.options("/api/missions/{missionId}/complete", ctx -> cors(ctx));
    }

    private static void respondMission(io.javalin.http.Context ctx,
                                       java.util.Optional<MissionManager.Mission> mission) {
        if (mission.isEmpty()) {
            ctx.status(404);
            ctx.json(Map.of("error", "mission_not_found"));
            return;
        }
        ctx.contentType("application/json");
        ctx.json(mission.get());
    }

    private static JsonObject parse(String raw) {
        try {
            if (raw == null || raw.isBlank()) return new JsonObject();
            var element = JsonParser.parseString(raw);
            return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (Exception ignored) {
            return new JsonObject();
        }
    }

    private static String string(JsonObject body, String field) {
        try {
            if (body != null && body.has(field) && !body.get(field).isJsonNull()) return body.get(field).getAsString();
        } catch (Exception ignored) { }
        return null;
    }

    private static Long longValue(JsonObject body, String field) {
        try {
            if (body != null && body.has(field) && !body.get(field).isJsonNull()) return body.get(field).getAsLong();
        } catch (Exception ignored) { }
        return null;
    }

    private static MissionManager.MissionStatus status(JsonObject body, String field) {
        String value = string(body, field);
        if (value == null) return null;
        try { return MissionManager.MissionStatus.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static boolean authorized(io.javalin.http.Context ctx) {
        String token = ctx.queryParam("token");
        if (token == null) {
            String header = ctx.header("Authorization");
            if (header != null && header.startsWith("Bearer ")) token = header.substring(7);
        }
        if (!AuthManager.instance().verifyToken(token)) {
            ctx.status(401);
            ctx.contentType("application/json");
            ctx.json(Map.of("error", "unauthorized"));
            return false;
        }
        return true;
    }

    private static void cors(io.javalin.http.Context ctx) {
        ctx.header("Access-Control-Allow-Origin", "*");
        ctx.header("Access-Control-Allow-Methods", "GET, POST, PATCH, OPTIONS");
        ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
        ctx.result("");
    }
}
