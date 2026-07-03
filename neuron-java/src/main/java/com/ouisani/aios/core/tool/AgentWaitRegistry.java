package com.ouisani.aios.core.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Agent 等待注册表 — 实现操作系统级的 waitpid() 阻塞原语。
 * <p>
 * OS 类比：在 Unix 中，父进程调用 waitpid(pid) 会阻塞自己，
 * 直到子进程退出并返回 exit status。期间父进程释放 CPU（进入 S 状态）。
 * <p>
 * 本注册表在 JVM 层面实现等价语义：
 * <ul>
 *   <li>父 Agent 调用 {@link #waitForChild(String)} 阻塞当前虚拟线程</li>
 *   <li>子 Agent 完成后调用 {@link #completeChild(String, String)} 唤醒父 Agent</li>
 *   <li>支持超时，防止僵尸进程永久阻塞</li>
 * </ul>
 *
 * @see AgentTool
 */
public class AgentWaitRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentWaitRegistry.class);

    private static final class Holder {
        static final AgentWaitRegistry INSTANCE = new AgentWaitRegistry();
    }

    public static AgentWaitRegistry instance() {
        return Holder.INSTANCE;
    }

    /** 子 Agent ID → 完成 Future 的映射 */
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingChildren = new ConcurrentHashMap<>();

    /** 父 Agent ID → 其正在等待的子 Agent ID 集合（用于进程树追踪） */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> processTree = new ConcurrentHashMap<>();

    private AgentWaitRegistry() {
    }

    /**
     * 注册一个子 Agent，使其可被父 Agent 等待。
     *
     * @param childAgentId  子 Agent ID
     * @param parentAgentId 父 Agent ID
     */
    public void registerChild(String childAgentId, String parentAgentId) {
        pendingChildren.put(childAgentId, new CompletableFuture<>());
        processTree.computeIfAbsent(parentAgentId, k -> new ConcurrentHashMap<>())
                .put(childAgentId, System.currentTimeMillis());
        log.info("[WaitRegistry] 子 Agent 已注册: child={}, parent={}", childAgentId, parentAgentId);
    }

    /**
     * 父 Agent 阻塞等待子 Agent 完成 — 对标 waitpid()。
     * <p>
     * 当前虚拟线程会被挂起（释放 CPU），直到子 Agent 调用 completeChild() 或超时。
     *
     * @param childAgentId 要等待的子 Agent ID
     * @param timeoutMs    超时时间（毫秒），0 表示无限等待
     * @return 子 Agent 的输出结果，超时返回 null
     */
    public String waitForChild(String childAgentId, long timeoutMs) {
        CompletableFuture<String> future = pendingChildren.get(childAgentId);
        if (future == null) {
            log.warn("[WaitRegistry] 未找到子 Agent: {}（可能已完成或未注册）", childAgentId);
            return null;
        }

        try {
            log.info("[WaitRegistry] 父 Agent 正在等待子 Agent 完成 (waitpid): child={}, timeoutMs={}",
                    childAgentId, timeoutMs);

            if (timeoutMs > 0) {
                return future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } else {
                return future.get();
            }
        } catch (TimeoutException e) {
            log.warn("[WaitRegistry] 等待子 Agent 超时: child={}", childAgentId);
            return null;
        } catch (Exception e) {
            log.error("[WaitRegistry] 等待子 Agent 异常: child={}, error={}", childAgentId, e.getMessage());
            return null;
        } finally {
            pendingChildren.remove(childAgentId);
        }
    }

    /**
     * 子 Agent 完成执行，唤醒等待的父 Agent。
     *
     * @param childAgentId 子 Agent ID
     * @param result       子 Agent 的输出结果
     */
    public void completeChild(String childAgentId, String result) {
        CompletableFuture<String> future = pendingChildren.get(childAgentId);
        if (future != null) {
            future.complete(result);
            log.info("[WaitRegistry] 子 Agent 已完成，父 Agent 已被唤醒: child={}, resultLen={}",
                    childAgentId, result != null ? result.length() : 0);
        } else {
            log.debug("[WaitRegistry] 子 Agent 完成但无等待者: {}", childAgentId);
        }

        // 从进程树中移除
        processTree.values().forEach(children -> children.remove(childAgentId));
    }

    /**
     * 子 Agent 执行失败，唤醒等待的父 Agent 并传递错误。
     *
     * @param childAgentId 子 Agent ID
     * @param error        错误信息
     */
    public void failChild(String childAgentId, String error) {
        CompletableFuture<String> future = pendingChildren.get(childAgentId);
        if (future != null) {
            future.complete("ERROR: " + error);
            log.warn("[WaitRegistry] 子 Agent 失败，父 Agent 已被唤醒: child={}, error={}",
                    childAgentId, error);
        }
        processTree.values().forEach(children -> children.remove(childAgentId));
    }

    /**
     * 获取指定父 Agent 的所有活跃子 Agent。
     *
     * @param parentAgentId 父 Agent ID
     * @return 子 Agent ID 集合
     */
    public java.util.Set<String> getActiveChildren(String parentAgentId) {
        ConcurrentHashMap<String, Long> children = processTree.get(parentAgentId);
        return children != null ? children.keySet() : java.util.Set.of();
    }

    /**
     * 检查指定子 Agent 是否仍在运行。
     *
     * @param childAgentId 子 Agent ID
     * @return true 如果子 Agent 已注册但尚未完成
     */
    public boolean isChildRunning(String childAgentId) {
        CompletableFuture<String> future = pendingChildren.get(childAgentId);
        return future != null && !future.isDone();
    }
}
