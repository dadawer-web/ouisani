package com.ouisani.aios.core.task;

import java.util.List;

/**
 * SOP 驱动描述符 — 描述一个专家领域的技能契约。
 * <p>
 * 取代硬编码的 {@link ExpertDomain} 枚举，实现基于 VFS 的动态专家加载。
 * 每个 SopDescriptor 是一个可插拔的"驱动"，存储在 VFS 路径
 * {@code /system/drivers/sops/} 下，系统启动时由 {@code MoEGatingRouter} 动态扫描装载。
 * <p>
 * <b>字段语义</b>：
 * <ul>
 *   <li>{@code domainName} — 领域唯一标识（如 "software_engineering"）</li>
 *   <li>{@code description} — 自然语言描述，供 LLM/向量计算匹配度</li>
 *   <li>{@code requiredTools} — 该领域必须可用的工具列表（如 ["file_read", "bash"]）</li>
 * </ul>
 * <p>
 * VFS 文件格式（JSON）：
 * <pre>
 * {
 *   "domainName": "software_engineering",
 *   "description": "写代码、Debug、部署、架构设计、重构、代码审查",
 *   "requiredTools": ["file_read", "file_write", "bash", "grep", "glob"]
 * }
 * </pre>
 *
 * @see com.ouisani.aios.user.cli.MoEGatingRouter
 */
public record SopDescriptor(
        /** 领域唯一标识（如 "software_engineering"） */
        String domainName,
        /** 自然语言描述，供 LLM/向量计算匹配度 */
        String description,
        /** 该领域必须可用的工具列表 */
        List<String> requiredTools
) {
    /** 兼容构造：无 requiredTools 时默认空列表 */
    public SopDescriptor(String domainName, String description) {
        this(domainName, description, List.of());
    }

    /** 规范化：null 字段转为空值，避免 NPE */
    public SopDescriptor {
        if (domainName == null) domainName = "";
        if (description == null) description = "";
        if (requiredTools == null) requiredTools = List.of();
    }

    @Override
    public String toString() {
        return domainName + " (" + description + ") requires: " + requiredTools;
    }
}
