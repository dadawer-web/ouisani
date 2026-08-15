package com.ouisani.aios.core.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UpstreamMeta 单元测试 — 验证 JSONL 序列化往返与字段降级。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>toJsonLine / fromJsonLine 往返一致</li>
 *   <li>null 字段渲染为 {@code null}</li>
 *   <li>特殊字符转义（" \ \n \r \t + 控制字符）</li>
 *   <li>缺字段反序列化降级（best-effort 返回非 null）</li>
 *   <li>空/null 输入返回 null</li>
 * </ul>
 */
class UpstreamMetaTest {

    @Test
    @DisplayName("完整字段往返一致")
    void roundTrip_allFieldsPopulated() {
        UpstreamMeta original = new UpstreamMeta(
                "llm.think", 842L, 200,
                "tokens:1234in/567out", 1536L, null,
                1784592000000L, "agent_5", "sess_abc"
        );

        String json = original.toJsonLine();
        UpstreamMeta restored = UpstreamMeta.fromJsonLine(json);

        assertNotNull(restored);
        assertEquals("llm.think", restored.upstreamName());
        assertEquals(842L, restored.upstreamDurationMs());
        assertEquals(200, restored.upstreamStatusCode());
        assertEquals("tokens:1234in/567out", restored.upstreamCostUnits());
        assertEquals(1536L, restored.upstreamBytes());
        assertNull(restored.errorCode());
        assertEquals(1784592000000L, restored.ts());
        assertEquals("agent_5", restored.agentId());
        assertEquals("sess_abc", restored.sessionId());
    }

    @Test
    @DisplayName("v1 字段：cost_units 与 errorCode 均为 null 时渲染为 null")
    void toJsonLine_v1NullableFields_renderAsNull() {
        UpstreamMeta v1 = new UpstreamMeta(
                "storage.write", 5L, 200,
                null, 0L, null,
                1784592000000L, null, null
        );

        String json = v1.toJsonLine();

        assertTrue(json.contains("\"upstream_cost_units\":null"));
        assertTrue(json.contains("\"error_code\":null"));
        assertTrue(json.contains("\"agentId\":null"));
        assertTrue(json.contains("\"sessionId\":null"));
    }

    @Test
    @DisplayName("特殊字符转义：双引号、反斜杠、换行、制表符")
    void toJsonLine_specialCharacters_escaped() {
        UpstreamMeta meta = new UpstreamMeta(
                "tool.\"weird\"\\name\n\t", 1L, 500,
                null, 0L, "ERR: \"bad\"\\input\n",
                0L, null, null
        );

        String json = meta.toJsonLine();

        // 反序列化应能完整还原
        UpstreamMeta restored = UpstreamMeta.fromJsonLine(json);
        assertNotNull(restored);
        assertEquals("tool.\"weird\"\\name\n\t", restored.upstreamName());
        assertEquals("ERR: \"bad\"\\input\n", restored.errorCode());
    }

    @Test
    @DisplayName("控制字符（U+0001）转义为 \\u0001")
    void toJsonLine_controlCharacter_escapedAsUnicode() {
        UpstreamMeta meta = new UpstreamMeta(
                "ctrl\u0001char", 1L, 200, null, 0L, null, 0L, null, null
        );

        String json = meta.toJsonLine();

        assertTrue(json.contains("\\u0001"));
        UpstreamMeta restored = UpstreamMeta.fromJsonLine(json);
        assertNotNull(restored);
        assertEquals("ctrl\u0001char", restored.upstreamName());
    }

    @Test
    @DisplayName("fromJsonLine 输入为 null/空 → 返回 null（best-effort）")
    void fromJsonLine_nullOrBlankInput_returnsNull() {
        assertNull(UpstreamMeta.fromJsonLine(null));
        assertNull(UpstreamMeta.fromJsonLine(""));
        assertNull(UpstreamMeta.fromJsonLine("   "));
    }

    @Test
    @DisplayName("fromJsonLine 输入为非法 JSON → 返回 null（best-effort）")
    void fromJsonLine_invalidJson_returnsNull() {
        assertNull(UpstreamMeta.fromJsonLine("{not valid json"));
    }

    @Test
    @DisplayName("fromJsonLine 缺字段时降级为默认值（best-effort，不抛）")
    void fromJsonLine_missingFields_degradesGracefully() {
        // 只有 upstream_name 和 ts，其他字段缺失
        String partial = "{\"upstream_name\":\"llm.think\",\"ts\":1784592000000}";

        UpstreamMeta restored = UpstreamMeta.fromJsonLine(partial);

        assertNotNull(restored);
        assertEquals("llm.think", restored.upstreamName());
        assertEquals(1784592000000L, restored.ts());
        assertEquals(0L, restored.upstreamDurationMs());
        assertEquals(0, restored.upstreamStatusCode());
        assertNull(restored.upstreamCostUnits());
        assertEquals(0L, restored.upstreamBytes());
        assertNull(restored.errorCode());
        assertNull(restored.agentId());
        assertNull(restored.sessionId());
    }

    @Test
    @DisplayName("upstreamName 为 null 时降级为 \"unknown\"（紧凑构造器）")
    void compactConstructor_nullUpstreamName_degradesToUnknown() {
        UpstreamMeta meta = new UpstreamMeta(
                null, 1L, 200, null, 0L, null, 0L, null, null
        );

        assertEquals("unknown", meta.upstreamName());
    }

    @Test
    @DisplayName("toJsonLine 字段命名对齐 nuwa snake_case 约定")
    void toJsonLine_fieldNames_alignWithNuwaSnakeCase() {
        UpstreamMeta meta = new UpstreamMeta("x", 1L, 200, null, 0L, null, 0L, null, null);

        String json = meta.toJsonLine();

        // 6 个标准字段必须用 snake_case（与 nuwa 对齐）
        assertTrue(json.contains("\"upstream_name\":"));
        assertTrue(json.contains("\"upstream_duration_ms\":"));
        assertTrue(json.contains("\"upstream_status_code\":"));
        assertTrue(json.contains("\"upstream_cost_units\":"));
        assertTrue(json.contains("\"upstream_bytes\":"));
        assertTrue(json.contains("\"error_code\":"));
        // 3 个元字段沿用 ProvenanceRecord 的 camelCase 命名（便于 DAG 关联）
        assertTrue(json.contains("\"agentId\":"));
        assertTrue(json.contains("\"sessionId\":"));
    }

    @Test
    @DisplayName("跨多条记录的 JSONL 反序列化（模拟磁盘回读）")
    void fromJsonLine_multipleRecordsInSequence() {
        UpstreamMeta r1 = new UpstreamMeta("llm.think", 100L, 200, null, 1024L, null, 1000L, "a1", "s1");
        UpstreamMeta r2 = new UpstreamMeta("storage.write", 5L, 500, null, 0L, "ERR:FAIL", 2000L, "a2", "s2");

        UpstreamMeta restored1 = UpstreamMeta.fromJsonLine(r1.toJsonLine());
        UpstreamMeta restored2 = UpstreamMeta.fromJsonLine(r2.toJsonLine());

        assertEquals("llm.think", restored1.upstreamName());
        assertEquals(200, restored1.upstreamStatusCode());
        assertEquals("storage.write", restored2.upstreamName());
        assertEquals("ERR:FAIL", restored2.errorCode());
    }
}
