package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.state.BoulderCheckpoint;
import com.ouisani.aios.core.state.BoulderStateManager;
import com.ouisani.aios.core.VfsManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 工作流自愈器 — 从 WorkflowEngine 抽取的故障恢复与拓扑突变逻辑。
 * <p>
 * 职责：
 * <ul>
 *   <li>Semantic Core Dump 生成（崩溃现场保存）</li>
 *   <li>热重启（AutoMedic 修复后原地复活）</li>
 *   <li>拓扑突变（节点无能时动态替换）</li>
 *   <li>恢复挂起节点（SIGCONT）</li>
 *   <li>强制失败（OOM Killer 兜底）</li>
 * </ul>
 * <p>
 * OS 类比：Linux 的 CRIU + kdump + hotplug — 检查点保存、崩溃转储、热插拔替换。
 */
class WorkflowHealer {

    private static final Logger log = LoggerFactory.getLogger(WorkflowHealer.class);

    private final WorkflowEngine engine;
    private final WorkflowRegistry registry;

    WorkflowHealer(WorkflowEngine engine, WorkflowRegistry registry) {
        this.engine = engine;
        this.registry = registry;
    }

    // ════════════════════════════════════════════════════════════════
    //  Semantic Core Dump
    // ════════════════════════════════════════════════════════════════

