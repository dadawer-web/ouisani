package com.ouisani.aios.core.snapshot;

/**
 * 进程级分片 — 包装 {@link ProcessSnapshot}(CRIU 进程快照)。
 * <p>
 * 由内建 {@code ProcessSectionCapturer} 产出,捕获 AgentTask 的寄存器、
 * 内存页、VFS 句柄、信号队列、Journal 尾部。内嵌 ProcessSnapshot 引用
 * (同包,ProcessSnapshot 已 implements Serializable)。
 *
 * @param processSnapshot 被包装的进程快照
 */
public record ProcessSection(ProcessSnapshot processSnapshot) implements SnapshotSection {

    @Override
    public String sectionType() {
        return "Process";
    }
}
