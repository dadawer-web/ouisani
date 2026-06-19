package com.ouisani.aios.core.trace;

import com.ouisani.aios.core.cgroup.CgroupManager;
import com.ouisani.aios.core.cache.SemanticCacheManager;
import com.ouisani.aios.core.security.BpfManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;

/**
 * 追踪代理工厂 — 为 LLM 接口创建 JDK 动态代理，拦截所有方法调用。
 * <p>
 * 在方法调用前后注入横切关注点：
 * <ul>
 *   <li>REPLAY 模式：从时间机器回放历史调用结果（strace 重放）</li>
 *   <li>RECORD 模式：将调用请求/响应记录到 TraceManager（strace 录制）</li>
 *   <li>语义 eBPF 防火墙：拦截 think() 调用，评估 prompt 安全性</li>
 *   <li>语义缓存：对 think() 调用检查缓存命中，避免重复 LLM 请求</li>
 *   <li>Cgroup Token 计费：对 LLM 响应估算并消耗 Token 配额</li>
 * </ul>
 * <p>
 * OS 类比: strace + eBPF + page cache 的组合体，在内核边界拦截系统调用。
 *
 * @see TraceManager
 * @see TraceMode
 */
public class TraceProxyFactory {

    private static final Logger log = LoggerFactory.getLogger(TraceProxyFactory.class);

    @SuppressWarnings("unchecked")
    public static <T> T createProxy(T target, Class<T> interfaceType, String agentId) {
        return (T) Proxy.newProxyInstance(
                interfaceType.getClassLoader(),
                new Class<?>[]{interfaceType},
                new TraceInvocationHandler<>(target, interfaceType, agentId)
        );
    }

    private static class TraceInvocationHandler<T> implements InvocationHandler {

        private final T target;
        private final Class<T> interfaceType;
        private final String agentId;

        TraceInvocationHandler(T target, Class<T> interfaceType, String agentId) {
            this.target = target;
            this.interfaceType = interfaceType;
            this.agentId = agentId;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(target, args);
            }

            String methodName = method.getName();
            String eventType = interfaceType.getSimpleName() + "." + methodName;
            String requestPayload = serializeArgs(args);

            TraceManager traceManager = TraceManager.instance();
            TraceMode mode = traceManager.mode();

            if (mode == TraceMode.REPLAY) {
                String historicalResponse = traceManager.replayEvent(agentId, eventType, requestPayload);
                if (historicalResponse != null) {
                    System.out.printf("  ⏪ [Time Machine] 正在回放拦截的方法调用: %s → 返回缓存结果%n", methodName);
                    log.info("[Time Machine] 回放: agentId={}, method={}, eventType={}", agentId, methodName, eventType);
                    return deserializeResult(method, historicalResponse);
                }
                log.warn("[Time Machine] 回放未命中: agentId={}, method={}, 回退至真实调用", agentId, methodName);
                System.out.printf("  ⏪ [Time Machine] 方法 %s 回放未命中，执行真实调用%n", methodName);
            }

            // ── Semantic eBPF Firewall: intercept LLM calls ──
            if ("think".equals(methodName) && args != null && args.length > 0) {
                String prompt = args[0] != null ? args[0].toString() : "";
                if (!BpfManager.instance().evaluatePrompt(agentId, prompt)) {
                    throw new SecurityException("Prompt blocked by eBPF Policy");
                }
            }

            // ── Semantic Cache: bypass LLM if similar query cached ──
            if ("think".equals(methodName) && args != null && args.length > 0) {
                String prompt = args[0] != null ? args[0].toString() : "";
                String cached = SemanticCacheManager.instance().getCachedResponse(prompt);
                if (cached != null) {
                    return cached;
                }
            }

            Object result = method.invoke(target, args);

            // ── Semantic Cache: store LLM response for future hits ──
            if ("think".equals(methodName) && args != null && args.length > 0 && result instanceof String responseText) {
                String prompt = args[0] != null ? args[0].toString() : "";
                try {
                    float[] queryVector = ((com.ouisani.aios.core.llm.LlmProvider) target).embed(prompt);
                    SemanticCacheManager.instance().putCache(prompt, queryVector, responseText);
                } catch (Exception e) {
                    log.debug("[Semantic Cache] 缓存响应失败: {}", e.getMessage());
                }
            }

            if (mode == TraceMode.RECORD) {
                String responsePayload = serializeResult(result);
                traceManager.recordEvent(agentId, eventType, requestPayload, responsePayload);
                System.out.printf("  ⏺ [Time Machine] 正在记录拦截的方法调用: %s → 已录入%n", methodName);
                log.info("[Time Machine] 记录: agentId={}, method={}, eventType={}, responseLen={}",
                        agentId, methodName, eventType, responsePayload.length());
            }

            if (result instanceof String textResult) {
                long tokens = CgroupManager.instance().estimateAndConsumeForCurrentThread(textResult);
                if (tokens > 0) {
                    log.debug("[Cgroup] 为 {}.{} 消耗了 {} tokens (responseLen={})",
                            tokens, interfaceType.getSimpleName(), methodName, textResult.length());
                }
            }

            return result;
        }

        private String serializeArgs(Object[] args) {
            if (args == null || args.length == 0) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append("|");
                sb.append(args[i] != null ? args[i].toString() : "null");
            }
            return sb.toString();
        }

        private String serializeResult(Object result) {
            if (result == null) return "";
            return result.toString();
        }

        private Object deserializeResult(Method method, String serialized) {
            Class<?> returnType = method.getReturnType();
            if (returnType == void.class) return null;
            if (returnType == String.class) return serialized;
            if (returnType == int.class || returnType == Integer.class) return Integer.parseInt(serialized);
            if (returnType == long.class || returnType == Long.class) return Long.parseLong(serialized);
            if (returnType == boolean.class || returnType == Boolean.class) return Boolean.parseBoolean(serialized);
            if (returnType == double.class || returnType == Double.class) return Double.parseDouble(serialized);
            if (returnType == float.class || returnType == Float.class) return Float.parseFloat(serialized);
            return serialized;
        }
    }
}
