package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.snapshot.NodeOutputSection;
import com.ouisani.aios.core.snapshot.SnapshotCapturer;
import com.ouisani.aios.core.snapshot.SnapshotSection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 节点输出捕获器 — 镜像 {@link WorkflowContext#getNodeOutput} 为 {@link NodeOutputSection}。
 * <p>
 * 持有 {@link WorkflowContext},capture 深拷贝 globalMemory(节点 ID → 变量键值),
 * restore 用 {@link WorkflowContext#commitNodeOutput} 回填。
 * <p>
 * <b>不全局注册</b>:WorkflowEngine 是单例,可并发运行多个工作流;全局 capturer
 * 注册表按 sectionType 去重,并发工作流会互相覆盖导致捕获错上下文。故 WorkflowEngine
 * 双写时直接实例化本类(绑定本地 context)调 capture,经
 * {@link com.ouisani.aios.core.snapshot.EnvironmentSnapshotManager#capture(String, SnapshotSection...)}
 * 显式传 section,绕开全局注册。
 *
 * @see CarryoverCapturer
 */
public class WorkflowContextCapturer implements SnapshotCapturer {

    private final WorkflowContext context;

    public WorkflowContextCapturer(WorkflowContext context) {
        this.context = context;
    }

    @Override
    public String sectionType() {
        return "NodeOutput";
    }

    @Override
    public SnapshotSection capture() {
        Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
        for (String nodeId : context.getNodeIds()) {
            copy.put(nodeId, new LinkedHashMap<>(context.getNodeOutput(nodeId)));
        }
        return new NodeOutputSection(copy);
    }

    @Override
    public void restore(SnapshotSection section) {
        if (!(section instanceof NodeOutputSection nos)) return;
        for (Map.Entry<String, Map<String, Object>> e : nos.nodeOutputs().entrySet()) {
            context.commitNodeOutput(e.getKey(), e.getValue());
        }
    }
}
