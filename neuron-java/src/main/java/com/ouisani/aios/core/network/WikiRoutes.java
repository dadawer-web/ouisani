package com.ouisani.aios.core.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ouisani.aios.core.ipc.MemoryAccessContext;
import com.ouisani.aios.core.wiki.WikiCompiler;
import io.javalin.Javalin;

import java.util.List;
import java.util.Map;

/** HTTP read/confirmation API for the governed MemoryRecord Wiki projection. */
public final class WikiRoutes {

    private WikiRoutes() {}

    public static void attachTo(Javalin app) {
        app.get("/api/wiki", ctx -> {
            if (!authorized(ctx)) return;
            MemoryAccessContext caller = caller(ctx);
            if (caller == null) return;
            WikiCompiler.Category category = WikiCompiler.Category.parse(ctx.queryParam("category"));
            Boolean confirmed = booleanParam(ctx.queryParam("confirmed"));
            boolean includeLegacy = !"false".equalsIgnoreCase(ctx.queryParam("includeLegacy"));
            List<WikiCompiler.WikiEntry> entries = WikiCompiler.instance().query(caller,
                    ctx.queryParam("namespace"), category, ctx.queryParam("q"), confirmed, includeLegacy);
            ctx.contentType("application/json");
            ctx.json(Map.of("agentId", caller.agentId(), "count", entries.size(), "entries", entries));
        });

        app.get("/api/wiki/{wikiId}", ctx -> {
            if (!authorized(ctx)) return;
            MemoryAccessContext caller = caller(ctx);
            if (caller == null) return;
            String id = ctx.pathParam("wikiId");
            var entry = WikiCompiler.instance().query(caller, ctx.queryParam("namespace"), null,
                    null, null, true).stream().filter(item -> id.equals(item.wikiId())).findFirst();
            if (entry.isEmpty()) {
                ctx.status(404);
                ctx.json(Map.of("error", "wiki_entry_not_found"));
                return;
            }
            ctx.contentType("application/json");
            ctx.json(entry.get());
        });

        app.post("/api/wiki/{wikiId}/confirm", ctx -> {
            if (!authorized(ctx)) return;
            MemoryAccessContext caller = caller(ctx);
            if (caller == null) return;
            boolean confirmed = confirmationValue(ctx.body());
            var entry = WikiCompiler.instance().confirm(ctx.pathParam("wikiId"), caller, confirmed);
            if (entry.isEmpty()) {
                ctx.status(404);
                ctx.json(Map.of("error", "wiki_entry_not_found_or_not_visible"));
                return;
            }
            ctx.contentType("application/json");
            ctx.json(entry.get());
        });

        app.options("/api/wiki", ctx -> cors(ctx, "GET, OPTIONS"));
        app.options("/api/wiki/{wikiId}", ctx -> cors(ctx, "GET, OPTIONS"));
        app.options("/api/wiki/{wikiId}/confirm", ctx -> cors(ctx, "POST, OPTIONS"));
    }

    private static MemoryAccessContext caller(io.javalin.http.Context ctx) {
        String agentId = clean(ctx.queryParam("agentId"));
        if (agentId == null) {
            ctx.status(400);
            ctx.contentType("application/json");
            ctx.json(Map.of("error", "agentId_required_for_scoped_wiki"));
            return null;
        }
        return MemoryAccessContext.of(agentId, clean(ctx.queryParam("tenantId")),
                clean(ctx.queryParam("workflowId")), clean(ctx.queryParam("teamId")));
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

    private static boolean confirmationValue(String raw) {
        try {
            if (raw != null && !raw.isBlank()) {
                JsonObject body = JsonParser.parseString(raw).getAsJsonObject();
                if (body.has("confirmed")) return body.get("confirmed").getAsBoolean();
            }
        } catch (Exception ignored) { }
        return true;
    }

    private static Boolean booleanParam(String raw) {
        if (raw == null || raw.isBlank()) return null;
        if ("true".equalsIgnoreCase(raw)) return true;
        if ("false".equalsIgnoreCase(raw)) return false;
        return null;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void cors(io.javalin.http.Context ctx, String methods) {
        ctx.header("Access-Control-Allow-Origin", "*");
        ctx.header("Access-Control-Allow-Methods", methods);
        ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
        ctx.result("");
    }
}
