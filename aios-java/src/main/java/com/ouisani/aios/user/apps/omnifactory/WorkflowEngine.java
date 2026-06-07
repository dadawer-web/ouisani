package com.ouisani.aios.user.apps.omnifactory;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.user.bin.AiosAppManager;
import com.ouisani.aios.user.sdk.AiosSdk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流执行引擎 — 单例，负责将 WorkflowManifest 编排为运行中的 Agent 进程。
 * <p>
 * OS 类比：相当于 systemd + docker-compose up — 引擎遍历工作流清单中的节点，
 * 从蓝图注册表中取出代码模板，注入用户参数和 EventBus topic 配置，
 * 然后通过 TaskScheduler / AiosAppManager 为每个节点拉起一个隔离的 Agent 进程。
 * <p>
 * 执行流程：
 * <pre>
 *   WorkflowEngine.executeWorkflow(manifest, blueprintRegistry)
 *     ├─ 遍历 manifest.nodes
 *     │   ├─ 从 blueprintRegistry 取出蓝图
 *     │   ├─ 将 userParams + subscribeTopic + publishTopic 组装为环境变量
 *     │   ├─ 将蓝图代码写入 VFS: /factory/{instanceId}.py
 *     │   └─ 通过 AiosAppManager 拉起 Agent 进程
 *     └─ 打印编排完成日志
 * </pre>
 *
 * @see WorkflowManifest
 * @see AgentBlueprint
 * @see WorkflowNode
 */
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private static final class Holder {
        static final WorkflowEngine INSTANCE = new WorkflowEngine();
    }

    public static WorkflowEngine getInstance() {
        return Holder.INSTANCE;
    }

    private WorkflowEngine() {
        log.info("[OmniFactory] Workflow Engine initialized. Ready to orchestrate user-defined AI agents via EventBus.");
        System.out.println("[OmniFactory] Workflow Engine initialized. Ready to orchestrate user-defined AI agents via EventBus.");
    }

    /**
     * 执行工作流 — 将清单中的节点编排为运行中的 Agent 进程。
     * <p>
     * 对于每个节点：
     * <ol>
     *   <li>从蓝图注册表取出代码模板</li>
     *   <li>将用户参数和 EventBus topic 注入为环境变量前缀</li>
     *   <li>将最终代码写入 VFS</li>
     *   <li>通过 AiosAppManager 动态拉起隔离的 Agent 进程</li>
     * </ol>
     *
     * @param manifest           工作流总清单
     * @param blueprintRegistry  蓝图注册表（blueprintId → AgentBlueprint）
     */
    public void executeWorkflow(WorkflowManifest manifest, Map<String, AgentBlueprint> blueprintRegistry) {
        System.out.printf("[OmniFactory] Executing workflow '%s' with %d nodes...%n",
                manifest.workflowName(), manifest.nodes().size());
        log.info("[OmniFactory] Executing workflow '{}': {} nodes", manifest.workflowName(), manifest.nodes().size());

        AiosSdk sdk = AiosSdk.getInstance();
        List<WorkflowNode> nodes = manifest.nodes();

        // ── 第一遍：为每个节点写入代码到 VFS ──
        for (WorkflowNode node : nodes) {
            AgentBlueprint blueprint = blueprintRegistry.get(node.blueprintId());
            if (blueprint == null) {
                log.error("[OmniFactory] Blueprint '{}' not found for node '{}'. Skipping.",
                        node.blueprintId(), node.instanceId());
                System.out.printf("[OmniFactory]   ⚠ Blueprint '%s' not found for node '%s'. Skipping.%n",
                        node.blueprintId(), node.instanceId());
                continue;
            }

            // 将用户参数注入到代码头部作为环境变量声明
            String enrichedCode = injectParamsAndTopics(blueprint.codePayload(), node);
            String vfsPath = "/factory/" + node.instanceId() + ".py";
            sdk.writeFile("workflow_engine", vfsPath, enrichedCode);

            System.out.printf("[OmniFactory]   ├─ Node '%s' code written → %s (%d chars)%n",
                    node.instanceId(), vfsPath, enrichedCode.length());
            log.info("[OmniFactory] Node '{}' code written: {} chars", node.instanceId(), enrichedCode.length());
        }

        // ── 第二遍：生成 master 启动脚本并拼装 AppManifest ──
        StringBuilder masterScript = new StringBuilder("#!/bin/bash\n");
        for (WorkflowNode node : nodes) {
            AgentBlueprint blueprint = blueprintRegistry.get(node.blueprintId());
            if (blueprint == null) continue;

            // 构建环境变量前缀
            StringBuilder envPrefix = new StringBuilder();
            if (!node.subscribeTopic().isEmpty()) {
                envPrefix.append("SUBSCRIBE_TOPIC=").append(node.subscribeTopic()).append(" ");
            }
            if (!node.publishTopic().isEmpty()) {
                envPrefix.append("PUBLISH_TOPIC=").append(node.publishTopic()).append(" ");
            }
            // 注入用户自定义参数
            for (Map.Entry<String, String> param : node.userParams().entrySet()) {
                envPrefix.append("PARAM_").append(param.getKey().toUpperCase()).append("=").append(param.getValue()).append(" ");
            }

            masterScript.append(envPrefix)
                    .append("python /factory/").append(node.instanceId()).append(".py &\n");
        }
        masterScript.append("wait\n");

        sdk.writeFile("workflow_engine", "/factory/master_launch.sh", masterScript.toString());

        // 动态拼接 AppManifest
        StringBuilder manifestStr = new StringBuilder();
        manifestStr.append("APP_NAME ").append(manifest.workflowName()).append("\n");
        for (WorkflowNode node : nodes) {
            manifestStr.append(String.format("SPAWN %s 1%n", node.instanceId()));
        }
        manifestStr.append("BUDGET 20000\n");
        manifestStr.append("MOUNT /factory:/factory\n");
        manifestStr.append("MOUNT /shared:/shared\n");
        manifestStr.append("ENTRYPOINT sh /factory/master_launch.sh");

        System.out.println("[OmniFactory] AppManifest compiled:");
        for (String line : manifestStr.toString().split("\n")) {
            System.out.println("[OmniFactory]   " + line);
        }

        // ── 热装载 ──
        AiosAppManager.installAndRun(manifestStr.toString());

        System.out.printf("[OmniFactory] Workflow '%s' launched. %d agent processes dispatched.%n",
                manifest.workflowName(), nodes.size());
        log.info("[OmniFactory] Workflow '{}' launched: {} agents dispatched", manifest.workflowName(), nodes.size());
    }

    /**
     * 将用户参数和 EventBus topic 注入到代码头部。
     * <p>
     * 在 Python 代码头部插入 os.environ 读取逻辑，使节点进程
     * 可以通过 os.getenv() 获取 SUBSCRIBE_TOPIC、PUBLISH_TOPIC
     * 和 PARAM_* 自定义参数。
     *
     * @param code 原始蓝图代码
     * @param node 节点实例（包含 topic 和用户参数）
     * @return 注入参数读取逻辑后的代码
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
}
