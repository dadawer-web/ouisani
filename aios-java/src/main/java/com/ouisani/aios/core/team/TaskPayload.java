package com.ouisani.aios.core.team;

import com.ouisani.aios.user.apps.omnifactory.WorkflowNode;

import java.util.concurrent.CompletableFuture;

/**
 * 任务派发载荷 — DAG 引擎与 Actor 模式的桥梁。
 * <p>
 * 包含图纸节点信息，以及一条供 Agent 完工后填写的"回执单"。
 * <p>
 * 核心设计：
 * <pre>
 *   WorkflowEngine.executeNode()
 *     → 创建 TaskPayload(node, new CompletableFuture<>())
 *     → 封装为 MailMessage(TASK_ASSIGN)
 *     → TeamRegistry.dispatch() → Agent.mailbox.deliver()
 *     → Agent.handleTask() 执行完毕后 completionReceipt.complete(null)
 *     → WorkflowEngine 通过 future.join() 等待回执
 * </pre>
 * <p>
 * 这样 DAG 引擎的 CompletableFuture 依赖链与 Actor 的异步消息机制完美融合：
 * - DAG 层面：上游节点全部 SUCCESS 后才唤醒下游
 * - Actor 层面：每个节点是一个异步任务，通过回执单通知完成
 *
 * @param node               工作流节点（包含角色、参数、执行器等）
 * @param completionReceipt  完工回执单 — Agent 执行完毕后 complete 此 Future
 */
public record TaskPayload(
        WorkflowNode node,
        CompletableFuture<Void> completionReceipt
) {}
