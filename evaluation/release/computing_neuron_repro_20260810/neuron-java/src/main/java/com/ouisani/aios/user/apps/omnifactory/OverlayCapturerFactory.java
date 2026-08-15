package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.snapshot.EnvironmentSnapshot;
import com.ouisani.aios.core.snapshot.OverlayDiffSection;
import com.ouisani.aios.core.snapshot.SnapshotCapturer;
import com.ouisani.aios.core.snapshot.SnapshotCapturerFactory;
import com.ouisani.aios.vfs.OverlayNode;

import java.util.List;
import java.util.Set;

/**
 * Overlay fork 工厂 — 为 fork 分支创建隔离的 overlay 挂载 + diff 捕获器。
 * <p>
 * 借鉴 mobilegym "Final UI = World Data ⊕ Runtime Overlay" 与 docker layer:
 * <ul>
 *   <li>lower = /factory(只读基础镜像,所有分支共享)</li>
 *   <li>upper = /overlays/{branchId}(可写 diff,分支独占)</li>
 *   <li>mount = /fork/{branchId}(分支访问入口,经 overlay.readFile/writeFile)</li>
 * </ul>
 * <p>
 * {@link #createForFork} 激活 dormant {@link VfsManager#createOverlay},
 * 从种子 OverlayDiffSection 回填 diff 到新分支 upper 层,返回绑定该 overlay 的
 * {@link OverlayDiffCapturer}。与 {@link OmnifactoryCapturerFactory} 独立注册,
 * {@code forkFromSnapshot} 自动聚合两者 —— 既有 2-capturer 断言不受影响。
 * <p>
 * <b>并发约束</b>:不同 branchId 的 upper 目录互不冲突,可并发 createForFork;
 * 但 activator 注册到全局 capturer 表仍需串行(同既有约束)。
 */
public class OverlayCapturerFactory implements SnapshotCapturerFactory {

    private static final String LOWER_DIR = "/factory";
    private static final String OVERLAY_ROOT = "/overlays";
    private static final String MOUNT_ROOT = "/fork";

    @Override
    public Set<String> sectionTypes() {
        return Set.of("OverlayDiff");
    }

    @Override
    public List<SnapshotCapturer> createForFork(String branchId, EnvironmentSnapshot seed) {
        VfsManager vfs = VfsManager.instance();
        String mountPath = mountPathFor(branchId);
        String upperDir = OVERLAY_ROOT + "/" + branchId;

        // 激活 dormant createOverlay:挂载 lower=/factory + upper=/overlays/{branchId}
        vfs.createOverlay(mountPath, LOWER_DIR, upperDir);

        // 回填种子 overlay diff:写入新分支 upper 层
        OverlayDiffSection seedDiff = seed.getSection("OverlayDiff", OverlayDiffSection.class).orElse(null);
        if (seedDiff != null) {
            OverlayNode overlay = vfs.getOverlay(mountPath);
            if (overlay != null) {
                seedDiff.files().forEach(overlay::writeFile);
            }
        }

        return List.of(new OverlayDiffCapturer(branchId, mountPath));
    }

    /** fork 分支挂载点路径(分支执行侧经此路径取 overlay)。 */
    public static String mountPathFor(String branchId) {
        return MOUNT_ROOT + "/" + branchId;
    }

    /** 取 fork 分支的 OverlayNode(分支执行侧经此调用 readFile/writeFile)。 */
    public static OverlayNode overlayFor(String branchId) {
        return VfsManager.instance().getOverlay(mountPathFor(branchId));
    }
}
