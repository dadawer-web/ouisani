package com.ouisani.aios.user.apps.omnifactory;

/**
 * DAG 引擎 Layer 中间件 — Dify 风格的 GraphEngineLayer。
 * <p>
 * Layer 是插入到 DAG 引擎执行管线中的拦截器，可以在以下时机执行自定义逻辑：
 * <ul>
 *   <li>{@link #onGraphStart()} — 工作流开始前（如：初始化资源、检查配额）</li>
 *   <li>{@link #onEvent(GraphEngineEvent)} — 每个事件发出时（如：持久化状态、发送到前端大屏）</li>
 *   <li>{@link #onNodeRunStart(WorkflowNode)} — 节点开始执行前（如：检查 LLM 配额、创建 OTel span）</li>
 *   <li>{@link #onNodeRunEnd(WorkflowNode, Exception)} — 节点执行结束后（如：扣减配额、结束 span）</li>
 *   <li>{@link #onGraphEnd(Exception)} — 工作流结束后（如：清理资源、发送最终通知）</li>
 * </ul>
 * <p>
 * 使用方式：
 * <pre>
 *   WorkflowEngine engine = WorkflowEngine.getInstance();
 *   engine.addLayer(new EventBusBridgeLayer());   // 事件桥接到前端大屏
 *   engine.addLayer(new PersistenceLayer());       // 持久化节点状态
 *   engine.addLayer(new ExecutionLimitsLayer(60)); // 最多 60 步
 * </pre>
 *
 * @see GraphEngineEvent
 * @see WorkflowEngine
 */
public abstract class GraphEngineLayer {

    /** Layer 名称，用于日志 */
    public String name() {
        return getClass().getSimpleName();
    }

    /**
     * 工作流开始执行前调用。
     * <p>
     * 适用于：初始化资源、检查前置条件、发送启动通知。
     */
    public void onGraphStart(WorkflowContext context) {
        // 默认空实现，子类按需覆盖
    }

    /**
     * 引擎发出的每一个事件都会调用此方法。
     * <p>
     * 这是 Layer 的核心方法，通过 instanceof 或 switch 模式匹配过滤感兴趣的事件。
     * <pre>
     *   if (event instanceof GraphEngineEvent.GraphNodeEvent.NodeRunSucceededEvent e) {
     *       // 处理节点成功事件
     *   }
     * </pre>
     *
     * @param event 引擎发出的事件
     */
    public void onEvent(GraphEngineEvent event) {
        // 默认空实现，子类按需覆盖
    }

    /**
     * 节点开始执行前调用。
     * <p>
     * 适用于：检查配额、创建追踪 span、记录开始时间。
     * 如果抛出异常，将阻止该节点执行。
     *
     * @param node 即将执行的节点
     */
    public void onNodeRunStart(WorkflowNode node) {
        // 默认空实现，子类按需覆盖
    }

    /**
     * 节点执行结束后调用。
     * <p>
     * 适用于：扣减配额、结束追踪 span、记录执行耗时。
     *
     * @param node  执行完毕的节点
     * @param error 执行中的异常，null 表示成功
     */
    public void onNodeRunEnd(WorkflowNode node, Exception error) {
        // 默认空实现，子类按需覆盖
    }

    /**
     * 工作流执行结束后调用（无论成功或失败）。
     * <p>
     * 适用于：清理资源、发送最终通知、记录总耗时。
     *
     * @param error 工作流级别的异常，null 表示成功
     */
    public void onGraphEnd(Exception error) {
        // 默认空实现，子类按需覆盖
    }
}
