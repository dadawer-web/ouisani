package com.ouisani.aios.core.network;

import com.google.gson.Gson;
import com.ouisani.aios.core.audit.UnifiedAuditLog;
import com.ouisani.aios.core.continuation.ContinuationManager;
import com.ouisani.aios.user.apps.omnifactory.WorkflowEngine;
import io.javalin.Javalin;

import java.util.Map;

/** HTTP API for the Run control console. */
public final class RunRoutes {
    private static final Gson JSON = new Gson();
    private RunRoutes() {}

    public static void attachTo(Javalin app) {
        app.get("/api/runs", ctx -> {
            if (!authorized(ctx)) return;
            ctx.contentType("application/json");
            ctx.json(WorkflowEngine.instance().listRunSnapshots());
        });
        app.get("/api/runs/{runId}", ctx -> {
            if (!authorized(ctx)) return;
            var snapshot = WorkflowEngine.instance().runSnapshot(ctx.pathParam("runId"));
            if (snapshot.isEmpty()) {
                ctx.status(404);
                ctx.json(Map.of("error", "run_not_found"));
                return;
            }
            ctx.contentType("application/json");
            ctx.json(snapshot.get());
        });
        app.post("/api/runs/{runId}/control", ctx -> {
            if (!authorized(ctx)) return;
            String action = "";
            try {
                var body = JSON.fromJson(ctx.body(), Map.class);
                if (body != null && body.get("action") != null) action = String.valueOf(body.get("action"));
            } catch (Exception ignored) { }
            boolean accepted = WorkflowEngine.instance().controlRun(ctx.pathParam("runId"), action);
            if (accepted) {
                UnifiedAuditLog.append(UnifiedAuditLog.LAYER_PERMISSION, "RUN_CONTROL_" + action.toUpperCase(),
                        null, ctx.pathParam("runId"), "console_request");
            }
            ctx.status(accepted ? 200 : 409);
            ctx.contentType("application/json");
            ctx.json(Map.of("accepted", accepted, "runId", ctx.pathParam("runId"), "action", action));
        });
        app.get("/api/runs/{runId}/continuation", ctx -> {
            if (!authorized(ctx)) return;
            var plan = ContinuationManager.instance().get(ctx.pathParam("runId"));
            if (plan.isEmpty()) {
                ctx.status(404);
                ctx.json(Map.of("error", "continuation_not_found"));
                return;
            }
            ctx.contentType("application/json");
            ctx.json(plan.get());
        });
        app.post("/api/runs/{runId}/continue", ctx -> {
            if (!authorized(ctx)) return;
            String instruction = "";
            try {
                var body = JSON.fromJson(ctx.body(), Map.class);
                if (body != null && body.get("instruction") != null) instruction = String.valueOf(body.get("instruction"));
            } catch (Exception ignored) { }
            if (instruction.isBlank()) {
                ctx.status(400);
                ctx.json(Map.of("error", "instruction_required"));
                return;
            }
            var result = WorkflowEngine.instance().continueRun(ctx.pathParam("runId"), instruction);
            if (result.isEmpty()) {
                ctx.status(404);
                ctx.json(Map.of("error", "run_not_found"));
                return;
            }
            UnifiedAuditLog.append(UnifiedAuditLog.LAYER_CONTINUATION, "CONTINUATION_REQUESTED",
                    null, ctx.pathParam("runId"), instruction);
            ctx.contentType("application/json");
            ctx.json(result.get());
        });
        app.get("/api/runs/{runId}/timeline", ctx -> {
            if (!authorized(ctx)) return;
            String runId = ctx.pathParam("runId");
            var query = new UnifiedAuditLog.TimelineQuery(null, null, runId, runId, null,
                    Long.MIN_VALUE, Long.MAX_VALUE, java.util.Set.of(), java.util.Set.of());
            // Most runtime events use workflowId as runId; include trace-scoped fallback below.
            var entries = UnifiedAuditLog.query(query);
            if (entries.isEmpty()) {
                entries = UnifiedAuditLog.query(new UnifiedAuditLog.TimelineQuery(null, null, runId,
                        null, null, Long.MIN_VALUE, Long.MAX_VALUE, java.util.Set.of(), java.util.Set.of()));
            }
            ctx.contentType("application/json");
            ctx.json(entries);
        });
        app.options("/api/runs", ctx -> cors(ctx));
        app.options("/api/runs/{runId}", ctx -> cors(ctx));
        app.options("/api/runs/{runId}/control", ctx -> cors(ctx));
        app.options("/api/runs/{runId}/continuation", ctx -> cors(ctx));
        app.options("/api/runs/{runId}/continue", ctx -> cors(ctx));
        app.options("/api/runs/{runId}/timeline", ctx -> cors(ctx));
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
        ctx.header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
        ctx.result("");
    }
}