    /**
     * 生成语义级核心转储 — 借鉴 Linux 的 kdump。
     * <p>
     * 将节点崩溃时的完整语义上下文写入文件，供 AutoMedic 诊断。
     */
    String generateSemanticCoreDump(WorkflowNode node, Exception error,
                                    WorkflowContext context, String workflowId, long durationMs) {
        String dumpDir = com.ouisani.aios.core.config.AiosPaths.workspaces() + "/" + workflowId.replace(" ", "_") + "/factory";
        String dumpFileName = "dump_" + node.instanceId() + "_" + System.currentTimeMillis() + ".aios";
        String dumpFilePath = dumpDir + "/" + dumpFileName;

        try {
            java.nio.file.Path dir = java.nio.file.Path.of(dumpDir);
            if (!java.nio.file.Files.exists(dir)) {
                java.nio.file.Files.createDirectories(dir);
            }

            String traceId = com.ouisani.aios.core.ipc.TraceContext.getCurrentTraceId();
            if (traceId == null) {
                traceId = com.ouisani.aios.core.ipc.TraceContext.getTraceIdForTask(node.instanceId());
            }

            StringBuilder dump = new StringBuilder();
            dump.append("═══════════════════════════════════════════════════\n");
            dump.append("  AIOS Semantic Core Dump\n");
            dump.append("  Node: ").append(node.instanceId()).append("\n");
            dump.append("  Role: ").append(node.role()).append("\n");
            dump.append("  Executor: ").append(node.executor()).append("\n");
            dump.append("  Timestamp: ").append(java.time.Instant.now()).append("\n");
            dump.append("  Duration: ").append(durationMs).append("ms\n");
            dump.append("  Trace ID: ").append(traceId != null ? traceId : "(none)").append("\n");
            dump.append("═══════════════════════════════════════════════════\n\n");

            dump.append("── CRASH INFO ──\n");
            dump.append("Exception: ").append(error.getClass().getName()).append("\n");
            dump.append("Message: ").append(error.getMessage()).append("\n");
            if (error.getCause() != null) {
                dump.append("Cause: ").append(error.getCause().getClass().getName())
                        .append(": ").append(error.getCause().getMessage()).append("\n");
            }
            dump.append("\nStackTrace:\n");
            for (StackTraceElement ste : error.getStackTrace()) {
                dump.append("  at ").append(ste.toString()).append("\n");
            }
            dump.append("\n");

            dump.append("── VARIABLE SNAPSHOT ──\n");
            Map<String, Object> nodeVars = context.getNodeMemorySnapshot(node.instanceId());
            if (nodeVars != null && !nodeVars.isEmpty()) {
                for (Map.Entry<String, Object> entry : nodeVars.entrySet()) {
                    String val = entry.getValue() != null ? entry.getValue().toString() : "null";
                    if (val.length() > 500) val = val.substring(0, 500) + "...(truncated)";
                    dump.append("  ").append(entry.getKey()).append(" = ").append(val).append("\n");
                }
            } else {
                dump.append("  (empty)\n");
            }
            dump.append("\n");

            dump.append("── UPSTREAM OUTPUTS ──\n");
            for (String depId : node.getUpstreamDependencies()) {
                Map<String, Object> depOutput = context.getNodeMemorySnapshot(depId);
                if (depOutput != null && !depOutput.isEmpty()) {
                    dump.append("  [").append(depId).append("]\n");
                    for (Map.Entry<String, Object> entry : depOutput.entrySet()) {
                        String val = entry.getValue() != null ? entry.getValue().toString() : "null";
                        if (val.length() > 300) val = val.substring(0, 300) + "...";
                        dump.append("    ").append(entry.getKey()).append(" = ").append(val).append("\n");
                    }
                }
            }
            dump.append("\n");

            dump.append("── NODE BLUEPRINT ──\n");
            dump.append("  BlueprintId: ").append(node.blueprintId()).append("\n");
            dump.append("  Role: ").append(node.role()).append("\n");
            dump.append("  Subscribe: ").append(node.subscribeTopic()).append("\n");
            dump.append("  Publish: ").append(node.publishTopic()).append("\n");
            dump.append("\n");

            dump.append("── BOULDER CHECKPOINT HISTORY ──\n");
            BoulderStateManager.loadCheckpoint(workflowId, node.instanceId()).ifPresentOrElse(
                    cp -> dump.append("  RetryCount: ").append(cp.getRetryCount())
                            .append(", LastStatus: ").append(cp.getStatus())
                            .append(", LastError: ").append(cp.getErrorMessage()).append("\n"),
                    () -> dump.append("  (no previous checkpoint)\n")
            );
            dump.append("\n");

            dump.append("── VFS MOUNT STATE ──\n");
            try {
                String factoryListing = VfsManager.instance().readText(
                        "/factory/" + node.instanceId());
                if (factoryListing != null && !factoryListing.isBlank()) {
                    dump.append("  /factory/").append(node.instanceId()).append(": ")
                            .append(factoryListing.length()).append(" chars\n");
                }
            } catch (Exception ignored) {}
            dump.append("\n");

            dump.append("═══════════════════════════════════════════════════\n");
            dump.append("  END OF CORE DUMP\n");
            dump.append("═══════════════════════════════════════════════════\n");

            java.nio.file.Files.writeString(java.nio.file.Path.of(dumpFilePath), dump.toString());
            log.info("[DAG Engine] Core Dump 已写入: {} ({} chars)", dumpFilePath, dump.length());

        } catch (Exception ex) {
            log.error("[DAG Engine] Core Dump 写入失败: {}", ex.getMessage());
            dumpFilePath = "(dump failed: " + ex.getMessage() + ")";
        }

        return dumpFilePath;
    }

    // ════════════════════════════════════════════════════════════════
    //  状态回滚与热重启 (State Rollback & Hot Restart)
    //  借鉴 Linux 的 CRIU (Checkpoint/Restore In Userspace)
    // ════════════════════════════════════════════════════════════════

