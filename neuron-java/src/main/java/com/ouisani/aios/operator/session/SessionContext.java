package com.ouisani.aios.operator.session;

import java.util.List;

/**
 * 会话上下文 — buildSessionContext 的返回值。
 * <p>
 * 对标 OpenClaw 的 SessionContext。
 *
 * @param messages      发送给 LLM 的消息列表
 * @param thinkingLevel 当前思考级别
 * @param provider      当前 LLM Provider
 * @param modelId       当前模型 ID
 */
public record SessionContext(
        List<AgentMessage> messages,
        String thinkingLevel,
        String provider,
        String modelId
) {}
