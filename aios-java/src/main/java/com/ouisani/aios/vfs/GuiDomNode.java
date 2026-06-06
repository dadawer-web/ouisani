package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.network.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Semantic DOM Node — the AIOS kernel's framebuffer.
 * <p>
 * This is the write-side of the Semantic Display Server. When an Agent
 * calls {@code sys_write("/dev/gui/dom", jsonPayload)}, this node:
 * <ol>
 *   <li>Parses the JSON payload as a Semantic DOM tree</li>
 *   <li>Merges it with the current DOM state (incremental patching)</li>
 *   <li>Broadcasts the diff via EventBus {@code "ui_render"} to all
 *       connected frontends (dashboard.html)</li>
 * </ol>
 * <p>
 * <h3>Semantic DOM Protocol</h3>
 * The JSON payload follows a Virtual DOM format:
 * <pre>
 * {
 *   "type": "render",          // "render" = full replace, "patch" = incremental
 *   "agentId": "pm_agent",
 *   "timestamp": 1717584000000,
 *   "dom": {
 *     "id": "root",
 *     "type": "container",
 *     "props": { "direction": "column", "padding": 16 },
 *     "children": [
 *       { "id": "title", "type": "text", "props": { "value": "PRD Document", "style": "heading" } },
 *       { "id": "prd_content", "type": "text_area", "props": { "value": "...", "rows": 10 } },
 *       { "id": "btn_confirm", "type": "button", "props": { "label": "Confirm", "variant": "primary" } }
 *     ]
 *   }
 * }
 * </pre>
 * <p>
 * <h3>Supported Component Types</h3>
 * <ul>
 *   <li>{@code container} — flex layout container (like div)</li>
 *   <li>{@code text} — static text (like span/h1/h2)</li>
 *   <li>{@code text_area} — multi-line text (like textarea)</li>
 *   <li>{@code text_input} — single-line input (like input)</li>
 *   <li>{@code button} — clickable button</li>
 *   <li>{@code card} — bordered card container</li>
 *   <li>{@code list} — dynamic list of items</li>
 *   <li>{@code chart} — chart/graph placeholder</li>
 *   <li>{@code image} — image display (base64 or URL)</li>
 *   <li>{@code status} — status indicator (ok/warn/error)</li>
 * </ul>
 * <p>
 * <h3>OS Analogy: Framebuffer + DRM</h3>
 * Just as the Linux DRM/KMS subsystem manages framebuffers and pushes
 * pixel diffs to the display, GuiDomNode manages a DOM tree and pushes
 * JSON diffs to the frontend. The Agent is the "GPU" that renders into
 * this framebuffer, and the dashboard is the "monitor" that displays it.
 *
 * @see GuiActionNode
 * @see EventBus
 */
