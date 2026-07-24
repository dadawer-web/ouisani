package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.snapshot.OverlayDiffSection;
import com.ouisani.aios.vfs.OverlayNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * OverlayDiffCapturer 单元测试 — 验证 overlay 读写语义、diff 捕获、分支隔离与 restore 回放。
 * <p>
 * 借鉴 mobilegym "Final UI = World Data ⊕ Runtime Overlay":/factory 只读基础镜像,
 * /overlays/{branchId} 可写 diff,/fork/{branchId} 挂载点经 overlay.readFile/writeFile 访问。
 */
class OverlayDiffCapturerTest {

    private VfsManager vfs;

    @BeforeEach
    void setup() {
        vfs = VfsManager.instance();
        vfs.init();
        // 植入 /factory 基础镜像(world data,只读)
        vfs.writeText("/factory/.keep", "");
        vfs.writeText("/factory/base.txt", "base-content");
    }

    @AfterEach
    void teardown() {
        // 清理本测试创建的 overlay 挂载点
        for (String branchId : new String[]{"b1", "b2", "test-branch"}) {
            String mount = "/fork/" + branchId;
            if (vfs.getOverlay(mount) != null) {
                OverlayNode.destroy(mount);
            }
        }
    }

    @Test
    void readFile_fallsBackToLowerWhenUpperMisses() {
        vfs.createOverlay("/fork/b1", "/factory", "/overlays/b1");
        OverlayNode overlay = vfs.getOverlay("/fork/b1");
        assertEquals("base-content", overlay.readFile("base.txt"));
    }

    @Test
    void writeFile_shadowsLowerThenCaptureDiff() {
        vfs.writeText("/factory/shared.txt", "lower");
        vfs.createOverlay("/fork/b1", "/factory", "/overlays/b1");
        OverlayNode overlay = vfs.getOverlay("/fork/b1");
        overlay.writeFile("shared.txt", "override");

        OverlayDiffCapturer capturer = new OverlayDiffCapturer("b1", "/fork/b1");
        OverlayDiffSection diff = (OverlayDiffSection) capturer.capture();

        assertEquals("override", diff.files().get("/shared.txt"));
        // diff 不含未修改的 base.txt
        assertNull(diff.files().get("/base.txt"));
    }

    @Test
    void forkBranchesAreFilesystemIsolated() {
        vfs.createOverlay("/fork/b1", "/factory", "/overlays/b1");
        vfs.createOverlay("/fork/b2", "/factory", "/overlays/b2");
        OverlayNode o1 = vfs.getOverlay("/fork/b1");
        OverlayNode o2 = vfs.getOverlay("/fork/b2");

        o1.writeFile("branch.txt", "b1");
        o2.writeFile("branch.txt", "b2");

        assertEquals("b1", o1.readFile("branch.txt"));
        assertEquals("b2", o2.readFile("branch.txt"));
        // /factory 无 branch.txt(隔离)
        assertNull(vfs.readText("/factory/branch.txt"));
    }

    @Test
    void restore_replaysDiffIntoOverlay() {
        vfs.createOverlay("/fork/b1", "/factory", "/overlays/b1");
        OverlayDiffCapturer capturer = new OverlayDiffCapturer("b1", "/fork/b1");
        capturer.restore(new OverlayDiffSection(Map.of("/replayed.txt", "restored")));

        OverlayNode overlay = vfs.getOverlay("/fork/b1");
        assertEquals("restored", overlay.readFile("/replayed.txt"));
    }
}
