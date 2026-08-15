package com.ouisani.aios.core.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 委托安全守卫 — 借鉴 OMA (open-multi-agent) 的 delegate_to_agent 安全约束 + SoA 生命周期控制。
 * <p>
 * 堵住"通过委托绕过 OOM"的漏洞，三维生命周期控制（借鉴 SoA max_depth + 广度界）：
 * <ul>
 *   <li><b>深度限制</b>（maxDepth，SoA max_depth 等价）— 最多 N 层委托 (A→B→C→D 拒绝)，防止无限递归烧 token。</li>
 *   <li><b>广度限制</b>（maxSubagentsPerNode）— 单个 agent 最多派生 N 个子 Agent（per-agent ThreadLocal 计数），防止单点爆炸。</li>
 *   <li><b>全局总数限制</b>（maxTotalSpawns）— 作用域级派生总数帽（AtomicInteger），防止累积型 OOM。</li>
 *   <li><b>防环检测</b> — 委托链中不允许出现重复 agentId (A→B→A 拒绝)</li>
 *   <li><b>自委托禁止</b> — A→A 直接拒绝</li>
 * </ul>
 *
 * <h2>per-workflow 作用域隔离</h2>
 * 配置与计数器封装在 {@link DelegationScope} 中，通过 {@link ThreadLocal} 绑定到当前线程，
 * 并通过 {@link DelegationContext} 跨虚拟线程传播。每个工作流在启动时调用
 * {@link #bindScope(DelegationScope)} 绑定独立 scope，实现并发工作流间配置与计数器隔离：
 * <ul>
 *   <li>workflow A 把 maxDepth 调成 2 不影响 workflow B</li>
 *   <li>workflow A 的派生不消耗 workflow B 的 maxTotalSpawns 预算</li>
 *   <li>不再需要 resetTotalSpawns（每个 scope 自带 0 初始计数）</li>
 * </ul>
 * 未绑定 scope 时（测试、独立调用），{@link #currentScope()} 回退到 {@link #GLOBAL_DEFAULT_SCOPE}
 * （由 env {@code AIOS_MAX_SPAWN_DEPTH} / {@code AIOS_MAX_SUBAGENTS_PER_NODE} / {@code AIOS_MAX_TOTAL_SPAWNS} 初始化）。
 * {@link #configureMaxDepth(int)} 等 mutator 仅作用于 GLOBAL_DEFAULT_SCOPE，保留测试兼容；
 * 生产代码应优先用 {@link #createScope(String, int)} + {@link #bindScope(DelegationScope)}。
 *
 * <h2>使用模型</h2>
 * 使用 ThreadLocal 在虚拟线程间传递委托上下文:
 * 父 Agent 调用 {@link #enter} 获取上下文（含当前 scope 快照）,子 Agent 虚拟线程调用
 * {@link #activate} 继承深度/委托链/scope。{@link #activate} 会重置广度计数（子 agent 有独立 per-agent 广度预算）。
 *
 * <h2>与 AgentTool 的分层职责</h2>
 * <ul>
 *   <li>{@link #checkSpawnAllowed()} — 策略层预检查（读），AgentTool 据此决定降级还是派生</li>
 *   <li>{@link #enter} — 安全网层，做原子计数 + 兜底检查 + 自委托/防环</li>
 * </ul>
 *
 * <h2>已知限制</h2>
 * <ul>
 *   <li><b>后台子 agent</b>（AgentTool {@code runInBackground=true}、TaskTool）走 SandboxAgentTask
 *       用平台 {@code new Thread}，不调 {@link #activate}，不继承 scope → 回退 GLOBAL_DEFAULT_SCOPE。
 *       预先存在，非本次引入。</li>
 *   <li><b>checkSpawnAllowed 与 enter 对 totalSpawns 的 TOCTOU</b>：多个并发子 agent 可能略超
 *       maxTotalSpawns（±1-2）。属软帽，enter 内 incrementAndGet 原子不丢计数。</li>
 * </ul>
 *
 * <p>OS 类比:相当于 Linux cgroup 的 pids.max（进程数限制）+ RLIMIT_NPROC + namespace 层级约束，
 * 且每个 cgroup 目录（工作流）有独立配额。
 */
public final class DelegationGuard {

    private static final Logger log = LoggerFactory.getLogger(DelegationGuard.class);

    // ════════════════════════════════════════════════════════════════
    //  默认值常量
    // ════════════════════════════════════════════════════════════════

    /** 默认最大委托深度 — A(0)→B(1)→C(2)→D(3) 允许,D→E 拒绝 */
    public static final int DEFAULT_MAX_DEPTH = 3;

    /** 默认单 agent 最大子派生数 — 防止单点爆炸 */
    public static final int DEFAULT_MAX_SUBAGENTS_PER_NODE = 5;

    /** 默认作用域级最大派生总数 — 防止累积型 OOM */
    public static final int DEFAULT_MAX_TOTAL_SPAWNS = 50;

    // ════════════════════════════════════════════════════════════════
    //  DelegationScope — per-workflow 作用域（配置 + 计数器）
    // ════════════════════════════════════════════════════════════════

    /**
     * 委托作用域 — 一个工作流的独立配置与计数器单元。
     * <p>
     * 由 {@link #createScope(String, int)} 等工厂创建，通过 {@link #bindScope(DelegationScope)}
     * 绑定到母体虚拟线程，再通过 {@link DelegationContext} 传播到所有同步子 agent。
     * <p>
     * 字段为 volatile 非 final：仅 {@link #GLOBAL_DEFAULT_SCOPE} 需被 {@code configureMax*} 改写
     * （测试兼容）；workflow scope 按约定创建后不再修改。
     */
    public static final class DelegationScope {
        private final String workflowId;
        private volatile int maxDepth;
        private volatile int maxSubagentsPerNode;
        private volatile int maxTotalSpawns;
        private final AtomicInteger totalSpawns = new AtomicInteger(0);

        /** 包级构造器 — 强制走 {@link #createScope} 工厂 */
        DelegationScope(String workflowId, int maxDepth, int maxSubagentsPerNode, int maxTotalSpawns) {
            this.workflowId = workflowId;
            this.maxDepth = validatePositive(maxDepth, "scope.maxDepth");
            this.maxSubagentsPerNode = validatePositive(maxSubagentsPerNode, "scope.maxSubagentsPerNode");
            this.maxTotalSpawns = validatePositive(maxTotalSpawns, "scope.maxTotalSpawns");
        }

        public String workflowId() { return workflowId; }
        public int maxDepth() { return maxDepth; }
        public int maxSubagentsPerNode() { return maxSubagentsPerNode; }
        public int maxTotalSpawns() { return maxTotalSpawns; }
        public int totalSpawns() { return totalSpawns.get(); }

        /** 包级 — 供 {@link DelegationGuard#enter} 原子递增 */
        AtomicInteger totalSpawnsCounter() { return totalSpawns; }

        @Override
        public String toString() {
            return "DelegationScope{workflowId='" + workflowId + "', maxDepth=" + maxDepth
                    + ", maxSubagents=" + maxSubagentsPerNode + ", maxTotal=" + maxTotalSpawns
                    + ", totalSpawns=" + totalSpawns.get() + "}";
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  全局默认 scope + ThreadLocal 绑定
    // ════════════════════════════════════════════════════════════════

    /**
     * 全局默认作用域 — 未 bind 时的回退。由 env 初始化，
     * 被 {@code configureMax*} / {@code resetTotalSpawns} 改写（测试兼容）。
     */
    private static final DelegationScope GLOBAL_DEFAULT_SCOPE = new DelegationScope(
            "global-default",
            resolveFromEnv("AIOS_MAX_SPAWN_DEPTH", DEFAULT_MAX_DEPTH),
            resolveFromEnv("AIOS_MAX_SUBAGENTS_PER_NODE", DEFAULT_MAX_SUBAGENTS_PER_NODE),
            resolveFromEnv("AIOS_MAX_TOTAL_SPAWNS", DEFAULT_MAX_TOTAL_SPAWNS));

    /** 当前线程绑定的工作流 scope — null 时回退到 GLOBAL_DEFAULT_SCOPE */
    private static final ThreadLocal<DelegationScope> SCOPE = new ThreadLocal<>();

    /** 当前线程（agent）的委托深度 */
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    /** 当前线程（agent）已派生的子 agent 数 — per-agent 广度计数，activate 时重置 */
    private static final ThreadLocal<Integer> BREADTH = ThreadLocal.withInitial(() -> 0);

    /** 当前线程的委托链 (从根到当前节点的 agentId 集合,用于防环) */
    private static final ThreadLocal<Set<String>> CHAIN = ThreadLocal.withInitial(() -> new HashSet<>());

    /** 当前线程持有的已签名委托令牌。普通 ThreadLocal 避免未经显式 activate 的权限泄漏。 */
    private static final ThreadLocal<DelegationToken> TOKEN = new ThreadLocal<>();

    private DelegationGuard() {}

    // ════════════════════════════════════════════════════════════════
    //  DelegationContext — 传递给子线程的不可变快照（含 scope）
    // ════════════════════════════════════════════════════════════════

    /**
     * 委托上下文快照 — 由父线程通过 {@link #enter} 创建,
     * 传递给子线程通过 {@link #activate} 激活。
     *
     * @param depth   子线程应激活的深度
     * @param chain   子线程应继承的委托链 (不含子 agentId,activate 时添加)
     * @param agentId 子 Agent 的 ID
     * @param scope   子线程应继承的委托作用域（配置 + 计数器）
     */
    public record DelegationContext(int depth, Set<String> chain, String agentId,
                                    DelegationScope scope, DelegationToken token) {

        /** 保持既有四参数调用点兼容，并捕获当前线程令牌。 */
        public DelegationContext(int depth, Set<String> chain, String agentId, DelegationScope scope) {
            this(depth, chain, agentId, scope, null);
        }

        /**
         * 3 参便捷构造器 — 捕获当前线程的 {@link #currentScope()}。
         * 保留所有现有调用点（测试、AgentTool 内部）零改动。
         */
        public DelegationContext(int depth, Set<String> chain, String agentId) {
            this(depth, chain, agentId, currentScope(), null);
        }

        /** 防御性拷贝 */
        @Override
        public Set<String> chain() {
            return Set.copyOf(chain);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  DelegationException
    // ════════════════════════════════════════════════════════════════

    /**
     * 委托被拒绝时抛出 — 深度超限 / 广度超限 / 作用域总数超限 / 环检测 / 自委托。
     */
    public static class DelegationException extends RuntimeException {
        public DelegationException(String message) {
            super(message);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  作用域 API（per-workflow 隔离）
    // ════════════════════════════════════════════════════════════════

    /**
     * 当前线程绑定的作用域 — 未 bind 时回退到 {@link #GLOBAL_DEFAULT_SCOPE}。
     * 永不返回 null。
     */
    public static DelegationScope currentScope() {
        DelegationScope s = SCOPE.get();
        return s != null ? s : GLOBAL_DEFAULT_SCOPE;
    }

    /**
     * 全局默认作用域（未 bind 时的回退）。主要供测试重置配置使用。
     */
    public static DelegationScope globalDefaultScope() {
        return GLOBAL_DEFAULT_SCOPE;
    }

    /**
     * 绑定作用域到当前线程 — 由 OmniMotherAgent 在 onStart 开头调用。
     * 子 agent 通过 {@link #activate} 继承，无需手动 bind。
     *
     * @param scope 工作流作用域，不能为 null
     */
    public static void bindScope(DelegationScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        SCOPE.set(scope);
        log.info("[DelegationGuard] 作用域已绑定: {}", scope);
    }

    /**
     * 创建工作流作用域 — depth 显式，breadth/total 从 env 解析。
     * <p>
     * OmniMotherAgent 用此重载：depth 按节点数分档（≤5 → 2，复杂 → 3）或 env 覆盖，
     * breadth/total 透明地从 env（AIOS_MAX_SUBAGENTS_PER_NODE / AIOS_MAX_TOTAL_SPAWNS）解析。
     */
    public static DelegationScope createScope(String workflowId, int depth) {
        return new DelegationScope(workflowId, depth,
                resolveFromEnv("AIOS_MAX_SUBAGENTS_PER_NODE", DEFAULT_MAX_SUBAGENTS_PER_NODE),
                resolveFromEnv("AIOS_MAX_TOTAL_SPAWNS", DEFAULT_MAX_TOTAL_SPAWNS));
    }

    /** 创建工作流作用域 — 三维全部从 env 解析（通用快捷） */
    public static DelegationScope createScope(String workflowId) {
        return new DelegationScope(workflowId,
                resolveFromEnv("AIOS_MAX_SPAWN_DEPTH", DEFAULT_MAX_DEPTH),
                resolveFromEnv("AIOS_MAX_SUBAGENTS_PER_NODE", DEFAULT_MAX_SUBAGENTS_PER_NODE),
                resolveFromEnv("AIOS_MAX_TOTAL_SPAWNS", DEFAULT_MAX_TOTAL_SPAWNS));
    }

    /** 创建工作流作用域 — 三维全显式（测试用） */
    public static DelegationScope createScope(String workflowId, int depth, int breadth, int total) {
        return new DelegationScope(workflowId, depth, breadth, total);
    }

    // ════════════════════════════════════════════════════════════════
    //  配置读取 API（全部走 currentScope）
    // ════════════════════════════════════════════════════════════════

    /** 当前作用域最大委托深度 */
    public static int maxDepth() { return currentScope().maxDepth(); }

    /** 当前作用域单 agent 最大子派生数 */
    public static int maxSubagentsPerNode() { return currentScope().maxSubagentsPerNode(); }

    /** 当前作用域最大派生总数 */
    public static int maxTotalSpawns() { return currentScope().maxTotalSpawns(); }

    /** 当前作用域已累计的派生总数 */
    public static int totalSpawns() { return currentScope().totalSpawns(); }

    /** 当前线程（agent）已派生的子 agent 数 */
    public static int currentBreadth() { return BREADTH.get(); }

    // ════════════════════════════════════════════════════════════════
    //  配置 mutator — 仅作用于 GLOBAL_DEFAULT_SCOPE（测试兼容）
    // ════════════════════════════════════════════════════════════════

    /**
     * 配置最大委托深度 — 仅作用于 {@link #GLOBAL_DEFAULT_SCOPE}。
     * <p>
     * <b>已绑定工作流 scope 的线程不受影响</b>（其 currentScope 是 workflow scope）。
     * 生产代码应优先用 {@link #createScope(String, int)} + {@link #bindScope(DelegationScope)}。
     * 保留此方法主要为测试兼容（测试不 bind，直接改全局默认）。
     */
    public static void configureMaxDepth(int depth) {
        GLOBAL_DEFAULT_SCOPE.maxDepth = validatePositive(depth, "maxDepth");
        log.info("[DelegationGuard] GLOBAL_DEFAULT_SCOPE.maxDepth 已配置为 {}", GLOBAL_DEFAULT_SCOPE.maxDepth);
    }

    public static void configureMaxSubagentsPerNode(int breadth) {
        GLOBAL_DEFAULT_SCOPE.maxSubagentsPerNode = validatePositive(breadth, "maxSubagentsPerNode");
        log.info("[DelegationGuard] GLOBAL_DEFAULT_SCOPE.maxSubagentsPerNode 已配置为 {}",
                GLOBAL_DEFAULT_SCOPE.maxSubagentsPerNode);
    }

    public static void configureMaxTotalSpawns(int total) {
        GLOBAL_DEFAULT_SCOPE.maxTotalSpawns = validatePositive(total, "maxTotalSpawns");
        log.info("[DelegationGuard] GLOBAL_DEFAULT_SCOPE.maxTotalSpawns 已配置为 {}",
                GLOBAL_DEFAULT_SCOPE.maxTotalSpawns);
    }

    /**
     * 重置 {@link #GLOBAL_DEFAULT_SCOPE} 的派生总数计数器 — 仅影响未 bind 的线程。
     * <p>
     * 已绑定工作流 scope 的线程不受影响（其计数器在 scope 内，新 scope 自带 0 初始）。
     * 生产代码用 {@link #createScope} 创建新 scope 即可获得全新预算，无需调此方法。
     */
    public static void resetTotalSpawns() {
        GLOBAL_DEFAULT_SCOPE.totalSpawnsCounter().set(0);
    }

    // ════════════════════════════════════════════════════════════════
    //  派生预检查（策略层 — AgentTool 调用）
    // ════════════════════════════════════════════════════════════════

    /**
     * 派生预检查 — AgentTool 在派生前调用，判断是否允许派生。
     * <p>
     * 三维检查（按优先级，全部读当前作用域）：
     * <ol>
     *   <li>深度 — 当前深度 &gt;= {@link #maxDepth()} 返回 "depth"</li>
     *   <li>广度 — 当前 agent 已派生数 &gt;= {@link #maxSubagentsPerNode()} 返回 "breadth"</li>
     *   <li>总数 — 作用域派生总数 &gt;= {@link #maxTotalSpawns()} 返回 "total"</li>
     * </ol>
     * 返回 null 表示允许派生；返回非空原因字符串表示应降级为 in-context（借鉴 1 优雅降级精神）。
     *
     * @return null 允许；"depth"/"breadth"/"total" 表示超限原因
     */
    public static String checkSpawnAllowed() {
        DelegationScope s = currentScope();
        if (DEPTH.get() >= s.maxDepth()) return "depth";
        if (BREADTH.get() >= s.maxSubagentsPerNode()) return "breadth";
        if (s.totalSpawns() >= s.maxTotalSpawns()) return "total";
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    //  主入口（安全网层 — 兜底检查 + 原子计数）
    // ════════════════════════════════════════════════════════════════

    /**
     * 检查并进入委托 — 在父 Agent 线程中调用。
     * <p>
     * 检查项（兜底，防 AgentTool 漏检或绕过）:
     * <ol>
     *   <li>深度限制 — 当前深度 + 1 不能超过当前作用域 {@link #maxDepth()}</li>
     *   <li>自委托禁止 — parentAgentId 不能等于 childAgentId</li>
     *   <li>环检测 — childAgentId 不能已在委托链中</li>
     * </ol>
     * 通过后递增广度（per-agent）和当前作用域总数计数器。
     * 返回的 {@link DelegationContext} 携带当前作用域快照，供子线程 {@link #activate} 继承。
     *
     * @param parentAgentId 父 Agent ID
     * @param childAgentId  子 Agent ID
     * @return 委托上下文,传递给子线程 {@link #activate}
     * @throws DelegationException 如果委托被拒绝
     */
    public static DelegationContext enter(String parentAgentId, String childAgentId) {
        return enter(parentAgentId, childAgentId, Set.of());
    }

    public static DelegationContext enter(String parentAgentId, String childAgentId,
                                          Set<String> requestedCapabilities) {
        return enter(parentAgentId, childAgentId, requestedCapabilities,
                DelegationToken.DEFAULT_TTL_MS, DelegationToken.DEFAULT_MAX_CALLS);
    }

    /** Create a child delegation with an attenuated capability set and explicit budgets. */
    public static DelegationContext enter(String parentAgentId, String childAgentId,
                                          Set<String> requestedCapabilities,
                                          long ttlMs, int maxCalls) {
        return enter(parentAgentId, childAgentId, requestedCapabilities, null,
                ttlMs, maxCalls, null);
    }

    /**
     * Variant used when a parent has no token yet but its effective PermissionProfile
     * must become the root capability boundary for the newly-issued child token.
     * A null parentCapabilities preserves legacy unrestricted-root behavior.
     */
    public static DelegationContext enter(String parentAgentId, String childAgentId,
                                          Set<String> requestedCapabilities,
                                          long ttlMs, int maxCalls,
                                          Set<String> parentCapabilities) {
        return enter(parentAgentId, childAgentId, requestedCapabilities, null,
                ttlMs, maxCalls, parentCapabilities);
    }

    /**
     * Enter a child scope with an explicit memory asset loadout.  The set is
     * passed to the signed token primitive, so a later child can only further
     * attenuate it.  A null set preserves the legacy inherited boundary.
     */
    public static DelegationContext enter(String parentAgentId, String childAgentId,
                                          Set<String> requestedCapabilities,
                                          Set<String> requestedMemoryAssets,
                                          long ttlMs, int maxCalls,
                                          Set<String> parentCapabilities) {
        return enter(parentAgentId, childAgentId, requestedCapabilities,
                requestedMemoryAssets, ttlMs, maxCalls, parentCapabilities, null);
    }

    /** Preserve the tenant when an adapter has not installed a parent token yet. */
    public static DelegationContext enter(String parentAgentId, String childAgentId,
                                          Set<String> requestedCapabilities,
                                          Set<String> requestedMemoryAssets,
                                          long ttlMs, int maxCalls,
                                          Set<String> parentCapabilities,
                                          String tenantId) {

        int currentDepth = DEPTH.get();
        Set<String> currentChain = CHAIN.get();
        DelegationScope scope = currentScope();

        // 1. 深度检查（兜底）
        if (currentDepth >= scope.maxDepth()) {
            log.warn("[DelegationGuard] 委托深度超限: {} >= {} (parent={}, child={}, scope={})",
                    currentDepth, scope.maxDepth(), parentAgentId, childAgentId, scope.workflowId());
            throw new DelegationException(String.format(
                    "委托深度超限: 当前 %d >= 最大 %d (parent=%s, child=%s, scope=%s)",
                    currentDepth, scope.maxDepth(), parentAgentId, childAgentId, scope.workflowId()));
        }

        // 2. 自委托检查
        if (parentAgentId.equals(childAgentId)) {
            log.warn("[DelegationGuard] 自委托禁止: {}", parentAgentId);
            throw new DelegationException("自委托禁止: " + parentAgentId);
        }

        // 3. 环检测
        if (currentChain.contains(childAgentId)) {
            log.warn("[DelegationGuard] 委托环检测: {} 已在委托链中: {}", childAgentId, currentChain);
            throw new DelegationException(String.format(
                    "委托环检测: %s 已在委托链中: %s", childAgentId, currentChain));
        }

        // 构建子线程上下文:深度+1, 链=当前链+父agentId, 子agentId, 当前 scope
        DelegationToken parentToken = currentToken();
        if (parentToken == null) {
            parentToken = parentCapabilities == null
                    ? DelegationToken.root(parentAgentId, tenantId, scope.workflowId(), null)
                    : DelegationToken.rootWithCapabilities(parentAgentId, tenantId, scope.workflowId(), null,
                    parentCapabilities);
        }
        final DelegationToken childToken;
        try {
            int effectiveMaxCalls = maxCalls > 0 ? maxCalls
                    : parentToken.maxCalls() > 0 ? Math.min(parentToken.maxCalls(), DelegationToken.DEFAULT_MAX_CALLS)
                    : DelegationToken.DEFAULT_MAX_CALLS;
            childToken = requestedMemoryAssets == null
                    ? DelegationToken.issueChild(parentToken, childAgentId, requestedCapabilities,
                    ttlMs > 0 ? ttlMs : DelegationToken.DEFAULT_TTL_MS, effectiveMaxCalls)
                    : DelegationToken.issueChildWithMemoryAssets(parentToken, childAgentId,
                    requestedCapabilities, requestedMemoryAssets,
                    ttlMs > 0 ? ttlMs : DelegationToken.DEFAULT_TTL_MS, effectiveMaxCalls);
        } catch (IllegalArgumentException e) {
            log.warn("[DelegationGuard] delegation token rejected: parent={}, child={}, reason={}",
                    parentAgentId, childAgentId, e.getMessage());
            throw new DelegationException("delegation token invalid: " + e.getMessage());
        }

        Set<String> childChain = new HashSet<>(currentChain);
        childChain.add(parentAgentId);

        // 计数：per-agent 广度 +1，作用域总数 +1
        BREADTH.set(BREADTH.get() + 1);
        scope.totalSpawnsCounter().incrementAndGet();

        log.debug("[DelegationGuard] 委托允许: depth {}→{}, breadth={}, scope={} totalSpawns={}, child={}",
                currentDepth, currentDepth + 1, BREADTH.get(), scope.workflowId(),
                scope.totalSpawns(), childAgentId);

        return new DelegationContext(currentDepth + 1, childChain, childAgentId, scope, childToken);
    }

    /** Convenience overload for an exact memory loadout without profile bounds. */
    public static DelegationContext enter(String parentAgentId, String childAgentId,
                                          Set<String> requestedCapabilities,
                                          Set<String> requestedMemoryAssets,
                                          long ttlMs, int maxCalls) {
        return enter(parentAgentId, childAgentId, requestedCapabilities, requestedMemoryAssets,
                ttlMs, maxCalls, null);
    }

    /**
     * 在子 Agent 线程中激活委托上下文。
     * <p>
     * 必须在子虚拟线程启动后第一时间调用,以继承父 Agent 的深度、委托链和<b>作用域</b>。
     * 广度计数重置为 0 — 子 agent 有独立的 per-agent 广度预算。
     * 作用域继承自父线程（ctx.scope）— 子 agent 的派生计入同一工作流预算。
     *
     * @param ctx 由 {@link #enter} 返回的上下文
     */
    public static void activate(DelegationContext ctx) {
        if (ctx == null) throw new DelegationException("delegation context must not be null");
        if (ctx.token() != null) {
            if (!ctx.token().isValid()) {
                throw new DelegationException("delegation token expired or signature invalid");
            }
            if (!ctx.token().childAgentId().equals(ctx.agentId())) {
                throw new DelegationException("delegation token identity mismatch");
            }
        }
        DEPTH.set(ctx.depth());
        BREADTH.set(0);  // 子 agent 重置广度预算
        Set<String> chain = new HashSet<>(ctx.chain());
        chain.add(ctx.agentId());
        CHAIN.set(chain);
        if (ctx.token() != null) {
            TOKEN.set(ctx.token());
        } else {
            TOKEN.remove();
        }
        SCOPE.set(ctx.scope());  // 作用域跨线程传播（子 agent 计入同一工作流预算）
        log.debug("[DelegationGuard] 子线程委托上下文已激活: depth={}, chain={}, scope={}",
                ctx.depth(), chain, ctx.scope() == null ? "global-default" : ctx.scope().workflowId());
    }

    /**
     * 清理当前线程的委托上下文 — 在子 Agent 线程结束时调用。
     * 作用域绑定也被清除（回退到 GLOBAL_DEFAULT_SCOPE）。
     */
    public static void clear() {
        DEPTH.remove();
        BREADTH.remove();
        CHAIN.remove();
        SCOPE.remove();
        TOKEN.remove();
    }

    /**
     * 获取当前线程的委托深度。
     */
    public static int currentDepth() {
        return DEPTH.get();
    }

    /**
     * 获取当前线程的委托链快照。
     */
    public static Set<String> currentChain() {
        return Set.copyOf(CHAIN.get());
    }

    /** Current signed delegation token, or null for a top-level legacy Agent. */
    public static DelegationToken currentToken() {
        return TOKEN.get();
    }

    /** Current signed memory asset boundary, or an empty set for a legacy Agent. */
    public static Set<String> currentDelegableMemoryAssets() {
        DelegationToken token = currentToken();
        return token == null ? Set.of() : token.delegableMemoryAssets();
    }

    /** Check delegation capability and atomically consume one tool-call budget unit. */
    public static String checkToolAllowed(String toolName) {
        DelegationToken token = currentToken();
        if (token == null) return null;
        if (!token.isValid()) return "delegation_token_expired_or_invalid";
        if (!token.allowsTool(toolName)) return "delegation_capability_denied:" + toolName;
        if (!token.consumeCall()) return "delegation_call_budget_exhausted";
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    //  辅助
    // ════════════════════════════════════════════════════════════════

    private static int resolveFromEnv(String key, int defaultVal) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) return defaultVal;
        try {
            int parsed = Integer.parseInt(v.trim());
            return parsed > 0 ? parsed : defaultVal;
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private static int validatePositive(int val, String name) {
        if (val <= 0) {
            throw new IllegalArgumentException(name + " must be positive, got: " + val);
        }
        return val;
    }
}
