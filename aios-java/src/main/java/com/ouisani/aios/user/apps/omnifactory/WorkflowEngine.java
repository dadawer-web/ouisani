package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.state.BoulderCheckpoint;
import com.ouisani.aios.core.state.BoulderStateManager;
import com.ouisani.aios.user.sdk.AiosSdk;
import com.ouisani.aios.user.sdk.AbstractAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 真正的 AGI 操作系统 DAG 引擎 (The Dify-like Core)。
 * <p>
 * 废弃原有的 Bash 脚本生成模式，实现内存级 DAG（有向无环图）执行引擎，
 * 利用 Java 21 Virtual Threads 进行并发调度和动态母体分发。
 * <p>
 * 核心设计（Dify 对齐）：
 * <pre>
 *   1. CompletableFuture 编排依赖链：上游全部 SUCCESS 后才唤醒下游
 *   2. 同层无依赖节点在虚拟线程中并发执行
 *   3. 节点级动态母体派发：executor="omni" → OmniMotherAgent, "operator" → OperatorAgent
 *   4. WorkflowContext 内存总线替代物理文件传递
 *   5. GraphEngineEvent 事件体系 — 驱动前端大屏、持久化、可观测性
 *   6. GraphEngineLayer 中间件 — 可插拔的拦截层
 *   7. ChildEngineBuilder 子引擎 — 迭代/循环嵌套执行
 * </pre>
 *
 * @see WorkflowNode
 * @see WorkflowContext
 * @see GraphEngineEvent
 * @see GraphEngineLayer
 * @see ChildEngineBuilder
 */
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    // 拥抱 Java 21：虚拟线程池，实现极其轻量级的并发沙箱隔离
    private final ExecutorService vThreadPool = Executors.newVirtualThreadPerTaskExecutor();

    // ── Layer 中间件注册表 ──
    private final List<GraphEngineLayer> layers = new CopyOnWriteArrayList<>();

    // 默认注册的 Layer
    private static final List<GraphEngineLayer> DEFAULT_LAYERS = List.of(
            new EventBusBridgeLayer(),
            new ExecutionLimitsLayer(100, 600_000) // 最多 100 步，最长 10 分钟
    );

    private static final class Holder {
        static final WorkflowEngine INSTANCE = new WorkflowEngine();
    }

    public static WorkflowEngine getInstance() {
        return Holder.INSTANCE;
    }

    private WorkflowEngine() {
        // 注册默认 Layer
        layers.addAll(DEFAULT_LAYERS);
        log.info("[DAG Engine] Workflow Engine initialized. Layers: {}, Virtual Thread DAG scheduler ready.",
                layers.stream().map(GraphEngineLayer::name).toList());
        System.out.println("[DAG Engine] Workflow Engine initialized. Virtual Thread DAG scheduler ready.");
    }

    /**
     * 注册 Layer 中间件。
     *
     * @param layer 要注册的 Layer
     */
    public void addLayer(GraphEngineLayer layer) {
        layers.add(layer);
        log.info("[DAG Engine] Layer registered: {}", layer.name());
    }

    /**
     * 移除 Layer 中间件。
     */
    public void removeLayer(Class<? extends GraphEngineLayer> layerClass) {
        layers.removeIf(l -> l.getClass().equals(layerClass));
    }

    /**
     * 执行工作流 — 兼容旧 API 入口。
     */
    public void executeWorkflow(WorkflowManifest manifest, Map<String, AgentBlueprint> blueprintRegistry) {
        System.out.printf("[DAG Engine] Executing workflow '%s' with %d nodes...%n",
                manifest.workflowName(), manifest.nodes().size());
        log.info("[DAG Engine] Executing workflow '{}': {} nodes", manifest.workflowName(), manifest.nodes().size());

        List<WorkflowNode> nodes = manifest.nodes();

        // ── 第一遍：为每个节点写入代码到 VFS ──
        AiosSdk sdk = AiosSdk.getInstance();
        for (WorkflowNode node : nodes) {
            AgentBlueprint blueprint = blueprintRegistry.get(node.blueprintId());
            if (blueprint == null) {
                log.warn("[DAG Engine] Blueprint '{}' not found for node '{}'. Skipping.",
                        node.blueprintId(), node.instanceId());
                continue;
            }
            String enrichedCode = injectParamsAndTopics(blueprint.codePayload(), node);
            String vfsPath = "/factory/" + node.instanceId() + ".py";
            sdk.writeFile("dag_engine", vfsPath, enrichedCode);
            log.info("[DAG Engine] Node '{}' code written: {} chars", node.instanceId(), enrichedCode.length());
        }

        // ── 构建 DAG 依赖图 ──
        buildDependencyGraph(nodes);

        // ── 委托给 DAG 调度器 ──
        executeDag(nodes, manifest.workflowName(), manifest.enabledSkills(), manifest.enabledRoles());
    }

    /**
     * 核心 DAG 调度器 — 公开入口（从零启动主引擎）。
     */
    public void executeDag(List<WorkflowNode> nodes, String workflowId) {
        WorkflowContext rootContext = new WorkflowContext(workflowId);
        executeDagInternal(nodes, workflowId, rootContext);
    }

    /**
     * 兼容旧 API — 保留 enabledSkills/enabledRoles 参数签名（内部不再使用）。
     */
    public void executeDag(List<WorkflowNode> nodes, String workflowId,
                           List<String> enabledSkills, List<String> enabledRoles) {
        executeDag(nodes, workflowId);
    }

    /**
     * 支持外部注入 WorkflowContext 的公开入口（子引擎 / ChildEngineBuilder 使用）。
     */
    public void executeDagWithContext(List<WorkflowNode> nodes, String workflowId, WorkflowContext context) {
        executeDagInternal(nodes, workflowId, context);
    }

    /**
     * 核心 DAG 调度器 — 内部实现，支持外部注入 WorkflowContext（子引擎递归使用）。
     * <p>
     * 完整的事件驱动流程：
     * <pre>
     *   1. onGraphStart() → GraphRunStartedEvent
     *   2. 对每个节点:
     *      a. onNodeRunStart() → NodeRunStartedEvent
     *      b. 执行节点（迭代节点→子引擎递归 / 常规节点→动态母体派发）
     *      c. onNodeRunEnd() → NodeRunSucceededEvent / NodeRunFailedEvent / NodeRunSkippedEvent
     *   3. onGraphEnd() → GraphRunSucceededEvent / GraphRunFailedEvent
     * </pre>
     */
    private void executeDagInternal(List<WorkflowNode> nodes, String workflowId, WorkflowContext context) {
        log.info("[DAG Engine] Igniting workflow '{}' with {} nodes.", workflowId, nodes.size());

        // ── 发出工作流开始事件 ──
        emitEvent(new GraphEngineEvent.GraphRunStartedEvent(workflowId, nodes.size()));
        invokeLayers(layer -> layer.onGraphStart(context));

        // 映射节点引用与 Future 任务图
        Map<String, WorkflowNode> nodeMap = new HashMap<>();
        Map<String, CompletableFuture<Void>> futures = new HashMap<>();
        for (WorkflowNode node : nodes) {
            nodeMap.put(node.instanceId(), node);
        }

        // 构建有向无环图的 CompletableFuture 依赖链
        for (WorkflowNode node : nodes) {
            List<CompletableFuture<Void>> upstreamFutures = node.getUpstreamDependencies().stream()
                    .map(futures::get)
                    .filter(Objects::nonNull)
                    .toList();

            CompletableFuture<Void> allUpstream = upstreamFutures.isEmpty()
                    ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.allOf(upstreamFutures.toArray(new CompletableFuture[0]));

            CompletableFuture<Void> nodeExecution = allUpstream.thenRunAsync(() -> {
                executeNode(node, nodeMap, context, workflowId);
            }, vThreadPool);

            futures.put(node.instanceId(), nodeExecution);
        }

        // 等待整个拓扑图执行完毕
        Exception graphError;
        try {
            CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0])).join();
            graphError = null;
        } catch (Exception e) {
            graphError = e;
            log.error("[DAG Engine] Workflow interrupted: {}", e.getMessage());
        }

        // ── 发出工作流结束事件 ──
        long successCount = nodes.stream().filter(n -> n.getStatus() == WorkflowNode.Status.SUCCESS).count();
        long failedCount = nodes.stream().filter(n -> n.getStatus() == WorkflowNode.Status.FAILED).count();

        if (failedCount == 0) {
            Map<String, Map<String, Object>> outputs = new HashMap<>();
            for (WorkflowNode node : nodes) {
                if (node.getStatus() == WorkflowNode.Status.SUCCESS) {
                    outputs.put(node.instanceId(), node.getOutputData());
                }
            }
            emitEvent(new GraphEngineEvent.GraphRunSucceededEvent(workflowId, outputs));
        } else {
            emitEvent(new GraphEngineEvent.GraphRunFailedEvent(workflowId,
                    graphError != null ? graphError.getMessage() : failedCount + " node(s) failed",
                    (int) failedCount));
        }

        final Exception finalError = graphError;
        for (GraphEngineLayer layer : layers) {
            try {
                layer.onGraphEnd(finalError);
            } catch (Exception e) {
                log.warn("[DAG Engine] Layer '{}' onGraphEnd failed: {}", layer.name(), e.getMessage());
            }
        }

        // 打印执行摘要
        printExecutionSummary(nodes, workflowId);
    }

    /**
     * 执行单个节点 — 包含完整的事件驱动生命周期。
     * 支持迭代节点（Iteration Node）的子引擎递归调度。
     */
    private void executeNode(WorkflowNode node, Map<String, WorkflowNode> nodeMap,
                             WorkflowContext context, String workflowId) {
        // A. 检查依赖节点的健康状态
        boolean canRun = true;
        for (String depId : node.getUpstreamDependencies()) {
            WorkflowNode depNode = nodeMap.get(depId);
            if (depNode != null && depNode.getStatus() != WorkflowNode.Status.SUCCESS) {
                canRun = false;
                break;
            }
        }

        if (!canRun) {
            node.setStatus(WorkflowNode.Status.SKIPPED);
            String reason = "Upstream node failed";
            log.warn("[DAG Engine] Node '{}' SKIPPED: {}", node.instanceId(), reason);
            emitEvent(new GraphEngineEvent.GraphNodeEvent.NodeRunSkippedEvent(
                    workflowId, node.instanceId(), node.executor(), reason, null));
            return;
        }

        // A2. 【Boulder 断点恢复拦截】— 检查是否有已完成的检查点
        Optional<BoulderCheckpoint> pastState = BoulderStateManager.loadCheckpoint(workflowId, node.instanceId());
        if (pastState.isPresent() && pastState.get().isCompleted()) {
            log.info("[DAG Engine] Boulder recovered. Node {} already SUCCESS. Skipping execution.",
                    node.instanceId());
            node.setStatus(WorkflowNode.Status.SUCCESS);
            // 将过去保存的输出强行注回当前内存总线
            context.commitNodeOutput(node.instanceId(), pastState.get().getOutputSnapshot());
            // 发出跳过事件（已通过检查点恢复）
            emitEvent(new GraphEngineEvent.GraphNodeEvent.NodeRunSkippedEvent(
                    workflowId, node.instanceId(), node.executor(),
                    "Boulder checkpoint recovered (already SUCCESS)", null));
            return; // 直接短路跳过执行！
        }

        // B. 节点启动
        node.setStatus(WorkflowNode.Status.RUNNING);
        long startTime = System.currentTimeMillis();

        emitEvent(new GraphEngineEvent.GraphNodeEvent.NodeRunStartedEvent(
                workflowId, node.instanceId(), node.executor(), node.role(), null));
        invokeLayers(layer -> layer.onNodeRunStart(node));

        try {
            if (node.isIteration()) {
                // 🔄 【开启子引擎模式】迭代节点递归调度
                log.info("[DAG Engine] Node {} is an Iteration Node. Igniting Child Engine...", node.instanceId());
                executeIterationNode(node, context, workflowId);
            } else {
                // 🏃 【Actor Mode 派单机制】通过 TeamRegistry 异步发信
                AbstractAgent taskAgent;
                if ("operator".equalsIgnoreCase(node.executor())) {
                    log.info("[DAG Engine]   └─ Routing to OperatorAgent (Physical RPA)");
                    taskAgent = new OperatorAgent(node, context);
                } else {
                    log.info("[DAG Engine]   └─ Routing to OmniMotherAgent (Logic/Code)");
                    taskAgent = new OmniMotherAgent(node, context);
                }

                // 1. 让 Agent 在邮局注册打卡
                com.ouisani.aios.core.team.TeamRegistry.getInstance().register(taskAgent);

                // 2. 在后台虚拟线程中拉起 Agent 的数字生命循环 (不阻塞)
                Thread.startVirtualThread(taskAgent::startEventLoop);

                // 3. 准备完工回执单与任务载荷
                java.util.concurrent.CompletableFuture<Void> completionReceipt = new java.util.concurrent.CompletableFuture<>();
                com.ouisani.aios.core.team.TaskPayload payload = new com.ouisani.aios.core.team.TaskPayload(node, completionReceipt);

                // 4. 引擎作为大主管 (Atlas)，向执行者 (Sisyphus) 派发任务邮件！
                com.ouisani.aios.core.team.MailMessage mail = new com.ouisani.aios.core.team.MailMessage(
                        "DAG_Atlas_Engine",
                        taskAgent.getAgentId(),
                        com.ouisani.aios.core.team.MailMessage.MessageType.TASK_ASSIGN,
                        payload
                );
                com.ouisani.aios.core.team.TeamRegistry.getInstance().dispatch(mail);

                // 5. 引擎挂起，死等回执单 (利用虚拟线程，完美阻塞不耗物理CPU)
                try {
                    completionReceipt.join();
                } finally {
                    // 6. 收到回执 (无论成功失败)，向 Agent 发送死亡药丸，安排下班
                    com.ouisani.aios.core.team.TeamRegistry.getInstance().dispatch(
                            new com.ouisani.aios.core.team.MailMessage(
                                    "DAG_Atlas_Engine", taskAgent.getAgentId(),
                                    com.ouisani.aios.core.team.MailMessage.MessageType.POISON_PILL, null)
                    );
                    com.ouisani.aios.core.team.TeamRegistry.getInstance().unregister(taskAgent.getAgentId());
                }
            }

            // C. 成功完成
            long durationMs = System.currentTimeMillis() - startTime;
            node.setStatus(WorkflowNode.Status.SUCCESS);
            context.commitNodeOutput(node.instanceId(), node.getOutputData());

            // 【Boulder 落地快照】— 节点成功后立即持久化
            BoulderCheckpoint checkpoint = new BoulderCheckpoint();
            checkpoint.setWorkflowId(workflowId);
            checkpoint.setNodeId(node.instanceId());
            checkpoint.setStatus(WorkflowNode.Status.SUCCESS);
            checkpoint.setOutputSnapshot(context.getNodeMemorySnapshot(node.instanceId()));
            checkpoint.setTimestamp(System.currentTimeMillis());
            checkpoint.setDurationMs(durationMs);
            BoulderStateManager.saveCheckpoint(checkpoint);

            emitEvent(new GraphEngineEvent.GraphNodeEvent.NodeRunSucceededEvent(
                    workflowId, node.instanceId(), node.executor(),
                    node.getOutputData(), durationMs, null));
            invokeLayers(layer -> layer.onNodeRunEnd(node, null));

            log.info("[DAG Engine] Node '{}' SUCCESS ({}ms).", node.instanceId(), durationMs);

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            node.setStatus(WorkflowNode.Status.FAILED);

            // 【Boulder 失败快照】— 记录失败状态和重试次数
            BoulderCheckpoint failCheckpoint = new BoulderCheckpoint();
            failCheckpoint.setWorkflowId(workflowId);
            failCheckpoint.setNodeId(node.instanceId());
            failCheckpoint.setStatus(WorkflowNode.Status.FAILED);
            failCheckpoint.setErrorMessage(e.getMessage());
            failCheckpoint.setDurationMs(durationMs);
            // 递增重试计数
            BoulderStateManager.loadCheckpoint(workflowId, node.instanceId()).ifPresent(past -> {
                failCheckpoint.setRetryCount(past.getRetryCount() + 1);
            });
            BoulderStateManager.saveCheckpoint(failCheckpoint);

            emitEvent(new GraphEngineEvent.GraphNodeEvent.NodeRunFailedEvent(
                    workflowId, node.instanceId(), node.executor(),
                    e.getMessage(), durationMs, null));
            invokeLayers(layer -> layer.onNodeRunEnd(node, e));

            log.error("[DAG Engine] Node '{}' FAILED ({}ms): {}", node.instanceId(), durationMs, e.getMessage(), e);
        }
    }

    /**
     * 递归执行迭代节点 (Child Engine Builder)。
     * <p>
     * 解析迭代节点的目标数组变量，为数组中每个元素创建隔离的子上下文，
     * 然后递归调用 executeDagInternal 执行子 DAG。
     *
     * @param iterationNode 迭代节点（包含 childNodes 子图定义）
     * @param parentContext  父引擎上下文（作用域链上游）
     * @param workflowId     工作流 ID
     */
    private void executeIterationNode(WorkflowNode iterationNode, WorkflowContext parentContext, String workflowId) {
        // 1. 解析目标数组变量 (例如 "{{fetch_node.url_list}}")
        Object rawList = parentContext.resolveValue(iterationNode.getIteratorDataVariable());

        if (!(rawList instanceof List<?> items)) {
            log.warn("[DAG Engine] Iteration node {} failed: target variable is not a List. Value: {}",
                    iterationNode.instanceId(), rawList);
            return; // 无法迭代，跳过
        }

        log.info("[DAG Engine] Iteration node {} found {} items. Commencing loop.",
                iterationNode.instanceId(), items.size());

        // 2. 遍历数组，每一次循环启动一个独立的子引擎
        for (int i = 0; i < items.size(); i++) {
            Object currentItem = items.get(i);

            // A. 创造局部隔离上下文（作用域链）
            WorkflowContext childContext = new WorkflowContext(workflowId, parentContext);

            // B. 将当前遍历到的 item 强行塞入局部上下文中，供内部节点读取
            // 比如设置了别名为 "item"，内部节点就可以通过 {{item}} 获取当前值
            Map<String, Object> loopInject = new HashMap<>();
            loopInject.put(iterationNode.getIteratorItemAlias(), currentItem);
            // 用一个虚拟的源节点ID（当前循环的特有ID）作为空间
            childContext.commitNodeOutput(iterationNode.instanceId() + "_scope", loopInject);

            log.info("  ├─ [Child Engine] Starting Iteration {}/{}", (i + 1), items.size());

            // C. 递归调用 executeDagInternal，执行内部的子节点！
            executeDagInternal(iterationNode.getChildNodes(), workflowId, childContext);
        }
    }

    /**
     * 构建 DAG 依赖图 — 通过 subscribeTopic → publishTopic 推断节点间边关系。
     */
    private void buildDependencyGraph(List<WorkflowNode> nodes) {
        Map<String, String> topicPublishers = new HashMap<>();
        for (WorkflowNode node : nodes) {
            if (node.publishTopic() != null && !node.publishTopic().isBlank()) {
                topicPublishers.put(node.publishTopic(), node.instanceId());
            }
        }

        for (WorkflowNode node : nodes) {
            String subTopic = node.subscribeTopic();
            if (subTopic != null && !subTopic.isBlank() && topicPublishers.containsKey(subTopic)) {
                String parentId = topicPublishers.get(subTopic);
                node.addDependency(parentId);
                log.debug("[DAG Engine] Dependency: {} → {}", parentId, node.instanceId());
            }
        }
    }

    /**
     * 打印 DAG 执行摘要。
     */
    private void printExecutionSummary(List<WorkflowNode> nodes, String workflowId) {
        long success = nodes.stream().filter(n -> n.getStatus() == WorkflowNode.Status.SUCCESS).count();
        long failed = nodes.stream().filter(n -> n.getStatus() == WorkflowNode.Status.FAILED).count();
        long skipped = nodes.stream().filter(n -> n.getStatus() == WorkflowNode.Status.SKIPPED).count();

        System.out.printf("[DAG Engine] ── Workflow '%s' Summary ──%n", workflowId);
        System.out.printf("[DAG Engine]   SUCCESS: %d | FAILED: %d | SKIPPED: %d | Total: %d%n",
                success, failed, skipped, nodes.size());

        for (WorkflowNode node : nodes) {
            String icon = switch (node.getStatus()) {
                case SUCCESS -> "OK";
                case FAILED -> "FAIL";
                case SKIPPED -> "SKIP";
                default -> "?";
            };
            System.out.printf("[DAG Engine]   [%s] %s (executor=%s)%n", icon, node.instanceId(), node.executor());
        }
    }

    /**
     * 将用户参数和 EventBus topic 注入到代码头部。
     */
    private String injectParamsAndTopics(String code, WorkflowNode node) {
        StringBuilder header = new StringBuilder();
        header.append("import os\n");

        if (!node.subscribeTopic().isEmpty()) {
            header.append("SUBSCRIBE_TOPIC = os.getenv('SUBSCRIBE_TOPIC', '")
                    .append(node.subscribeTopic()).append("')\n");
        }
        if (!node.publishTopic().isEmpty()) {
            header.append("PUBLISH_TOPIC = os.getenv('PUBLISH_TOPIC', '")
                    .append(node.publishTopic()).append("')\n");
        }

        for (Map.Entry<String, String> param : node.userParams().entrySet()) {
            header.append("PARAM_").append(param.getKey().toUpperCase())
                    .append(" = os.getenv('PARAM_").append(param.getKey().toUpperCase())
                    .append("', '").append(param.getValue()).append("')\n");
        }

        header.append("\n");
        header.append(code);
        return header.toString();
    }

    // ════════════════════════════════════════════════════════════════
    //  Layer 调度工具方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 向所有已注册的 Layer 发送事件。
     */
    private void emitEvent(GraphEngineEvent event) {
        for (GraphEngineLayer layer : layers) {
            try {
                layer.onEvent(event);
            } catch (Exception e) {
                log.warn("[DAG Engine] Layer '{}' failed to handle event: {}",
                        layer.name(), e.getMessage());
            }
        }
    }

    /**
     * 对所有 Layer 执行操作（函数式接口）。
     */
    private void invokeLayers(java.util.function.Consumer<GraphEngineLayer> action) {
        for (GraphEngineLayer layer : layers) {
            try {
                action.accept(layer);
            } catch (Exception e) {
                log.warn("[DAG Engine] Layer '{}' callback failed: {}",
                        layer.name(), e.getMessage());
            }
        }
    }
}
