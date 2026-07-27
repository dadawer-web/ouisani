package com.ouisani.aios.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link VfsManager#translateVfsPathsInCommand} 单测 — 验证 VFS 虚拟路径到宿主机物理路径的翻译。
 * <p>
 * 修复 OmniMother SWE 循环的 bug：LLM 经 VFS 写文件到 {@code /factory/xxx.py}，
 * 但 BashTool 在宿主机执行 {@code python3 -u /factory/xxx.py} 时找不到文件（{@code /factory}
 * 是 VFS 虚拟路径，host 根目录下不存在）。本测试覆盖翻译的正确性与误匹配防御。
 */
class VfsManagerPathTranslationTest {

    private static final String PHYSICAL_FACTORY = "/tmp/aios_test_factory_001";
    private static final String PHYSICAL_FACTORY_WF = "/tmp/aios_test_factory_wf_002";

    @AfterEach
    void cleanup() {
        // 清理单例状态，避免测试间泄漏
        VfsManager.instance().unregisterPhysicalWorkspace("/factory");
        VfsManager.instance().unregisterPhysicalWorkspace("/factory/wf_test");
    }

    @Test
    void translatesSingleFactoryPath() {
        VfsManager.instance().registerPhysicalWorkspace("/factory", PHYSICAL_FACTORY);
        String translated = VfsManager.instance().translateVfsPathsInCommand(
                "python3 -u /factory/agent_1.py");
        assertEquals("python3 -u " + PHYSICAL_FACTORY + "/agent_1.py", translated);
    }

    @Test
    void translatesMultipleFactoryPathsInOneCommand() {
        VfsManager.instance().registerPhysicalWorkspace("/factory", PHYSICAL_FACTORY);
        String translated = VfsManager.instance().translateVfsPathsInCommand(
                "ls /factory/outputs/ && cat /factory/result.json");
        assertEquals("ls " + PHYSICAL_FACTORY + "/outputs/ && cat " + PHYSICAL_FACTORY + "/result.json",
                translated);
    }

    @Test
    void doesNotTranslateWhenNoMappingRegistered() {
        // 无任何注册映射 → 原样返回
        String cmd = "python3 -u /factory/agent_1.py";
        assertEquals(cmd, VfsManager.instance().translateVfsPathsInCommand(cmd));
    }

    @Test
    void doesNotTranslateNonPathText() {
        VfsManager.instance().registerPhysicalWorkspace("/factory", PHYSICAL_FACTORY);
        // 无 /factory 路径 → 原样返回
        assertEquals("echo hello world", VfsManager.instance().translateVfsPathsInCommand("echo hello world"));
    }

    @Test
    void avoidsFalsePositiveOnSimilarPrefix() {
        VfsManager.instance().registerPhysicalWorkspace("/factory", PHYSICAL_FACTORY);
        // /factoryX 不应被当作 /factory 翻译（前缀后跟 word 字符）
        String translated = VfsManager.instance().translateVfsPathsInCommand("cat /factoryX/notes");
        assertEquals("cat /factoryX/notes", translated);
    }

    @Test
    void avoidsFalsePositiveOnSubpathContainingPrefix() {
        VfsManager.instance().registerPhysicalWorkspace("/factory", PHYSICAL_FACTORY);
        // /x/factory/data 含 /factory 子串（第二个 / 起），但前面是 word 字符 'x'，
        // lookbehind (?<![\w\-\.]) 应阻止匹配 —— 不应把子路径里的 factory 目录翻译掉
        String translated = VfsManager.instance().translateVfsPathsInCommand("cat /x/factory/data");
        assertEquals("cat /x/factory/data", translated);
    }

    @Test
    void longestPrefixWinsWhenMultipleRegistered() {
        // 同时注册 /factory 和 /factory/wf_test，最具体的优先
        VfsManager.instance().registerPhysicalWorkspace("/factory", PHYSICAL_FACTORY);
        VfsManager.instance().registerPhysicalWorkspace("/factory/wf_test", PHYSICAL_FACTORY_WF);

        String translated = VfsManager.instance().translateVfsPathsInCommand(
                "python3 -u /factory/wf_test/agent.py");
        // /factory/wf_test 应优先匹配 → 用 PHYSICAL_FACTORY_WF
        assertEquals("python3 -u " + PHYSICAL_FACTORY_WF + "/agent.py", translated);
    }

    @Test
    void shorterPrefixStillMatchesWhenLongerDoesNotApply() {
        VfsManager.instance().registerPhysicalWorkspace("/factory", PHYSICAL_FACTORY);
        VfsManager.instance().registerPhysicalWorkspace("/factory/wf_test", PHYSICAL_FACTORY_WF);
        // /factory/outputs 不匹配 /factory/wf_test，应回退到 /factory
        String translated = VfsManager.instance().translateVfsPathsInCommand(
                "ls /factory/outputs/");
        assertEquals("ls " + PHYSICAL_FACTORY + "/outputs/", translated);
    }

    @Test
    void handlesNullAndEmptyCommand() {
        VfsManager.instance().registerPhysicalWorkspace("/factory", PHYSICAL_FACTORY);
        assertNull(VfsManager.instance().translateVfsPathsInCommand(null));
        assertEquals("", VfsManager.instance().translateVfsPathsInCommand(""));
    }

    @Test
    void preservesQuotedPath() {
        VfsManager.instance().registerPhysicalWorkspace("/factory", PHYSICAL_FACTORY);
        // 路径在引号内也应翻译（前缀前是引号，满足边界）
        String translated = VfsManager.instance().translateVfsPathsInCommand(
                "python3 -u '/factory/agent.py'");
        assertEquals("python3 -u '" + PHYSICAL_FACTORY + "/agent.py'", translated);
    }
}
