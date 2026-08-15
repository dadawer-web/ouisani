package com.ouisani.aios.core.snapshot;

import java.io.Serializable;

/**
 * 快照状态分片 — 统一执行环境快照的可序列化切片契约。
 * <p>
 * 借鉴 mobilegym 的结构化状态模型:整个执行环境被切分为若干独立的
 * {@code SnapshotSection},每个 section 由对应的 {@link SnapshotCapturer}
 * 捕获/恢复,可独立参与 {@code diff} 与 {@code fork}。
 * <p>
 * 依赖倒置(DIP,镜像 {@code core/tool/ToolSdk} 模式):core/snapshot 定义
 * 此 sealed 接口与各纯数据 record 子类型,user 态实现 SnapshotCapturer
 * 并注册到 {@link EnvironmentSnapshotManager}。core 绝不 import user 态。
 * <p>
 * OS 类比:类比 Linux CRIU 的 images-dir 中按类别分文件存储
 * (pages.img / fdinfo.img / sigacts.img ...),每个 section 对应一类。
 *
 * @see SnapshotCapturer
 * @see EnvironmentSnapshot
 */
public sealed interface SnapshotSection extends Serializable
        permits NodeOutputSection, CarryoverSection, ProcessSection,
                HibernationSection, VfsSection, BoulderSection, OverlayDiffSection {

    /** section 类型标识,用作 {@link EnvironmentSnapshot#sections()} 的 key。 */
    String sectionType();
}
