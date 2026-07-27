package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.importance.ImportanceBackwardPass;
import com.ouisani.aios.core.importance.ImportanceRecord;
import com.ouisani.aios.core.importance.ImportanceStore;
import com.ouisani.aios.core.ipc.VariablePool;
import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.role.RoleBlueprint;
import com.ouisani.aios.core.role.RoleBlueprintLoader;
import com.ouisani.aios.core.selection.RoleSelector;
import com.ouisani.aios.core.selection.SelectionPolicy;
import com.ouisani.aios.core.snapshot.CarryoverSection;
import com.ouisani.aios.core.snapshot.EnvironmentSnapshot;
import com.ouisani.aios.core.snapshot.EnvironmentSnapshotManager;
import com.ouisani.aios.core.snapshot.NodeOutputSection;
import com.ouisani.aios.core.state.BoulderCheckpoint;
import com.ouisani.aios.core.state.BoulderStateManager;
import com.ouisani.aios.core.tool.Port;
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

    /** 活跃工作流注册表 — 节点映射与上下文的共享状态 */
    private final WorkflowRegistry registry = new WorkflowRegistry();
    /** 自愈器 — 故障恢复与拓扑突变 */
    private final WorkflowHealer healer = new WorkflowHealer(this, registry);

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

    /** 包内可见 — 快照所有活跃工作流节点映射,供 OmnifactoryTaskQueueProvider 枚举。 */
    Map<String, Map<String, WorkflowNode>> snapshotActiveNodeMaps() {
        return registry.snapshotActiveNodeMaps();
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
        // 借鉴 DyLAN listwise agent team selection：把 manifest 的 selectionPolicy 塞进 context，
        // executeDagInternal 开头据此裁剪未选中 role 的节点。null = 未声明（零行为变化）。
        rootContext.setSelectionPolicy(manifest.selectionPolicy());
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

        // ── Listwise 角色裁剪（借鉴 DyLAN listwise agent team selection）──
        // 在 DAG 调度前根据 query 裁剪角色池：未选中 role 的节点标记 SKIPPED。
        // selectionPolicy 通过 WorkflowContext 传入（null = 未声明，零行为变化）。
        // try/catch 兜底：裁剪失败不阻断工作流，全选 fallback。
        applyListwiseSelection(nodes, workflowId, context);

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
        registry.registerActiveWorkflow(workflowId, nodeMap, context);

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
                        registry.unregisterActiveWorkflow(workflowId);
                        break;
                    }
                }
            });
        } else {
            // 注销活跃工作流
            registry.unregisterActiveWorkflow(workflowId);
        }
    }

    /**
     * 执行单个节点 — 包含完整的事件驱动生命周期。
     * 支持迭代节点（Iteration Node）的子引擎递归调度。
     */
    void executeNode(WorkflowNode node, Map<String, WorkflowNode> nodeMap,
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
                } else if (node.executor() != null && node.executor().startsWith("sub")) {
                    // ── 主从智能体树 — 借鉴 Apix main_agent_node/sub_agent_node ──
                    // executor="sub:tool_name" 挂载单一技能，或 "sub" 走纯 LLM 推理
                    log.info("[DAG Engine]   └─ 路由至 SubAgent（主从树打工人）");
                    taskAgent = new SubAgent(node, context);
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

            // 【EnvironmentSnapshot 双写】— 借鉴 mobilegym,节点成功后冻结完整执行环境
            try {
                NodeOutputSection nodeOut = (NodeOutputSection) new WorkflowContextCapturer(context).capture();
                CarryoverSection carry = (CarryoverSection) new CarryoverCapturer(context).capture();
                EnvironmentSnapshot envSnap = EnvironmentSnapshotManager.instance().capture(workflowId, nodeOut, carry);
                checkpoint.setCarryoverSnapshot(CarryoverStateSectionMapper.toMap(context.getCarryoverState()));
                checkpoint.setEnvironmentSnapshotId(envSnap.snapshotId());
                BoulderStateManager.saveCheckpoint(checkpoint);
            } catch (Exception ex) {
                log.warn("[DAG Engine] EnvironmentSnapshot 双写失败(成功路径): {}", ex.getMessage());
            }

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
            String dumpFilePath = healer.generateSemanticCoreDump(node, e, context, workflowId, durationMs);

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
                    WorkflowNode checkNode = registry.findNodeInActiveWorkflows(suspendedNodeId);
                    if (checkNode != null && checkNode.getStatus() == WorkflowNode.Status.SUSPENDED) {
                        log.warn("[DAG Engine] 节点 '{}' 恢复超时（120s），自动降级为 FAILED", suspendedNodeId);
                        checkNode.setStatus(WorkflowNode.Status.FAILED);
                        // 通知下游节点可以继续（不再无限等待）
                        WorkflowContext ctx = registry.getActiveWorkflowContext(suspendedWorkflowId);
                        if (ctx != null) {
                            ctx.commitNodeOutput(suspendedNodeId, Map.of(
                                    "status", "failed",
                                    "error", "Recovery timeout after 120s"
                            ));
                        }
                        // 重新触发下游 SKIPPED 节点（让它们自行判断是否可执行）
                        Map<String, WorkflowNode> timeoutNodeMap = registry.findNodeMapForWorkflow(suspendedWorkflowId);
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

            // 保存 Boulder 检查点(基础现场)
            BoulderCheckpoint crashCheckpoint = new BoulderCheckpoint();
            crashCheckpoint.setWorkflowId(workflowId);
            crashCheckpoint.setNodeId(node.instanceId());
            crashCheckpoint.setStatus(WorkflowNode.Status.SUSPENDED);
            crashCheckpoint.setOutputSnapshot(context.getNodeMemorySnapshot(node.instanceId()));
            crashCheckpoint.setErrorMessage(e.getMessage());
            crashCheckpoint.setDurationMs(durationMs);
            BoulderStateManager.saveCheckpoint(crashCheckpoint);

            // 【EnvironmentSnapshot 双写】— 借鉴 mobilegym,崩溃瞬间冻结完整执行环境供诊断
            try {
                NodeOutputSection nodeOut = (NodeOutputSection) new WorkflowContextCapturer(context).capture();
                CarryoverSection carry = (CarryoverSection) new CarryoverCapturer(context).capture();
                EnvironmentSnapshot envSnap = EnvironmentSnapshotManager.instance().capture(workflowId, nodeOut, carry);
                crashCheckpoint.setCarryoverSnapshot(CarryoverStateSectionMapper.toMap(context.getCarryoverState()));
                crashCheckpoint.setEnvironmentSnapshotId(envSnap.snapshotId());
                BoulderStateManager.saveCheckpoint(crashCheckpoint);
            } catch (Exception ex) {
                log.warn("[DAG Engine] EnvironmentSnapshot 双写失败(崩溃路径): {}", ex.getMessage());
            }
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
     * Listwise 角色裁剪 — 借鉴 DyLAN（arXiv:2310.02170）的 listwise agent team selection。
     * <p>
     * 在 DAG 调度前根据 query 裁剪角色池：{@link RoleSelector#select} 调 LLM 选 top-K role，
     * 未选中 role 的节点标记 {@link WorkflowNode.Status#SKIPPED}（保留 DAG 结构供 importance
     * 反向传播，SKIPPED 节点得 0 importance 符合预期）。
     * <p>
     * <b>触发条件</b>：{@code context.selectionPolicy} 非 null 且类型为 {@code listwise_top_k}。
     * null / none / 其他类型 → 零行为变化（向后兼容）。
     * <p>
     * <b>容错</b>：try/catch 兜底，任何失败（LLM 不可用 / 解析失败 / 加载 role 池失败）→ 全选 fallback，
     * 不阻断工作流主流程。{@link RoleSelector} 内部已有更细粒度的 fallback，此处是外层安全网。
     * <p>
     * <b>与 importance 互补</b>：importance 离线选角色池（跨 session 累积），
     * listwise 在线裁剪当前激活集（单次 query-adaptive）。
     *
     * @param nodes      工作流节点（status 待设置）
     * @param workflowId 工作流标识（作 query 代理，与 importance taskType 同源）
     * @param context    工作流上下文（含 selectionPolicy）
     */
    private void applyListwiseSelection(List<WorkflowNode> nodes, String workflowId, WorkflowContext context) {
        SelectionPolicy policy = context.getSelectionPolicy();
        if (policy == null || policy.isNone() || !policy.isListwiseTopK()) {
            return;  // 未声明或非 listwise，零行为变化
        }

        try {
            // 1. 收集 nodes 中所有 distinct role（去重）
            List<String> distinctRoles = new ArrayList<>();
            for (WorkflowNode n : nodes) {
                String role = n.role();
                if (role != null && !role.isBlank() && !distinctRoles.contains(role)) {
                    distinctRoles.add(role);
                }
            }
            if (distinctRoles.isEmpty()) {
                log.debug("[DAG Engine] listwise: 无 role 节点，跳过裁剪");
                return;
            }

            // 2. 加载 RoleBlueprint 池，构造候选列表
            //    role 无 blueprint 时用 fallback blueprint（description=null），LLM 按 role 名猜
            java.nio.file.Path rolesDir = java.nio.file.Path.of(com.ouisani.aios.core.config.AiosPaths.rolesDir());
            Map<String, RoleBlueprint> pool = RoleBlueprintLoader.loadAll(rolesDir);
            List<RoleBlueprint> candidates = new ArrayList<>();
            for (String role : distinctRoles) {
                RoleBlueprint bp = pool.get(role);
                candidates.add(bp != null ? bp : new RoleBlueprint(role, null, null));
            }

            // 3. RoleSelector 选 top-K（内部触发检查 + shuffle + LLM + 解析 + fallback）
            Set<String> selected = RoleSelector.select(workflowId, candidates, policy);

            // 4. 未选中 role 的节点标记 SKIPPED（保留 DAG 结构，importance 仍能传播）
            int skipped = 0;
            for (WorkflowNode node : nodes) {
                if (!selected.contains(node.role())) {
                    node.setStatus(WorkflowNode.Status.SKIPPED);
                    skipped++;
                }
            }

            log.info("[DAG Engine] listwise 裁剪: 候选 {} 角色 → 选中 {} (skipped {} 节点)",
                    distinctRoles.size(), selected, skipped);
            System.out.printf("[DAG Engine] listwise 裁剪: 候选 %d → 选中 %s (跳过 %d 节点)%n",
                    distinctRoles.size(), selected, skipped);
        } catch (Exception e) {
            log.warn("[DAG Engine] listwise 裁剪失败，全选 fallback（不阻断工作流）: {}", e.getMessage());
            // 不重抛：裁剪是优化，失败时所有节点保留原 status（不 SKIPPED），工作流正常执行
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

        // ── 借鉴 DyLAN：反向传播计算 role 贡献度，持久化供离线 team optimization ──
        // importance 是观测信号：try/catch 兜底，绝不阻断工作流主流程。
        // taskType 第一版用 workflowId 代理（同 workflowName 多次运行才能累积同 taskType 信号），
        // follow-up 在 WorkflowManifest 加显式 taskType 字段细化。
        try {
            ImportanceRecord rec = ImportanceBackwardPass.compute(
                    nodes, workflowId, workflowId /* taskType 第一版用 workflowId 代理 */);
            ImportanceStore.append(rec);
            log.info("[DAG Engine] importance 已记录: {}", rec.roleImportance());
        } catch (Exception e) {
            log.warn("[DAG Engine] importance 计算失败（不影响主流程）: {}", e.getMessage());
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
    void emitEvent(GraphEngineEvent event) {
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
    void invokeLayers(java.util.function.Consumer<GraphEngineLayer> action) {
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
    //  恢复与拓扑突变 API（委托给 WorkflowHealer）
    //  供 RecoveryOrchestrator 异步调用
    // ════════════════════════════════════════════════════════════════

    public boolean resumeNode(String nodeId, String workflowId, Map<String, Object> newContext) {
        return healer.resumeNode(nodeId, workflowId, newContext);
    }

    public boolean resumeNode(String nodeId, AutoMedicAgent.MedicalReport report, String workflowId) {
        return healer.resumeNode(nodeId, report, workflowId);
    }

    public boolean mutateTopology(String failedNodeId, String workflowId, String suggestedRole) {
        return healer.mutateTopology(failedNodeId, workflowId, suggestedRole);
    }

    public boolean forceFailNode(String nodeId) {
        return healer.forceFailNode(nodeId);
    }

    // ── 活跃工作流管理（委托给 WorkflowRegistry）──

    public void registerActiveWorkflow(String workflowId, Map<String, WorkflowNode> nodeMap, WorkflowContext context) {
        registry.registerActiveWorkflow(workflowId, nodeMap, context);
    }

    public void unregisterActiveWorkflow(String workflowId) {
        registry.unregisterActiveWorkflow(workflowId);
    }
}
