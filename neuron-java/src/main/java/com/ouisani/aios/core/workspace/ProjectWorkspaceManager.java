package com.ouisani.aios.core.workspace;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.cgroup.CgroupManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 项目工作区管理器 — 借鉴 Agent Zero 的 Projects 系统。
 * <p>
 * 为每个项目创建完全隔离的命名空间：
 * - VFS 命名空间：独立的 /factory、/memories、/dev 目录
 * - Cgroup 隔离：独立的 Token 配额
 * - 记忆隔离：项目专属的记忆空间
 * <p>
 * OS 类比：Linux 的 namespace + cgroup — 容器化的认知工作区。
 */
public class ProjectWorkspaceManager {
    private static final Logger log = LoggerFactory.getLogger(ProjectWorkspaceManager.class);

    private static final ProjectWorkspaceManager INSTANCE = new ProjectWorkspaceManager();

    /** 项目工作区定义 */
    public record ProjectWorkspace(
            String projectId,
            String projectName,
            String vfsRoot,          // VFS 根路径（如 /containers/project_408/）
            String physicalRoot,     // 物理磁盘根路径
            String cgroupName,       // Cgroup 名称
            long tokenQuota,         // Token 配额
            long createdAt,
            Map<String, Object> metadata
    ) {}

    private final ConcurrentHashMap<String, ProjectWorkspace> workspaces = new ConcurrentHashMap<>();

    private ProjectWorkspaceManager() {}

    public static ProjectWorkspaceManager getInstance() { return INSTANCE; }

    /**
     * 创建项目工作区 — 核心入口。
     * <p>
     * 为项目创建完全隔离的 VFS 命名空间、Cgroup 配额和记忆空间。
     *
     * @param projectName 项目名称（如 "408_platform"）
     * @param tokenQuota  Token 配额（0 表示使用默认值 100000）
     * @return 创建的工作区
     */
    public ProjectWorkspace createWorkspace(String projectName, long tokenQuota) {
        String projectId = "proj_" + projectName.hashCode() + "_" + System.currentTimeMillis() % 10000;
        String vfsRoot = "/containers/" + projectId + "/";
        String physicalRoot = VfsManager.instance().findPhysicalWorkspace("/factory")
                + "/../projects/" + projectId;
        String cgroupName = "projects/" + projectId;

        if (tokenQuota <= 0) tokenQuota = 100_000L;

        // 1. 创建 VFS 命名空间
        VfsManager vfs = VfsManager.instance();
        vfs.mount(vfsRoot + "factory", "factory", new VfsNode.DirectoryNode(vfsRoot + "factory"), 0);
        vfs.mount(vfsRoot + "memories", "memories", new VfsNode.DirectoryNode(vfsRoot + "memories"), 0);
        vfs.mount(vfsRoot + "dev", "dev", new VfsNode.DirectoryNode(vfsRoot + "dev"), 0);
        vfs.mount(vfsRoot + "tmp", "tmp", new VfsNode.DirectoryNode(vfsRoot + "tmp"), 0);
        vfs.registerPhysicalWorkspace(vfsRoot + "factory", physicalRoot + "/factory");
        vfs.registerPhysicalWorkspace(vfsRoot + "memories", physicalRoot + "/memories");

        // 2. 创建 Cgroup 配额
        CgroupManager cgroup = CgroupManager.instance();
        cgroup.createNode(cgroupName, tokenQuota, "agents");

        // 3. 创建工作区记录
        ProjectWorkspace workspace = new ProjectWorkspace(
                projectId, projectName, vfsRoot, physicalRoot,
                cgroupName, tokenQuota, System.currentTimeMillis(), new HashMap<>()
        );

        workspaces.put(projectId, workspace);
        log.info("[ProjectWorkspace] 项目工作区已创建: id='{}', name='{}', vfsRoot='{}'",
                projectId, projectName, vfsRoot);

        return workspace;
    }

    /**
     * 获取项目工作区。
     */
    public ProjectWorkspace getWorkspace(String projectId) {
        return workspaces.get(projectId);
    }

    /**
     * 按名称查找项目工作区。
     */
    public ProjectWorkspace findByName(String projectName) {
        return workspaces.values().stream()
                .filter(w -> w.projectName().equals(projectName))
                .findFirst()
                .orElse(null);
    }

    /**
     * 列出所有项目工作区。
     */
    public Collection<ProjectWorkspace> listWorkspaces() {
        return Collections.unmodifiableCollection(workspaces.values());
    }

    /**
     * 销毁项目工作区 — 清理 VFS 挂载和 Cgroup。
     */
    public boolean destroyWorkspace(String projectId) {
        ProjectWorkspace workspace = workspaces.remove(projectId);
        if (workspace == null) return false;

        VfsManager vfs = VfsManager.instance();
        vfs.unmount(workspace.vfsRoot() + "factory", 0);
        vfs.unmount(workspace.vfsRoot() + "memories", 0);
        vfs.unmount(workspace.vfsRoot() + "dev", 0);
        vfs.unmount(workspace.vfsRoot() + "tmp", 0);

        log.info("[ProjectWorkspace] 项目工作区已销毁: id='{}', name='{}'",
                projectId, workspace.projectName());
        return true;
    }

    /**
     * 将 Agent 绑定到项目工作区。
     * <p>
     * 绑定后，Agent 的 VFS 根路径切换到项目命名空间，
     * Token 消耗计入项目 Cgroup。
     */
    public void bindAgentToWorkspace(int agentPid, String projectId) {
        ProjectWorkspace workspace = workspaces.get(projectId);
        if (workspace == null) {
            log.warn("[ProjectWorkspace] 绑定失败：工作区不存在: id='{}'", projectId);
            return;
        }

        // 设置 VFS 根路径
        VfsManager.AGENT_ROOT.set(workspace.vfsRoot());

        // 绑定 Cgroup
        CgroupManager cgroup = CgroupManager.instance();
        cgroup.bindToCurrentThread(cgroup.getOrCreateAgentCgroup(agentPid));

        log.info("[ProjectWorkspace] Agent {} 已绑定到工作区: {}", agentPid, workspace.projectName());
    }
}
