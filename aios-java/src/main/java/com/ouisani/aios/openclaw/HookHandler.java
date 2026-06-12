package com.ouisani.aios.openclaw;

import java.util.Map;

/**
 * 钩子处理函数接口 — 对标 OpenClaw 的 Hook 回调。
 * <p>
 * 钩子可以拦截和修改工具调用的输入/输出，
 * 或在生命周期事件（会话开始/结束）时执行副作用。
 */
@FunctionalInterface
public interface HookHandler {

    /**
     * 处理钩子事件。
     *
     * @param event    事件名称
     * @param payload  事件数据（可修改）
     * @return 修改后的事件数据，或原样返回以继续传递
     */
    Map<String, Object> handle(String event, Map<String, Object> payload);
}
