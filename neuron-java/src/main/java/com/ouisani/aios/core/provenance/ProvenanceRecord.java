package com.ouisani.aios.core.provenance;

/**
 * Provenance 版本记录 — 每次 Agent 成功写文件时追加一条。
 * <p>
 * 借鉴 ai4s-research/open-science 的 {@code .openscience/provenance.jsonl} 设计：
 * 每个 artifact（figure/table/report/notebook）都能追溯到生成代码 + 输入 +
 * 环境 + 模型输出 + 对话上下文。
 * <p>
 * 与 P2 MemoryRecord "一等元数据"理念一致 — 把"文件被谁、何时、用什么工具写入"
 * 从隐式日志提升为可查询的结构化数据。
 *
 * <h3>字段对照（open-science → neuron-java）</h3>
 * <table>
 *   <tr><th>open-science</th><th>neuron-java</th><th>说明</th></tr>
 *   <tr><td>path</td><td>path</td><td>VFS 虚拟路径</td></tr>
 *   <tr><td>—</td><td>version</td><td>同 path 维度递增版本号（neuron-java 新增）</td></tr>
 *   <tr><td>—</td><td>ts</td><td>epoch millis 时间戳（neuron-java 新增）</td></tr>
 *   <tr><td>tool</td><td>tool</td><td>工具名（write/apply_patch/edit 等）</td></tr>
 *   <tr><td>content</td><td>content</td><td>写入的文本内容</td></tr>
 *   <tr><td>—</td><td>agentId</td><td>Agent 标识（neuron-java 新增，多 Agent 隔离）</td></tr>
 *   <tr><td>sessionId</td><td>sessionId</td><td>会话标识</td></tr>
 * </table>
 *
 * @param path      VFS 虚拟路径（如 /factory/output/survey.md）
 * @param version   同 path 维度递增版本号（从 1 开始）
 * @param ts        写入时间戳（epoch millis）
 * @param tool      触发写入的工具名（write/apply_patch/edit 等）
 * @param content   写入的文本内容（可能为 null，如纯二进制写入）
 * @param agentId   写入的 Agent 标识（可能为 null，表示无 agent 上下文）
 * @param sessionId 会话标识（可能为 null）
 * @param traceId   端到端追踪标识（可能为 null）。由 {@link com.ouisani.aios.core.ipc.TraceContext}
 *                  在 turn 入口注入，使本条 VFS 写入可与同 traceId 下的 cgroup/permission/sandbox
 *                  决策在 {@link com.ouisani.aios.core.audit.UnifiedAuditLog} 中关联。
 */
public record ProvenanceRecord(
        String path,
        long version,
        long ts,
        String tool,
        String content,
        String agentId,
        String sessionId,
        String traceId
) {
    /**
     * 紧凑构造器 — 允许 path 为 null 时仍创建记录（best-effort）。
     */
    public ProvenanceRecord {
        if (path == null) path = "";
        if (tool == null) tool = "unknown";
    }

    /**
     * 向后兼容构造 — 旧 7 参数调用方（无 traceId）默认 traceId=null，零回归。
     */
    public ProvenanceRecord(String path, long version, long ts, String tool,
                            String content, String agentId, String sessionId) {
        this(path, version, ts, tool, content, agentId, sessionId, null);
    }

    /**
     * 序列化为 JSONL 一行（单行 JSON，无换行）。
     * <p>
     * 手写序列化避免引入 Jackson 依赖到 core 层 — 字段固定且简单。
     *
     * @return 单行 JSON 字符串
     */
    public String toJsonLine() {
        StringBuilder sb = new StringBuilder(128);
        sb.append('{');
        sb.append("\"path\":").append(escape(path)).append(',');
        sb.append("\"version\":").append(version).append(',');
        sb.append("\"ts\":").append(ts).append(',');
        sb.append("\"tool\":").append(escape(tool)).append(',');
        sb.append("\"content\":").append(content == null ? "null" : escape(content)).append(',');
        sb.append("\"agentId\":").append(agentId == null ? "null" : escape(agentId)).append(',');
        sb.append("\"sessionId\":").append(sessionId == null ? "null" : escape(sessionId)).append(',');
        sb.append("\"traceId\":").append(traceId == null ? "null" : escape(traceId));
        sb.append('}');
        return sb.toString();
    }

    /**
     * 从 JSONL 一行反序列化 — {@link #toJsonLine} 的对偶（Phase 6：跨 session 磁盘回读）。
     * <p>
     * <b>不用 Gson 反射 record</b>（包未 {@code opens} 到 gson，会抛 InaccessibleObjectAccessException），
     * 改为 Gson 树模型 {@code JsonObject} 手动取字段（同 {@code ReviewVerdictParser} 范式）。
     *
     * @param line JSONL 单行
     * @return 记录；解析失败/输入空 → null（best-effort，不抛）
     */
    public static ProvenanceRecord fromJsonLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
            return new ProvenanceRecord(
                    optString(o, "path"),
                    o.has("version") && o.get("version").isJsonPrimitive() ? o.get("version").getAsLong() : 0L,
                    o.has("ts") && o.get("ts").isJsonPrimitive() ? o.get("ts").getAsLong() : 0L,
                    optString(o, "tool"),
                    o.has("content") && !o.get("content").isJsonNull() ? optString(o, "content") : null,
                    o.has("agentId") && !o.get("agentId").isJsonNull() ? optString(o, "agentId") : null,
                    o.has("sessionId") && !o.get("sessionId").isJsonNull() ? optString(o, "sessionId") : null,
                    o.has("traceId") && !o.get("traceId").isJsonNull() ? optString(o, "traceId") : null
            );
        } catch (Exception e) {
            return null;
        }
    }

    private static String optString(com.google.gson.JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    /**
     * JSON 字符串转义 — 仅处理必要字符（" \ 换行 制表符）。
     */
    private static String escape(String s) {
        if (s == null) return "null";
        StringBuilder out = new StringBuilder(s.length() + 8);
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }
}
