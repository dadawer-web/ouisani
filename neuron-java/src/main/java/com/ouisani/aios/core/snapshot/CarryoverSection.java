package com.ouisani.aios.core.snapshot;

import java.util.List;
import java.util.Map;

/**
 * 携带状态分片 — 镜像 {@code WorkflowContext.CarryoverState} 四字段。
 * <p>
 * 跨上下文压缩持久化的工作记忆:任务焦点、已读文件、已调用工具、工作日志。
 * fork 时携带此分片,使并行分支继承原分支完整工作记忆,策略对比公平;
 * diff 时检测"意外遗忘"(readFiles/taskFocus key 在 before 有 after 缺)。
 *
 * @param taskFocus     任务焦点(键 → 值,如 current_goal/next_step)
 * @param readFiles     已读文件(路径 → 行范围)
 * @param invokedTools  已调用工具(工具名 → 调用摘要列表)
 * @param workLog       工作日志(append-only,按时间顺序)
 */
public record CarryoverSection(
        Map<String, String> taskFocus,
        Map<String, String> readFiles,
        Map<String, List<String>> invokedTools,
        List<String> workLog
) implements SnapshotSection {

    @Override
    public String sectionType() {
        return "Carryover";
    }
}
