package com.ouisani.aios.test.redteam;

import com.ouisani.aios.core.tool.CanaryBeaconTool;
import com.ouisani.aios.core.tool.ToolCallLedger;
import com.ouisani.aios.core.tool.ToolContext;
import com.ouisani.aios.core.tool.ToolOutput;
import com.ouisani.aios.core.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CanaryBeaconTool + ToolCallLedger 金丝雀度量基础设施验证测试。
 * <p>
 * 验证三件事：
 * <ol>
 *   <li>金丝雀工具可被调用，调用后台账正确记录</li>
 *   <li>{@code hasCanaryBeenTriggered(expectedStatus)} 按 status 精确查询</li>
 *   <li>测试环境注册不污染生产内核 {@code registerBuiltinTools()}</li>
 * </ol>
 */
class CanaryBeaconToolTest {

    private ToolCallLedger ledger;

    @BeforeEach
    void setUp() {
        // 每个测试前清空共享台账，避免跨测试污染
        RedTeamHarness.resetCanaryLedger();
        // 在测试环境注册金丝雀工具（不污染生产内核）
        ledger = RedTeamHarness.registerCanaryTool();
    }

    @Test
    void canaryToolIsCallableAndRecordsToLedger() {
        // 调用金丝雀工具，status="attack_directive_1"
        CanaryBeaconTool tool = new CanaryBeaconTool(ledger);
        ToolContext ctx = new ToolContext("agent_test_1", null, "/tmp/test", null, null);

        ToolOutput out = tool.call(new CanaryBeaconTool.Input("attack_directive_1"), ctx);

        // 工具返回 ok
        assertTrue(out.success(), "金丝雀工具应返回 success");
        assertTrue(out.toText().contains("attack_directive_1"));

        // 台账记录了调用
        assertTrue(ledger.wasCalled(CanaryBeaconTool.TOOL_NAME), "台账应记录金丝雀被调用");
        assertEquals(1, ledger.callCount(CanaryBeaconTool.TOOL_NAME));
    }

    @Test
    void hasCanaryBeenTriggered_matchesByStatus() {
        CanaryBeaconTool tool = new CanaryBeaconTool(ledger);
        ToolContext ctx = new ToolContext("agent_test_2", null, "/tmp/test", null, null);

        // 调用两次，不同 status
        tool.call(new CanaryBeaconTool.Input("directive_A"), ctx);
        tool.call(new CanaryBeaconTool.Input("directive_B"), ctx);

        // 精确查询：directive_A 触发了
        assertTrue(ledger.hasCanaryBeenTriggered("directive_A"),
                "status=directive_A 的金丝雀应被触发");
        assertTrue(ledger.hasCanaryBeenTriggered("directive_B"),
                "status=directive_B 的金丝雀应被触发");

        // 未触发的 status
        assertFalse(ledger.hasCanaryBeenTriggered("directive_C"),
                "status=directive_C 从未调用，应返回 false");

        // null/空 status → 退化到"是否被调用过"
        assertTrue(ledger.hasCanaryBeenTriggered(null),
                "null status 应退化到'是否被调用过'，工具被调用过→true");
        assertTrue(ledger.hasCanaryBeenTriggered(""),
                "空串 status 应退化到'是否被调用过'");

        // 调用次数 = 2
        assertEquals(2, ledger.callCount(CanaryBeaconTool.TOOL_NAME));
        assertEquals(2, ledger.statusesOf(CanaryBeaconTool.TOOL_NAME).size());
    }

    @Test
    void hasCanaryBeenTriggered_returnsFalseWhenNeverCalled() {
        // 全新台账（reset 后未调用任何工具）
        ToolCallLedger freshLedger = new ToolCallLedger();

        assertFalse(freshLedger.hasCanaryBeenTriggered("any_status"),
                "从未调用过金丝雀，应返回 false");
        assertFalse(freshLedger.hasCanaryBeenTriggered(null),
                "从未调用过，null status 也应返回 false");
    }

    @Test
    void canaryToolIsRegisteredInTestEnvironment() {
        // 验证测试环境注册成功 — ToolRegistry 可查到 canary_beacon
        assertTrue(ToolRegistry.instance().get(CanaryBeaconTool.TOOL_NAME).isPresent(),
                "测试环境应能从 ToolRegistry 查到 canary_beacon 工具");

        // 验证工具元数据正确（通过 Tool 接口访问，避免泛型强转）
        var tool = ToolRegistry.instance().get(CanaryBeaconTool.TOOL_NAME).get();
        assertEquals(CanaryBeaconTool.TOOL_NAME, tool.name());
        assertTrue(tool.readOnly(), "金丝雀工具应是只读的（无副作用）");
        assertTrue(tool.description().contains("Canary beacon"),
                "工具描述应包含 Canary beacon");
    }

    @Test
    void resetCanaryLedger_clearsRecords() {
        // 先触发一次
        CanaryBeaconTool tool = new CanaryBeaconTool(ledger);
        ToolContext ctx = new ToolContext("agent_test_3", null, "/tmp/test", null, null);
        tool.call(new CanaryBeaconTool.Input("before_reset"), ctx);
        assertTrue(ledger.hasCanaryBeenTriggered("before_reset"));

        // reset 后清空
        RedTeamHarness.resetCanaryLedger();
        assertFalse(ledger.hasCanaryBeenTriggered("before_reset"),
                "reset 后应无记录");
        assertFalse(ledger.wasCalled(CanaryBeaconTool.TOOL_NAME),
                "reset 后 wasCalled 应返回 false");
        assertEquals(0, ledger.callCount(CanaryBeaconTool.TOOL_NAME));
    }
}
