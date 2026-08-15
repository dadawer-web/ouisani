package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.snapshot.CarryoverSection;
import com.ouisani.aios.core.snapshot.EnvironmentSnapshot;
import com.ouisani.aios.core.snapshot.NodeOutputSection;
import com.ouisani.aios.core.snapshot.SnapshotCapturer;
import com.ouisani.aios.core.snapshot.SnapshotCapturerFactory;

import java.util.List;
import java.util.Set;

/**
 * Omnifactory fork 工厂 — 为 fork 分支批量创建隔离的 capturer 集合。
 * <p>
 * {@link #createForFork} 新建独立 {@link WorkflowContext}(命名空间 "fork-"+branchId),
 * 从种子快照的 NodeOutputSection 回填 globalMemory、从 CarryoverSection 重建
 * CarryoverState,返回绑定到该隔离 context 的 [WorkflowContextCapturer, CarryoverCapturer]。
 * <p>
 * 借鉴 mobilegym 的 "fork 结构化状态成 N 个并行 rollout":每个分支拿到相同的初始
 * 状态副本,各自独立执行,互不污染,适合 GRPO 式 group-RL 策略对比。
 */
public class OmnifactoryCapturerFactory implements SnapshotCapturerFactory {

    @Override
    public Set<String> sectionTypes() {
        return Set.of("NodeOutput", "Carryover");
    }

    @Override
    public List<SnapshotCapturer> createForFork(String branchId, EnvironmentSnapshot seed) {
        WorkflowContext forkedCtx = new WorkflowContext("fork-" + branchId);

        // 回填 NodeOutput:种子快照各节点输出写入隔离 context
        NodeOutputSection nodeOut = seed.getSection("NodeOutput", NodeOutputSection.class).orElse(null);
        if (nodeOut != null) {
            nodeOut.nodeOutputs().forEach(forkedCtx::commitNodeOutput);
        }

        // 回填 Carryover:从 section 重建工作记忆到隔离 context
        CarryoverSection carry = seed.getSection("Carryover", CarryoverSection.class).orElse(null);
        if (carry != null) {
            CarryoverStateSectionMapper.applyTo(carry, forkedCtx.getCarryoverState());
        }

        return List.of(new WorkflowContextCapturer(forkedCtx), new CarryoverCapturer(forkedCtx));
    }
}

