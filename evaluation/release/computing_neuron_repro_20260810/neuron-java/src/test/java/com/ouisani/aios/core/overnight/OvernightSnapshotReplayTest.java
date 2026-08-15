package com.ouisani.aios.core.overnight;

import com.ouisani.aios.core.snapshot.EnvironmentSnapshot;
import com.ouisani.aios.core.snapshot.EnvironmentSnapshotManager;
import com.ouisani.aios.core.snapshot.ForkHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OvernightRunner fork 复现能力单元测试 — 验证 reproduceWithFork 委托与
 * snapshotIdForCard 关联查询。借鉴 mobilegym "出错前一刻快照 + fork 复现"。
 * <p>
 * 通过反射操作单例私有字段 preTaskSnapshotByCardId(与 HibernationManagerTaskQueueTest
 * 反射 captureTaskQueue() 同模式),避免触发完整 coordinator 循环。
 */
class OvernightSnapshotReplayTest {

    private final OvernightRunner runner = OvernightRunner.instance();
    private String capturedSnapshotId; // cleanup 用

    @AfterEach
    void cleanup() throws Exception {
        // 清理 sidecar map,避免单例共享状态污染其它测试
        Field f = OvernightRunner.class.getDeclaredField("preTaskSnapshotByCardId");
        f.setAccessible(true);
        ((Map<String, String>) f.get(runner)).clear();
        // 清理本测试捕获的快照
        if (capturedSnapshotId != null) {
            EnvironmentSnapshotManager.instance().deleteSnapshot(capturedSnapshotId);
            capturedSnapshotId = null;
        }
    }

    @Test
    void reproduceWithFork_unknownSnapshot_throws() {
        assertThrows(IllegalStateException.class,
                () -> runner.reproduceWithFork("nonexistent-snapshot-id", 2));
    }

    @Test
    void reproduceWithFork_delegatesToManager() {
        // 无 capturer 注册时 capture 返回空 sections 快照,fork 仍可工作
        EnvironmentSnapshot snap = EnvironmentSnapshotManager.instance().capture("test-replay-scope");
        capturedSnapshotId = snap.snapshotId();

        List<ForkHandle> handles = runner.reproduceWithFork(snap.snapshotId(), 3);

        assertEquals(3, handles.size());
        for (ForkHandle h : handles) {
            assertEquals(snap.snapshotId(), h.seedSnapshotId());
            assertNotNull(h.branchId());
        }
    }

    @Test
    void snapshotIdForCard_returnsNullForUnknownCardId() {
        assertNull(runner.snapshotIdForCard("unknown-card-id"));
    }

    @Test
    void snapshotIdForCard_returnsAssociatedId() throws Exception {
        // 反射注入关联(模拟 FAILED 卡片已写入 sidecar)
        Field f = OvernightRunner.class.getDeclaredField("preTaskSnapshotByCardId");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, String> map =
                (ConcurrentHashMap<String, String>) f.get(runner);
        map.put("card-failed-1", "snap-turn-42");

        assertEquals("snap-turn-42", runner.snapshotIdForCard("card-failed-1"));
    }
}
