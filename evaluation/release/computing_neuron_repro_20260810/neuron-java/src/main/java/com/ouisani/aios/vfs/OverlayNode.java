package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.VfsNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VFS OverlayFS 上下文覆盖层 — 借鉴 Docker 镜像层和 Linux OverlayFS。
 * <p>
 * 核心思想：
 * <ul>
 *   <li>底层（lower layer）：核心业务代码，只读，多个 Agent 共享</li>
 *   <li>上层（upper layer）：Agent 运行时的修改和生成，读写，Agent 独占</li>
 *   <li>合并视图：读取时先查上层，未命中再查底层</li>
 * </ul>
 * <p>
 * 这比深拷贝更省内存，且符合 Linux 容器哲学。
 * 多个 Agent 并发时，共享底层内存，各自在 Overlay 层写入，互不干扰。
 *
 * @see VfsManager#createOverlay(String, String)
 */
public non-sealed class OverlayNode implements VfsNode {

    private static final Logger log = LoggerFactory.getLogger(OverlayNode.class);

    private final String path;
    private final String lowerDir;
    private final String upperDir;
    private int ownerUid;
    private int permissions;

    /** Overlay 挂载点 → OverlayNode 实例注册表 */
    private static final ConcurrentHashMap<String, OverlayNode> overlays = new ConcurrentHashMap<>();

    /** 上层已删除的文件集合（whiteout 文件） */
    private final Set<String> whiteouts = ConcurrentHashMap.newKeySet();

    /**
     * 创建 Overlay 挂载点。
     *
     * @param mountPath 挂载路径（如 /containers/agent_1001/workspace）
     * @param lowerDir  底层只读目录（如 /factory/myproject）
     * @param upperDir  上层读写目录（如 /containers/agent_1001/overlay）
     */
    public static OverlayNode create(String mountPath, String lowerDir, String upperDir) {
        OverlayNode node = new OverlayNode(mountPath, lowerDir, upperDir);
        overlays.put(mountPath, node);
        log.info("[OverlayFS] 挂载点已创建: {} (lower={}, upper={})", mountPath, lowerDir, upperDir);
        return node;
    }

    /**
     * 获取指定挂载点的 OverlayNode。
     */
    public static OverlayNode get(String mountPath) {
        return overlays.get(mountPath);
    }

    /**
     * 销毁指定挂载点的 Overlay。
     */
    public static void destroy(String mountPath) {
        overlays.remove(mountPath);
        log.info("[OverlayFS] 挂载点已销毁: {}", mountPath);
    }

    public OverlayNode(String path, String lowerDir, String upperDir) {
        this.path = path;
        this.lowerDir = lowerDir;
        this.upperDir = upperDir;
        this.ownerUid = 0;
        this.permissions = 0755;
    }

    @Override
    public VfsNodeType nodeType() {
        return VfsNodeType.DIRECTORY;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public int ownerUid() {
        return ownerUid;
    }

    @Override
    public void setOwnerUid(int uid) {
        this.ownerUid = uid;
    }

    @Override
    public int permissions() {
        return permissions;
    }

    @Override
    public void setPermissions(int perm) {
        this.permissions = perm;
    }

    /**
     * 读取 Overlay 合并视图。
     * <p>
     * 返回合并后的文件列表，包含：
     * 1. 上层新增的文件
     * 2. 底层未被 whiteout 删除的文件
     */
    @Override
    public String read() {
        VfsManager vfs = VfsManager.instance();
        Set<String> mergedFiles = new TreeSet<>();

        // 收集底层文件
        List<String> lowerFiles = vfs.listFilesUnder(lowerDir);
        for (String f : lowerFiles) {
            String relative = f.substring(lowerDir.length());
            if (!whiteouts.contains(relative)) {
                mergedFiles.add(relative);
            }
        }

        // 收集上层文件（覆盖同名底层文件）
        List<String> upperFiles = vfs.listFilesUnder(upperDir);
        for (String f : upperFiles) {
            String relative = f.substring(upperDir.length());
            mergedFiles.add(relative);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("OverlayFS: ").append(path).append("\n");
        sb.append("  Lower (readonly): ").append(lowerDir).append("\n");
        sb.append("  Upper (read-write): ").append(upperDir).append("\n");
        sb.append("  Merged files:\n");
        for (String f : mergedFiles) {
            boolean isUpper = vfs.exists(upperDir + f);
            sb.append("    ").append(isUpper ? "[U] " : "[L] ").append(f).append("\n");
        }
        return sb.toString();
    }

    @Override
    public boolean write(String data) {
        log.warn("[OverlayFS] OverlayNode 是目录节点，不支持直接写入: {}", path);
        return false;
    }

    /**
     * 通过 Overlay 读取文件内容。
     * <p>
     * 读取顺序：先查上层（upper），未命中再查底层（lower）。
     * 如果文件被 whiteout，返回 null。
     *
     * @param relativePath 相对于挂载点的文件路径
     * @return 文件内容，不存在则返回 null
     */
    public String readFile(String relativePath) {
        // 检查 whiteout
        if (whiteouts.contains(relativePath)) {
            log.debug("[OverlayFS] 文件已被 whiteout: {}", relativePath);
            return null;
        }

        VfsManager vfs = VfsManager.instance();

        // 先查上层
        String upperPath = upperDir + (relativePath.startsWith("/") ? relativePath : "/" + relativePath);
        String content = vfs.readText(upperPath);
        if (content != null) {
            log.debug("[OverlayFS] 从上层读取: {}", relativePath);
            return content;
        }

        // 再查底层
        String lowerPath = lowerDir + (relativePath.startsWith("/") ? relativePath : "/" + relativePath);
        content = vfs.readText(lowerPath);
        if (content != null) {
            log.debug("[OverlayFS] 从底层读取: {}", relativePath);
            return content;
        }

        return null;
    }

    /**
     * 通过 Overlay 写入文件内容。
     * <p>
     * 写入总是发生在上层（upper layer），底层保持只读。
     * 如果文件之前被 whiteout，取消 whiteout。
     *
     * @param relativePath 相对于挂载点的文件路径
     * @param content      要写入的内容
     * @return true 写入成功
     */
    public boolean writeFile(String relativePath, String content) {
        VfsManager vfs = VfsManager.instance();
        String upperPath = upperDir + (relativePath.startsWith("/") ? relativePath : "/" + relativePath);

        // 取消 whiteout
        whiteouts.remove(relativePath);

        boolean ok = vfs.writeText(upperPath, content);
        if (ok) {
            log.debug("[OverlayFS] 写入上层: {}", relativePath);
        }
        return ok;
    }

    /**
     * 通过 Overlay 删除文件。
     * <p>
     * 如果文件在上层，直接删除上层副本。
     * 如果文件在底层，创建 whiteout 标记（底层只读，不能删除）。
     *
     * @param relativePath 相对于挂载点的文件路径
     * @return true 删除成功
     */
    public boolean deleteFile(String relativePath) {
        VfsManager vfs = VfsManager.instance();

        // 删除上层副本
        String upperPath = upperDir + (relativePath.startsWith("/") ? relativePath : "/" + relativePath);
        if (vfs.exists(upperPath)) {
            // 通过写入空内容模拟删除（VFS 没有显式删除 API）
            vfs.writeText(upperPath, "");
        }

        // 如果底层也有此文件，创建 whiteout
        String lowerPath = lowerDir + (relativePath.startsWith("/") ? relativePath : "/" + relativePath);
        if (vfs.exists(lowerPath)) {
            whiteouts.add(relativePath);
            log.info("[OverlayFS] 创建 whiteout: {}", relativePath);
        }

        return true;
    }

    /**
     * 列出 Overlay 合并视图中的所有文件。
     *
     * @return 合并后的相对路径列表
     */
    public List<String> listMergedFiles() {
        VfsManager vfs = VfsManager.instance();
        Set<String> merged = new TreeSet<>();

        // 底层文件（排除 whiteout）
        for (String f : vfs.listFilesUnder(lowerDir)) {
            String relative = f.substring(lowerDir.length());
            if (!whiteouts.contains(relative)) {
                merged.add(relative);
            }
        }

        // 上层文件
        for (String f : vfs.listFilesUnder(upperDir)) {
            String relative = f.substring(upperDir.length());
            merged.add(relative);
        }

        return new ArrayList<>(merged);
    }

    /**
     * 获取底层目录路径。
     */
    public String getLowerDir() {
        return lowerDir;
    }

    /**
     * 获取上层目录路径。
     */
    public String getUpperDir() {
        return upperDir;
    }
}
