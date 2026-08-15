package com.ouisani.aios.core.wiki;

import com.ouisani.aios.core.ipc.MemoryAccessContext;
import com.ouisani.aios.core.ipc.MemoryRecord;
import com.ouisani.aios.core.ipc.MemoryScope;
import com.ouisani.aios.core.ipc.SharedMemoryManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WikiCompilerTest {

    @TempDir
    Path tempDir;

    private final SharedMemoryManager memory = SharedMemoryManager.instance();
    private final WikiCompiler compiler = WikiCompiler.instance();

    @AfterEach
    void resetWikiMetadata() {
        // The shared-memory singleton intentionally has no global clear API;
        // use unique namespaces/ids in each test so records cannot affect a
        // later projection while still testing the real governed store.
        compiler.setConfirmationFileForTest(tempDir.resolve("wiki-confirmations.json"));
        compiler.clearConfirmationMetadataForTest();
    }

    @Test
    void compilesGovernedRecordWithProvenanceAndDecisionRelationship() {
        compiler.setConfirmationFileForTest(tempDir.resolve("wiki-confirmations.json"));
        compiler.clearConfirmationMetadataForTest();
        MemoryAccessContext caller = MemoryAccessContext.of("agent-wiki-a", "tenant-wiki", "wf-wiki", null);
        long now = System.currentTimeMillis();
        MemoryRecord record = new MemoryRecord(
                "decision-new", "project/wiki-test", MemoryScope.TASK, "agent-wiki-a",
                "wf-wiki", "tenant-wiki", null, "text/markdown", "Use the governed compiler",
                "tool_result", "tool://research/1", "researcher", "trace-wiki-1", 4L, 0.82,
                Set.of("decision", "title:Compiler decision", "basis:scope review", "supersedes:decision-old"),
                now, now, null);
        memory.createMemory(record, caller);

        WikiCompiler.WikiEntry entry = compiler.compile(List.of(record), caller).stream()
                .filter(item -> item.memoryId().equals("decision-new"))
                .findFirst().orElseThrow();

        assertEquals(WikiCompiler.Category.DECISIONS, entry.category());
        assertEquals("Compiler decision", entry.title());
        assertEquals("tool_result", entry.source());
        assertEquals("tool://research/1", entry.sourceRef());
        assertEquals("researcher", entry.sourceAgentId());
        assertEquals("trace-wiki-1", entry.traceId());
        assertEquals(0.82, entry.confidence());
        assertEquals(4L, entry.version());
        assertEquals("TASK", entry.visibilityScope());
        assertEquals("scope review", entry.basis());
        assertEquals("decision-old", entry.supersedesWikiId());
        assertFalse(entry.userConfirmed());
    }

    @Test
    void compilesOnlyRecordsVisibleToCallerAndPersistsConfirmation() {
        compiler.setConfirmationFileForTest(tempDir.resolve("wiki-confirmations.json"));
        compiler.clearConfirmationMetadataForTest();
        MemoryAccessContext owner = MemoryAccessContext.of("agent-wiki-owner", "tenant-wiki", "wf-visible", null);
        MemoryAccessContext outsider = MemoryAccessContext.of("agent-wiki-outsider", "tenant-wiki", "wf-visible", null);
        memory.putMemory("project/wiki-visible", "visible", "topic content", MemoryScope.TASK,
                "agent-inference", owner);
        memory.putMemory("project/wiki-private", "private", "private content", MemoryScope.PRIVATE,
                "agent-inference", owner);

        List<WikiCompiler.WikiEntry> visible = compiler.compileVisible(outsider, false);
        assertTrue(visible.stream().anyMatch(item -> item.memoryId().equals("visible")));
        assertTrue(visible.stream().noneMatch(item -> item.memoryId().equals("private")));

        WikiCompiler.WikiEntry target = compiler.compileVisible(owner, false).stream()
                .filter(item -> item.memoryId().equals("visible")).findFirst().orElseThrow();
        assertTrue(compiler.confirm(target.wikiId(), owner, true).orElseThrow().userConfirmed());
        assertTrue(compiler.compileVisible(owner, false).stream()
                .filter(item -> item.wikiId().equals(target.wikiId())).findFirst().orElseThrow().userConfirmed());
    }
}
