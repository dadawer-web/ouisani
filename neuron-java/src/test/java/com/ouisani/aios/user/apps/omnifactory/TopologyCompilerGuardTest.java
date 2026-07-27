package com.ouisani.aios.user.apps.omnifactory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TopologyCompiler} 输入守卫单元测试 — 验证 {@code shouldClarify} 两段式判定
 * （Stage A 极短输入 / Stage B 歧义词+停用词剥离无主题），以及触发守卫时
 * {@code compileTopology} 返回的澄清 JSON 契约（needClarification + agentType）。
 * <p>
 * 关键：避免误杀合法短请求（"调研丰田环境问题"9字 / "研究特斯拉电池"7字）。
 */
class TopologyCompilerGuardTest {

    // ════════════════════════════════════════════════════════════════
    //  shouldClarify — 应触发澄清
    // ════════════════════════════════════════════════════════════════

    @Test
    void ambiguousResearchInput_shouldClarify() {
        // 用户原始 case: 10字, 含"环境"+科研, 剥离后空
        assertTrue(TopologyCompiler.shouldClarify("帮我做一个环境的科研"));
    }

    @Test
    void tooShortInput_shouldClarify_stageA() {
        assertTrue(TopologyCompiler.shouldClarify("环境"));      // 2字
        assertTrue(TopologyCompiler.shouldClarify("搭建环境"));   // 4字
        assertTrue(TopologyCompiler.shouldClarify("部署服务"));   // 4字
    }

    @Test
    void ambiguousNoSubject_shouldClarify_stageB() {
        // 含歧义词但剥离后无具体实体
        assertTrue(TopologyCompiler.shouldClarify("做系统科研"));   // 剥离后空
        assertTrue(TopologyCompiler.shouldClarify("配置一下工具")); // 剥离后空
    }

    @Test
    void nullAndBlank_shouldClarify() {
        assertTrue(TopologyCompiler.shouldClarify(null));
        assertTrue(TopologyCompiler.shouldClarify(""));
        assertTrue(TopologyCompiler.shouldClarify("   "));
    }

    // ════════════════════════════════════════════════════════════════
    //  shouldClarify — 不应误杀合法短请求
    // ════════════════════════════════════════════════════════════════

    @Test
    void researchWithConcreteSubject_shouldNotClarify() {
        assertFalse(TopologyCompiler.shouldClarify("调研丰田环境问题"));  // 9字, 剥离后"丰田"
        assertFalse(TopologyCompiler.shouldClarify("研究特斯拉电池"));     // 7字, 不含歧义词
        assertFalse(TopologyCompiler.shouldClarify("分析某地环境保护政策")); // 剥离后"某地"
    }

    @Test
    void setupWithTechStack_shouldNotClarify() {
        assertFalse(TopologyCompiler.shouldClarify("搭建Python开发环境")); // 剥离后"Python开发"
        assertFalse(TopologyCompiler.shouldClarify("搭建Java环境"));       // 剥离后"Java"
        assertFalse(TopologyCompiler.shouldClarify("配置K8s集群"));        // 剥离后"K8s集群"
        assertFalse(TopologyCompiler.shouldClarify("部署服务到K8s"));      // 7字, 剥离后"K8s"
    }

    @Test
    void nonAmbiguousLongInput_shouldNotClarify() {
        assertFalse(TopologyCompiler.shouldClarify("写一个排序算法"));
        assertFalse(TopologyCompiler.shouldClarify("翻译这段文档"));
    }

    // ════════════════════════════════════════════════════════════════
    //  compileTopology 守卫触发 — 验证澄清 JSON 契约
    // ════════════════════════════════════════════════════════════════

    @Test
    void compileTopology_ambiguousInput_returnsClarificationJson() {
        // 守卫在 LLM 调用之前返回, 不触发 AiosSdk, 安全可测
        String result = TopologyCompiler.compileTopology(
                "帮我做一个环境的科研", java.util.List.of(), java.util.List.of());

        assertTrue(result.contains("\"needClarification\":true"),
                "澄清 JSON 应含 needClarification:true, 实际: " + result);
        assertTrue(result.contains("\"agentType\":\"omni\""),
                "应自带 agentType 免改 AppGateway, 实际: " + result);
        assertTrue(result.contains("\"nodes\":[]"),
                "应含空 nodes 数组, 实际: " + result);
        assertTrue(result.contains("环境"),
                "clarificationQuestion 应提示命中的歧义词「环境」, 实际: " + result);
    }

    @Test
    void compileTopology_shortInput_returnsClarificationJson() {
        String result = TopologyCompiler.compileTopology(
                "搭建环境", java.util.List.of(), java.util.List.of());
        assertTrue(result.contains("\"needClarification\":true"),
                "短输入应触发守卫返回澄清 JSON, 实际: " + result);
    }
}
