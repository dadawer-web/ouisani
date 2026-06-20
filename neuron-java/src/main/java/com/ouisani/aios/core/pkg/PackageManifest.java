package com.ouisani.aios.core.pkg;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 包清单 — AIOS 软件源中一个包的完整元数据。
 * <p>
 * 类比 Debian 的 DEBIAN/control 文件或 Docker Hub 的 Image Manifest，
 * PackageManifest 描述了一个 AIOS 软件包的所有信息，包括：
 * <ul>
 *   <li>包名、版本、描述</li>
 *   <li>包类型（插件 or Agent 镜像）</li>
 *   <li>依赖列表</li>
 *   <li>Agentfile 内容（Agent 镜像专用）</li>
 *   <li>知识库文档（安装时自动向量化）</li>
 *   <li>工具 Schema（插件专用）</li>
 * </ul>
 *
 * @see AiosApt
 */
public record PackageManifest(

        /** 包名（如 "github-mcp-plugin", "senior-java-coder-agent"） */
        String name,

        /** 版本号（语义化版本，如 "1.2.3"） */
        String version,

        /** 简短描述 */
        String description,

        /** 作者 */
        String author,

        /** 包类型 */
        PackageType type,

        /** 依赖的其他包名列表 */
        List<String> depends,

        /** Agentfile 内容（仅 Agent 镜像类型） */
        String agentfile,

        /** 知识库文档内容（README.md 等，安装时自动向量化） */
        String knowledgeDoc,

        /** 工具 Schema JSON（仅插件类型，描述工具的输入输出格式） */
        String toolSchema,

        /** 插件字节码的 Base64 编码（仅 WASM 插件类型） */
        String pluginBytecodeBase64,

        /** 额外元数据 */
        Map<String, String> metadata

) implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 包类型枚举 — 决定安装时的处理路径。
     */
    public enum PackageType {
        /** 插件 — 通过 PluginManager 注册为可调用工具 */
        PLUGIN,
        /** Agent 镜像 — 通过 AgentfileParser 解析为 AgentImageConfig */
        AGENT_IMAGE,
        /** 知识库 — 纯文档，仅向量化存入 VectorNode */
        KNOWLEDGE_BASE
    }

    /**
     * 判断此包是否为插件类型。
     */
    public boolean isPlugin() {
        return type == PackageType.PLUGIN;
    }

    /**
     * 判断此包是否为 Agent 镜像类型。
     */
    public boolean isAgentImage() {
        return type == PackageType.AGENT_IMAGE;
    }

    /**
     * 判断此包是否附带知识库文档。
     */
    public boolean hasKnowledge() {
        return knowledgeDoc != null && !knowledgeDoc.isBlank();
    }

    /**
     * 判断此包是否有依赖。
     */
    public boolean hasDepends() {
        return depends != null && !depends.isEmpty();
    }

    /**
     * 获取包的唯一标识（name@version）。
     */
    public String qualifiedName() {
        return name + "@" + version;
    }
}
