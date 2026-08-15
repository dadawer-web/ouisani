package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.tool.DataTypes;
import com.ouisani.aios.core.tool.Port;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 索引节点 — 渐进式披露 (Progressive Disclosure) 的核心实现。
 * <p>
 * 借鉴 Google Knowledge Catalog 的 OKF (Open Knowledge Format) 标准：
 * 每个目录下自动生成一个隐藏的 index.md，包含该目录下所有文件的语义摘要。
 * <p>
 * 当 Agent 访问某个陌生目录时，FileReadTool 优先返回 index.md，
 * Agent 像人类看地图一样，先看摘要，发现需要改密码，再精准去读 config.xml。
 * <p>
 * 这让 VFS 不再是冰冷的文件树，而是一棵自带导航的知识图谱树。
 *
 * @see VfsManager#indexDirectory(String)
 */
public non-sealed class IndexNode implements VfsNode {

    private static final Logger log = LoggerFactory.getLogger(IndexNode.class);

    private final String path;
    private final String dirPath;
    private volatile String cachedContent;
    private volatile long lastGenerated;
    private int ownerUid;
    private int permissions;

    /** 目录路径 → IndexNode 的全局注册表 */
    private static final ConcurrentHashMap<String, IndexNode> registry = new ConcurrentHashMap<>();

    /**
     * 获取或创建指定目录的 IndexNode。
     *
     * @param dirPath 目录路径（如 /factory/myproject）
     * @return IndexNode 实例
     */
    public static IndexNode getOrCreate(String dirPath) {
        String indexPath = dirPath.endsWith("/") ? dirPath + "index.md" : dirPath + "/index.md";
        return registry.computeIfAbsent(dirPath, k -> {
            log.info("[IndexNode] 为目录创建索引节点: {}", dirPath);
            return new IndexNode(indexPath, dirPath);
        });
    }

    public IndexNode(String path, String dirPath) {
        this.path = path;
        this.dirPath = dirPath;
        this.ownerUid = 0;
        this.permissions = 0644;
        this.cachedContent = "";
        this.lastGenerated = 0;
    }

    @Override
    public VfsNodeType nodeType() {
        return VfsNodeType.FILE;
    }

    // ── 强类型 I/O 契约 ──
    // IndexNode 是只读节点，write() 始终返回 false，索引由 regenerate() 自动生成
    @Override
    public List<Port> inputPorts() {
        return List.of(); // 只读节点，无 write 入口
    }

    @Override
    public List<Port> outputPorts() {
        return List.of(new Port("index", DataTypes.MARKDOWN_TEXT,
                "目录索引 Markdown（read 出口，含文件摘要表格）", true));
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
     * 读取索引内容。如果索引过期或未生成，自动重新生成。
     */
    @Override
    public String read() {
        if (cachedContent == null || cachedContent.isEmpty() || isStale()) {
            regenerate();
        }
        return cachedContent;
    }

    @Override
    public boolean write(String data) {
        // 索引节点不允许直接写入，只能通过 regenerate() 自动生成
        log.warn("[IndexNode] 索引节点不支持直接写入，请使用 regenerate(): {}", path);
        return false;
    }

    /**
     * 检查索引是否过期（超过 60 秒视为过期）。
     */
    public boolean isStale() {
        return System.currentTimeMillis() - lastGenerated > 60_000;
    }

    /**
     * 重新生成索引内容。
     * <p>
     * 扫描目录下所有文件节点，为每个文件生成一行语义摘要。
     * 摘要格式：`filename: 内容前 80 字符的摘要`
     */
    public synchronized void regenerate() {
        VfsManager vfs = VfsManager.instance();
        List<String> files = vfs.listFilesUnder(dirPath);

        StringBuilder sb = new StringBuilder();
        sb.append("# 目录索引: ").append(dirPath).append("\n\n");
        sb.append("> 这是自动生成的渐进式披露索引。Agent 应先阅读此索引，再按需深入读取具体文件。\n\n");

        if (files.isEmpty()) {
            sb.append("（目录为空）\n");
        } else {
            sb.append("| 文件 | 摘要 |\n");
            sb.append("|------|------|\n");
            for (String filePath : files) {
                // 跳过 index.md 自身
                if (filePath.endsWith("/index.md") || filePath.equals("index.md")) continue;

                String filename = filePath.substring(filePath.lastIndexOf('/') + 1);
                String summary = generateFileSummary(filePath, vfs);
                sb.append("| `").append(filename).append("` | ").append(summary).append(" |\n");
            }
        }

        sb.append("\n---\n");
        sb.append("*由 AIOS IndexNode 自动生成 | ").append(new Date()).append("*\n");

        cachedContent = sb.toString();
        lastGenerated = System.currentTimeMillis();
        log.info("[IndexNode] 索引已重新生成: {} ({} 个文件)", dirPath, files.size());
    }

    /**
     * 为单个文件生成语义摘要。
     * <p>
     * 策略：
     * 1. 读取文件内容前 200 字符
     * 2. 尝试提取第一行注释或标题
     * 3. 如果内容为空，返回 "(empty)"
     */
    private String generateFileSummary(String filePath, VfsManager vfs) {
        String content = vfs.readText(filePath);
        if (content == null || content.isEmpty()) {
            return "(empty)";
        }

        // 截取前 200 字符
        String preview = content.length() > 200 ? content.substring(0, 200) + "..." : content;

        // 尝试提取第一行有意义的注释或标题
        String[] lines = content.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            // 跳过 package/import/using 等声明行
            if (trimmed.startsWith("package ") || trimmed.startsWith("import ")
                    || trimmed.startsWith("using ") || trimmed.startsWith("#!")) {
                continue;
            }
            // 提取注释行作为摘要
            if (trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("/*")
                    || trimmed.startsWith("*") || trimmed.startsWith("<!--")) {
                String comment = trimmed.replaceAll("^[#/\\*<!\\-]+\\s*", "").trim();
                if (!comment.isEmpty() && comment.length() < 120) {
                    return comment;
                }
            }
            // 提取 Markdown 标题
            if (trimmed.startsWith("#")) {
                return trimmed.replaceAll("^#+\\s*", "");
            }
            // 提取类/函数声明
            if (trimmed.contains("class ") || trimmed.contains("def ") || trimmed.contains("func ")
                    || trimmed.contains("function ") || trimmed.contains("public ")
                    || trimmed.contains("private ")) {
                return trimmed.length() > 80 ? trimmed.substring(0, 80) + "..." : trimmed;
            }
            // 默认取第一行非空内容
            return trimmed.length() > 80 ? trimmed.substring(0, 80) + "..." : trimmed;
        }

        return preview.length() > 80 ? preview.substring(0, 80) + "..." : preview;
    }

    /**
     * 获取最后生成时间戳。
     */
    public long getLastGenerated() {
        return lastGenerated;
    }

    /**
     * 获取目录路径。
     */
    public String getDirPath() {
        return dirPath;
    }
}
