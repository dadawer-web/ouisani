package com.ouisani.aios.user.apps.devhouse;

import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.user.sdk.AbstractAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PM Agent — the Product Manager of the Auto Dev House.
 * <p>
 * This Agent acts as a top-tier product manager. It receives a project
 * brief, generates a PRD (Product Requirements Document) using the LLM,
 * and writes it to the VFS for downstream Dev Agents to consume.
 * <p>
 * Lifecycle:
 * <ol>
 *   <li>Initialize status to "INIT"</li>
 *   <li>Call LLM to generate PRD</li>
 *   <li>Write PRD to /devhouse/prd.txt</li>
 *   <li>Update status to "PRD_READY"</li>
 *   <li>Exit — job done</li>
 * </ol>
 */
public class PmAgent extends AbstractAgent {

    private static final Logger log = LoggerFactory.getLogger(PmAgent.class);

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
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");
        log.info("[PM Agent] Starting with priority=HIGH, budget=10000");

        // Step 1: Initialize status
        sdk.writeFile(agentId, "/devhouse/status", "INIT");
        System.out.println("  ├─ [PM Agent] Status → INIT");
        log.info("[PM Agent] Status initialized to INIT");

        // Step 2: Generate PRD via LLM
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

        // Step 3: Write PRD to VFS
        sdk.writeFile(agentId, "/devhouse/prd.txt", prdContent);
        System.out.println("  ├─ [PM Agent] PRD written to /devhouse/prd.txt");

        // Step 4: Update status
        sdk.writeFile(agentId, "/devhouse/status", "PRD_READY");
        System.out.println("  ├─ [PM Agent] Status → PRD_READY");

        // Step 5: Done
        System.out.println("  └─ [PM Agent] PRD generated and written to /devhouse/prd.txt. My job here is done.");
        log.info("[PM Agent] PRD generated and written to /devhouse/prd.txt. My job here is done.");

        // Exit — PM's job is complete
        exit();
    }

    @Override
    protected void onMessage(String msg) {
        log.info("[PM Agent] Received message (but PM has already exited): {}", msg);
    }

    /**
     * Fallback PRD template in case LLM is unavailable.
     */
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
