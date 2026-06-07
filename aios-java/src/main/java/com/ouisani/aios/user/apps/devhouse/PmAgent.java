package com.ouisani.aios.user.apps.devhouse;

import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.ipc.SemanticMemoryBlock;
import com.ouisani.aios.core.ipc.SharedMemoryManager;
import com.ouisani.aios.core.ipc.SignalType;
import com.ouisani.aios.user.sdk.AbstractAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PM Agent — 自动开发屋的产品经理。
 * <p>
 * OS 类比：相当于内核中负责资源分配和任务编排的调度器 —
 * 接收项目简报，使用 LLM 生成 PRD（产品需求文档），
 * 并写入 {@link SemanticMemoryBlock} 供下游 Agent（Coder、Reviewer）零拷贝共享。
 * <p>
 * <h3>IPC 模型：共享内存 + 硬件中断</h3>
 * PM Agent 不通过 VFS 文件轮询，而是：
 * <ol>
 *   <li>通过 {@code shmWrite()} 将 PRD 写入 SemanticMemoryBlock</li>
 *   <li>写入 ContextPointer 指向 PRD（神经 mmap）</li>
 *   <li>向项目组所有 Agent 广播 {@code SIG_CONTEXT_UPDATE} —
 *       硬件级中断，即时唤醒</li>
 * </ol>
 * <p>
 * 消除了 500ms 轮询循环。Coder Agent 收到中断后微秒级从共享内存读取 PRD —
 * 无 VFS I/O、无文本拷贝、无轮询。
 * <p>
 * 生命周期：
 * <ol>
 *   <li>在共享内存中初始化状态为 "INIT"</li>
 *   <li>调用 LLM 生成 PRD</li>
 *   <li>将 PRD 写入 SemanticMemoryBlock（神经 mmap）</li>
 *   <li>写入 ContextPointer（PRD 引用 + 摘要）</li>
 *   <li>更新共享内存状态为 "PRD_READY"</li>
 *   <li>广播 SIG_CONTEXT_UPDATE 到项目组</li>
 *   <li>退出 — 任务完成</li>
 * </ol>
 */
public class PmAgent extends AbstractAgent {

    private static final Logger log = LoggerFactory.getLogger(PmAgent.class);

    /** DevHouse 项目共享内存块 ID */
    private static final String SHM_BLOCK_ID = "devhouse_project";

    private static final String PRD_PROMPT = """
            你是一个顶级的后台产品经理。请简要写一份需求文档：用 Python 写一个极其极简、不依赖外部库的 HTTP Web 服务器，返回 'Hello from AIOS'。
            
            请按以下格式输出：
            1. 项目概述
            2. 功能需求
            3. 技术规格
            4. 验收标准
            """;

    public PmAgent() {
        super("pm_agent", ProcessPriority.HIGH, 10000);
    }

