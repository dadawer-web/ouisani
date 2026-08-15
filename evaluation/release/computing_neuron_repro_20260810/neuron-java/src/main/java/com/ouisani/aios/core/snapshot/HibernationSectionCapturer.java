package com.ouisani.aios.core.snapshot;

import com.ouisani.aios.core.hibernation.AgentSnapshot;
import com.ouisani.aios.core.hibernation.HibernationManager;

/**
 * 工作区级快照捕获器 — 包装 {@link HibernationManager} 为 {@link HibernationSection}。
 * <p>
 * 持有 workspaceId,capture 时调 {@link HibernationManager#suspendToDisk(String)}
 * (Semantic Core Hibernation,写 VFS),restore 时调
 * {@link HibernationManager#resumeFromDisk(String)}。
 * <p>
 * <b>副作用提示</b>:{@code suspendToDisk} 会序列化整个工作区状态到 VFS
 * ({@code .aios_snapshot} 文件),属重操作。仅用于真正挂起/恢复场景。
 * <p>
 * <b>测试缝</b>:{@link #hibernationManager()} 为 protected,单测可覆写注入 mock
 * HibernationManager,避免触发真实 VFS 写入。
 */
public class HibernationSectionCapturer implements SnapshotCapturer {

    private final String workspaceId;

    public HibernationSectionCapturer(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    @Override
    public String sectionType() {
        return "Hibernation";
    }

    @Override
    public SnapshotSection capture() {
        AgentSnapshot snap = hibernationManager().suspendToDisk(workspaceId);
        return snap != null ? new HibernationSection(snap) : null;
    }

    @Override
    public void restore(SnapshotSection section) {
        if (!(section instanceof HibernationSection hs)) return;
        hibernationManager().resumeFromDisk(hs.agentSnapshot().workspaceId());
    }

    /** 可覆写:返回 HibernationManager 单例,便于单测注入 mock。 */
    protected HibernationManager hibernationManager() {
        return HibernationManager.instance();
    }
}
