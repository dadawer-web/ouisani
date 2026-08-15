package com.ouisani.aios.core.browser;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.audit.UnifiedAuditLog;
import com.ouisani.aios.core.config.AiosPaths;
import com.ouisani.aios.vfs.ChromeBridgeNode;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Metadata workspace for browser sessions; commands still go through ChromeBridgeNode. */
public final class BrowserWorkspaceManager {
    private static final Gson JSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<BrowserWorkspace>>() {}.getType();
    private static final class Holder { static final BrowserWorkspaceManager INSTANCE = new BrowserWorkspaceManager(); }
    public static BrowserWorkspaceManager instance() { return Holder.INSTANCE; }

    public record BrowserWorkspace(String workspaceId, String runId, String missionId,
                                   String sessionId, String url, String title,
                                   String status, boolean connected, long createdAt, long updatedAt) {}

    private final Path file;
    private final Map<String, BrowserWorkspace> workspaces = new LinkedHashMap<>();

    private BrowserWorkspaceManager() {
        file = Path.of(AiosPaths.aiosHome(), "var", "browser", "workspaces.json");
        load();
    }

    public synchronized List<BrowserWorkspace> list() {
        refreshState();
        return List.copyOf(workspaces.values());
    }

    public synchronized BrowserWorkspace open(String runId, String missionId, String url) {
        if (url != null && !url.isBlank() && !isHttpUrl(url.trim())) {
            audit("BROWSER_WORKSPACE_DENIED", "", "only http(s) URLs are allowed");
            return null;
        }
        String id = "browser-ws-" + UUID.randomUUID();
        long now = System.currentTimeMillis();
        BrowserWorkspace workspace = new BrowserWorkspace(id, clean(runId), clean(missionId), "",
                clean(url), "", connected() ? "READY" : "WAITING_FOR_EXTENSION", connected(), now, now);
        workspaces.put(id, workspace); save();
        if (url != null && !url.isBlank()) navigate(id, url);
        audit("BROWSER_WORKSPACE_OPENED", id, "runId=" + runId);
        return workspaces.get(id);
    }

    public synchronized Optional<BrowserWorkspace> get(String id) {
        refreshState();
        return Optional.ofNullable(workspaces.get(id));
    }

    public synchronized Optional<BrowserWorkspace> navigate(String id, String url) {
        BrowserWorkspace current = workspaces.get(id);
        if (current == null || url == null || url.isBlank()) return Optional.empty();
        String safeUrl = url.trim();
        if (!isHttpUrl(safeUrl)) {
            audit("BROWSER_NAVIGATE_DENIED", id, "only http(s) URLs are allowed");
            return Optional.empty();
        }
        Optional<ChromeBridgeNode> bridge = bridge();
        if (bridge.isEmpty() || !bridge.get().write("{\"action\":\"navigate\",\"url\":\"" + escape(safeUrl) + "\"}")) {
            audit("BROWSER_NAVIGATE_DENIED", id, "browser extension is not connected");
            return Optional.empty();
        }
        BrowserWorkspace updated = new BrowserWorkspace(current.workspaceId(), current.runId(), current.missionId(),
                current.sessionId(), safeUrl, current.title(), "NAVIGATING", true, current.createdAt(), System.currentTimeMillis());
        workspaces.put(id, updated); save();
        audit("BROWSER_NAVIGATE_REQUESTED", id, safeUrl);
        return Optional.of(updated);
    }

    public synchronized boolean close(String id) {
        BrowserWorkspace removed = workspaces.remove(id);
        if (removed == null) return false;
        save(); audit("BROWSER_WORKSPACE_CLOSED", id, "closed by user"); return true;
    }

    private void refreshState() {
        Optional<ChromeBridgeNode> bridge = bridge();
        String state = bridge.map(b -> b.readSubPath("active_tab")).orElse("{}");
        String url = extract(state, "url"); String title = extract(state, "title");
        boolean connected = bridge.map(ChromeBridgeNode::isConnected).orElse(false);
        if (workspaces.isEmpty()) return;
        for (var e : new ArrayList<>(workspaces.entrySet())) {
            BrowserWorkspace w = e.getValue();
            workspaces.put(e.getKey(), new BrowserWorkspace(w.workspaceId(), w.runId(), w.missionId(), w.sessionId(),
                    url.isBlank() ? w.url() : url, title.isBlank() ? w.title() : title,
                    connected ? "READY" : "WAITING_FOR_EXTENSION", connected, w.createdAt(), System.currentTimeMillis()));
        }
    }

    private Optional<ChromeBridgeNode> bridge() {
        return VfsManager.instance().resolve("/dev/host/browser")
                .filter(ChromeBridgeNode.class::isInstance).map(ChromeBridgeNode.class::cast);
    }
    private boolean connected() { return bridge().map(ChromeBridgeNode::isConnected).orElse(false); }
    private String clean(String value) { return value == null || value.isBlank() ? "" : value.trim(); }
    private boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
    private String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
    private String extract(String json, String key) {
        String marker = "\"" + key + "\""; int start = json.indexOf(marker); if (start < 0) return "";
        int colon = json.indexOf(':', start); if (colon < 0) return ""; int q = json.indexOf('"', colon + 1); if (q < 0) return "";
        int end = json.indexOf('"', q + 1); return end > q ? json.substring(q + 1, end) : "";
    }
    private void load() { try { if (Files.exists(file)) { List<BrowserWorkspace> l = JSON.fromJson(Files.readString(file), LIST_TYPE); if (l != null) l.forEach(w -> workspaces.put(w.workspaceId(), w)); } } catch (Exception ignored) {} }
    private void save() { try { Files.createDirectories(file.getParent()); Files.writeString(file, JSON.toJson(workspaces.values()), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING); } catch (IOException ignored) {} }
    private void audit(String decision, String target, String reason) { UnifiedAuditLog.append(new UnifiedAuditLog.TimelineEvent(UnifiedAuditLog.LAYER_BROWSER, decision, decision, null, target, reason, UnifiedAuditLog.AuditContext.current())); }
}
