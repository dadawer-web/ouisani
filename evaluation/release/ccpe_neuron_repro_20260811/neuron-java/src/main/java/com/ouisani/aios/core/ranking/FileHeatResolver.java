package com.ouisani.aios.core.ranking;

/**
 * 文件热度解析器 — 依赖反转接口，注入到 {@code CompactCutoffGuard}
 * 让 CCR 压缩切点选择时优先保留热文件。
 * <p>
 * 镜像 {@code CompactCutoffGuard.SemanticBoundaryDetector} 的 NOOP 默认 + setter 注入模式。
 */
@FunctionalInterface
public interface FileHeatResolver {
    /** 返回 path 的当前热度分数（>=0，越高越热）；未知返回 0 */
    double heatOf(String path);
}
