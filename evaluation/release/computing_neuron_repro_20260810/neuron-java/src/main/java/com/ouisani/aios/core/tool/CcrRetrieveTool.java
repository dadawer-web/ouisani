package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.compact.CompressionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * CCR 检索工具 — 借鉴 Headroom 的 {@code headroom_retrieve} 工具。
 * <p>
 * 当 CompactService 压缩工具输出时，原始内容缓存到 {@link CompressionStore}，
 * prompt 里只留一个标记 {@code [N items compressed to M. Retrieve more: hash=abc123]}。
 * LLM 需要细节时调用此工具取回原文。
 * <p>
 * <b>使用场景：</b>
 * <ul>
 *   <li>LLM 发现压缩后的工具输出缺少需要的细节</li>
 *   <li>LLM 需要查看被压缩掉的完整 JSON 数组</li>
 *   <li>LLM 需要在压缩内容中搜索特定关键词</li>
 * </ul>
 * <p>
 * <b>工具注入机制：</b>此工具默认不注册到 ToolRegistry。
 * {@code CcrToolInjector} 检测到上下文中存在压缩标记时，
 * 自动将此工具注册到 ToolRegistry，让 LLM 可以调用。
 * <p>
 * 借鉴 Headroom {@code ccr/tool_injection.py} 的 {@code CCR_TOOL_NAME}。
 */
public class CcrRetrieveTool implements Tool<CcrRetrieveTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(CcrRetrieveTool.class);

    /** 工具名 — 与压缩标记中的提示一致 */
    public static final String TOOL_NAME = "ccr_retrieve";

    /** 检索未命中时的恢复指引 — 借鉴 Headroom CCR_MISS_MESSAGE */
    private static final String CCR_MISS_MESSAGE =
            "Entry not found or expired (CCR TTL: 30 minutes). " +
            "To recover: if the compression marker references a file Read, re-read that file " +
            "(the path is in the marker; disk is the source of truth). " +
            "If it was command output, re-run the command.";

    public record Input(
            String hash,
            String query
    ) implements ToolInput {
        public Input {
            if (hash == null || hash.isBlank()) {
                throw new IllegalArgumentException("hash required");
            }
            if (query == null) query = "";
        }

        @Override
        public String toJson() {
            return "{\"hash\":\"" + hash.replace("\"", "\\\"")
                    + "\",\"query\":\"" + query.replace("\"", "\\\"") + "\"}";
        }
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return "Retrieve original uncompressed content that was compressed to save tokens. " +
                "Use this when you need more data than what's shown in compressed tool results. " +
                "The hash is provided in compression markers like " +
                "[N items compressed to M. Retrieve more: hash=abc123]. " +
                "Optionally provide a query to search within the compressed content.";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{" +
                "\"hash\":{\"type\":\"string\",\"description\":\"Hash key from the compression marker " +
                "(e.g. 'abc123' from hash=abc123)\"}," +
                "\"query\":{\"type\":\"string\",\"description\":\"Optional search query to filter results. " +
                "If provided, only returns items matching the query. " +
                "If omitted, returns all original items.\"}" +
                "},\"required\":[\"hash\"]}";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        CompressionStore store = CompressionStore.instance();
        String hash = input.hash();
        String query = input.query();

        log.info("[CCR Retrieve] 检索压缩内容: hash={}, query='{}'", hash,
                query.isBlank() ? "(full)" : query);

        // 有查询 → 搜索模式
        if (!query.isBlank()) {
            List<String> results = store.search(hash, query, 20);
            if (results.isEmpty()) {
                log.warn("[CCR Retrieve] 搜索未命中: hash={}, query='{}'", hash, query);
                return ToolOutput.fail("No matches found for query '" + query
                        + "' in compressed content. " + CCR_MISS_MESSAGE);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("[CCR Search Results: ").append(results.size())
                    .append(" matches for '").append(query).append("']\n\n");
            for (int i = 0; i < results.size(); i++) {
                sb.append("--- Match ").append(i + 1).append(" ---\n");
                sb.append(results.get(i)).append("\n\n");
            }
            return ToolOutput.ok(sb.toString());
        }

        // 无查询 → 全量检索模式
        CompressionStore.CompressionEntry entry = store.retrieve(hash, null);
        if (entry == null) {
            log.warn("[CCR Retrieve] 未命中: hash={}", hash);
            return ToolOutput.fail(CCR_MISS_MESSAGE);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[CCR Retrieved Original Content]\n");
        sb.append("Tool: ").append(entry.toolName() != null ? entry.toolName() : "unknown").append("\n");
        sb.append("Original tokens: ").append(entry.originalTokens()).append("\n");
        sb.append("Strategy: ").append(entry.compressionStrategy() != null
                ? entry.compressionStrategy() : "unknown").append("\n");
        sb.append("---\n\n");
        sb.append(entry.originalContent());

        log.info("[CCR Retrieve] 检索成功: hash={}, {} chars",
                hash, entry.originalContent().length());
        return ToolOutput.ok(sb.toString());
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public String prompt() {
        return "Use " + TOOL_NAME + " to retrieve original uncompressed content when you need " +
                "more data than what's shown in compressed tool results. " +
                "Look for markers like [N items compressed to M. Retrieve more: hash=abc123] " +
                "in tool results to find the hash for each compressed output. " +
                "You can optionally provide a query to search within the compressed content " +
                "instead of retrieving everything.";
    }

    // ── 强类型 I/O 契约 ──

    @Override
    public List<Port> inputPorts() {
        return List.of(
                new Port("hash", DataTypes.PLAIN_TEXT, "压缩标记中的哈希键", true),
                new Port("query", DataTypes.PLAIN_TEXT, "可选搜索查询", false)
        );
    }

    @Override
    public List<Port> outputPorts() {
        return List.of(
                new Port("result", DataTypes.PLAIN_TEXT, "检索到的原始内容")
        );
    }
}