    /**
     * AutoMedic 修复成功后，回滚节点状态并热重启。
     * <p>
     * 流程：
     * 1. 抹除节点崩溃前最后一次错误的对话记录（状态倒回）
     * 2. 将 Medic 修改后的正确上下文重新注入节点
     * 3. 重新执行节点（SIGCONT → 原地复活）
     *
     * @return true=重启成功, false=重启失败
     */
    private boolean restartNodeAfterHeal(WorkflowNode node, AutoMedicAgent.MedicalReport report,
                                         WorkflowContext context, String workflowId) {
        try {
            log.info("[DAG Engine] 节点 '{}' 热重启中... (修复内容: {} chars)",
                    node.instanceId(), report.patchedCode() != null ? report.patchedCode().length() : 0);

            if (report.patchedCode() != null && !report.patchedCode().isBlank()
                    && report.patchedVfsPath() != null) {
                try {
                    VfsManager.instance().writeText(report.patchedVfsPath(), report.patchedCode());
                    log.info("[DAG Engine] 修复代码已写入 VFS: {}", report.patchedVfsPath());
                } catch (Exception e) {
                    log.warn("[DAG Engine] VFS 写入失败: {}", e.getMessage());
                }
            }

            if (report.reflectionHint() != null && !report.reflectionHint().isBlank()) {
                context.commitNodeOutput(node.instanceId() + "_medic_hint",
                        Map.of("reflection", report.reflectionHint()));
                log.info("[DAG Engine] 反思提示已注入: {}", report.reflectionHint().substring(0, Math.min(80, report.reflectionHint().length())));
            }

            node.setStatus(WorkflowNode.Status.PENDING);
            node.putOutput("_medic_healed", true);
            node.putOutput("_medic_diagnosis", report.diagnosis());

            int healCount = 0;
            if (node.getOutputData().containsKey("_heal_count")) {
                healCount = (int) node.getOutputData().get("_heal_count");
            }
            if (healCount >= 2) {
                log.warn("[DAG Engine] 节点 '{}' 已热重启 {} 次，不再重试", node.instanceId(), healCount);
                return false;
            }
            node.putOutput("_heal_count", healCount + 1);

            log.info("[DAG Engine] 节点 '{}' 原地复活！第 {} 次热重启", node.instanceId(), healCount + 1);
            engine.executeNode(node, Map.of(node.instanceId(), node), context, workflowId);

            return node.getStatus() == WorkflowNode.Status.SUCCESS;

        } catch (Exception e) {
            log.error("[DAG Engine] 节点 '{}' 热重启失败: {}", node.instanceId(), e.getMessage());
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  DAG 拓扑运行时突变 (Dynamic Topology Mutation)
    //  借鉴 Kubernetes 的 Pod 替换和 Linux 的热插拔
    // ════════════════════════════════════════════════════════════════

    /**
     * DAG 拓扑突变 — 当 AutoMedic 判定节点无能时，动态插入替代节点。
     * <p>
     * 机制：
     * 1. 根据 MedicalReport.suggestedRole() 创建新节点 A'
     * 2. 将原本指向 A 的入度和出度，动态切换到 A' 上
     * 3. 在 DAG 中执行 A'（虚拟线程并发）
     *
     * @return true=突变成功, false=突变失败
     */
    private boolean doMutateTopology(WorkflowNode failedNode, AutoMedicAgent.MedicalReport report,
                                   Map<String, WorkflowNode> nodeMap,
                                   WorkflowContext context, String workflowId) {
        try {
            String suggestedRole = report.suggestedRole();
            if (suggestedRole == null || suggestedRole.isBlank()) {
                log.warn("[DAG Engine] AutoMedic 未建议替代角色，无法突变");
                return false;
            }

            String replacementId = failedNode.instanceId() + "_prime_" + System.currentTimeMillis() % 10000;
            WorkflowNode replacementNode = new WorkflowNode(
                    replacementId,
                    suggestedRole,
                    failedNode.blueprintId(),
                    failedNode.userParams(),
                    failedNode.subscribeTopic(),
                    failedNode.publishTopic(),
                    failedNode.executor()
            );
            replacementNode.setVerificationContract(failedNode.verificationContract());
            replacementNode.setInputSchema(failedNode.inputSchema());
            replacementNode.setOutputSchema(failedNode.outputSchema());

            for (String depId : failedNode.getUpstreamDependencies()) {
                replacementNode.addDependency(depId);
            }

            if (failedNode.getCondition() != null) {
                replacementNode.setCondition(failedNode.getCondition());
            }

            failedNode.setStatus(WorkflowNode.Status.SKIPPED);
            failedNode.putOutput("_replaced_by", replacementId);
            failedNode.putOutput("_replacement_reason", report.diagnosis());

            nodeMap.put(replacementId, replacementNode);
            log.info("[DAG Engine] 拓扑突变：节点 '{}' 被 '{}' 替代（角色: {}→{}）",
                    failedNode.instanceId(), replacementId, failedNode.role(), suggestedRole);

            for (WorkflowNode downstream : nodeMap.values()) {
                if (downstream.getUpstreamDependencies().contains(failedNode.instanceId())) {
                    downstream.addDependency(replacementId);
                    log.debug("[DAG Engine] 下游 '{}' 的依赖已更新：+{}", downstream.instanceId(), replacementId);
                }
            }

            replacementNode.setStatus(WorkflowNode.Status.PENDING);
            replacementNode.putOutput("_is_mutation", true);
            replacementNode.putOutput("_replacing", failedNode.instanceId());

            for (String depId : failedNode.getUpstreamDependencies()) {
                Map<String, Object> depOutput = context.getNodeMemorySnapshot(depId);
                if (depOutput != null) {
                    context.commitNodeOutput(replacementId, depOutput);
                }
            }

            engine.executeNode(replacementNode, nodeMap, context, workflowId);

            if (replacementNode.getStatus() == WorkflowNode.Status.SUCCESS) {
                context.commitNodeOutput(failedNode.instanceId(), replacementNode.getOutputData());
                failedNode.setLastVerificationResult(replacementNode.lastVerificationResult());
                failedNode.setStatus(WorkflowNode.Status.SUCCESS);
                log.info("[DAG Engine] 拓扑突变成功！替代节点 '{}' 完成了原节点 '{}' 的任务",
                        replacementId, failedNode.instanceId());
                return true;
            } else {
                log.warn("[DAG Engine] 替代节点 '{}' 也失败了", replacementId);
                return false;
            }

        } catch (Exception e) {
            log.error("[DAG Engine] 拓扑突变异常: {}", e.getMessage());
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  事件驱动自愈 — 恢复与拓扑突变公开接口
    //  供 RecoveryOrchestrator 异步调用
    // ════════════════════════════════════════════════════════════════

    /**
     * 恢复挂起节点 — 供 RecoveryOrchestrator 在修复完成后调用。
     * <p>
     * OS 类比：Linux 的 SIGCONT — 进程被 SIGSTOP 挂起后，收到 SIGCONT 信号恢复执行。
     *
     * @param nodeId     节点 ID
     * @param workflowId 工作流 ID
     * @param newContext 修复后的新上下文（可为 null）
     * @return true=恢复成功, false=恢复失败
     */
    boolean resumeNode(String nodeId, String workflowId, Map<String, Object> newContext) {
        WorkflowNode node = registry.findNodeInActiveWorkflows(nodeId);
        if (node == null) {
            log.warn("[DAG Engine] resumeNode: 节点 '{}' 未找到", nodeId);
            return false;
        }

        if (node.getStatus() != WorkflowNode.Status.SUSPENDED) {
            log.warn("[DAG Engine] resumeNode: 节点 '{}' 状态为 {}，非 SUSPENDED",
                    nodeId, node.getStatus());
            return false;
        }

        log.info("[DAG Engine] 节点 '{}' 收到 SIGCONT，恢复执行", nodeId);

        if (newContext != null) {
            for (Map.Entry<String, Object> entry : newContext.entrySet()) {
                node.putOutput(entry.getKey(), entry.getValue());
            }
        }

        node.setStatus(WorkflowNode.Status.PENDING);
        int currentHealCount = 0;
        Object healCountObj = node.getOutputData().get("_heal_count");
        if (healCountObj instanceof Integer) {
            currentHealCount = (Integer) healCountObj;
        }
        node.putOutput("_heal_count", currentHealCount + 1);

        WorkflowContext context = registry.getActiveWorkflowContext(workflowId);
        if (context == null) {
            log.warn("[DAG Engine] resumeNode: 工作流 '{}' 上下文未找到", workflowId);
            return false;
        }

        // 【恢复 carryover】— 从 BoulderCheckpoint 还原工作记忆,避免节点恢复后失忆
        try {
            BoulderStateManager.loadCheckpoint(workflowId, nodeId).ifPresent(cp -> {
                if (cp.getCarryoverSnapshot() != null && !cp.getCarryoverSnapshot().isEmpty()) {
                    CarryoverStateSectionMapper.fromMap(cp.getCarryoverSnapshot(), context.getCarryoverState());
                    log.info("[DAG Engine] resumeNode '{}' 已恢复 carryover (taskFocus={} entries)",
                            nodeId, context.getCarryoverState().getTaskFocus().size());
                }
            });
        } catch (Exception ex) {
            log.warn("[DAG Engine] resumeNode '{}' carryover 恢复失败: {}", nodeId, ex.getMessage());
        }

        Thread.startVirtualThread(() -> {
            try {
                engine.executeNode(node, registry.findNodeMapForWorkflow(workflowId), context, workflowId);
                log.info("[DAG Engine] 节点 '{}' 恢复执行完成: status={}", nodeId, node.getStatus());

                if (node.getStatus() == WorkflowNode.Status.SUCCESS) {
                    Map<String, WorkflowNode> nodeMap = registry.findNodeMapForWorkflow(workflowId);
                    if (nodeMap != null) {
                        for (WorkflowNode downstream : nodeMap.values()) {
                            if (downstream.getUpstreamDependencies().contains(nodeId)
                                    && downstream.getStatus() == WorkflowNode.Status.SKIPPED) {
                                log.info("[DAG Engine] 重新触发下游节点 '{}'（因上游 '{}' 恢复成功）",
                                        downstream.instanceId(), nodeId);
                                downstream.setStatus(WorkflowNode.Status.PENDING);
                                Thread.startVirtualThread(() -> {
                                    try {
                                        engine.executeNode(downstream, nodeMap, context, workflowId);
                                    } catch (Exception e) {
                                        log.error("[DAG Engine] 下游节点 '{}' 重新执行失败: {}",
                                                downstream.instanceId(), e.getMessage());
                                    }
                                });
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.error("[DAG Engine] 节点 '{}' 恢复执行失败: {}", nodeId, e.getMessage());
            }

            EventBus.instance().broadcast("sys.workflow.node_resumed",
                    String.format("{\"workflowId\":\"%s\",\"nodeId\":\"%s\",\"status\":\"%s\"}",
                            workflowId.replace("\"", "\\\""),
                            nodeId.replace("\"", "\\\""),
                            node.getStatus().name()));
        });

        return true;
    }

    /**
     * 拓扑突变 — 供 RecoveryOrchestrator 调用。
     */
    boolean mutateTopology(String failedNodeId, String workflowId, String suggestedRole) {
        WorkflowNode failedNode = registry.findNodeInActiveWorkflows(failedNodeId);
        if (failedNode == null) return false;

        WorkflowContext context = registry.getActiveWorkflowContext(workflowId);
        Map<String, WorkflowNode> nodeMap = registry.findNodeMapForWorkflow(workflowId);
        if (context == null || nodeMap == null) return false;

        AutoMedicAgent.MedicalReport report = new AutoMedicAgent.MedicalReport(
                AutoMedicAgent.Outcome.INCAPABLE,
                "RecoveryOrchestrator requested topology mutation",
                null, null, suggestedRole, null
        );

        return doMutateTopology(failedNode, report, nodeMap, context, workflowId);
    }

    /**
     * 强制将节点标记为 FAILED — 供 RecoveryOrchestrator 在 resumeNode 失败时调用。
     * <p>
     * OS 类比：Linux 内核的 OOM Killer — 进程已无响应，强制回收资源让系统继续运转。
     */
    boolean forceFailNode(String nodeId) {
        WorkflowNode node = registry.findNodeInActiveWorkflows(nodeId);
        if (node == null) {
            log.warn("[DAG Engine] forceFailNode: 节点 '{}' 未找到，可能工作流已注销", nodeId);
            return false;
        }
        if (node.getStatus() == WorkflowNode.Status.SUCCESS) {
            log.info("[DAG Engine] forceFailNode: 节点 '{}' 已是 SUCCESS，跳过", nodeId);
            return true;
        }
        log.warn("[DAG Engine] forceFailNode: 强制将节点 '{}' 从 {} 降级为 FAILED（resumeNode 失败后的兜底）",
                nodeId, node.getStatus());
        node.setStatus(WorkflowNode.Status.FAILED);
        engine.emitEvent(new GraphEngineEvent.GraphNodeEvent.NodeRunFailedEvent(
                null, nodeId, node.executor(),
                "Recovery succeeded but resumeNode failed — forced FAIL to prevent deadlock",
                0, null));
        return true;
    }

    // ════════════════════════════════════════════════════════════════
    //  节点恢复 — 供 RecoveryOrchestrator 在修复完成后调用
    //  借鉴 Linux 的 SIGCONT：挂起的进程收到恢复信号后继续执行
    // ════════════════════════════════════════════════════════════════

    /**
     * 恢复挂起的节点 — RecoveryOrchestrator 修复完成后的回调入口。
     *
     * @param nodeId      挂起的节点 ID
     * @param report      AutoMedic 的诊断报告
     * @param workflowId  工作流 ID
     * @return true=恢复成功, false=恢复失败
     */
    boolean resumeNode(String nodeId, AutoMedicAgent.MedicalReport report, String workflowId) {
        log.info("[DAG Engine] resumeNode() 被调用: nodeId={}, outcome={}", nodeId, report.outcome());

        WorkflowNode node = registry.findNodeInActiveWorkflows(nodeId);
        if (node == null) {
            log.error("[DAG Engine] resumeNode: 找不到节点 '{}'", nodeId);
            return false;
        }

        if (node.getStatus() != WorkflowNode.Status.SUSPENDED) {
            log.warn("[DAG Engine] resumeNode: 节点 '{}' 状态不是 SUSPENDED（当前: {}），跳过",
                    nodeId, node.getStatus());
            return false;
        }

        switch (report.outcome()) {
            case HEALED -> {
                return hotRestartNode(node, report, workflowId);
            }
            case INCAPABLE -> {
                return mutateTopologyFromReport(node, report, workflowId);
            }
            case FAILED -> {
                log.error("[DAG Engine] resumeNode: AutoMedic 修复失败，节点 '{}' 标记为 FAILED: {}",
                        nodeId, report.diagnosis());
                node.setStatus(WorkflowNode.Status.FAILED);
                engine.emitEvent(new GraphEngineEvent.GraphNodeEvent.NodeRunFailedEvent(
                        workflowId, nodeId, node.executor(),
                        report.diagnosis(), 0, null));
                return false;
            }
        }
        return false;
    }

    /**
     * 热重启节点 — AutoMedic 修复成功后的恢复逻辑。
     */
    private boolean hotRestartNode(WorkflowNode node, AutoMedicAgent.MedicalReport report, String workflowId) {
        try {
            log.info("[DAG Engine] 节点 '{}' 热重启中... (修复内容: {} chars)",
                    node.instanceId(), report.patchedCode() != null ? report.patchedCode().length() : 0);

            if (report.patchedCode() != null && !report.patchedCode().isBlank()
                    && report.patchedVfsPath() != null) {
                try {
                    VfsManager.instance().writeText(report.patchedVfsPath(), report.patchedCode());
                    log.info("[DAG Engine] 修复代码已写入 VFS: {}", report.patchedVfsPath());
                } catch (Exception e) {
                    log.warn("[DAG Engine] VFS 写入失败: {}", e.getMessage());
                }
            }

            if (report.reflectionHint() != null && !report.reflectionHint().isBlank()) {
                node.putOutput("_medic_hint", report.reflectionHint());
                log.info("[DAG Engine] 反思提示已注入: {}",
                        report.reflectionHint().substring(0, Math.min(80, report.reflectionHint().length())));
            }

            node.setStatus(WorkflowNode.Status.PENDING);
            node.putOutput("_medic_healed", true);
            node.putOutput("_medic_diagnosis", report.diagnosis());

            int healCount = 0;
            if (node.getOutputData().containsKey("_heal_count")) {
                healCount = (int) node.getOutputData().get("_heal_count");
            }
            if (healCount >= 2) {
                log.warn("[DAG Engine] 节点 '{}' 已热重启 {} 次，不再重试", node.instanceId(), healCount);
                node.setStatus(WorkflowNode.Status.FAILED);
                return false;
            }
            node.putOutput("_heal_count", healCount + 1);

            log.info("[DAG Engine] 节点 '{}' 原地复活！第 {} 次热重启", node.instanceId(), healCount + 1);

            WorkflowContext context = registry.getActiveWorkflowContext(workflowId);
            if (context == null) {
                log.error("[DAG Engine] 找不到工作流 '{}' 的上下文", workflowId);
                node.setStatus(WorkflowNode.Status.FAILED);
                return false;
            }

            engine.executeNode(node, Map.of(node.instanceId(), node), context, workflowId);
            return node.getStatus() == WorkflowNode.Status.SUCCESS;

        } catch (Exception e) {
            log.error("[DAG Engine] 节点 '{}' 热重启失败: {}", node.instanceId(), e.getMessage());
            node.setStatus(WorkflowNode.Status.FAILED);
            return false;
        }
    }

    /**
     * 拓扑突变 — AutoMedic 判定节点无能时的替代方案。
     */
    private boolean mutateTopologyFromReport(WorkflowNode failedNode, AutoMedicAgent.MedicalReport report, String workflowId) {
        try {
            String suggestedRole = report.suggestedRole();
            if (suggestedRole == null || suggestedRole.isBlank()) {
                log.warn("[DAG Engine] AutoMedic 未建议替代角色，无法突变");
                failedNode.setStatus(WorkflowNode.Status.FAILED);
                return false;
            }

            String replacementId = failedNode.instanceId() + "_prime_" + System.currentTimeMillis() % 10000;
            WorkflowNode replacementNode = new WorkflowNode(
                    replacementId,
                    suggestedRole,
                    failedNode.blueprintId(),
                    failedNode.userParams(),
                    failedNode.subscribeTopic(),
                    failedNode.publishTopic(),
                    failedNode.executor()
            );
            replacementNode.setVerificationContract(failedNode.verificationContract());
            replacementNode.setInputSchema(failedNode.inputSchema());
            replacementNode.setOutputSchema(failedNode.outputSchema());

            for (String depId : failedNode.getUpstreamDependencies()) {
                replacementNode.addDependency(depId);
            }

            failedNode.setStatus(WorkflowNode.Status.SKIPPED);
            failedNode.putOutput("_replaced_by", replacementId);
            failedNode.putOutput("_replacement_reason", report.diagnosis());

            log.info("[DAG Engine] 拓扑突变：节点 '{}' 被 '{}' 替代（角色: {}→{}）",
                    failedNode.instanceId(), replacementId, failedNode.role(), suggestedRole);

            replacementNode.setStatus(WorkflowNode.Status.PENDING);
            replacementNode.putOutput("_is_mutation", true);
            replacementNode.putOutput("_replacing", failedNode.instanceId());

            WorkflowContext context = registry.getActiveWorkflowContext(workflowId);
            if (context == null) {
                log.error("[DAG Engine] 找不到工作流 '{}' 的上下文", workflowId);
                failedNode.setStatus(WorkflowNode.Status.FAILED);
                return false;
            }

            for (String depId : failedNode.getUpstreamDependencies()) {
                Map<String, Object> depOutput = context.getNodeMemorySnapshot(depId);
                if (depOutput != null) {
                    context.commitNodeOutput(replacementId, depOutput);
                }
            }

            engine.executeNode(replacementNode, Map.of(replacementId, replacementNode), context, workflowId);

            if (replacementNode.getStatus() == WorkflowNode.Status.SUCCESS) {
                context.commitNodeOutput(failedNode.instanceId(), replacementNode.getOutputData());
                failedNode.setLastVerificationResult(replacementNode.lastVerificationResult());
                failedNode.setStatus(WorkflowNode.Status.SUCCESS);
                log.info("[DAG Engine] 拓扑突变成功！替代节点 '{}' 完成了原节点 '{}' 的任务",
                        replacementId, failedNode.instanceId());
                return true;
            } else {
                log.warn("[DAG Engine] 替代节点 '{}' 也失败了", replacementId);
                failedNode.setStatus(WorkflowNode.Status.FAILED);
                return false;
            }

        } catch (Exception e) {
            log.error("[DAG Engine] 拓扑突变异常: {}", e.getMessage());
            failedNode.setStatus(WorkflowNode.Status.FAILED);
            return false;
        }
    }
}