public non-sealed class GuiDomNode implements VfsNode {

    private static final Logger log = LoggerFactory.getLogger(GuiDomNode.class);

    // ── VFS Fields ──

    private final String path;
    private int ownerUid;
    private int permissions;

    // ── DOM State ──

    /** Current full DOM tree (per agentId). */
    private final ConcurrentHashMap<String, String> agentDomState = new ConcurrentHashMap<>();

    /** Version counter per agent (for incremental patching). */
    private final ConcurrentHashMap<String, AtomicLong> agentVersions = new ConcurrentHashMap<>();

    /** Global render sequence number. */
    private final AtomicLong renderSeq = new AtomicLong(0);

    public GuiDomNode(String path) {
        this(path, 0, 0666);
    }

    public GuiDomNode(String path, int ownerUid, int permissions) {
        this.path = path;
        this.ownerUid = ownerUid;
        this.permissions = permissions;
    }

    // ════════════════════════════════════════════════════════════════
    //  VfsNode Interface
    // ════════════════════════════════════════════════════════════════

    @Override
    public VfsNodeType nodeType() { return VfsNodeType.DEVICE; }

    @Override
    public String path() { return path; }

    @Override
    public int ownerUid() { return ownerUid; }

    @Override
    public void setOwnerUid(int uid) { this.ownerUid = uid; }

    @Override
    public int permissions() { return permissions; }

    @Override
    public void setPermissions(int perms) { this.permissions = perms; }

    // ════════════════════════════════════════════════════════════════
    //  Read: Return current DOM state
    // ════════════════════════════════════════════════════════════════

    /**
     * Read the current DOM state (all agents).
     * <p>
     * Returns a JSON object mapping agentId → their current DOM tree.
     */
    @Override
    public String read() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : agentDomState.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":").append(entry.getValue());
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Read the DOM state for a specific agent.
     */
    public String readAgent(String agentId) {
        return agentDomState.getOrDefault(agentId, "{}");
    }

    // ════════════════════════════════════════════════════════════════
    //  Write: Render / Patch DOM + Push to Frontend
    // ════════════════════════════════════════════════════════════════

    /**
     * Write a Semantic DOM payload to the framebuffer.
     * <p>
     * Supports two modes:
     * <ul>
     *   <li>{@code "type": "render"} — full DOM replacement (like swapping a framebuffer)</li>
     *   <li>{@code "type": "patch"} — incremental update (like dirty rect rendering)</li>
     * </ul>
     * <p>
     * After updating the DOM state, broadcasts the diff via EventBus
     * {@code "ui_render"} to all connected frontends.
     */
    @Override
    public boolean write(String payload) {
        if (payload == null || payload.isBlank()) return false;

        try {
            String type = extractField(payload, "type");
            String agentId = extractField(payload, "agentId");

            if (agentId == null || agentId.isBlank()) {
                agentId = "default";
            }

            long version = agentVersions.computeIfAbsent(agentId, k -> new AtomicLong(0)).incrementAndGet();
            long seq = renderSeq.incrementAndGet();

            if ("patch".equals(type)) {
                // Incremental patching: merge with existing DOM
                String currentDom = agentDomState.getOrDefault(agentId, "{}");
                String patched = applyPatch(currentDom, payload);
                agentDomState.put(agentId, patched);

                // Broadcast the patch to frontend
                String renderEvent = String.format(
                        "{\"type\":\"patch\",\"agentId\":\"%s\",\"version\":%d,\"seq\":%d,\"patch\":%s,\"timestamp\":%d}",
                        agentId, version, seq, payload, System.currentTimeMillis());
                EventBus.instance().broadcast("ui_render", renderEvent);
            } else {
                // Full render: replace entire DOM
                String domContent = extractDomBlock(payload);
                if (domContent != null) {
                    agentDomState.put(agentId, domContent);
                } else {
                    agentDomState.put(agentId, payload);
                }

                // Broadcast the full render to frontend
                String renderEvent = String.format(
                        "{\"type\":\"render\",\"agentId\":\"%s\",\"version\":%d,\"seq\":%d,\"dom\":%s,\"timestamp\":%d}",
                        agentId, version, seq, agentDomState.get(agentId), System.currentTimeMillis());
                EventBus.instance().broadcast("ui_render", renderEvent);
            }

            log.info("[GuiDomNode] Render: agent={}, type={}, version={}, seq={}",
                    agentId, type, version, seq);
            return true;

        } catch (Exception e) {
            log.error("[GuiDomNode] Render failed: {}", e.getMessage());
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  DOM Patching (Incremental Update)
    // ════════════════════════════════════════════════════════════════

    /**
     * Apply an incremental patch to the current DOM state.
     * <p>
     * The patch format specifies which nodes to update:
     * <pre>
     * {
     *   "type": "patch",
     *   "agentId": "pm_agent",
     *   "ops": [
     *     {"op": "update", "id": "title", "props": {"value": "Updated Title"}},
     *     {"op": "remove", "id": "btn_cancel"},
     *     {"op": "insert", "parentId": "root", "node": {...}}
     *   ]
     * }
     * </pre>
     */
    private String applyPatch(String currentDom, String patchPayload) {
        // For now, a simple implementation: if the patch contains a "dom" field,
        // use it as the new state. Otherwise, keep current + append patch metadata.
        String patchDom = extractDomBlock(patchPayload);
        if (patchDom != null) {
            return patchDom;
        }
        // Fallback: return current dom unchanged (patch ops not yet fully implemented)
        return currentDom;
    }

    // ════════════════════════════════════════════════════════════════
    //  Utility: JSON Field Extraction (lightweight, no Jackson)
    // ════════════════════════════════════════════════════════════════

    private static String extractField(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return null;

        // Skip whitespace
        int start = colon + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;

        if (start >= json.length()) return null;

        if (json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            if (end < 0) return null;
            return json.substring(start + 1, end);
        } else {
            int end = start;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}' && json.charAt(end) != ']') {
                end++;
            }
            return json.substring(start, end).trim();
        }
    }

    private static String extractDomBlock(String json) {
        int idx = json.indexOf("\"dom\"");
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return null;

        int start = colon + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length()) return null;

        if (json.charAt(start) == '{') {
            int depth = 0;
            for (int i = start; i < json.length(); i++) {
                if (json.charAt(i) == '{') depth++;
                if (json.charAt(i) == '}') depth--;
                if (depth == 0) return json.substring(start, i + 1);
            }
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    //  Diagnostics
    // ════════════════════════════════════════════════════════════════

    public int activeAgentCount() {
        return agentDomState.size();
    }

    public long getRenderSeq() {
        return renderSeq.get();
    }
}
