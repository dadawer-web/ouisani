package com.ouisani.aios.user.apps.omnifactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 执行限制层 — Dify 风格的 ExecutionLimitsLayer。
 * <p>
 * 限制工作流的最大执行步数和最大执行时间，防止失控的工作流无限运行。
 * 当超出限制时，抛出 {@link WorkflowExecutionLimitExceededException} 中止执行。
 *
 * @see GraphEngineLayer
 */
public class ExecutionLimitsLayer extends GraphEngineLayer {

    private static final Logger log = LoggerFactory.getLogger(ExecutionLimitsLayer.class);

    private final int maxSteps;
    private final long maxDurationMs;
    private long startTimeMs;
    private int completedSteps;

    /**
     * @param maxSteps       最大执行步数（节点执行次数），0 表示不限
     * @param maxDurationMs  最大执行时间（毫秒），0 表示不限
     */
    public ExecutionLimitsLayer(int maxSteps, long maxDurationMs) {
        this.maxSteps = maxSteps;
        this.maxDurationMs = maxDurationMs;
    }

    public ExecutionLimitsLayer(int maxSteps) {
        this(maxSteps, 0);
    }

    @Override
    public String name() {
        return "ExecutionLimits";
    }

    @Override
    public void onGraphStart(WorkflowContext context) {
        this.startTimeMs = System.currentTimeMillis();
        this.completedSteps = 0;
    }

    @Override
    public void onNodeRunStart(WorkflowNode node) {
        // 检查步数限制
        if (maxSteps > 0 && completedSteps >= maxSteps) {
            String msg = "Execution limit exceeded: maxSteps=" + maxSteps;
            log.error("[ExecutionLimits] {}", msg);
            throw new WorkflowExecutionLimitExceededException(msg);
        }

        // 检查时间限制
        if (maxDurationMs > 0) {
            long elapsed = System.currentTimeMillis() - startTimeMs;
            if (elapsed > maxDurationMs) {
                String msg = "Execution time limit exceeded: maxDuration=" + maxDurationMs + "ms, elapsed=" + elapsed + "ms";
                log.error("[ExecutionLimits] {}", msg);
                throw new WorkflowExecutionLimitExceededException(msg);
            }
        }
    }

    @Override
    public void onNodeRunEnd(WorkflowNode node, Exception error) {
        completedSteps++;
    }

    /**
     * 执行限制超出异常。
     */
    public static class WorkflowExecutionLimitExceededException extends RuntimeException {
        public WorkflowExecutionLimitExceededException(String message) {
            super(message);
        }
    }
}
