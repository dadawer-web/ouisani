package com.ouisani.aios.core.permission;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * ActionTier / Urgency 枚举契约测试 — 覆盖 fromString 降级、snake_case 解析、DEFAULT 常量。
 */
class ActionTierUrgencyTest {

    // ── ActionTier.fromString ──

    @Test
    void action_tier_snake_case_auto_allowed() {
        assertEquals(ActionTier.AutoAllowed, ActionTier.fromString("auto_allowed"));
    }

    @Test
    void action_tier_snake_case_requires_permission() {
        assertEquals(ActionTier.RequiresPermission, ActionTier.fromString("requires_permission"));
    }

    @Test
    void action_tier_case_insensitive() {
        assertEquals(ActionTier.AutoAllowed, ActionTier.fromString("AUTO_ALLOWED"));
        assertEquals(ActionTier.RequiresPermission, ActionTier.fromString("Requires_Permission"));
    }

    @Test
    void action_tier_unknown_degrades_to_requires_permission() {
        assertEquals(ActionTier.RequiresPermission, ActionTier.fromString("unknown_tier"));
        assertEquals(ActionTier.RequiresPermission, ActionTier.fromString("malicious"));
    }

    @Test
    void action_tier_null_or_blank_degrades() {
        assertEquals(ActionTier.RequiresPermission, ActionTier.fromString(null));
        assertEquals(ActionTier.RequiresPermission, ActionTier.fromString(""));
        assertEquals(ActionTier.RequiresPermission, ActionTier.fromString("   "));
    }

    @Test
    void action_tier_alias_auto_and_requires() {
        assertEquals(ActionTier.AutoAllowed, ActionTier.fromString("auto"));
        assertEquals(ActionTier.RequiresPermission, ActionTier.fromString("requires"));
    }

    // ── Urgency.fromString ──

    @Test
    void urgency_low_normal_high() {
        assertEquals(Urgency.Low, Urgency.fromString("low"));
        assertEquals(Urgency.Normal, Urgency.fromString("normal"));
        assertEquals(Urgency.High, Urgency.fromString("high"));
    }

    @Test
    void urgency_case_insensitive() {
        assertEquals(Urgency.High, Urgency.fromString("HIGH"));
        assertEquals(Urgency.Low, Urgency.fromString("Low"));
    }

    @Test
    void urgency_unknown_degrades_to_normal() {
        assertEquals(Urgency.Normal, Urgency.fromString("urgent"));
        assertEquals(Urgency.Normal, Urgency.fromString("critical"));
    }

    @Test
    void urgency_null_or_blank_degrades_to_normal() {
        assertEquals(Urgency.Normal, Urgency.fromString(null));
        assertEquals(Urgency.Normal, Urgency.fromString(""));
        assertEquals(Urgency.Normal, Urgency.fromString("  "));
    }

    @Test
    void urgency_default_constant_is_normal() {
        assertNotNull(Urgency.DEFAULT);
        assertEquals(Urgency.Normal, Urgency.DEFAULT);
    }
}
