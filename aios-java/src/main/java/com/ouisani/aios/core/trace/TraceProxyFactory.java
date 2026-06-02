package com.ouisani.aios.core.trace;

import com.ouisani.aios.core.cgroup.CgroupManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;

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
                    System.out.printf("  ⏪ [Time Machine] Replaying intercepted call to method: %s → returning cached result%n", methodName);
                    log.info("[Time Machine] REPLAY: agentId={}, method={}, eventType={}", agentId, methodName, eventType);
                    return deserializeResult(method, historicalResponse);
                }
                log.warn("[Time Machine] REPLAY miss: agentId={}, method={}, falling through to real call", agentId, methodName);
                System.out.printf("  ⏪ [Time Machine] Replay miss for method: %s, executing real call%n", methodName);
            }

            Object result = method.invoke(target, args);

            if (mode == TraceMode.RECORD) {
                String responsePayload = serializeResult(result);
                traceManager.recordEvent(agentId, eventType, requestPayload, responsePayload);
                System.out.printf("  ⏺ [Time Machine] Recording intercepted call to method: %s → taped%n", methodName);
                log.info("[Time Machine] RECORD: agentId={}, method={}, eventType={}, responseLen={}",
                        agentId, methodName, eventType, responsePayload.length());
            }

            if (result instanceof String textResult) {
                long tokens = CgroupManager.instance().estimateAndConsumeForCurrentThread(textResult);
                if (tokens > 0) {
                    log.debug("[Cgroup] Consumed {} tokens for {}.{} (responseLen={})",
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
