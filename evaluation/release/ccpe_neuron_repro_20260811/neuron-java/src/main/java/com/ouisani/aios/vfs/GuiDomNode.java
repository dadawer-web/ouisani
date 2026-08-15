package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.network.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 语义 DOM 节点 — AIOS 内核的帧缓冲区（Framebuffer）。
 * <p>
 * 这是语义显示服务器的写入端。当 Agent 调用
 * {@code sys_write("/dev/gui/dom", jsonPayload)} 时，此节点：
 * <ol>
 *   <li>将 JSON 载荷解析为语义 DOM 树</li>
 *   <li>与当前 DOM 状态合并（增量补丁）</li>
 *   <li>通过 EventBus {@code "ui_render"} 将差异广播到所有
 *       已连接的前端（dashboard.html）</li>
 * </ol>
 *
 * <h3>语义 DOM 协议</h3>
 * JSON 载荷遵循 Virtual DOM 格式：
 * <pre>
 * {
 *   "type": "render",          // "render" = 全量替换, "patch" = 增量更新
 *   "agentId": "pm_agent",
 *   "timestamp": 1717584000000,
 *   "dom": {
 *     "id": "root",
 *     "type": "container",
 *     "props": { "direction": "column", "padding": 16 },
 *     "children": [
 *       { "id": "title", "type": "text", "props": { "value": "PRD 文档", "style": "heading" } },
 *       { "id": "prd_content", "type": "text_area", "props": { "value": "...", "rows": 10 } },
 *       { "id": "btn_confirm", "type": "button", "props": { "label": "确认", "variant": "primary" } }
 *     ]
 *   }
 * }
 * </pre>
 *
 * <h3>支持的组件类型</h3>
 * <ul>
 *   <li>{@code container} — 弹性布局容器（类似 div）</li>
 *   <li>{@code text} — 静态文本（类似 span/h1/h2）</li>
 *   <li>{@code text_area} — 多行文本（类似 textarea）</li>
 *   <li>{@code text_input} — 单行输入（类似 input）</li>
 *   <li>{@code button} — 可点击按钮</li>
 *   <li>{@code card} — 带边框的卡片容器</li>
 *   <li>{@code list} — 动态列表</li>
 *   <li>{@code chart} — 图表占位符</li>
 *   <li>{@code image} — 图片显示（base64 或 URL）</li>
 *   <li>{@code status} — 状态指示器（ok/warn/error）</li>
 * </ul>
 *
 * <h3>OS 类比：帧缓冲区 + DRM</h3>
 * 正如 Linux DRM/KMS 子系统管理帧缓冲区并将像素差异推送到显示器，
 * GuiDomNode 管理 DOM 树并将 JSON 差异推送到前端。
 * Agent 是渲染到此帧缓冲区的"GPU"，而 dashboard 是显示它的"显示器"。
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

    /** 当前完整 DOM 树（按 agentId 分组） */
    private final ConcurrentHashMap<String, String> agentDomState = new ConcurrentHashMap<>();

    /** 每个 Agent 的版本计数器（用于增量补丁） */
    private final ConcurrentHashMap<String, AtomicLong> agentVersions = new ConcurrentHashMap<>();

    /** 全局渲染序列号 */
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
     * 读取当前所有 Agent 的 DOM 状态。
     *
     * @return JSON 对象，键为 agentId，值为对应的 DOM 树
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
     * 读取指定 Agent 的 DOM 状态。
     *
     * @param agentId Agent 标识
     * @return 该 Agent 的 DOM 树 JSON，不存在则返回空对象
     */
    public String readAgent(String agentId) {
        return agentDomState.getOrDefault(agentId, "{}");
    }

    // ════════════════════════════════════════════════════════════════
    //  Write: Render / Patch DOM + Push to Frontend
    // ════════════════════════════════════════════════════════════════

    /**
     * 写入语义 DOM 载荷到帧缓冲区。
     * <p>
     * 支持两种模式：
     * <ul>
     *   <li>{@code "type": "render"} — 全量 DOM 替换（类似交换帧缓冲区）</li>
     *   <li>{@code "type": "patch"} — 增量更新（类似脏矩形渲染）</li>
     * </ul>
     * <p>
     * 更新 DOM 状态后，通过 EventBus {@code "ui_render"} 广播差异到所有已连接前端。
     *
     * @param payload 语义 DOM JSON 载荷
     * @return 是否成功渲染
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
     * 对当前 DOM 状态应用增量补丁。
     * <p>
     * 补丁格式指定要更新的节点：
     * <pre>
     * {
     *   "type": "patch",
     *   "agentId": "pm_agent",
     *   "ops": [
     *     {"op": "update", "id": "title", "props": {"value": "更新标题"}},
     *     {"op": "remove", "id": "btn_cancel"},
     *     {"op": "insert", "parentId": "root", "node": {...}}
     *   ]
     * }
     * </pre>
     *
     * @param currentDom   当前 DOM 状态 JSON
     * @param patchPayload 补丁载荷 JSON
     * @return 补丁后的 DOM 状态 JSON
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
