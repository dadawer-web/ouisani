package com.ouisani.aios.core.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * 委托安全守卫 — 借鉴 OMA (open-multi-agent) 的 delegate_to_agent 安全约束。
 * <p>
 * 堵住"通过委托绕过 OOM"的漏洞:
 * <ul>
 *   <li>深度限制 — 最多 3 层委托 (A→B→C→D 拒绝),防止无限递归烧 token</li>
 *   <li>防环检测 — 委托链中不允许出现重复 agentId (A→B→A 拒绝)</li>
 *   <li>自委托禁止 — A→A 直接拒绝</li>
 * </ul>
 * <p>
 * 使用 ThreadLocal 在虚拟线程间传递委托上下文:
 * 父 Agent 调用 {@link #enter} 获取上下文,子 Agent 虚拟线程调用 {@link #activate} 继承。
 * <p>
 * OS 类比:相当于 Linux 的进程资源限制 (RLIMIT_NPROC) + namespace 层级约束。
 */
public final class DelegationGuard {

    private static final Logger log = LoggerFactory.getLogger(DelegationGuard.class);

    /** 最大委托深度 — A(0)→B(1)→C(2)→D(3) 允许,D→E 拒绝 */
    public static final int MAX_DEPTH = 3;

    /** 当前线程的委托深度 */
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    /** 当前线程的委托链 (从根到当前节点的 agentId 集合,用于防环) */
    private static final ThreadLocal<Set<String>> CHAIN = ThreadLocal.withInitial(() -> new HashSet<>());

    private DelegationGuard() {}

    // ════════════════════════════════════════════════════════════════
    //  DelegationContext — 传递给子线程的不可变快照
    // ════════════════════════════════════════════════════════════════

    /**
     * 委托上下文快照 — 由父线程通过 {@link #enter} 创建,
     * 传递给子线程通过 {@link #activate} 激活。
     *
     * @param depth   子线程应激活的深度
     * @param chain   子线程应继承的委托链 (不含子 agentId,activate 时添加)
     * @param agentId 子 Agent 的 ID
     */
    public record DelegationContext(int depth, Set<String> chain, String agentId) {

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
     * 委托被拒绝时抛出 — 深度超限 / 环检测 / 自委托。
     */
    public static class DelegationException extends RuntimeException {
        public DelegationException(String message) {
            super(message);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  主入口
    // ════════════════════════════════════════════════════════════════

    /**
     * 检查并进入委托 — 在父 Agent 线程中调用。
     * <p>
     * 检查项:
     * <ol>
     *   <li>深度限制 — 当前深度 + 1 不能超过 {@link #MAX_DEPTH}</li>
     *   <li>自委托禁止 — parentAgentId 不能等于 childAgentId</li>
     *   <li>环检测 — childAgentId 不能已在委托链中</li>
     * </ol>
     *
     * @param parentAgentId 父 Agent ID
     * @param childAgentId  子 Agent ID
     * @return 委托上下文,传递给子线程 {@link #activate}
     * @throws DelegationException 如果委托被拒绝
     */
    public static DelegationContext enter(String parentAgentId, String childAgentId) {
        int currentDepth = DEPTH.get();
        Set<String> currentChain = CHAIN.get();

        // 1. 深度检查
        if (currentDepth >= MAX_DEPTH) {
            log.warn("[DelegationGuard] 委托深度超限: {} >= {} (parent={}, child={})",
                    currentDepth, MAX_DEPTH, parentAgentId, childAgentId);
            throw new DelegationException(String.format(
                    "委托深度超限: 当前 %d >= 最大 %d (parent=%s, child=%s)",
                    currentDepth, MAX_DEPTH, parentAgentId, childAgentId));
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

        // 构建子线程上下文:深度+1, 链=当前链+父agentId, 子agentId
        Set<String> childChain = new HashSet<>(currentChain);
        childChain.add(parentAgentId);

        log.debug("[DelegationGuard] 委托允许: depth {}→{}, chain={}, child={}",
                currentDepth, currentDepth + 1, childChain, childAgentId);

        return new DelegationContext(currentDepth + 1, childChain, childAgentId);
    }

    /**
     * 在子 Agent 线程中激活委托上下文。
     * <p>
     * 必须在子虚拟线程启动后第一时间调用,以继承父 Agent 的深度和委托链。
     *
     * @param ctx 由 {@link #enter} 返回的上下文
     */
    public static void activate(DelegationContext ctx) {
        DEPTH.set(ctx.depth());
        Set<String> chain = new HashSet<>(ctx.chain());
        chain.add(ctx.agentId());
        CHAIN.set(chain);
        log.debug("[DelegationGuard] 子线程委托上下文已激活: depth={}, chain={}",
                ctx.depth(), chain);
    }

    /**
     * 清理当前线程的委托上下文 — 在子 Agent 线程结束时调用。
     */
    public static void clear() {
        DEPTH.remove();
        CHAIN.remove();
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
}
