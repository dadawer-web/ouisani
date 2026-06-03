package com.ouisani.aios.core.security;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BpfManager {

    private static final class Holder {
        static final BpfManager INSTANCE = new BpfManager();
    }

    private final ConcurrentHashMap<String, String> jsProbes = new ConcurrentHashMap<>();
    private volatile Context sharedJsContext;

    private BpfManager() {}

    public static BpfManager instance() {
        return Holder.INSTANCE;
    }

    public void attachProbe(String name, String jsCode) {
        jsProbes.put(name, jsCode);
        System.out.printf("  [eBPF] Probe '%s' attached (%d chars)%n", name, jsCode.length());
    }

    public void detachProbe(String name) {
        jsProbes.remove(name);
        System.out.printf("  [eBPF] Probe '%s' detached%n", name);
    }

    public boolean evaluatePrompt(String agentId, String prompt) {
        if (jsProbes.isEmpty()) return true;

        Context ctx = getOrCreateJsContext();

        for (Map.Entry<String, String> entry : jsProbes.entrySet()) {
            String probeName = entry.getKey();
            String jsCode = entry.getValue();

            try {
                Value bindings = ctx.getBindings("js");
                bindings.putMember("agentId", agentId != null ? agentId : "unknown");
                bindings.putMember("prompt", prompt != null ? prompt : "");

                Value result = ctx.eval("js", jsCode);

                if (result.isBoolean() && !result.asBoolean()) {
                    System.err.printf("  ╔══════════════════════════════════════════════════════════════╗%n");
                    System.err.printf("  ║  🛑 [eBPF Guard] Prompt from Agent '%s' BLOCKED by probe: %s%n",
                            agentId, probeName);
                    System.err.printf("  ║  Prompt: %s%n",
                            prompt != null && prompt.length() > 80
                                    ? prompt.substring(0, 80) + "..." : prompt);
                    System.err.printf("  ╚══════════════════════════════════════════════════════════════╝%n");
                    return false;
                }
            } catch (Exception e) {
                System.err.printf("  ⚠️ [eBPF] Probe '%s' execution error: %s%n", probeName, e.getMessage());
            }
        }

        return true;
    }

    private Context getOrCreateJsContext() {
        if (sharedJsContext == null) {
            synchronized (this) {
                if (sharedJsContext == null) {
                    sharedJsContext = Context.newBuilder("js")
                            .allowAllAccess(true)
                            .build();
                    System.out.println("  [eBPF] GraalVM JS context initialized");
                }
            }
        }
        return sharedJsContext;
    }

    public Map<String, String> getProbes() {
        return Collections.unmodifiableMap(jsProbes);
    }

    public int probeCount() {
        return jsProbes.size();
    }

    public void clearProbes() {
        jsProbes.clear();
        System.out.println("  [eBPF] All probes cleared");
    }
}
