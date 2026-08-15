package com.ouisani.aios.core.network;
import com.ouisani.aios.core.channel.ChannelDirectoryManager;
import io.javalin.Javalin;
import java.util.Map;
/** Multi-channel capability directory and selection API. */
public final class ChannelRoutes {
    private ChannelRoutes() {}
    public static void attachTo(Javalin app) {
        app.get("/api/channels", c->{if(auth(c))c.json(ChannelDirectoryManager.instance().list());});
        app.post("/api/channels/{channelId}/select", c->{if(!auth(c))return;var r=ChannelDirectoryManager.instance().select(c.pathParam("channelId"));if(r.isEmpty()){c.status(404).json(Map.of("error","channel_not_found"));return;}c.json(r.get());});
        app.post("/api/channels/{channelId}/connect", c->{if(!auth(c))return;var r=ChannelDirectoryManager.instance().setConnected(c.pathParam("channelId"),true);if(r.isEmpty()){c.status(404).json(Map.of("error","channel_not_found"));return;}c.json(r.get());});
        app.post("/api/channels/{channelId}/disconnect", c->{if(!auth(c))return;var r=ChannelDirectoryManager.instance().setConnected(c.pathParam("channelId"),false);if(r.isEmpty()){c.status(404).json(Map.of("error","channel_not_found"));return;}c.json(r.get());});
        app.options("/api/channels",ChannelRoutes::cors);app.options("/api/channels/{channelId}/select",ChannelRoutes::cors);app.options("/api/channels/{channelId}/connect",ChannelRoutes::cors);app.options("/api/channels/{channelId}/disconnect",ChannelRoutes::cors);
    }
    private static boolean auth(io.javalin.http.Context c){String t=c.queryParam("token"),h=c.header("Authorization");if(t==null&&h!=null&&h.startsWith("Bearer "))t=h.substring(7);if(!AuthManager.instance().verifyToken(t)){c.status(401).json(Map.of("error","unauthorized"));return false;}return true;}
    private static void cors(io.javalin.http.Context c){c.header("Access-Control-Allow-Origin","*");c.header("Access-Control-Allow-Methods","GET, POST, OPTIONS");c.header("Access-Control-Allow-Headers","Content-Type, Authorization");c.result("");}
}
