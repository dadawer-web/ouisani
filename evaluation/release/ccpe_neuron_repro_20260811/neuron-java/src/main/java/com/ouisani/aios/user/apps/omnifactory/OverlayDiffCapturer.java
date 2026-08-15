package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.snapshot.OverlayDiffSection;
import com.ouisani.aios.core.snapshot.SnapshotCapturer;
import com.ouisani.aios.core.snapshot.SnapshotSection;
import com.ouisani.aios.vfs.OverlayNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Overlay diff 捕获器 — 镜像 fork 分支 overlay 上层写入差异为 {@link OverlayDiffSection}。
 * <p>
 * capture:遍历 upper 目录文件(排除 .keep),读内容,组装 relPath → content 映射。
 * restore:把 section 中每个文件经 {@link OverlayNode#writeFile} 写回 upper 层。
 * <p>
 * <b>不全局注册</b>(同 {@link CarryoverCapturer} 的并发说明):fork 分支经
 * {@link com.ouisani.aios.core.snapshot.ForkHandle#activate} 临时注册到全局表。
 */
public class OverlayDiffCapturer implements SnapshotCapturer {

    private final String branchId;
    private final String mountPath;

    public OverlayDiffCapturer(String branchId, String mountPath) {
        this.branchId = branchId;
        this.mountPath = mountPath;
    }

    @Override
    public String sectionType() {
        return "OverlayDiff";
    }

    @Override
    public SnapshotSection capture() {
        VfsManager vfs = VfsManager.instance();
        OverlayNode overlay = vfs.getOverlay(mountPath);
        if (overlay == null) {
            return new OverlayDiffSection(Map.of());
        }
        String upperDir = overlay.getUpperDir();
        Map<String, String> files = new LinkedHashMap<>();
        for (String absPath : vfs.listFilesUnder(upperDir)) {
            String rel = absPath.substring(upperDir.length());
            if (rel.equals("/.keep") || rel.equals(".keep")) continue;
            String content = vfs.readText(absPath);
            if (content != null) {
                files.put(rel, content);
            }
        }
        return new OverlayDiffSection(Map.copyOf(files));
    }

    @Override
    public void restore(SnapshotSection section) {
        if (!(section instanceof OverlayDiffSection ods)) return;
        VfsManager vfs = VfsManager.instance();
        OverlayNode overlay = vfs.getOverlay(mountPath);
        if (overlay == null) return;
        ods.files().forEach(overlay::writeFile);
    }

    public String branchId() { return branchId; }
    public String mountPath() { return mountPath; }
}
