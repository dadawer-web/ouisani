package com.ouisani.aios.core.ipc;

import com.ouisani.aios.core.tool.DelegationToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ScopedSharedMemoryTest {

    private final SharedMemoryManager memory = SharedMemoryManager.instance();

    @AfterEach
    void clearCallerContext() {
        CallerContext.clear();
    }

    @Test
    void privateMemoryNeverLeaksToAnotherAgentInSameWorkflow() {
        MemoryAccessContext owner = MemoryAccessContext.of("researcher", "tenant-a", "wf-1", null);
        MemoryAccessContext peer = MemoryAccessContext.of("writer", "tenant-a", "wf-1", null);

        memory.putMemory("task/research/private", "private-1", "draft", MemoryScope.PRIVATE,
                "agent-inference", owner);

        assertThrows(MemoryAccessDeniedException.class,
                () -> memory.getMemory("task/research/private", "private-1", peer));
        assertEquals("draft", memory.getMemory("task/research/private", "private-1", owner).content());
    }

    @Test
    void taskMemoryRequiresSameWorkflowAndTenant() {
        MemoryAccessContext researcher = MemoryAccessContext.of("researcher", "tenant-a", "wf-1", null);
        MemoryAccessContext writer = MemoryAccessContext.of("writer", "tenant-a", "wf-1", null);
        MemoryAccessContext otherWorkflow = MemoryAccessContext.of("writer", "tenant-a", "wf-2", null);
        MemoryAccessContext otherTenant = MemoryAccessContext.of("writer", "tenant-b", "wf-1", null);

        memory.putMemory("task/research/evidence", "evidence-1", "fact", MemoryScope.TASK,
                "tool_result/external", researcher);
        assertEquals("fact", memory.getMemory("task/research/evidence", "evidence-1", writer).content());
        assertThrows(MemoryAccessDeniedException.class,
                () -> memory.getMemory("task/research/evidence", "evidence-1", otherWorkflow));
        assertThrows(MemoryAccessDeniedException.class,
                () -> memory.getMemory("task/research/evidence", "evidence-1", otherTenant));
    }

    @Test
    void delegationTokenLimitsNamespaceAccess() {
        DelegationToken root = DelegationToken.rootWithCapabilities(
                "coordinator", "tenant-a", "wf-1", "trace-1", Set.of("memory:task/research/*"));
        DelegationToken child = DelegationToken.issueChild(root, "writer",
                Set.of("memory:task/research/evidence"));
        MemoryAccessContext owner = MemoryAccessContext.of("researcher", "tenant-a", "wf-1", null);
        MemoryAccessContext delegated = MemoryAccessContext.of("writer", "tenant-a", "wf-1", null, child);

        memory.putMemory("task/research/evidence", "evidence-token", "fact", MemoryScope.TASK,
                "tool_result/external", owner);
        memory.putMemory("task/secret", "secret-1", "classified", MemoryScope.TASK,
                "agent-inference", owner);
        assertEquals("fact", memory.getMemory("task/research/evidence", "evidence-token", delegated).content());
        assertThrows(MemoryAccessDeniedException.class,
                () -> memory.getMemory("task/secret", "secret-1", delegated));
    }

    @Test
    void appendCasAndHistoryPreventSilentOverwrite() {
        MemoryAccessContext owner = MemoryAccessContext.of("researcher", "tenant-a", "wf-1", null);
        MemoryRecord created = memory.putMemory("task/research/evidence", "evidence-cas", "one",
                MemoryScope.TASK, "tool_result/external", owner);
        MemoryRecord appended = memory.appendMemory("task/research/evidence", "evidence-cas", "two",
                "agent-inference", owner);

        assertEquals(2L, appended.version());
        assertTrue(appended.content().contains("one"));
        assertTrue(memory.compareAndSetMemory("task/research/evidence", "evidence-cas", 1L,
                "stale", "agent-inference", owner).isEmpty());
        MemoryRecord committed = memory.compareAndSetMemory("task/research/evidence", "evidence-cas", 2L,
                "three", "agent-inference", owner).orElseThrow();
        assertEquals(3L, committed.version());
        assertEquals(2, memory.memoryHistory("task/research/evidence", "evidence-cas", owner).size());
        assertEquals(created.version(), memory.memoryHistory("task/research/evidence", "evidence-cas", owner).get(0).version());
    }

    @Test
    void teamMemoryIsVisibleOnlyToMatchingTeam() {
        MemoryAccessContext owner = MemoryAccessContext.of("researcher", "tenant-a", "wf-1", "team-red");
        MemoryAccessContext peer = MemoryAccessContext.of("writer", "tenant-a", "wf-2", "team-red");
        MemoryAccessContext outsider = MemoryAccessContext.of("writer", "tenant-a", "wf-2", "team-blue");

        memory.putMemory("team/research", "team-1", "shared", MemoryScope.TEAM,
                "agent-inference", "team-red", owner);
        assertEquals("shared", memory.getMemory("team/research", "team-1", peer).content());
        assertThrows(MemoryAccessDeniedException.class,
                () -> memory.getMemory("team/research", "team-1", outsider));
    }
}
