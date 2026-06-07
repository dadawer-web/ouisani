package com.ouisani.aios.user.apps.omnifactory;

import java.util.Map;

/**
 * 工作流节点实例 — 用户编排的蓝图的运行时实例。
 * <p>
 * OS 类比：相当于 Docker Container — 从蓝图 (Image) 实例化而来，
 * 填入了用户自定义参数，并挂载到 EventBus 的 Pub-Sub 总线上。
 * <p>
 * 每个节点实例是工作流 DAG 中的一个顶点，通过 subscribeTopic 和 publishTopic
 * 与上下游节点形成数据流边。
 *
 * @param instanceId     节点实例唯一标识（如 "spider_agent_1"）
 * @param role           节点角色描述（如 "数据采集"、"情感分析"）
 * @param blueprintId    引用的蓝图 ID
 * @param userParams     用户填写的自定义参数（key=参数名, value=用户值）
 * @param subscribeTopic 上游 EventBus topic（源头节点为空字符串）
 * @param publishTopic   下游 EventBus topic
 */
public record WorkflowNode(
        String instanceId,
        String role,
        String blueprintId,
        Map<String, String> userParams,
        String subscribeTopic,
        String publishTopic
) {}
