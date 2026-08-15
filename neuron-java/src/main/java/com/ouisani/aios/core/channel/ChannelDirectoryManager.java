package com.ouisani.aios.core.channel;

import com.ouisani.aios.core.audit.UnifiedAuditLog;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Stable multi-channel capability directory for chat, workflow and external adapters. */
public final class ChannelDirectoryManager {
    private static final class Holder { static final ChannelDirectoryManager INSTANCE = new ChannelDirectoryManager(); }
    public static ChannelDirectoryManager instance() { return Holder.INSTANCE; }
    public record Channel(String id, String name, String kind, boolean connected, boolean selected,
                          List<String> capabilities, String owner) {}
    private final Map<String, Channel> channels = new LinkedHashMap<>();
    private volatile String selected = "webchat";
    private ChannelDirectoryManager() {
        register("webchat", "Web Chat", "native", true, List.of("text","markdown","file"), "aios-core");
        register("workflow", "Workflow Control", "native", true, List.of("runs","approvals","events"), "aios-core");
        register("browser", "Browser Workspace", "bridge", false, List.of("navigate","tabs","dom","screenshot"), "chrome-bridge");
        register("telegram", "Telegram", "external", false, List.of("text","image","file","voice"), "openclaw-telegram");
        register("discord", "Discord", "external", false, List.of("text","image","file"), "openclaw-discord");
    }
    private void register(String id,String name,String kind,boolean connected,List<String> caps,String owner){channels.put(id,new Channel(id,name,kind,connected,id.equals(selected),caps,owner));}
    public synchronized List<Channel> list() { return List.copyOf(channels.values()); }
    public synchronized Optional<Channel> select(String id) { if (!channels.containsKey(id)) return Optional.empty(); selected=id; channels.replaceAll((k,v)->new Channel(v.id(),v.name(),v.kind(),v.connected(),k.equals(id),v.capabilities(),v.owner())); UnifiedAuditLog.append(new UnifiedAuditLog.TimelineEvent(UnifiedAuditLog.LAYER_CHANNEL,"CHANNEL_SELECTED","CHANNEL_SELECTED",null,id,"selected by user",UnifiedAuditLog.AuditContext.current())); return Optional.of(channels.get(id)); }
    public synchronized Optional<Channel> setConnected(String id, boolean connected) { Channel c=channels.get(id); if(c==null)return Optional.empty(); Channel u=new Channel(c.id(),c.name(),c.kind(),connected,c.selected(),c.capabilities(),c.owner()); channels.put(id,u); UnifiedAuditLog.append(new UnifiedAuditLog.TimelineEvent(UnifiedAuditLog.LAYER_CHANNEL,connected?"CHANNEL_CONNECTED":"CHANNEL_DISCONNECTED",connected?"CHANNEL_CONNECTED":"CHANNEL_DISCONNECTED",null,id,"state changed",UnifiedAuditLog.AuditContext.current())); return Optional.of(u); }
}
