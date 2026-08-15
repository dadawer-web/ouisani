package com.ouisani.aios.user.sdk;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.action.ActionGovernor;
import com.ouisani.aios.core.action.ActionRecord;
import com.ouisani.aios.core.action.RiskLevel;
import com.ouisani.aios.core.security.ImpersonationContext;
import com.ouisani.aios.core.security.SecurityToken;
import com.ouisani.aios.core.snapshot.EnvironmentSnapshotManager;
import com.ouisani.aios.core.syscall.SyscallDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1 接入集成测试 — 验证 AiosSdk.storageWriteGoverned 真正调用 ActionGovernor。
 * <p>
 * <b>背景</b>：补强前 ActionGovernor 建好但 0 生产调用者。补强后
 * {@link AiosSdk#storageWriteGoverned} 作为 opt-in 治理路径接入点，
 * 让调用方可选地获得 before/after 快照 + diff + undo 能力。
 * <p>
 * <b>测试隔离</b>：每个测试方法用独立 agentId（基于 nanoTime），
 * 避免与 {@code ActionGovernorTest}（用 "agent-1"）及其它测试互相污染 undo 栈。
 * <p>
 * <b>安全令牌</b>：production 中 SecurityToken 由 AgentTask 绑定（见
 * {@code SecurityToken#forAgent}），单元测试无 AgentTask 上下文，需通过
 * {@link ImpersonationContext#impersonate} 注入内核级令牌，否则
 * {@code BpfManager.PRIVILEGE_ESCALATION} 规则会以 "No SecurityToken" 拦截写操作。
 * <p>
 * <b>OS 类比</b>：相当于 Linux 的 {@code open(O_SYNC)} —— 显式 opt-in 同步写路径，
 * 默认 {@code open(0)} 仍是异步快路径。
 */
class AiosSdkGovernedWriteIntegrationTest {

    private AiosSdk sdk;
    private ActionGovernor governor;
    private String agentId;
    private AutoCloseable impersonation;
    private final List<String> snapshotsToCleanup = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // 初始化 VFS + 配置 SyscallDispatcher（让 storage.write 真正落到 VFS）
        VfsManager.instance().init();
        SyscallDispatcher.getInstance().configure(null, VfsManager.instance(), null);

        // 注入内核级 SecurityToken —— production 中由 AgentTask.primaryToken() 提供，
        // 单元测试无任务上下文，需显式注入否则 BpfManager 拦截写操作
        impersonation = ImpersonationContext.impersonate(SecurityToken.kernelToken("test-kernel"));

        sdk = AiosSdk.getInstance();
        governor = ActionGovernor.getInstance();
        // 每个测试方法用独立 agentId — 无需 clearAll（package-private）也能隔离
        agentId = "governed-test-" + System.nanoTime();
    }

    @AfterEach
    void tearDown() {
        if (impersonation != null) {
            try { impersonation.close(); } catch (Exception ignored) {}
        }
        // 清理本测试产生的快照文件（避免 ~/.aios/env_snapshots/ 累积）
        for (String snapId : snapshotsToCleanup) {
            try {
                EnvironmentSnapshotManager.instance().deleteSnapshot(snapId);
            } catch (Exception ignored) {}
        }
        snapshotsToCleanup.clear();
    }

    @Test
    @DisplayName("storageWriteGoverned — 调用后返回非 null ActionRecord")
    void storageWriteGoverned_returnsActionRecord() {
        ActionRecord record = sdk.storageWriteGoverned(agentId,
                "/test/governed-write-1.txt", "hello governed");

        assertNotNull(record, "应返回 ActionRecord");
        assertNotNull(record.requestId());
        assertTrue(record.requestId().startsWith("act-"));
        if (record.snapshotId() != null && !record.snapshotId().isEmpty()) {
            snapshotsToCleanup.add(record.snapshotId());
        }
    }

    @Test
    @DisplayName("storageWriteGoverned — ActionRecord.riskLevel 为 REVERSIBLE")
    void storageWriteGoverned_riskIsReversible() {
        ActionRecord record = sdk.storageWriteGoverned(agentId,
                "/test/governed-write-2.txt", "data");

        assertNotNull(record);
        assertEquals(RiskLevel.REVERSIBLE, record.riskLevel(),
                "storage.write 应被 RiskClassifier 判定为 REVERSIBLE");
        if (record.snapshotId() != null && !record.snapshotId().isEmpty()) {
            snapshotsToCleanup.add(record.snapshotId());
        }
    }

    @Test
    @DisplayName("storageWriteGoverned — 触发 before/after 快照（snapshotId 非空）")
    void storageWriteGoverned_capturesSnapshots() {
        ActionRecord record = sdk.storageWriteGoverned(agentId,
                "/test/governed-write-3.txt", "snapshot test");

        assertNotNull(record);
        assertNotNull(record.snapshotId(), "REVERSIBLE 动作应捕获 before 快照");
        assertFalse(record.snapshotId().isEmpty());
        snapshotsToCleanup.add(record.snapshotId());

        // 验证快照确实在 EnvironmentSnapshotManager 中可加载
        assertTrue(EnvironmentSnapshotManager.instance().load(record.snapshotId()).isPresent(),
                "快照应可在 EnvironmentSnapshotManager 中加载: " + record.snapshotId());
    }

    @Test
    @DisplayName("storageWriteGoverned — 数据真正写入 VFS")
    void storageWriteGoverned_dataPersistedToVfs() {
        String path = "/test/governed-write-4.txt";
        String data = "governed-payload-" + System.nanoTime();

        ActionRecord record = sdk.storageWriteGoverned(agentId, path, data);

        assertNotNull(record);
        if (record.snapshotId() != null && !record.snapshotId().isEmpty()) {
            snapshotsToCleanup.add(record.snapshotId());
        }
        // 验证数据写入
        String readBack = sdk.readFile(agentId, path);
        assertEquals(data, readBack,
                "governed 写入的数据应可通过 read 读回");
    }

    @Test
    @DisplayName("storageWriteGoverned — 入 undo 栈，lastAction 可查")
    void storageWriteGoverned_pushedToUndoStack() {
        assertEquals(0, governor.history(agentId).size(),
                "新 agentId 的 undo 栈应为空");

        ActionRecord record = sdk.storageWriteGoverned(agentId,
                "/test/governed-write-5.txt", "stack test");

        assertEquals(1, governor.history(agentId).size(),
                "governed 写入后 undo 栈应有 1 条");
        assertSame(record, governor.lastAction(agentId),
                "lastAction 应返回本次 record");
        if (record.snapshotId() != null && !record.snapshotId().isEmpty()) {
            snapshotsToCleanup.add(record.snapshotId());
        }
    }

    @Test
    @DisplayName("storageWriteGoverned — 多次调用累积 undo 栈")
    void storageWriteGoverned_multipleCallsAccumulateStack() {
        sdk.storageWriteGoverned(agentId, "/test/governed-write-6a.txt", "a");
        sdk.storageWriteGoverned(agentId, "/test/governed-write-6b.txt", "b");
        sdk.storageWriteGoverned(agentId, "/test/governed-write-6c.txt", "c");

        List<ActionRecord> history = governor.history(agentId);
        assertEquals(3, history.size(), "3 次 governed 写入应入栈 3 条");

        // 收集所有 snapshotId 用于清理
        for (ActionRecord r : history) {
            if (r.snapshotId() != null && !r.snapshotId().isEmpty()) {
                snapshotsToCleanup.add(r.snapshotId());
            }
        }
    }

    @Test
    @DisplayName("对照：storageWrite（裸调用）不入 undo 栈")
    void storageWrite_rawDoesNotEnterGovernorStack() {
        assertEquals(0, governor.history(agentId).size());

        sdk.storageWrite(agentId, "/test/raw-write.txt", "raw");

        assertEquals(0, governor.history(agentId).size(),
                "裸 storageWrite 不应触发 governance，undo 栈应仍为空");
        assertNull(governor.lastAction(agentId),
                "裸调用后 lastAction 应为 null");
    }
}
