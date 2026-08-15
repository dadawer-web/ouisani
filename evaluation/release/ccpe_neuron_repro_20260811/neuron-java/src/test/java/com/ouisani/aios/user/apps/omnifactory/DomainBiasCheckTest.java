package com.ouisani.aios.user.apps.omnifactory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DomainBiasCheck} 单测 — 验证"科研意图被锚定到系统环境搭建"的偏差检测。
 * <p>
 * 覆盖四类场景：偏差触发 / 偏差不触发 / think 标签独立性 / 混合意图豁免。
 * 复现 Terminal#374-430 bug 的节点模式（INSTALL/CLONE/FETCH_AIOS/项目结构等）。
 */
class DomainBiasCheckTest {

    // ════════════════════════════════════════════════════════════════
    //  偏差应触发 — 科研意图 + 系统搭建节点
    // ════════════════════════════════════════════════════════════════

    @Test
    void researchIntentWithInstallMarker_isBiased() {
        DomainBiasCheck.BiasResult r = DomainBiasCheck.check(
                "帮我做一个环境的科研",
                "{\"nodes\":[{\"instanceId\":\"step1_install_os\",\"role\":\"INSTALL OS utils\"}]}");
        assertTrue(r.biased(), () -> "科研意图 + INSTALL 节点应判偏差: " + r.reason());
        assertTrue(r.hitMarkers().stream().anyMatch(m -> m.contains("INSTALL")),
                () -> "hitMarkers 应含 INSTALL: " + r.hitMarkers());
    }

    @Test
    void researchIntentWithCloneMarker_isBiased() {
        DomainBiasCheck.BiasResult r = DomainBiasCheck.check(
                "调研AIOS系统架构的科研",
                "{\"nodes\":[{\"instanceId\":\"clone_source\",\"role\":\"CLONE repo\"}]}");
        assertTrue(r.biased(), () -> "科研 + CLONE 应判偏差: " + r.reason());
    }

    @Test
    void researchIntentWithProjectScaffolding_isBiased() {
        DomainBiasCheck.BiasResult r = DomainBiasCheck.check(
                "做固态电池科研",
                "{\"nodes\":[{\"role\":\"创建项目结构 pom.xml\"}]}");
        assertTrue(r.biased(), () -> "科研 + 项目结构应判偏差: " + r.reason());
    }

    @Test
    void researchIntentWithAiosArchitectureMarker_isBiased() {
        // 用户 bug report 实证：FETCH_AIOS_ARCHITECTURE_DOC
        DomainBiasCheck.BiasResult r = DomainBiasCheck.check(
                "做环境的科研",
                "{\"nodes\":[{\"instanceId\":\"fetch_aios_architecture_doc\"}]}");
        assertTrue(r.biased(), () -> "科研 + AIOS_ARCHITECTURE 应判偏差: " + r.reason());
    }

    @Test
    void researchIntentWithMultipleMarkers_reportsAllHits() {
        DomainBiasCheck.BiasResult r = DomainBiasCheck.check(
                "做科研",
                "{\"nodes\":[{\"role\":\"INSTALL\"},{\"role\":\"CLONE\"},{\"role\":\"CONFIGURE\"}]}");
        assertTrue(r.biased());
        assertTrue(r.hitMarkers().size() >= 3,
                () -> "应报告全部命中标记，实际: " + r.hitMarkers());
    }

    // ════════════════════════════════════════════════════════════════
    //  偏差不应触发
    // ════════════════════════════════════════════════════════════════

    @Test
    void researchIntentWithoutMarkers_notBiased() {
        DomainBiasCheck.BiasResult r = DomainBiasCheck.check(
                "调研丰田环境问题",
                "{\"nodes\":[{\"instanceId\":\"web_search\",\"role\":\"WebSearch 丰田固态电池\"}]}");
        assertFalse(r.biased(), () -> "科研 + WebSearch 节点不应判偏差: " + r.reason());
    }

    @Test
    void nonResearchIntentWithInstall_notBiased() {
        // 搭建 Python 环境 → INSTALL 合法（无科研意图词）
        DomainBiasCheck.BiasResult r = DomainBiasCheck.check(
                "搭建Python开发环境",
                "{\"nodes\":[{\"role\":\"INSTALL python deps\"}]}");
        assertFalse(r.biased(), () -> "非科研意图 + INSTALL 不应判偏差: " + r.reason());
    }

    @Test
    void emptyTopology_notBiased() {
        DomainBiasCheck.BiasResult r = DomainBiasCheck.check("做科研", "");
        assertFalse(r.biased());
        r = DomainBiasCheck.check("做科研", null);
        assertFalse(r.biased());
    }

    // ════════════════════════════════════════════════════════════════
    //  混合意图豁免 — 用户显式要求搭建/部署，即使含科研词也不判偏差
    // ════════════════════════════════════════════════════════════════

    @Test
    void mixedIntentWithSetupVerb_notBiased() {
        DomainBiasCheck.BiasResult r = DomainBiasCheck.check(
                "调研并搭建XX环境",
                "{\"nodes\":[{\"role\":\"INSTALL deps\"}]}");
        assertFalse(r.biased(), () -> "混合意图(调研+搭建)应豁免: " + r.reason());
    }

    @Test
    void mixedIntentWithDeployVerb_notBiased() {
        DomainBiasCheck.BiasResult r = DomainBiasCheck.check(
                "研究后部署该服务",
                "{\"nodes\":[{\"role\":\"CLONE repo\"}]}");
        assertFalse(r.biased(), () -> "混合意图(研究+部署)应豁免: " + r.reason());
    }

    // ════════════════════════════════════════════════════════════════
    //  think 标签独立性 — 只看实际节点，不看 LLM 的思考过程
    // ════════════════════════════════════════════════════════════════

    @Test
    void markersOnlyInThinkBlock_notBiased() {
        // LLM 在 think 里 musing INSTALL/CLONE，但实际节点是 WebSearch → 不应误判
        String topology = "<think>我考虑过要 INSTALL 依赖并 CLONE 仓库，但这不对</think>"
                + "{\"nodes\":[{\"instanceId\":\"web_search\",\"role\":\"WebSearch\"}]}";
        DomainBiasCheck.BiasResult r = DomainBiasCheck.check("做环境的科研", topology);
        assertFalse(r.biased(), () -> "think 内的标记不应触发偏差: " + r.reason());
    }

    @Test
    void markersInActualNodes_stillBiased_afterThinkStripped() {
        String topology = "<think>这是科研任务</think>"
                + "{\"nodes\":[{\"role\":\"INSTALL os utils\"}]}";
        DomainBiasCheck.BiasResult r = DomainBiasCheck.check("做科研", topology);
        assertTrue(r.biased(), () -> "节点内的 INSTALL 应触发偏差: " + r.reason());
    }

    // ════════════════════════════════════════════════════════════════
    //  英文标记大小写不敏感
    // ════════════════════════════════════════════════════════════════

    @Test
    void asciiMarkersCaseInsensitive() {
        DomainBiasCheck.BiasResult r = DomainBiasCheck.check(
                "做科研", "{\"nodes\":[{\"role\":\"install os utils\"}]}");
        assertTrue(r.biased(), () -> "小写 install 应同样触发偏差: " + r.reason());
    }
}
