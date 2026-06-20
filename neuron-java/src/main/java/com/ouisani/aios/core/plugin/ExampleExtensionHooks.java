package com.ouisani.aios.core.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 示例扩展钩子 — 展示如何使用 @Extensible 机制。
 * <p>
 * 借鉴 Agent Zero 的扩展点设计，插件可以实现 ExtensionHook 接口
 * 来拦截或修改核心方法的行为。
 * <p>
 * 使用方式：
 * <pre>
 * ExtensibleHookRegistry.getInstance().register(new ExampleExtensionHooks.QueryLogger());
 * </pre>
 */
public class ExampleExtensionHooks {

    private static final Logger log = LoggerFactory.getLogger(ExampleExtensionHooks.class);

    /**
     * 查询日志钩子 — 记录每次 query 调用的耗时。
     */
    public static class QueryLogger implements ExtensionHook {
        private static final String POINT = "query";

        @Override public String extensionPoint() { return POINT; }
        @Override public Phase phase() { return Phase.BEFORE; }
        @Override public int priority() { return 10; }

        @Override
        public Object beforeHook(Object target, Map<String, Object> args) {
            String userMessage = (String) args.get("userMessage");
            log.info("[Extension:QueryLogger] query 开始, messageLen={}",
                    userMessage != null ? userMessage.length() : 0);
            args.put("_startTime", System.currentTimeMillis());
            return null; // 不短路
        }
    }

    /**
     * 查询耗时统计钩子 — after 阶段记录耗时。
     */
    public static class QueryTiming implements ExtensionHook {
        private static final String POINT = "query";

        @Override public String extensionPoint() { return POINT; }
        @Override public Phase phase() { return Phase.AFTER; }
        @Override public int priority() { return 20; }

        @Override
        public Object afterHook(Object target, Object result, Map<String, Object> args) {
            Object start = args.get("_startTime");
            if (start instanceof Long startTime) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.info("[Extension:QueryTiming] query 完成, 耗时={}ms, resultLen={}",
                        elapsed, result != null ? result.toString().length() : 0);
            }
            return result;
        }
    }

    /**
     * LLM 调用审计钩子 — 记录每次 LLM 调用的 prompt 长度。
     */
    public static class LlmAuditHook implements ExtensionHook {
        @Override public String extensionPoint() { return "llm_think"; }
        @Override public Phase phase() { return Phase.BEFORE; }
        @Override public int priority() { return 10; }

        @Override
        public Object beforeHook(Object target, Map<String, Object> args) {
            String prompt = (String) args.get("prompt");
            log.info("[Extension:LlmAudit] LLM 调用, promptLen={}",
                    prompt != null ? prompt.length() : 0);
            return null;
        }
    }
}
