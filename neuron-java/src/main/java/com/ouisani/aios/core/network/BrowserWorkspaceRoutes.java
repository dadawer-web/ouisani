package com.ouisani.aios.core.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ouisani.aios.core.browser.BrowserWorkspaceManager;
import io.javalin.Javalin;
import java.util.Map;

/** Browser workspace metadata plus governed navigate boundary. */
public final class BrowserWorkspaceRoutes {
    private BrowserWorkspaceRoutes() {}
    public static void attachTo(Javalin app) {
        app.get("/api/browser/workspaces", ctx -> { if (auth(ctx)) ctx.json(BrowserWorkspaceManager.instance().list()); });
        app.post("/api/browser/workspaces", ctx -> {
            if (!auth(ctx)) return; JsonObject b = parse(ctx.body());
            var workspace = BrowserWorkspaceManager.instance().open(str(b,"runId"), str(b,"missionId"), str(b,"url"));
            if (workspace == null) { ctx.status(400).json(Map.of("error", "browser_workspace_denied")); return; }
            ctx.status(201).json(workspace);
        });
        app.post("/api/browser/workspaces/{workspaceId}/navigate", ctx -> {
            if (!auth(ctx)) return; JsonObject b = parse(ctx.body());
            var result = BrowserWorkspaceManager.instance().navigate(ctx.pathParam("workspaceId"), str(b,"url"));
            if (result.isEmpty()) { ctx.status(409).json(Map.of("error","browser_navigate_denied")); return; } ctx.json(result.get());
        });
        app.delete("/api/browser/workspaces/{workspaceId}", ctx -> { if (auth(ctx)) ctx.json(Map.of("closed", BrowserWorkspaceManager.instance().close(ctx.pathParam("workspaceId")))); });
        app.options("/api/browser/workspaces", BrowserWorkspaceRoutes::cors);
        app.options("/api/browser/workspaces/{workspaceId}/navigate", BrowserWorkspaceRoutes::cors);
        app.options("/api/browser/workspaces/{workspaceId}", BrowserWorkspaceRoutes::cors);
    }
    private static JsonObject parse(String raw) { try { var e= JsonParser.parseString(raw==null?"{}":raw); return e.isJsonObject()?e.getAsJsonObject():new JsonObject(); } catch(Exception e){return new JsonObject();} }
    private static String str(JsonObject b,String k){try{return b.has(k)&&!b.get(k).isJsonNull()?b.get(k).getAsString():null;}catch(Exception e){return null;}}
    private static boolean auth(io.javalin.http.Context c){String t=c.queryParam("token"),h=c.header("Authorization");if(t==null&&h!=null&&h.startsWith("Bearer "))t=h.substring(7);if(!AuthManager.instance().verifyToken(t)){c.status(401).json(Map.of("error","unauthorized"));return false;}return true;}
    private static void cors(io.javalin.http.Context c){c.header("Access-Control-Allow-Origin","*");c.header("Access-Control-Allow-Methods","GET, POST, DELETE, OPTIONS");c.header("Access-Control-Allow-Headers","Content-Type, Authorization");c.result("");}
}