    @Override
    protected void onStart() {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  [PM Agent] Product Manager reporting for duty!             ║");
        System.out.println("  ║  Agent ID: pm_agent | Priority: HIGH | Budget: 10000       ║");
        System.out.println("  ║  IPC: SemanticMemoryBlock + SIG_CONTEXT_UPDATE              ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");
        log.info("[PM Agent] Starting with priority=HIGH, budget=10000, ipc=SHM+SIGNAL");

        // Step 1: Allocate the shared memory block (neural mmap)
        SemanticMemoryBlock block = sdk.shmGetBlock(SHM_BLOCK_ID);
        block.setOwnerPid(getPid());
        block.setMetadata("project", "devhouse");
        block.setMetadata("owner", "pm_agent");
        System.out.println("  ├─ [PM Agent] SemanticMemoryBlock allocated: " + SHM_BLOCK_ID);

        // Step 2: Initialize status
        sdk.shmWrite(agentId, SHM_BLOCK_ID, "status", "INIT");
        System.out.println("  ├─ [PM Agent] Status → INIT (written to SHM)");

        // Step 3: Generate PRD via LLM
        System.out.println("  ├─ [PM Agent] Generating PRD via LLM...");
        log.info("[PM Agent] Calling LLM for PRD generation...");

        String prdContent = sdk.think(agentId, PRD_PROMPT);

        if (prdContent == null || prdContent.isEmpty() || prdContent.startsWith("[SDK Error]")) {
            System.out.println("  ├─ [PM Agent] LLM call failed! Using fallback PRD template.");
            log.warn("[PM Agent] LLM call failed, using fallback PRD");
            prdContent = generateFallbackPrd();
        } else {
            System.out.println("  ├─ [PM Agent] PRD generated successfully (" + prdContent.length() + " chars)");
            log.info("[PM Agent] PRD generated: {} chars", prdContent.length());
        }

        // Step 4: Write PRD to shared memory (neural mmap — zero-copy for readers)
        // Also write to VFS for backward compatibility / human readability
        sdk.shmWrite(agentId, SHM_BLOCK_ID, "prd_content", prdContent);
        sdk.writeFile(agentId, "/devhouse/prd.txt", prdContent);
        System.out.println("  ├─ [PM Agent] PRD written to SHM block '" + SHM_BLOCK_ID + "' (key=prd_content)");

        // Step 5: Write ContextPointer — a lightweight reference to the PRD
        // This is the neural equivalent of a memory pointer: cheap to pass,
        // the reader dereferences it only when needed
        String prdSummary = prdContent.length() > 200
                ? prdContent.substring(0, 200) + "..."
                : prdContent;
        SharedMemoryManager.instance().putContextPointer(
                SHM_BLOCK_ID, "prd_pointer", "/devhouse/prd.txt", prdSummary, prdContent.hashCode());
        System.out.println("  ├─ [PM Agent] ContextPointer written: prd_pointer → /devhouse/prd.txt");

        // Step 6: Update status to PRD_READY
        sdk.shmWrite(agentId, SHM_BLOCK_ID, "status", "PRD_READY");
        sdk.writeFile(agentId, "/devhouse/status", "PRD_READY");
        System.out.println("  ├─ [PM Agent] Status → PRD_READY (written to SHM + VFS)");

        // Step 7: Broadcast SIG_CONTEXT_UPDATE to all agents in the project group
        // This is the hardware interrupt — zero polling, instant delivery
        sdk.broadcastSignal("agents", SignalType.SIG_CONTEXT_UPDATE);
        System.out.println("  ├─ [PM Agent] SIG_CONTEXT_UPDATE broadcast to all agents in 'agents' cgroup");
        System.out.println("  │  \u001B[32m[PM Agent] ⚡ Interrupt fired! Coder/Reviewer will wake instantly.\u001B[0m");

        // Step 8: Done
        System.out.println("  └─ [PM Agent] PRD deployed to shared memory. Interrupt sent. My job here is done.");
        log.info("[PM Agent] PRD deployed to SHM. SIG_CONTEXT_UPDATE broadcast. Exiting.");

        exit();
    }

    @Override
    protected void onMessage(String msg) {
        log.info("[PM Agent] Received message (but PM has already exited): {}", msg);
    }

    private String generateFallbackPrd() {
        return """
                # PRD: Minimal HTTP Web Server
                
                ## 1. 项目概述
                开发一个极其极简的 Python HTTP Web 服务器，不依赖任何外部库，
                仅使用 Python 标准库，返回 'Hello from AIOS'。
                
                ## 2. 功能需求
                - 监听 8080 端口
                - 接受 HTTP GET 请求
                - 返回 HTTP 200 响应，body 为 'Hello from AIOS'
                
                ## 3. 技术规格
                - 语言: Python 3.10+
                - 依赖: 无（仅使用 http.server 标准库）
                - 代码行数: < 20 行
                
                ## 4. 验收标准
                - curl http://localhost:8080 返回 'Hello from AIOS'
                - 无外部依赖
                - 代码简洁可读
                """;
    }
}
