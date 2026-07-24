package com.ouisani.aios.core.snapshot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EnvironmentSnapshotManager 单元测试 — 验证 capture→persist→load→restore→delete 循环。
 * <p>
 * 用 Mockito mock 一个 SnapshotCapturer(sectionType="NodeOutput"),避免触碰
 * 真实管理器(Boulder/Hibernation/Process)的副作用。EnvironmentSnapshotManager
 * 持久化到 ~/.aios/env_snapshots/ (静态 final 路径),测试用唯一 scopeId +
 * deleteSnapshot 清理磁盘。
 */
class EnvironmentSnapshotManagerTest {

    private EnvironmentSnapshotManager manager;
    private SnapshotCapturer capturer;

    @BeforeEach
    void setUp() {
        manager = EnvironmentSnapshotManager.instance();
        capturer = mock(SnapshotCapturer.class);
        when(capturer.sectionType()).thenReturn("NodeOutput");
        manager.registerCapturer(capturer);
    }

    @AfterEach
    void tearDown() {
        manager.unregisterCapturer("NodeOutput");
    }

    private NodeOutputSection sampleSection() {
        Map<String, Map<String, Object>> outputs = new LinkedHashMap<>();
        Map<String, Object> node1 = new LinkedHashMap<>();
        node1.put("result", "hello");
        outputs.put("node-1", node1);
        return new NodeOutputSection(outputs);
    }

    @Test
    void capture_invokesRegisteredCapturer_andStoresSection() {
        when(capturer.capture()).thenReturn(sampleSection());

        EnvironmentSnapshot snap = manager.capture("test-scope-capture");

        assertEquals("test-scope-capture", snap.scopeId());
        assertTrue(snap.sections().containsKey("NodeOutput"),
                "capture 后 sections 应含 NodeOutput");
        NodeOutputSection sec = snap.getSection("NodeOutput", NodeOutputSection.class).orElseThrow();
        assertEquals("hello", sec.nodeOutputs().get("node-1").get("result"));
        manager.deleteSnapshot(snap.snapshotId());
    }

    @Test
    void restore_delegatesToCapturerForEachSection() {
        when(capturer.capture()).thenReturn(sampleSection());
        EnvironmentSnapshot snap = manager.capture("test-scope-restore");

        manager.restore(snap);

        verify(capturer, times(1)).restore(any(SnapshotSection.class));
        manager.deleteSnapshot(snap.snapshotId());
    }

    @Test
    void persist_thenLoad_roundTripsFromDisk() throws Exception {
        when(capturer.capture()).thenReturn(sampleSection());
        EnvironmentSnapshot snap = manager.capture("test-scope-persist");
        manager.persist(snap);

        Path file = Paths.get(System.getProperty("user.home"),
                ".aios", "env_snapshots", snap.snapshotId() + ".envsnap");
        assertTrue(Files.exists(file), "快照文件应已持久化到磁盘");

        // 清空内存索引,强制从磁盘 load(模拟重启场景)
        clearInMemoryStore();

        Optional<EnvironmentSnapshot> loaded = manager.load(snap.snapshotId());
        assertTrue(loaded.isPresent(), "清空内存后应从磁盘加载");
        assertEquals(snap.snapshotId(), loaded.get().snapshotId());
        assertTrue(loaded.get().sections().containsKey("NodeOutput"));
        NodeOutputSection sec = loaded.get()
                .getSection("NodeOutput", NodeOutputSection.class).orElseThrow();
        assertEquals("hello", sec.nodeOutputs().get("node-1").get("result"));

        assertTrue(manager.deleteSnapshot(snap.snapshotId()));
        assertFalse(Files.exists(file), "清理后磁盘文件应删除");
    }

    @Test
    void load_unknownSnapshotId_returnsEmpty() {
        Optional<EnvironmentSnapshot> loaded = manager.load("env-does-not-exist-12345");
        assertTrue(loaded.isEmpty());
    }

    @Test
    void restore_nullSnapshot_isNoop() {
        manager.restore(null); // 不应抛异常,不应委托给 capturer
        verify(capturer, never()).restore(any(SnapshotSection.class));
    }

    @Test
    void deleteSnapshot_removesFromStoreAndDisk() {
        when(capturer.capture()).thenReturn(sampleSection());
        EnvironmentSnapshot snap = manager.capture("test-scope-delete");
        manager.persist(snap);

        assertTrue(manager.deleteSnapshot(snap.snapshotId()));
        assertFalse(manager.listSnapshots().contains(snap.snapshotId()));
    }

    @SuppressWarnings("unchecked")
    private void clearInMemoryStore() throws Exception {
        Field storeField = EnvironmentSnapshotManager.class.getDeclaredField("store");
        storeField.setAccessible(true);
        Map<String, EnvironmentSnapshot> store =
                (Map<String, EnvironmentSnapshot>) storeField.get(manager);
        store.clear();
    }
}
