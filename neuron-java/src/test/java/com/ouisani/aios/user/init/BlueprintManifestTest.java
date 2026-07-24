package com.ouisani.aios.user.init;

import com.ouisani.aios.user.apps.omnifactory.AgentBlueprint;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class BlueprintManifestTest {

    @Test
    @DisplayName("解析 auto_medic BLUEPRINT.md")
    void testParseAutoMedicBlueprint() {
        String content = """
                ---
                blueprintId: auto_medic
                description: 系统自愈守护进程 — 监听 sys.kernel.panic 事件并自动修复崩溃节点
                requiredParams: []
                ---
                # AutoMedic — 系统内置，由 InitDaemon 自动拉起
                import BaseAgent
                class AutoMedicDaemon(BaseAgent.BaseAgent):
                    pass
                """;
        AgentBlueprint bp = InitDaemon.parseBlueprintManifest("auto_medic", content);
        assertEquals("auto_medic", bp.blueprintId());
        assertTrue(bp.description().contains("系统自愈守护进程"));
        assertTrue(bp.codePayload().contains("import BaseAgent"));
        assertTrue(bp.requiredParams().isEmpty());
    }

    @Test
    @DisplayName("解析带 requiredParams 的 BLUEPRINT.md")
    void testParseBlueprintWithRequiredParams() {
        String content = """
                ---
                blueprintId: custom_agent
                description: Custom agent with params
                requiredParams: ["target_url", "max_retries"]
                ---
                print("hello")
                """;
        AgentBlueprint bp = InitDaemon.parseBlueprintManifest("fallback_id", content);
        assertEquals("custom_agent", bp.blueprintId());
        assertEquals(2, bp.requiredParams().size());
        assertTrue(bp.requiredParams().contains("target_url"));
        assertTrue(bp.requiredParams().contains("max_retries"));
        assertEquals("print(\"hello\")", bp.codePayload());
    }
}
