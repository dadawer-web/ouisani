package com.ouisani.aios.user.apps.omnifactory;

import java.util.List;

/**
 * 智能体蓝图 (Agent Blueprint) — 母体生成的智能体模板。
 * <p>
 * OS 类比：相当于 Docker Image — 蓝图定义了智能体的"镜像"：
 * 它的职责描述、执行代码、以及需要用户在编排时填写的参数占位符。
 * 当 WorkflowEngine 拉起一个节点时，就是从蓝图实例化出一个运行中的"容器"。
 *
 * @param blueprintId   蓝图唯一标识（如 "spider_agent"）
 * @param description   职责描述
 * @param codePayload   母体生成的执行代码（Python / Shell 等）
 * @param requiredParams 该智能体需要用户在编排时填写的自定义参数名列表
 */
public record AgentBlueprint(
        String blueprintId,
        String description,
        String codePayload,
        List<String> requiredParams
) {}
