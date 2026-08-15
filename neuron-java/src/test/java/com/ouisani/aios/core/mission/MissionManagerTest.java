package com.ouisani.aios.core.mission;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MissionManagerTest {
    private Path store;
    private MissionManager manager;

    @BeforeEach
    void setUp() throws Exception {
        store = Files.createTempFile("aios-missions-", ".json");
        manager = MissionManager.instance();
        manager.setStoreFileForTest(store);
        manager.clearForTesting();
    }

    @AfterEach
    void tearDown() throws Exception {
        manager.setStoreFileForTest(Path.of(".aios", "missions.json"));
        Files.deleteIfExists(store);
    }

    @Test
    void missionTracksRunApprovalKnowledgeAndCompletion() {
        MissionManager.Mission mission = manager.create("Ship the weekly report", "Collecting data", "Run the analysis", MissionManager.MissionStatus.ACTIVE);
        manager.attachRun(mission.missionId(), "run-42");
        manager.addApproval(mission.missionId(), "approval-1", "Send report", "email.send", "team@example.com", "run-42", "trace-42");
        manager.addKnowledge(mission.missionId(), "artifact", "weekly-report.pdf", "Final report", "outputs");

        MissionManager.Mission waiting = manager.get(mission.missionId()).orElseThrow();
        assertEquals(MissionManager.MissionStatus.WAITING_APPROVAL, waiting.status());
        assertEquals(java.util.List.of("run-42"), waiting.runIds());
        assertEquals(1, waiting.pendingApprovals().size());
        assertEquals(1, waiting.confirmedKnowledge().size());

        assertTrue(manager.resolveApproval("approval-1"));
        manager.observeRun("run-42", "workflow-42", "trace-42", "SUCCEEDED", "Workflow completed", "Review outputs", "Delivered report");
        MissionManager.Mission completed = manager.get(mission.missionId()).orElseThrow();
        assertEquals(MissionManager.MissionStatus.COMPLETED, completed.status());
        assertEquals("Delivered report", completed.completionReport());
    }

    @Test
    void missionStateSurvivesReload() {
        MissionManager.Mission created = manager.create("Remember me", null, null, MissionManager.MissionStatus.PLANNED);
        manager.update(created.missionId(), null, "Scheduled", "Wake on event", null, 123L, "cron", null);

        manager.setStoreFileForTest(store);
        MissionManager.Mission loaded = manager.get(created.missionId()).orElseThrow();
        assertEquals("Remember me", loaded.goal());
        assertEquals(MissionManager.MissionStatus.PLANNED, loaded.status());
        assertEquals("cron", loaded.nextTriggerEvent());
    }
}
