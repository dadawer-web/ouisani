package com.ouisani.aios.core.memory.providers;

import com.ouisani.aios.core.memory.MemoryLayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MemoryRecord} / {@link MemoryDomain} 单元测试 — 覆盖 legacy 默认值、
 * wither 不可变性、域标记基础语义。
 */
class MemoryRecordTest {

    @Test
    void legacy_defaultsToAgentDomainVersion1() {
        MemoryRecord r = MemoryRecord.legacy("hello");
        assertNull(r.key(), "legacy 应不带 key");
        assertEquals("hello", r.content());
        assertEquals("legacy", r.source());
        assertEquals(1.0, r.confidence(), 1e-9);
        assertEquals(MemoryDomain.AGENT, r.domain(), "legacy 默认 AGENT 域");
        assertEquals(MemoryLayer.L0, r.layer(), "legacy 是原始证据 L0");
        assertEquals(1L, r.version());
        assertTrue(r.timestamp() > 0, "timestamp 应被填充");
    }

    @Test
    void withVersion_changesOnlyVersion() {
        MemoryRecord r = new MemoryRecord(
                "k1", "content", "user-input", 1000L, 0.9, MemoryDomain.USER, 1L);
        MemoryRecord r2 = r.withVersion(5L);
        assertEquals(5L, r2.version());
        assertEquals(r.key(), r2.key());
        assertEquals(r.content(), r2.content());
        assertEquals(r.source(), r2.source());
        assertEquals(r.timestamp(), r2.timestamp());
        assertEquals(r.confidence(), r2.confidence(), 1e-9);
        assertEquals(r.domain(), r2.domain());
        assertEquals(r.layer(), r2.layer());
    }

    @Test
    void withTimestamp_changesOnlyTimestamp() {
        MemoryRecord r = new MemoryRecord(
                "k1", "content", "user-input", 1000L, 0.9, MemoryDomain.USER, 1L);
        MemoryRecord r2 = r.withTimestamp(2000L);
        assertEquals(2000L, r2.timestamp());
        assertEquals(1L, r2.version(), "version 不应变");
        assertEquals(r.content(), r2.content());
    }

    @Test
    void record_isImmutable_recordSemantics() {
        MemoryRecord r1 = new MemoryRecord(
                "k", "c", "s", 1L, 0.5, MemoryDomain.AGENT, 3L);
        MemoryRecord r2 = new MemoryRecord(
                "k", "c", "s", 1L, 0.5, MemoryDomain.AGENT, 3L);
        assertEquals(r1, r2, "相同字段应相等");
        assertEquals(r1.hashCode(), r2.hashCode());
        assertNotNull(r1.toString());
        assertNotEquals(r1, r1.withVersion(4L));
    }

    @Test
    void domainEnum_hasUserAndAgent() {
        assertEquals(2, MemoryDomain.values().length);
        assertEquals(MemoryDomain.USER, MemoryDomain.valueOf("USER"));
        assertEquals(MemoryDomain.AGENT, MemoryDomain.valueOf("AGENT"));
    }

    @Test
    void layerFactories_andWithLayer_areExplicit() {
        MemoryRecord raw = MemoryRecord.raw("raw", "conversation", "chat", 1L, MemoryDomain.USER);
        assertEquals(MemoryLayer.L0, raw.layer());
        assertEquals(MemoryLayer.L2, raw.withLayer(MemoryLayer.L2).layer());
        assertEquals(MemoryLayer.L3,
                MemoryRecord.core("core", "rule", "review", 1L, 0.9, MemoryDomain.AGENT).layer());
    }
}
