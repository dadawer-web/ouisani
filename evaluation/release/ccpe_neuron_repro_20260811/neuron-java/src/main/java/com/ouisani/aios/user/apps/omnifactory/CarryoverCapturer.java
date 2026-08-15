package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.snapshot.CarryoverSection;
import com.ouisani.aios.core.snapshot.SnapshotCapturer;
import com.ouisani.aios.core.snapshot.SnapshotSection;

/**
 * 携带状态捕获器 — 镜像 {@link WorkflowContext.CarryoverState} 为 {@link CarryoverSection}。
 * <p>
 * 与 {@link WorkflowContextCapturer} 共享同一 {@link WorkflowContext} 引用,
 * 但产出不同 section 类型(Carryover),各自独立注册/使用。capture 经
 * {@link CarryoverStateSectionMapper#toSection} 深拷贝;restore 经
 * {@link CarryoverStateSectionMapper#applyTo} 回填。
 * <p>
 * <b>不全局注册</b>(同 {@link WorkflowContextCapturer} 的并发说明)。
 */
public class CarryoverCapturer implements SnapshotCapturer {

    private final WorkflowContext context;

    public CarryoverCapturer(WorkflowContext context) {
        this.context = context;
    }

    @Override
    public String sectionType() {
        return "Carryover";
    }

    @Override
    public SnapshotSection capture() {
        return CarryoverStateSectionMapper.toSection(context.getCarryoverState());
    }

    @Override
    public void restore(SnapshotSection section) {
        if (!(section instanceof CarryoverSection cs)) return;
        CarryoverStateSectionMapper.applyTo(cs, context.getCarryoverState());
    }
}
