package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.ipc.VariablePool;
import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.state.BoulderCheckpoint;
import com.ouisani.aios.core.state.BoulderStateManager;
import com.ouisani.aios.user.sdk.AiosSdk;
import com.ouisani.aios.user.sdk.AbstractAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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

    // ── 声明式边列表（借鉴 Langflow 的 Edge 路由机制） ──
    private final List<WorkflowEdge> edges = new ArrayList<>();

    /** 活跃工作流的节点映射（workflowId → nodeMap） */
    private final ConcurrentHashMap<String, Map<String, WorkflowNode>> activeNodeMaps = new ConcurrentHashMap<>();
    /** 活跃工作流的上下文（workflowId → context） */
    private final ConcurrentHashMap<String, WorkflowContext> activeContexts = new ConcurrentHashMap<>();

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

    /** Alias for getInstance() — used by RecoveryStrategy implementations */
    public static WorkflowEngine instance() {
        return getInstance();
    }

    private WorkflowEngine() {
        // 注册默认 Layer
        layers.addAll(DEFAULT_LAYERS);
        log.info("[DAG Engine] Workflow Engine 已初始化。Layer: {}, 虚拟线程 DAG 调度器就绪。",
                layers.stream().map(GraphEngineLayer::name).toList());
        System.out.println("[DAG Engine] Workflow Engine 已初始化。虚拟线程 DAG 调度器就绪。");
    }

    /**
     * 注册 Layer 中间件。
     *
     * @param layer 要注册的 Layer
     */
    public void addLayer(GraphEngineLayer layer) {
        layers.add(layer);
        log.info("[DAG Engine] Layer 已注册: {}", layer.name());
    }

    /**
     * 移除 Layer 中间件。
     */
    public void removeLayer(Class<? extends GraphEngineLayer> layerClass) {
        layers.removeIf(l -> l.getClass().equals(layerClass));
    }

    /**
     * 设置工作流边列表（借鉴 Langflow Edge 路由）。
     */
    public void setEdges(List<WorkflowEdge> edges) {
        this.edges.clear();
        if (edges != null) this.edges.addAll(edges);
    }

    /**
     * 获取工作流边列表（只读视图）。
     */
    public List<WorkflowEdge> getEdges() { return Collections.unmodifiableList(edges); }

    /**
     * 执行工作流 — 兼容旧 API 入口（TopologyCompiler 使用）。
     * <p>
     * 此方法负责：创建集装箱目录 → 写入蓝图代码到 VFS → 构建 DAG → 调度执行。
     * 直接调用 executeDagInternal，避免与 executeDag 重复创建目录。
     */
    public void executeWorkflow(WorkflowManifest manifest, Map<String, AgentBlueprint> blueprintRegistry) {
        System.out.printf("[DAG Engine] 正在执行工作流 '%s'，共 %d 个节点...%n",
                manifest.workflowName(), manifest.nodes().size());
        log.info("[DAG Engine] 正在执行工作流 '{}'：{} 个节点", manifest.workflowName(), manifest.nodes().size());

        List<WorkflowNode> nodes = manifest.nodes();

        // ── 创建工作流专属集装箱目录 — 输入输出统一收敛 ──
        String workflowId = String.valueOf(System.currentTimeMillis());
        String containerBase = com.ouisani.aios.core.config.AiosPaths
                .workspaceForWorkflow(workflowId, manifest.workflowName());
        java.nio.file.Path containerDir = java.nio.file.Path.of(containerBase);
        java.nio.file.Path physicalFactory = containerDir.resolve("factory");
        java.nio.file.Path physicalOutputs = containerDir.resolve("outputs");
        try {
            java.nio.file.Files.createDirectories(physicalFactory);
            java.nio.file.Files.createDirectories(physicalOutputs);
            log.info("[DAG Engine] 集装箱目录已创建: {} (factory + outputs)", containerBase);
        } catch (java.io.IOException e) {
            log.error("[DAG Engine] 集装箱目录创建失败: {}", e.getMessage());
        }

        // 注册工作流专属的 VFS 物理映射，使 /factory 写入落到集装箱目录
        VfsManager.instance().registerPhysicalWorkspace("/factory", physicalFactory.toString());

        // ── 第一遍：为每个节点写入代码到 VFS ──
        AiosSdk sdk = AiosSdk.getInstance();
        for (WorkflowNode node : nodes) {
            AgentBlueprint blueprint = blueprintRegistry.get(node.blueprintId());
            if (blueprint == null) {
                log.warn("[DAG Engine] Blueprint '{}' 未找到，对应节点 '{}'。跳过。",
                        node.blueprintId(), node.instanceId());
                continue;
            }
            String enrichedCode = injectParamsAndTopics(blueprint.codePayload(), node);
            String vfsPath = "/factory/" + node.instanceId() + ".py";
            sdk.writeFile("dag_engine", vfsPath, enrichedCode);
            log.info("[DAG Engine] Node '{}' 代码已写入：{} 字符", node.instanceId(), enrichedCode.length());
        }

        // ── 构建 DAG 依赖图 ──
        buildDependencyGraph(nodes);

        // ── 直接调度执行（跳过 executeDag 的目录创建，因为上面已经做了） ──
        WorkflowContext rootContext = new WorkflowContext(manifest.workflowName());
        executeDagInternal(nodes, manifest.workflowName(), rootContext);
    }

    /**
     * 核心 DAG 调度器 — 公开入口（从零启动主引擎）。
     * <p>
     * 自动创建工作流专属集装箱目录（factory/outputs/task.meta），
     * 并注册 VFS 物理映射，确保输入输出统一收敛。
     */
    public void executeDag(List<WorkflowNode> nodes, String workflowId) {
        // 创建工作流专属集装箱目录
        String containerBase = com.ouisani.aios.core.config.AiosPaths
                .workspaceForWorkflow(workflowId, workflowId);
        java.nio.file.Path containerDir = java.nio.file.Path.of(containerBase);
        java.nio.file.Path physicalFactory = containerDir.resolve("factory");
        java.nio.file.Path physicalOutputs = containerDir.resolve("outputs");
        try {
            java.nio.file.Files.createDirectories(physicalFactory);
            java.nio.file.Files.createDirectories(physicalOutputs);
            log.info("[DAG Engine] 集装箱目录已创建: {} (factory + outputs)", containerBase);
        } catch (java.io.IOException e) {
            log.error("[DAG Engine] 集装箱目录创建失败: {}", e.getMessage());
        }

        // 注册 VFS 物理映射，使 /factory 写入落到集装箱目录
        VfsManager.instance().registerPhysicalWorkspace("/factory", physicalFactory.toString());

        // 构建 DAG 依赖图
        buildDependencyGraph(nodes);

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
        // ── 端到端 Trace ID 注入 ──
        String traceId = com.ouisani.aios.core.ipc.TraceContext.ensureTraceId(workflowId);
        log.info("[DAG Engine] 工作流 {} 启动, TraceID={}", workflowId, traceId);

        log.info("[DAG Engine] 正在启动工作流 '{}'，共 {} 个节点。", workflowId, nodes.size());

        // ── 发出工作流开始事件 ──
        emitEvent(new GraphEngineEvent.GraphRunStartedEvent(workflowId, nodes.size()));
        invokeLayers(layer -> layer.onGraphStart(context));

        // 映射节点引用与 Future 任务图
        Map<String, WorkflowNode> nodeMap = new HashMap<>();
        Map<String, CompletableFuture<Void>> futures = new HashMap<>();
        for (WorkflowNode node : nodes) {
            nodeMap.put(node.instanceId(), node);
        }

        // 注册活跃工作流（供 resumeNode 查找节点和上下文）
        registerActiveWorkflow(workflowId, nodeMap, context);

        // 构建有向无环图的 CompletableFuture 依赖链
        // ── 容错隔离：使用 handleAsync 替代 thenRunAsync，防止单节点失败级联传播 ──
        // 借鉴 Firecrawl 的引擎隔离 + Kubernetes 的 Pod 故障隔离理念：
        // 每个节点是独立的工作单元，上游失败不应让下游 Future 异常完成，
        // 而是让下游节点自行决定是否执行（通过上游健康检查）。
        for (WorkflowNode node : nodes) {
            List<CompletableFuture<Void>> upstreamFutures = node.getUpstreamDependencies().stream()
                    .map(futures::get)
                    .filter(Objects::nonNull)
                    .toList();

            CompletableFuture<Void> allUpstream = upstreamFutures.isEmpty()
                    ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.allOf(upstreamFutures.toArray(new CompletableFuture[0]));

            // 关键修复：使用 handleAsync 而非 thenRunAsync
            // thenRunAsync 在上游异常完成时会跳过执行并传播异常 → 级联崩溃
            // handleAsync 无论上游成功/失败都会执行 → 下游节点可以自行判断是否运行
            CompletableFuture<Void> nodeExecution = allUpstream.handleAsync((result, ex) -> {
                if (ex != null) {
                    log.debug("[DAG Engine] Node '{}' 上游有异常，但仍进入健康检查", node.instanceId());
                }
                // 无论上游是否异常，都执行 executeNode（内部会检查上游状态）
                executeNode(node, nodeMap, context, workflowId);
                return null; // 将异常结果转为正常完成，阻止级联传播
            }, vThreadPool);

            futures.put(node.instanceId(), nodeExecution);
        }

        // 等待整个拓扑图执行完毕
        // 使用 exceptionally 吞掉 allOf 的异常，改为事后统计
        Exception graphError;
        try {
            CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
                    .exceptionally(ex -> {
                        log.debug("[DAG Engine] allOf 捕获异常（已隔离，不影响其他节点）: {}", ex.getMessage());
                        return null; // 吞掉异常，让所有节点都有机会执行完
                    }).join();
            graphError = null;
        } catch (Exception e) {
            graphError = e;
            log.error("[DAG Engine] 工作流被中断: {}", e.getMessage());
        }

        // ── 发出工作流结束事件 ──
        // 修复：支持"部分成功"——独立分支的成功结果不应被丢弃
        long successCount = nodes.stream().filter(n -> n.getStatus() == WorkflowNode.Status.SUCCESS).count();
        long failedCount = nodes.stream().filter(n -> n.getStatus() == WorkflowNode.Status.FAILED).count();
        long skippedCount = nodes.stream().filter(n -> n.getStatus() == WorkflowNode.Status.SKIPPED).count();
        long suspendedCount = nodes.stream().filter(n -> n.getStatus() == WorkflowNode.Status.SUSPENDED).count();

        // 收集所有成功节点的输出（即使有失败节点，成功的结果也要保留）
        Map<String, Map<String, Object>> outputs = new HashMap<>();
        for (WorkflowNode node : nodes) {
            if (node.getStatus() == WorkflowNode.Status.SUCCESS) {
                outputs.put(node.instanceId(), node.getOutputData());
            }
        }

        if (suspendedCount > 0) {
            // 有挂起节点 → 发出带挂起信息的事件，前端需要知道工作流在等待恢复
            log.info("[DAG Engine] 工作流有 {} 个挂起节点，等待恢复", suspendedCount);
            emitEvent(new GraphEngineEvent.GraphRunSucceededEvent(workflowId, outputs));
            // 广播挂起状态到前端
            EventBus.instance().broadcast("sys.workflow.suspended",
                    String.format("{\"workflowId\":\"%s\",\"suspendedNodes\":%d,\"message\":\"等待 RecoveryOrchestrator 修复\"}",
                            workflowId.replace("\"", "\\\""), suspendedCount));
        } else if (failedCount == 0) {
            emitEvent(new GraphEngineEvent.GraphRunSucceededEvent(workflowId, outputs));
        } else if (successCount > 0) {
            // 部分成功：有失败的节点，但也有成功的节点
            // 不再发出 GraphRunFailedEvent，而是发出带警告的成功事件
            log.warn("[DAG Engine] 工作流部分成功: success={}, failed={}, skipped={}",
                    successCount, failedCount, skippedCount);
            emitEvent(new GraphEngineEvent.GraphRunSucceededEvent(workflowId, outputs));
            // 额外发出失败节点的诊断信息
            for (WorkflowNode node : nodes) {
                if (node.getStatus() == WorkflowNode.Status.FAILED) {
                    log.warn("[DAG Engine] 失败节点: id={}, role={}", node.instanceId(), node.role());
                }
            }
        } else {
            emitEvent(new GraphEngineEvent.GraphRunFailedEvent(workflowId,
                    graphError != null ? graphError.getMessage() : failedCount + " node(s) failed",
                    (int) failedCount));
        }

        // ── globalMemory 过期清理 — 借鉴 Symphony 的内存回收策略 ──
        // 工作流完成后，清理非终点（Sink Node）的中间缓存，防止内存泄漏
        // Sink 节点定义：没有任何下游节点依赖它的输出
        try {
            Set<String> sinkNodeIds = new java.util.HashSet<>();
            Set<String> upstreamNodeIds = new java.util.HashSet<>();
            for (WorkflowNode node : nodes) {
                for (String depId : node.getUpstreamDependencies()) {
                    upstreamNodeIds.add(depId);
                }
            }
            for (WorkflowNode node : nodes) {
                if (!upstreamNodeIds.contains(node.instanceId())) {
                    sinkNodeIds.add(node.instanceId());
                }
            }
            int cleaned = context.cleanupIntermediateNodes(sinkNodeIds);
            if (cleaned > 0) {
                log.info("[DAG Engine] globalMemory 清理: 保留 {} 个 Sink 节点, 清理 {} 个中间节点",
                        sinkNodeIds.size(), cleaned);
            }
        } catch (Exception e) {
            log.warn("[DAG Engine] globalMemory 清理失败: {}", e.getMessage());
        }

        final Exception finalError = graphError;
        for (GraphEngineLayer layer : layers) {
            try {
                layer.onGraphEnd(finalError);
            } catch (Exception e) {
                log.warn("[DAG Engine] Layer '{}' onGraphEnd 失败: {}", layer.name(), e.getMessage());
            }
        }

        // 打印执行摘要
        printExecutionSummary(nodes, workflowId);

        // ── 延迟注销：如果有 SUSPENDED 节点，不能注销活跃工作流 ──
        // 否则 RecoveryOrchestrator 的 resumeNode 和超时降级都找不到节点
        // 复用上方已计算的 suspendedCount
        if (suspendedCount > 0) {
            log.info("[DAG Engine] 工作流有 {} 个 SUSPENDED 节点，延迟注销活跃工作流（等待恢复或超时降级）",
                    suspendedCount);
            // 注册一个延迟注销任务：每 30 秒检查一次，所有 SUSPENDED 节点都被解决后才注销
            Thread.startVirtualThread(() -> {
                while (true) {
                    try {
                        Thread.sleep(30_000);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                    boolean stillSuspended = false;
                    for (WorkflowNode n : nodes) {
                        if (n.getStatus() == WorkflowNode.Status.SUSPENDED) {
                            stillSuspended = true;
                            break;
                        }
                    }
                    if (!stillSuspended) {
                        log.info("[DAG Engine] 所有 SUSPENDED 节点已解决，注销活跃工作流: {}", workflowId);
                        unregisterActiveWorkflow(workflowId);
                        break;
                    }
                }
            });
        } else {
            // 注销活跃工作流
            unregisterActiveWorkflow(workflowId);
        }
    }

    /**
     * 执行单个节点 — 包含完整的事件驱动生命周期。
     * 支持迭代节点（Iteration Node）的子引擎递归调度。
     */
    private void executeNode(WorkflowNode node, Map<String, WorkflowNode> nodeMap,
                             WorkflowContext context, String workflowId) {
        // A. 检查依赖节点的健康状态 — 容错分支隔离
        // ── 借鉴 Kubernetes 的 Pod 故障隔离：只有直接依赖的分支失败才跳过 ──
        // 修复：之前要求所有上游都是 SUCCESS，导致一个节点失败整条链雪崩
        // 现在：区分"直接依赖"和"间接依赖"，只有直接上游 FAILED 才跳过
        // 独立分支（与失败节点无关的分支）可以继续执行
        boolean hasFailedDirectUpstream = false;
        boolean hasAnyUpstream = false;
        for (String depId : node.getUpstreamDependencies()) {
            WorkflowNode depNode = nodeMap.get(depId);
            if (depNode != null) {
                hasAnyUpstream = true;
                if (depNode.getStatus() == WorkflowNode.Status.FAILED) {
                    hasFailedDirectUpstream = true;
                    log.debug("[DAG Engine] Node '{}' 的直接上游 '{}' 已失败",
                            node.instanceId(), depId);
                }
            }
        }

        if (hasFailedDirectUpstream) {
            node.setStatus(WorkflowNode.Status.SKIPPED);
            String reason = "Direct upstream node failed (cascade isolation)";
            log.warn("[DAG Engine] Node '{}' 已跳过（级联隔离）: {}", node.instanceId(), reason);
            emitEvent(new GraphEngineEvent.GraphNodeEvent.NodeRunSkippedEvent(
                    workflowId, node.instanceId(), node.executor(), reason, null));
            return;
        }

        // 如果上游节点还在 PENDING/RUNNING（理论上不应该，但防御性编程）
        // 不再阻塞，允许节点执行（上游健康检查已通过 handleAsync 保证上游已完成）

        // A1. 【条件路由检查】— 借鉴 Langflow 的 conditionally_excluded_vertices
        // 如果节点定义了 condition 表达式，求值后决定是否跳过
        if (node.getCondition() != null && !node.getCondition().isBlank()) {
            boolean conditionMet = evaluateCondition(node.getCondition(), nodeMap, context);
            if (!conditionMet) {
                node.setStatus(WorkflowNode.Status.SKIPPED);
                String reason = "Condition not met: " + node.getCondition();
                log.info("[DAG Engine] Node '{}' 条件不满足，已跳过: {}", node.instanceId(), node.getCondition());
                emitEvent(new GraphEngineEvent.GraphNodeEvent.NodeRunSkippedEvent(
                        workflowId, node.instanceId(), node.executor(), reason, null));
                return;
            }
            log.info("[DAG Engine] Node '{}' 条件满足: {}", node.instanceId(), node.getCondition());
        }

        // A2. 【Boulder 断点恢复拦截】— 检查是否有已完成的检查点
        Optional<BoulderCheckpoint> pastState = BoulderStateManager.loadCheckpoint(workflowId, node.instanceId());
        if (pastState.isPresent() && pastState.get().isCompleted()) {
            log.info("[DAG Engine] Boulder 已恢复。Node {} 已 SUCCESS。跳过执行。",
                    node.instanceId());
            node.setStatus(WorkflowNode.Status.SUCCESS);
            // 将过去保存的输出强行注回当前内存总线
            context.commitNodeOutput(node.instanceId(), pastState.get().getOutputSnapshot());
            // 发出跳过事件（已通过检查点恢复）
            emitEvent(new GraphEngineEvent.GraphNodeEvent.NodeRunSkippedEvent(
                    workflowId, node.instanceId(), node.executor(),
                    "Boulder 检查点已恢复（已 SUCCESS）", null));
            return; // 直接短路跳过执行！
        }

        // A3. 【Frozen 缓存恢复】— 借鉴 Langflow 的 Frozen Vertex 机制
        // 如果节点标记为 frozen 且 VariablePool LRU 缓存中有其输出，直接恢复
        if (node.isFrozen() && VariablePool.getInstance().cacheGet("frozen:" + node.instanceId()) != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cachedOutput = VariablePool.getInstance().cacheGet("frozen:" + node.instanceId(), Map.class);
            if (cachedOutput != null && !cachedOutput.isEmpty()) {
                log.info("[DAG Engine] Frozen 缓存命中。Node {} 输出从 LRU 缓存恢复。", node.instanceId());
                node.setStatus(WorkflowNode.Status.SUCCESS);
                cachedOutput.forEach(node::putOutput);
                context.commitNodeOutput(node.instanceId(), cachedOutput);
                emitEvent(new GraphEngineEvent.GraphNodeEvent.NodeRunSkippedEvent(
                        workflowId, node.instanceId(), node.executor(),
                        "Frozen 缓存恢复（LRU 命中）", null));
                return;
            }
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
                log.info("[DAG Engine] Node {} 是迭代节点。正在启动子引擎...", node.instanceId());
                executeIterationNode(node, context, workflowId);
            } else {
                // 🏃 【Actor Mode 派单机制】通过 TeamRegistry 异步发信
                AbstractAgent taskAgent;
                if ("operator".equalsIgnoreCase(node.executor())) {
                    log.info("[DAG Engine]   └─ 路由至 OperatorAgent（物理 RPA）");
                    taskAgent = new OperatorAgent(node, context);
                } else if (node.executor() != null && node.executor().startsWith("external")) {
                    // ── 外部 Agent Runner — 借鉴 OmniGent 的 Runner Pattern ──
                    // executor="external" 或 "external:claude-code" 等
                    log.info("[DAG Engine]   └─ 路由至 ExternalAgentRunner（外部 Agent CLI）");
                    taskAgent = new ExternalAgentRunner(node, context);
                } else {
                    log.info("[DAG Engine]   └─ 路由至 OmniMotherAgent（逻辑/代码）");
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

            // ── 端口级数据路由（借鉴 Langflow Edge 路由） ──
            // 根据 Edge 定义，将本节点的输出端口数据精确路由到下游节点的输入端口
            for (WorkflowEdge edge : edges) {
                if (edge.sourceNodeId().equals(node.instanceId())) {
                    Object portData = node.getOutputData().get(edge.sourcePortName());
                    if (portData != null) {
                        WorkflowNode targetNode = nodeMap.get(edge.targetNodeId());
                        if (targetNode != null) {
                            // 类型兼容性检查
                            Port sourcePort = node.getOutputPort(edge.sourcePortName());
                            Port targetPort = targetNode.getInputPort(edge.targetPortName());
                            if (sourcePort != null && targetPort != null && !sourcePort.isCompatibleWith(targetPort)) {
                                log.warn("[DAG Engine] 端口类型不兼容: {} → {}, 跳过路由", sourcePort, targetPort);
                                continue;
                            }
                            // 将数据写入目标节点的输入端口
                            targetNode.putOutput(edge.targetPortName(), portData);
                            log.debug("[DAG Engine] 端口路由: {}.{} → {}.{}",
                                node.instanceId(), edge.sourcePortName(),
                                targetNode.instanceId(), edge.targetPortName());
                        }
                    }
                }
            }

            // 【Frozen 节点输出缓存】— 借鉴 Langflow 的 Frozen Vertex 机制
            if (node.isFrozen()) {
                VariablePool.getInstance().cacheSet("frozen:" + node.instanceId(), new HashMap<>(node.getOutputData()));
                log.info("[DAG Engine] Frozen 节点输出已缓存: {}", node.instanceId());
            }

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

            log.info("[DAG Engine] Node '{}' 成功 ({}ms)。", node.instanceId(), durationMs);

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;

            // ════════════════════════════════════════════════════════════════
            //  事件驱动自愈 — 引擎只负责"抛出中断"（Fail & Trap）
            //
            //  借鉴 Linux 内核的中断处理：进程崩溃时内核只做三件事：
            //  1. 保存现场（Core Dump）
            //  2. 挂起进程（SIGSTOP）
            //  3. 发出信号（signal → RecoveryOrchestrator）
            //
            //  引擎线程不阻塞等待 Medic，而是立即释放去执行其他分支。
            //  RecoveryOrchestrator 异步监听事件，调度 Medic 修复后
            //  通过 resumeNode() 唤醒节点。
            // ════════════════════════════════════════════════════════════════

            log.warn("[DAG Engine] Node '{}' 异常 ({}ms): {}。生成 Core Dump 并挂起...",
                    node.instanceId(), durationMs, e.getMessage());

            // ── Step 1: 生成 Semantic Core Dump ──
            String dumpFilePath = generateSemanticCoreDump(node, e, context, workflowId, durationMs);

            // ── Step 2: 挂起节点（SUSPENDED） ──
            node.setStatus(WorkflowNode.Status.SUSPENDED);
            node.putOutput("_crash_dump_path", dumpFilePath);
            node.putOutput("_crash_duration_ms", durationMs);
            node.putOutput("_crash_error", e.getMessage());

            // ── Step 3: 向全局总线抛出崩溃事件 ──
            // 引擎线程到此结束，不再阻塞等待 Medic
            String crashEvent = String.format(
                    "{\"eventType\":\"SEMANTIC_CRASH\",\"nodeId\":\"%s\",\"workflowId\":\"%s\","
                            + "\"dumpPath\":\"%s\",\"error\":\"%s\",\"durationMs\":%d,"
                            + "\"role\":\"%s\",\"blueprintId\":\"%s\",\"timestamp\":%d}",
                    node.instanceId().replace("\"", "\\\""),
                    workflowId.replace("\"", "\\\""),
                    dumpFilePath.replace("\"", "\\\"").replace("\n", ""),
                    e.getMessage() != null ? e.getMessage().replace("\"", "'").replace("\n", " ").substring(0, Math.min(e.getMessage().length(), 500)) : "unknown",
                    durationMs,
                    node.role() != null ? node.role().replace("\"", "\\\"") : "",
                    node.blueprintId() != null ? node.blueprintId().replace("\"", "\\\"") : "",
                    System.currentTimeMillis()
            );

            EventBus.instance().broadcast("sys.semantic.crash", crashEvent);
            log.info("[DAG Engine] 节点 '{}' 已挂起，崩溃事件已发布到 sys.semantic.crash", node.instanceId());

            // ── Step 4: 超时降级 — 如果 RecoveryOrchestrator 在限定时间内未能修复，自动降级为 FAILED ──
            // 防止节点永远 SUSPENDED 导致工作流卡死
            // OS 类比：Linux 的 hung_task_timeout_secs — 内核检测到任务挂起超时后自动标记
            final String suspendedNodeId = node.instanceId();
            final String suspendedWorkflowId = workflowId;
            Thread.startVirtualThread(() -> {
                try {
                    // 等待 120 秒，给 RecoveryOrchestrator 足够时间修复
                    Thread.sleep(120_000);
                    // 检查节点是否仍然 SUSPENDED
                    WorkflowNode checkNode = findNodeInActiveWorkflows(suspendedNodeId);
                    if (checkNode != null && checkNode.getStatus() == WorkflowNode.Status.SUSPENDED) {
                        log.warn("[DAG Engine] 节点 '{}' 恢复超时（120s），自动降级为 FAILED", suspendedNodeId);
                        checkNode.setStatus(WorkflowNode.Status.FAILED);
                        // 通知下游节点可以继续（不再无限等待）
                        WorkflowContext ctx = activeContexts.get(suspendedWorkflowId);
                        if (ctx != null) {
                            ctx.commitNodeOutput(suspendedNodeId, Map.of(
                                    "status", "failed",
                                    "error", "Recovery timeout after 120s"
                            ));
                        }
                        // 重新触发下游 SKIPPED 节点（让它们自行判断是否可执行）
                        Map<String, WorkflowNode> timeoutNodeMap = findNodeMapForWorkflow(suspendedWorkflowId);
                        if (timeoutNodeMap != null) {
                            for (WorkflowNode downstream : timeoutNodeMap.values()) {
                                if (downstream.getUpstreamDependencies().contains(suspendedNodeId)
                                        && downstream.getStatus() == WorkflowNode.Status.SKIPPED) {
                                    log.info("[DAG Engine] 超时降级后重新触发下游节点 '{}'", downstream.instanceId());
                                    downstream.setStatus(WorkflowNode.Status.PENDING);
                                    Thread.startVirtualThread(() -> {
                                        try {
                                            executeNode(downstream, timeoutNodeMap, ctx, suspendedWorkflowId);
                                        } catch (Exception ex) {
                                            log.error("[DAG Engine] 下游节点 '{}' 重新执行失败: {}",
                                                    downstream.instanceId(), ex.getMessage());
                                        }
                                    });
                                }
                            }
                        }
                    }
                } catch (InterruptedException ignored) {
                    // 被中断说明节点已被恢复，正常退出
                }
            });

            // 保存 Boulder 检查点
            BoulderCheckpoint crashCheckpoint = new BoulderCheckpoint();
            crashCheckpoint.setWorkflowId(workflowId);
            crashCheckpoint.setNodeId(node.instanceId());
            crashCheckpoint.setStatus(WorkflowNode.Status.SUSPENDED);
            crashCheckpoint.setErrorMessage(e.getMessage());
            crashCheckpoint.setDurationMs(durationMs);
            BoulderStateManager.saveCheckpoint(crashCheckpoint);
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
            log.warn("[DAG Engine] 迭代节点 {} 失败：目标变量不是 List。值: {}",
                    iterationNode.instanceId(), rawList);
            return; // 无法迭代，跳过
        }

        log.info("[DAG Engine] 迭代节点 {} 发现 {} 个元素。开始并行循环。",
                iterationNode.instanceId(), items.size());

        // 2. 并行遍历数组 — 每次循环启动一个独立的子引擎（虚拟线程并发）
        //    对标 Paperclip 的 maxConcurrentRuns，限制最大并发数防止 API 雪崩
        int maxConcurrency = Math.min(items.size(), 5); // 默认最多 5 个并发子引擎
        java.util.concurrent.Semaphore semaphore = new java.util.concurrent.Semaphore(maxConcurrency);
        java.util.List<java.util.concurrent.CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger failCount = new java.util.concurrent.atomic.AtomicInteger(0);

        for (int i = 0; i < items.size(); i++) {
            final int index = i;
            final Object currentItem = items.get(i);

            java.util.concurrent.CompletableFuture<Void> future = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    semaphore.acquire(); // 限流：最多 maxConcurrency 个子引擎同时运行
                    try {
                        // A. 创造局部隔离上下文（作用域链）
                        WorkflowContext childContext = new WorkflowContext(workflowId, parentContext);

                        // B. 将当前遍历到的 item 强行塞入局部上下文中
                        Map<String, Object> loopInject = new HashMap<>();
                        loopInject.put(iterationNode.getIteratorItemAlias(), currentItem);
                        childContext.commitNodeOutput(iterationNode.instanceId() + "_scope", loopInject);

                        log.info("  ├─ [Child Engine] 正在启动迭代 {}/{}（并行）", (index + 1), items.size());

                        // C. 递归调用 executeDagInternal，执行内部的子节点
                        executeDagInternal(iterationNode.getChildNodes(), workflowId, childContext);
                        successCount.incrementAndGet();
                    } finally {
                        semaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failCount.incrementAndGet();
                    log.warn("[DAG Engine] 迭代 {}/{} 被中断", (index + 1), items.size());
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    log.error("[DAG Engine] 迭代 {}/{} 失败: {}", (index + 1), items.size(), e.getMessage());
                }
            });

            futures.add(future);
        }

        // 3. 等待所有子引擎完成
        try {
            java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .get(10, java.util.concurrent.TimeUnit.MINUTES);
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("[DAG Engine] 迭代节点 {} 超时（10 分钟）", iterationNode.instanceId());
        } catch (Exception e) {
            log.warn("[DAG Engine] 迭代节点 {} 执行被中断: {}", iterationNode.instanceId(), e.getMessage());
        }

        log.info("[DAG Engine] 迭代节点 {} 完成：{}/{} 成功，{}/{} 失败",
                iterationNode.instanceId(), successCount.get(), items.size(), failCount.get(), items.size());
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
                log.debug("[DAG Engine] 依赖关系: {} → {}", parentId, node.instanceId());
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
        long suspended = nodes.stream().filter(n -> n.getStatus() == WorkflowNode.Status.SUSPENDED).count();

        System.out.printf("[DAG Engine] ── 工作流 '%s' 摘要 ──%n", workflowId);
        System.out.printf("[DAG Engine]   成功: %d | 失败: %d | 已跳过: %d | 挂起: %d | 总计: %d%n",
                success, failed, skipped, suspended, nodes.size());

        for (WorkflowNode node : nodes) {
            String icon = switch (node.getStatus()) {
                case SUCCESS -> "OK";
                case FAILED -> "FAIL";
                case SKIPPED -> "SKIP";
                case SUSPENDED -> "SUSP";
                case RUNNING -> "RUN";
                case PENDING -> "PEND";
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
                log.warn("[DAG Engine] Layer '{}' 处理事件失败: {}",
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
                log.warn("[DAG Engine] Layer '{}' 回调失败: {}",
                        layer.name(), e.getMessage());
            }
        }
    }

    /**
     * 条件表达式求值 — 借鉴 Langflow 的条件路由机制。
     * <p>
     * 支持的条件格式：
     * <ul>
     *   <li>{{node_id.key}} == 'value' — 字符串相等</li>
     *   <li>{{node_id.key}} != 'value' — 字符串不等</li>
     *   <li>{{node_id.key}} > 0 — 数值大于</li>
     *   <li>{{node_id.key}} <= 10 — 数值小于等于</li>
     *   <li>{{node_id.key}} contains 'text' — 包含检查</li>
     *   <li>{{node_id.key}} exists — 变量存在检查</li>
     * </ul>
     */
    private boolean evaluateCondition(String condition, Map<String, WorkflowNode> nodeMap, WorkflowContext context) {
        return ConditionEvaluator.evaluate(condition, nodeMap, context);
    }

    // ════════════════════════════════════════════════════════════════
    //  Semantic Core Dump — 现场冻结与认知核心转储
    //  借鉴 Linux Core Dump：进程崩溃时生成内存映像供事后分析
    // ════════════════════════════════════════════════════════════════

    /**
     * 生成语义核心转储 — 节点崩溃时冻结现场。
     * <p>
     * 收集节点崩溃那一刻的完整认知状态：
     * <ul>
     *   <li>异常信息（Exception 堆栈）</li>
     *   <li>节点的 LLM 上下文历史</li>
     *   <li>VariablePool 中该节点的变量表</li>
     *   <li>VFS 挂载状态</li>
     *   <li>Boulder 检查点历史</li>
     * </ul>
     * <p>
     * OS 类比：Linux 进程崩溃时内核生成 core 文件，
     * 包含进程的内存映像、寄存器状态、信号信息。
     *
     * @return dump 文件路径
     */
    private String generateSemanticCoreDump(WorkflowNode node, Exception error,
                                            WorkflowContext context, String workflowId, long durationMs) {
        String dumpDir = com.ouisani.aios.core.config.AiosPaths.workspaces() + "/" + workflowId.replace(" ", "_") + "/factory";
        String dumpFileName = "dump_" + node.instanceId() + "_" + System.currentTimeMillis() + ".aios";
        String dumpFilePath = dumpDir + "/" + dumpFileName;

        try {
            // 确保目录存在
            java.nio.file.Path dir = java.nio.file.Path.of(dumpDir);
            if (!java.nio.file.Files.exists(dir)) {
                java.nio.file.Files.createDirectories(dir);
            }

            // ── 端到端 Trace ID — 贯穿整个调用链 ──
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

            // 1. 崩溃信息
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

            // 2. 节点变量表（VariablePool 快照）
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

            // 3. 上游节点输出
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

            // 4. 节点蓝图（原始任务描述）
            dump.append("── NODE BLUEPRINT ──\n");
            dump.append("  BlueprintId: ").append(node.blueprintId()).append("\n");
            dump.append("  Role: ").append(node.role()).append("\n");
            dump.append("  Subscribe: ").append(node.subscribeTopic()).append("\n");
            dump.append("  Publish: ").append(node.publishTopic()).append("\n");
            dump.append("\n");

            // 5. Boulder 检查点历史
            dump.append("── BOULDER CHECKPOINT HISTORY ──\n");
            BoulderStateManager.loadCheckpoint(workflowId, node.instanceId()).ifPresentOrElse(
                    cp -> dump.append("  RetryCount: ").append(cp.getRetryCount())
                            .append(", LastStatus: ").append(cp.getStatus())
                            .append(", LastError: ").append(cp.getErrorMessage()).append("\n"),
                    () -> dump.append("  (no previous checkpoint)\n")
            );
            dump.append("\n");

            // 6. VFS 挂载状态
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

            // 1. 如果 AutoMedic 修复了代码，写入 VFS
            if (report.patchedCode() != null && !report.patchedCode().isBlank()
                    && report.patchedVfsPath() != null) {
                try {
                    VfsManager.instance().writeText(report.patchedVfsPath(), report.patchedCode());
                    log.info("[DAG Engine] 修复代码已写入 VFS: {}", report.patchedVfsPath());
                } catch (Exception e) {
                    log.warn("[DAG Engine] VFS 写入失败: {}", e.getMessage());
                }
            }

            // 2. 如果 AutoMedic 注入了反思提示，写入节点的上下文
            if (report.reflectionHint() != null && !report.reflectionHint().isBlank()) {
                context.commitNodeOutput(node.instanceId() + "_medic_hint",
                        Map.of("reflection", report.reflectionHint()));
                log.info("[DAG Engine] 反思提示已注入: {}", report.reflectionHint().substring(0, Math.min(80, report.reflectionHint().length())));
            }

            // 3. 重置节点状态为 PENDING（允许重新执行）
            node.setStatus(WorkflowNode.Status.PENDING);
            node.putOutput("_medic_healed", true);
            node.putOutput("_medic_diagnosis", report.diagnosis());

            // 4. 重新执行节点（递归调用 executeNode，但带自愈标记防止无限循环）
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
            executeNode(node, Map.of(node.instanceId(), node), context, workflowId);

            // 5. 检查重启后是否成功
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
     * 如果 Medic 发现节点失败的原因是"它根本没有能力完成这个任务"
     * （比如让一个只能写 Python 的 Agent 去编译 C++），
     * 那么最高阶的自愈就是动态改写执行图。
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

            // 1. 创建替代节点 A'
            String replacementId = failedNode.instanceId() + "_prime_" + System.currentTimeMillis() % 10000;
            WorkflowNode replacementNode = new WorkflowNode(
                    replacementId,
                    suggestedRole,  // 新角色（如 "C++ Coder" 替代 "Python Coder"）
                    failedNode.blueprintId(),
                    failedNode.userParams(),  // 继承用户参数
                    failedNode.subscribeTopic(),
                    failedNode.publishTopic(),
                    failedNode.executor()  // 保留执行器类型
            );

            // 继承上游依赖
            for (String depId : failedNode.getUpstreamDependencies()) {
                replacementNode.addDependency(depId);
            }

            // 继承条件
            if (failedNode.getCondition() != null) {
                replacementNode.setCondition(failedNode.getCondition());
            }

            // 标记原始节点为 REPLACED（新状态）
            failedNode.setStatus(WorkflowNode.Status.SKIPPED);
            failedNode.putOutput("_replaced_by", replacementId);
            failedNode.putOutput("_replacement_reason", report.diagnosis());

            // 2. 将替代节点加入 DAG
            nodeMap.put(replacementId, replacementNode);
            log.info("[DAG Engine] 拓扑突变：节点 '{}' 被 '{}' 替代（角色: {}→{}）",
                    failedNode.instanceId(), replacementId, failedNode.role(), suggestedRole);

            // 3. 更新下游节点的依赖关系
            for (WorkflowNode downstream : nodeMap.values()) {
                if (downstream.getUpstreamDependencies().contains(failedNode.instanceId())) {
                    downstream.addDependency(replacementId);
                    log.debug("[DAG Engine] 下游 '{}' 的依赖已更新：+{}", downstream.instanceId(), replacementId);
                }
            }

            // 4. 执行替代节点
            replacementNode.setStatus(WorkflowNode.Status.PENDING);
            replacementNode.putOutput("_is_mutation", true);
            replacementNode.putOutput("_replacing", failedNode.instanceId());

            // 将上游输出注入替代节点的上下文
            for (String depId : failedNode.getUpstreamDependencies()) {
                Map<String, Object> depOutput = context.getNodeMemorySnapshot(depId);
                if (depOutput != null) {
                    context.commitNodeOutput(replacementId, depOutput);
                }
            }

            executeNode(replacementNode, nodeMap, context, workflowId);

            // 5. 如果替代节点成功，将其输出映射到原始节点的输出
            if (replacementNode.getStatus() == WorkflowNode.Status.SUCCESS) {
                context.commitNodeOutput(failedNode.instanceId(), replacementNode.getOutputData());
                failedNode.setStatus(WorkflowNode.Status.SUCCESS); // 标记原始节点为成功（由替代完成）
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
     * OS 类比：Linux 的 SIGCONT — 进程被 SIGSTOP 挂起后，
     * 收到 SIGCONT 信号恢复执行。
     *
     * @param nodeId     节点 ID
     * @param workflowId 工作流 ID
     * @param newContext 修复后的新上下文（可为 null）
     * @return true=恢复成功, false=恢复失败
     */
    public boolean resumeNode(String nodeId, String workflowId, Map<String, Object> newContext) {
        // 从活跃工作流中查找节点
        WorkflowNode node = findNodeInActiveWorkflows(nodeId);
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

        // 注入新上下文
        if (newContext != null) {
            for (Map.Entry<String, Object> entry : newContext.entrySet()) {
                node.putOutput(entry.getKey(), entry.getValue());
            }
        }

        // 重置状态为 PENDING
        node.setStatus(WorkflowNode.Status.PENDING);
        int currentHealCount = 0;
        Object healCountObj = node.getOutputData().get("_heal_count");
        if (healCountObj instanceof Integer) {
            currentHealCount = (Integer) healCountObj;
        }
        node.putOutput("_heal_count", currentHealCount + 1);

        // 获取工作流上下文
        WorkflowContext context = activeContexts.get(workflowId);
        if (context == null) {
            log.warn("[DAG Engine] resumeNode: 工作流 '{}' 上下文未找到", workflowId);
            return false;
        }

        // 在虚拟线程中重新执行节点（异步，不阻塞调用者）
        Thread.startVirtualThread(() -> {
            try {
                executeNode(node, findNodeMapForWorkflow(workflowId), context, workflowId);
                log.info("[DAG Engine] 节点 '{}' 恢复执行完成: status={}", nodeId, node.getStatus());

                // ── 恢复成功后，重新触发下游节点 ──
                // 原始 DAG 执行时，下游节点因本节点 SUSPENDED 而被 SKIPPED。
                // 现在本节点已 SUCCESS，需要重新执行下游节点。
                if (node.getStatus() == WorkflowNode.Status.SUCCESS) {
                    Map<String, WorkflowNode> nodeMap = findNodeMapForWorkflow(workflowId);
                    if (nodeMap != null) {
                        for (WorkflowNode downstream : nodeMap.values()) {
                            if (downstream.getUpstreamDependencies().contains(nodeId)
                                    && downstream.getStatus() == WorkflowNode.Status.SKIPPED) {
                                log.info("[DAG Engine] 重新触发下游节点 '{}'（因上游 '{}' 恢复成功）",
                                        downstream.instanceId(), nodeId);
                                downstream.setStatus(WorkflowNode.Status.PENDING);
                                Thread.startVirtualThread(() -> {
                                    try {
                                        executeNode(downstream, nodeMap, context, workflowId);
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

            // ── 广播恢复完成事件给前端 ──
            // 前端需要知道节点恢复后的最终状态
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
    public boolean mutateTopology(String failedNodeId, String workflowId, String suggestedRole) {
        WorkflowNode failedNode = findNodeInActiveWorkflows(failedNodeId);
        if (failedNode == null) return false;

        WorkflowContext context = activeContexts.get(workflowId);
        Map<String, WorkflowNode> nodeMap = findNodeMapForWorkflow(workflowId);
        if (context == null || nodeMap == null) return false;

        // 构造一个最小 MedicalReport 用于 doMutateTopology
        AutoMedicAgent.MedicalReport report = new AutoMedicAgent.MedicalReport(
                AutoMedicAgent.Outcome.INCAPABLE,
                "RecoveryOrchestrator requested topology mutation",
                null, null, suggestedRole, null
        );

        return doMutateTopology(failedNode, report, nodeMap, context, workflowId);
    }

    /** 查找活跃工作流中的节点 */
    private WorkflowNode findNodeInActiveWorkflows(String nodeId) {
        for (Map<String, WorkflowNode> nodeMap : activeNodeMaps.values()) {
            WorkflowNode node = nodeMap.get(nodeId);
            if (node != null) return node;
        }
        return null;
    }

    /** 获取工作流的节点映射 */
    private Map<String, WorkflowNode> findNodeMapForWorkflow(String workflowId) {
        return activeNodeMaps.get(workflowId);
    }

    /**
     * 标记节点为 FAILED（统一入口）。
     */
    private void markNodeFailed(WorkflowNode node, Exception e, String workflowId, long durationMs) {
        node.setStatus(WorkflowNode.Status.FAILED);

        // Boulder 失败快照
        BoulderCheckpoint failCheckpoint = new BoulderCheckpoint();
        failCheckpoint.setWorkflowId(workflowId);
        failCheckpoint.setNodeId(node.instanceId());
        failCheckpoint.setStatus(WorkflowNode.Status.FAILED);
        failCheckpoint.setErrorMessage(e.getMessage());
        failCheckpoint.setDurationMs(durationMs);
        BoulderStateManager.loadCheckpoint(workflowId, node.instanceId()).ifPresent(past -> {
            failCheckpoint.setRetryCount(past.getRetryCount() + 1);
        });
        BoulderStateManager.saveCheckpoint(failCheckpoint);

        emitEvent(new GraphEngineEvent.GraphNodeEvent.NodeRunFailedEvent(
                workflowId, node.instanceId(), node.executor(),
                e.getMessage(), durationMs, null));
        invokeLayers(layer -> layer.onNodeRunEnd(node, e));

        log.error("[DAG Engine] Node '{}' FAILED ({}ms): {}", node.instanceId(), durationMs, e.getMessage());
    }

    // ════════════════════════════════════════════════════════════════
    //  节点恢复 — 供 RecoveryOrchestrator 在修复完成后调用
    //  借鉴 Linux 的 SIGCONT：挂起的进程收到恢复信号后继续执行
    // ════════════════════════════════════════════════════════════════

    /**
     * 恢复挂起的节点 — RecoveryOrchestrator 修复完成后的回调入口。
     * <p>
     * 流程：
     * 1. 检查节点是否处于 SUSPENDED 状态
     * 2. 如果修复了代码，写入 VFS
     * 3. 如果有反思提示，注入到节点上下文
     * 4. 重置节点状态为 PENDING
     * 5. 重新执行节点
     * 6. 如果是拓扑突变，创建替代节点并执行
     *
     * @param nodeId      挂起的节点 ID
     * @param report      AutoMedic 的诊断报告
     * @param workflowId  工作流 ID
     * @return true=恢复成功, false=恢复失败
     */
    public boolean resumeNode(String nodeId, AutoMedicAgent.MedicalReport report, String workflowId) {
        log.info("[DAG Engine] resumeNode() 被调用: nodeId={}, outcome={}", nodeId, report.outcome());

        // 从活跃工作流中查找节点
        WorkflowNode node = findNodeInActiveWorkflows(nodeId);
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
                // AutoMedic 修复成功 → 热重启
                return hotRestartNode(node, report, workflowId);
            }
            case INCAPABLE -> {
                // AutoMedic 判定节点无能 → 拓扑突变
                return mutateTopologyFromReport(node, report, workflowId);
            }
            case FAILED -> {
                // AutoMedic 修复失败 → 标记 FAILED
                log.error("[DAG Engine] resumeNode: AutoMedic 修复失败，节点 '{}' 标记为 FAILED: {}",
                        nodeId, report.diagnosis());
                node.setStatus(WorkflowNode.Status.FAILED);
                emitEvent(new GraphEngineEvent.GraphNodeEvent.NodeRunFailedEvent(
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

            // 1. 如果 AutoMedic 修复了代码，写入 VFS
            if (report.patchedCode() != null && !report.patchedCode().isBlank()
                    && report.patchedVfsPath() != null) {
                try {
                    VfsManager.instance().writeText(report.patchedVfsPath(), report.patchedCode());
                    log.info("[DAG Engine] 修复代码已写入 VFS: {}", report.patchedVfsPath());
                } catch (Exception e) {
                    log.warn("[DAG Engine] VFS 写入失败: {}", e.getMessage());
                }
            }

            // 2. 如果 AutoMedic 注入了反思提示，写入节点的上下文
            if (report.reflectionHint() != null && !report.reflectionHint().isBlank()) {
                node.putOutput("_medic_hint", report.reflectionHint());
                log.info("[DAG Engine] 反思提示已注入: {}",
                        report.reflectionHint().substring(0, Math.min(80, report.reflectionHint().length())));
            }

            // 3. 重置节点状态为 PENDING（允许重新执行）
            node.setStatus(WorkflowNode.Status.PENDING);
            node.putOutput("_medic_healed", true);
            node.putOutput("_medic_diagnosis", report.diagnosis());

            // 4. 防止无限重启
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

            // 5. 重新执行节点
            log.info("[DAG Engine] 节点 '{}' 原地复活！第 {} 次热重启", node.instanceId(), healCount + 1);

            // 获取工作流上下文
            WorkflowContext context = getActiveWorkflowContext(workflowId);
            if (context == null) {
                log.error("[DAG Engine] 找不到工作流 '{}' 的上下文", workflowId);
                node.setStatus(WorkflowNode.Status.FAILED);
                return false;
            }

            executeNode(node, Map.of(node.instanceId(), node), context, workflowId);
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

            // 创建替代节点 A'
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

            // 继承上游依赖
            for (String depId : failedNode.getUpstreamDependencies()) {
                replacementNode.addDependency(depId);
            }

            // 标记原始节点为 SKIPPED（被替代）
            failedNode.setStatus(WorkflowNode.Status.SKIPPED);
            failedNode.putOutput("_replaced_by", replacementId);
            failedNode.putOutput("_replacement_reason", report.diagnosis());

            log.info("[DAG Engine] 拓扑突变：节点 '{}' 被 '{}' 替代（角色: {}→{}）",
                    failedNode.instanceId(), replacementId, failedNode.role(), suggestedRole);

            // 执行替代节点
            replacementNode.setStatus(WorkflowNode.Status.PENDING);
            replacementNode.putOutput("_is_mutation", true);
            replacementNode.putOutput("_replacing", failedNode.instanceId());

            WorkflowContext context = getActiveWorkflowContext(workflowId);
            if (context == null) {
                log.error("[DAG Engine] 找不到工作流 '{}' 的上下文", workflowId);
                failedNode.setStatus(WorkflowNode.Status.FAILED);
                return false;
            }

            // 将上游输出注入替代节点的上下文
            for (String depId : failedNode.getUpstreamDependencies()) {
                Map<String, Object> depOutput = context.getNodeMemorySnapshot(depId);
                if (depOutput != null) {
                    context.commitNodeOutput(replacementId, depOutput);
                }
            }

            executeNode(replacementNode, Map.of(replacementId, replacementNode), context, workflowId);

            if (replacementNode.getStatus() == WorkflowNode.Status.SUCCESS) {
                context.commitNodeOutput(failedNode.instanceId(), replacementNode.getOutputData());
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

    // ── 活跃工作流管理 ──

    /**
     * 注册活跃工作流（在 executeDagInternal 开始时调用）。
     */
    public void registerActiveWorkflow(String workflowId, Map<String, WorkflowNode> nodeMap, WorkflowContext context) {
        activeNodeMaps.put(workflowId, nodeMap);
        activeContexts.put(workflowId, context);
    }

    /**
     * 注销活跃工作流（在 executeDagInternal 结束时调用）。
     */
    public void unregisterActiveWorkflow(String workflowId) {
        activeNodeMaps.remove(workflowId);
        activeContexts.remove(workflowId);
    }

    /**
     * 获取活跃工作流的上下文。
     */
    private WorkflowContext getActiveWorkflowContext(String workflowId) {
        return activeContexts.get(workflowId);
    }
}
