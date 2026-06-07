package com.ouisani.aios.core.tool;

import com.ouisani.aios.user.sdk.AiosSdk;

/**
 * 工具执行上下文 — 在工具执行期间提供对 AIOS 内核服务的访问。
 * <p>
 * 对标 Claude Code 的 ToolUseContext，但映射到 AIOS 的 SDK 体系。
 * <p>
 * OS 类比：相当于内核栈帧 — 保存当前系统调用的上下文信息。
 *
 * @param agentId  调用该工具的 Agent ID
 * @param sdk      AIOS SDK 实例，用于文件读写、LLM 调用等
 * @param workingDir 当前工作目录
 */
public record ToolContext(
        String agentId,
        AiosSdk sdk,
        String workingDir
) {}
