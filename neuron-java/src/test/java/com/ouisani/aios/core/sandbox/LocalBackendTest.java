package com.ouisani.aios.core.sandbox;

import com.ouisani.aios.core.VfsManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LocalBackend} 单元测试 — 覆盖七个原语（write_file / read_file / exec_shell /
 * join_path / file_exists / list_dir / delete_path）与后端可插拔契约。
 * <p>
 * 测试策略：
 * <ul>
 *   <li>宿主机文件系统路径（tempDir）验证 host I/O 行为；</li>
 *   <li>VFS 路径（/factory/...）验证 VFS 优先 + 路径翻译 + 物理映射行为；</li>
 *   <li>exec_shell 验证退出码、超时、VFS 路径翻译、环境变量注入。</li>
 * </ul>
 */
class LocalBackendTest {

    private static final String VFS_PREFIX = "/factory";

    @TempDir
    Path tempDir;

    private BackendBase backend;

    @BeforeAll
    static void initVfs() {
        // VfsManager.init() 只能跑一次；多次调用幂等。
        VfsManager.instance().init();
    }

    @BeforeEach
    void setUp() {
        backend = LocalBackend.instance();
        // 每个测试独立注册 VFS 物理映射，确保隔离
        VfsManager.instance().registerPhysicalWorkspace(VFS_PREFIX, tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        VfsManager.instance().unregisterPhysicalWorkspace(VFS_PREFIX);
    }

    // ════════════════════════════════════════════════════════════════
    //  write_file / read_file
    // ════════════════════════════════════════════════════════════════

    @Test
    void writeFileThenReadFileRoundTripOnHost() throws Exception {
        Path hostFile = tempDir.resolve("roundtrip.txt");
        String path = hostFile.toString();

        assertTrue(backend.write_file(path, "hello-local-backend"));
        assertEquals("hello-local-backend", backend.read_file(path));
    }

    @Test
    void writeFileCreatesParentDirectories() throws Exception {
        Path nested = tempDir.resolve("a/b/c/deep.txt");
        assertTrue(backend.write_file(nested.toString(), "nested-content"));
        assertEquals("nested-content", Files.readString(nested));
    }

    @Test
    void writeFileViaVfsPathRoutesToRegisteredPhysicalWorkspace() throws Exception {
        String vfsPath = VFS_PREFIX + "/agent_1.py";
        assertTrue(backend.write_file(vfsPath, "print('via-vfs')"));

        // VFS 读取应能拿到
        assertEquals("print('via-vfs')", backend.read_file(vfsPath));
        // 物理文件也应存在（通过 HostSourceNode 落盘）
        Path physical = tempDir.resolve("agent_1.py");
        assertTrue(Files.exists(physical));
        assertEquals("print('via-vfs')", Files.readString(physical));
    }

    @Test
    void readFileReturnsNullForMissingPath() {
        Path missing = tempDir.resolve("does-not-exist.txt");
        assertNull(backend.read_file(missing.toString()));
    }

    @Test
    void readFileHandlesBlankPath() {
        assertNull(backend.read_file(""));
        assertNull(backend.read_file(null));
    }

    @Test
    void writeFileHandlesBlankPath() {
        assertFalse(backend.write_file("", "content"));
        assertFalse(backend.write_file(null, "content"));
    }

    // ════════════════════════════════════════════════════════════════
    //  file_exists
    // ════════════════════════════════════════════════════════════════

    @Test
    void fileExistsTrueForExistingHostFile() throws Exception {
        Path f = tempDir.resolve("exists.txt");
        Files.writeString(f, "yes");
        assertTrue(backend.file_exists(f.toString()));
    }

    @Test
    void fileExistsFalseForMissingPath() {
        assertFalse(backend.file_exists(tempDir.resolve("missing.txt").toString()));
    }

    @Test
    void fileExistsTrueForVfsPath() {
        String vfsPath = VFS_PREFIX + "/vfs_exists.txt";
        assertTrue(backend.write_file(vfsPath, "x"));
        assertTrue(backend.file_exists(vfsPath));
    }

    // ════════════════════════════════════════════════════════════════
    //  join_path
    // ════════════════════════════════════════════════════════════════

    @Test
    void joinPathConcatenatesFragments() {
        String joined = backend.join_path("/vfs", "workspace", "agent.py");
        // Path.of 跨平台行为 — Linux 下为 /vfs/workspace/agent.py
        assertEquals("/vfs/workspace/agent.py", joined);
    }

    @Test
    void joinPathReturnsBaseWhenNoChildren() {
        assertEquals("/vfs", backend.join_path("/vfs"));
    }

    @Test
    void joinPathHandlesNullBase() {
        // null base → 空字符串基础，拼接结果以子路径为准
        assertNotNull(backend.join_path(null, "x"));
    }

    // ════════════════════════════════════════════════════════════════
    //  list_dir
    // ════════════════════════════════════════════════════════════════

    @Test
    void listDirReturnsHostChildren() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "a");
        Files.writeString(tempDir.resolve("b.txt"), "b");
        var entries = backend.list_dir(tempDir.toString());
        assertTrue(entries.contains("a.txt"));
        assertTrue(entries.contains("b.txt"));
    }

    @Test
    void listDirReturnsEmptyForMissingDir() {
        var entries = backend.list_dir(tempDir.resolve("no-such-dir").toString());
        assertTrue(entries.isEmpty());
    }

    @Test
    void listDirHandlesBlankPath() {
        assertTrue(backend.list_dir("").isEmpty());
        assertTrue(backend.list_dir(null).isEmpty());
    }

    // ════════════════════════════════════════════════════════════════
    //  delete_path
    // ════════════════════════════════════════════════════════════════

    @Test
    void deletePathRemovesHostFile() throws Exception {
        Path f = tempDir.resolve("to-delete.txt");
        Files.writeString(f, "bye");
        assertTrue(backend.delete_path(f.toString()));
        assertFalse(Files.exists(f));
    }

    @Test
    void deletePathReturnsFalseForMissing() {
        assertFalse(backend.delete_path(tempDir.resolve("missing.txt").toString()));
    }

    @Test
    void deletePathHandlesBlankPath() {
        assertFalse(backend.delete_path(""));
        assertFalse(backend.delete_path(null));
    }

    // ════════════════════════════════════════════════════════════════
    //  exec_shell
    // ════════════════════════════════════════════════════════════════

    @Test
    void execShellReturnsZeroExitCodeOnSuccess() {
        ExecResult result = backend.exec_shell("echo hello-backend", ExecOptions.DEFAULT);
        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("hello-backend"));
        assertFalse(result.timedOut());
        assertNull(result.errorMessage());
        assertTrue(result.success());
    }

    @Test
    void execShellReturnsNonZeroExitCodeOnFailure() {
        ExecResult result = backend.exec_shell("exit 7", ExecOptions.DEFAULT);
        assertEquals(7, result.exitCode());
        assertFalse(result.success());
    }

    @Test
    void execShellReturnsErrorForEmptyCommand() {
        ExecResult result = backend.exec_shell("", ExecOptions.DEFAULT);
        assertNotNull(result.errorMessage());
        assertFalse(result.success());
    }

    @Test
    void execShellReturnsErrorForBlankCommand() {
        ExecResult result = backend.exec_shell("   ", ExecOptions.DEFAULT);
        assertNotNull(result.errorMessage());
        assertFalse(result.success());
    }

    @Test
    void execShellTranslatesVfsPathsToPhysical() throws Exception {
        // VFS 路径 /factory/translate_me.txt 应翻译为 tempDir 下的物理路径
        Path physical = tempDir.resolve("translate_me.txt");
        Files.writeString(physical, "translated-content");

        ExecResult result = backend.exec_shell("cat " + VFS_PREFIX + "/translate_me.txt", ExecOptions.DEFAULT);
        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("translated-content"),
                () -> "VFS 路径应被翻译为物理路径，实际输出: " + result.output());
    }

    @Test
    void execShellRespectsWorkingDir() {
        ExecOptions options = ExecOptions.DEFAULT.withWorkingDir(tempDir.toString());
        ExecResult result = backend.exec_shell("pwd", options);
        assertEquals(0, result.exitCode());
        // ProcessBuilder.directory() 会规范化路径；tempDir 可能含软链（如 /tmp → /private/tmp），
        // 因此仅断言路径末段，避免误报。
        assertTrue(result.output().contains(tempDir.getFileName().toString()),
                () -> "pwd 应输出工作目录，实际: " + result.output());
    }

    @Test
    void execShellInjectsEnvironmentVariables() {
        ExecOptions options = ExecOptions.DEFAULT.withEnv(Map.of("AIOS_TEST_VAR", "injected-123"));
        ExecResult result = backend.exec_shell("echo $AIOS_TEST_VAR", options);
        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("injected-123"),
                () -> "环境变量应被注入，实际: " + result.output());
    }

    @Test
    void execShellEnforcesTimeout() {
        // sleep 5s 但超时设为 1s — 应被强制终止
        ExecOptions options = new ExecOptions(1, null, Map.of(), 0, true);
        ExecResult result = backend.exec_shell("sleep 5", options);
        assertTrue(result.timedOut(), () -> "应触发超时，实际 exitCode=" + result.exitCode());
        assertFalse(result.success());
    }

    @Test
    void execShellDefaultConstructorUsesDefaults() {
        // 不传 options 的便捷重载
        ExecResult result = backend.exec_shell("echo default-options");
        assertTrue(result.success());
        assertTrue(result.output().contains("default-options"));
    }

    @Test
    void execShellTruncatesOutputAtMaxOutputLength() {
        // 生成超长输出，maxOutputLength=100 应截断
        ExecOptions options = new ExecOptions(30, null, Map.of(), 100, true);
        ExecResult result = backend.exec_shell("yes truncated-marker | head -c 5000", options);
        assertTrue(result.output().length() <= 200, // 截断后约 100 + 提示语
                () -> "输出应被截断，实际长度: " + result.output().length());
        assertTrue(result.output().contains("truncated at 100 chars"),
                () -> "应包含截断提示，实际: " + result.output());
    }

    // ════════════════════════════════════════════════════════════════
    //  BackendBase 契约
    // ════════════════════════════════════════════════════════════════

    @Test
    void backendNameIsLocal() {
        assertEquals("local", backend.backendName());
    }

    @Test
    void localBackendIsSingleton() {
        assertSame(LocalBackend.instance(), LocalBackend.instance());
    }
}
