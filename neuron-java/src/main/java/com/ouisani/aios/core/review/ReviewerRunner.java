package com.ouisani.aios.core.review;

import com.ouisani.aios.core.cgroup.CgroupManager;
import com.ouisani.aios.core.cgroup.CgroupNode;
import com.ouisani.aios.core.permission.PermissionMode;
import com.ouisani.aios.core.tool.QueryEngine;
import com.ouisani.aios.core.tool.ToolSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Reviewer 派生器 — 镜像 {@link com.ouisani.aios.core.tool.AgentTool} L117-160 的
 * fresh-context + 虚拟线程 + 阻塞等待模式，但绕过 {@code AgentTool.call}（避免 ToolExecutionPipeline）。
 * <p>
 * 关键设计：
 * <ul>
 *   <li><b>Fresh context</b>：{@code new QueryEngine(...)} 是全新实例，不继承父 {@code historyCompressor} →
 *       盲性天然满足（只见到 reviewer prompt + artifact 路径，不见父 CoT）。</li>
 *   <li><b>只读锁定</b>：{@link PermissionMode#PLAN} 禁止写工具 / {@code agent} 工具 →
 *       reviewer 无法修改文件、无法递归 spawn 子 agent，无需 {@code DelegationGuard}。</li>
 *   <li><b>预算计入父</b>：绑定父 {@link CgroupNode}，reviewer token 消耗从父配额扣（堵住借审查绕过 OOM）。</li>
 *   <li><b>有界超时</b>：{@code CompletableFuture.get(timeoutMs)} 超时 → 返回 null → 降级 INCONCLUSIVE。</li>
 *   <li><b>Best-effort</b>：任何异常 → 返回 null，永不抛出（{@link ReviewGate} 外层再包一层 try/catch）。</li>
 * </ul>
 */
public final class ReviewerRunner {

    private static final Logger log = LoggerFactory.getLogger(ReviewerRunner.class);

    /** 测试覆盖入口：非 null 时替代真实 reviewer spawn。 */
    private static volatile ReviewerFn overrideForTesting = null;

    @FunctionalInterface
    public interface ReviewerFn {
        String apply(ToolSdk sdk, String parentAgentId, String workingDir,
                     String reviewerPrompt, long timeoutMs);
    }

    private ReviewerRunner() {}

    /**
     * 派生 fresh PLAN-mode reviewer 执行审查。
     *
     * @return reviewer 原始响应；超时/异常返回 null（best-effort）。
     */
    public static String run(ToolSdk sdk, String parentAgentId, String workingDir,
                             String reviewerPrompt, long timeoutMs) {
        if (overrideForTesting != null) {
            return overrideForTesting.apply(sdk, parentAgentId, workingDir, reviewerPrompt, timeoutMs);
        }
        return runReal(sdk, parentAgentId, workingDir, reviewerPrompt, timeoutMs);
    }

    private static String runReal(ToolSdk sdk, String parentAgentId, String workingDir,
                                  String reviewerPrompt, long timeoutMs) {
        String reviewerAgentId = "reviewer_" + parentAgentId + "_" + System.nanoTime();

        // 捕获父 cgroup → reviewer token 计入父预算（镜像 AgentTool L98/L130-133）
        CgroupNode parentCgroup = CgroupManager.instance().currentCgroup();

        CompletableFuture<String> future = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try {
                if (parentCgroup != null) {
                    CgroupManager.instance().bindToCurrentThread(parentCgroup);
                }
                // 关键：fresh QueryEngine + PLAN 模式（只读锁定）
                QueryEngine engine = new QueryEngine(
                        sdk, reviewerAgentId, workingDir, List.of(), PermissionMode.PLAN);
                String result = engine.query(reviewerPrompt);
                future.complete(result);
            } catch (Throwable t) {
                log.warn("[ReviewerRunner] reviewer 异常: {}", t.getMessage());
                future.completeExceptionally(t);
            } finally {
                try {
                    CgroupManager.instance().unbindFromCurrentThread();
                } catch (Throwable ignored) {
                    // best-effort 清理
                }
            }
        });

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            log.warn("[ReviewerRunner] reviewer 超时 ({}ms)，降级 INCONCLUSIVE", timeoutMs);
            future.cancel(true);
            return null;
        } catch (Throwable t) {
            log.warn("[ReviewerRunner] reviewer 等待失败: {}", t.getMessage());
            return null;
        }
    }

    // ── 测试覆盖入口 ──

    public static void setOverrideForTesting(ReviewerFn fn) {
        overrideForTesting = fn;
    }

    public static void clearOverrideForTesting() {
        overrideForTesting = null;
    }
}
