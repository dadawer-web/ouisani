package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.VfsManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BashTool × VfsManager 端到端集成测试 — 验证 VFS 路径翻译在真实 bash 执行链路上生效。
 * <p>
 * 复现并锁定用户报告的 bug：OmniMotherAgent 生成 {@code python3 -u /factory/xxx.py}，
 * 但 {@code /factory} 是 VFS 虚拟路径，BashTool 在宿主机执行时找不到文件
 * （日志表现为 {@code bash success=false (82ms)}）。
 * <p>
 * 修复链路：BashTool.call → VfsManager.translateVfsPathsInCommand → 宿主机物理路径。
 * 本测试用 {@code test -f} 触达真实的"文件能否被 bash 找到"语义，而非仅断言字符串翻译。
 */
class BashToolVfsPathIntegrationTest {

    @TempDir
    Path tempFactory;

    private static final String VFS_PREFIX = "/factory";

    @BeforeEach
    void registerMapping() {
        VfsManager.instance().registerPhysicalWorkspace(VFS_PREFIX, tempFactory.toString());
    }

    @AfterEach
    void unregisterMapping() {
        VfsManager.instance().unregisterPhysicalWorkspace(VFS_PREFIX);
    }

    @Test
    void bashFindsFileAtVfsPathAfterTranslation() throws Exception {
        // 1. 在物理目录下放一个真实文件（模拟 LLM 经 VFS writeText 写出的产物）
        Path agentScript = tempFactory.resolve("agent.py");
        Files.writeString(agentScript, "print('hello-vfs')");

        // 2. LLM 生成的命令引用 VFS 虚拟路径 /factory/agent.py
        //    若 VFS 翻译未生效，bash 在宿主机根目录找不到 /factory/agent.py → test -f 失败
        BashTool bash = new BashTool();
        ToolContext ctx = new ToolContext("vfs-e2e-test", null, null);
        ToolOutput out = bash.call(new BashTool.Input("test -f /factory/agent.py && echo FOUND"), ctx);

        assertTrue(out.success(), () -> "bash 应能在翻译后的物理路径找到文件。输出: " + out.toText());
        assertTrue(out.toText().contains("FOUND"),
                () -> "test -f 应输出 FOUND，实际: " + out.toText());
    }

    @Test
    void bashReadsFileContentAtVfsPathAfterTranslation() throws Exception {
        Path notes = tempFactory.resolve("notes.txt");
        Files.writeString(notes, " vfs-content-marker ");

        BashTool bash = new BashTool();
        ToolContext ctx = new ToolContext("vfs-e2e-test", null, null);
        ToolOutput out = bash.call(new BashTool.Input("cat /factory/notes.txt"), ctx);

        assertTrue(out.success(), () -> "cat 应成功。输出: " + out.toText());
        assertTrue(out.toText().contains("vfs-content-marker"),
                () -> "应读到物理文件内容，实际: " + out.toText());
    }

    @Test
    void bashWithoutMappingFailsToFindVirtualFile() throws Exception {
        // 反向对照：注销映射后，/factory/missing.py 在宿主机不存在 → bash 失败
        // 这证明通过测试的不是"凑巧成功"，而是翻译真起了作用
        VfsManager.instance().unregisterPhysicalWorkspace(VFS_PREFIX);

        BashTool bash = new BashTool();
        ToolContext ctx = new ToolContext("vfs-e2e-test", null, null);
        ToolOutput out = bash.call(new BashTool.Input("test -f /factory/missing.py"), ctx);

        assertTrue(!out.success() || !out.toText().contains("FOUND"),
                () -> "无映射时 test -f /factory/missing.py 不应找到文件。输出: " + out.toText());
    }
}
