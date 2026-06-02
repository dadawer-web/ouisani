package com.ouisani.aios.core.cgroup;

import com.ouisani.aios.core.VfsManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class CgroupManager {

    private static final Logger log = LoggerFactory.getLogger(CgroupManager.class);

    public static final ThreadLocal<CgroupNode> CURRENT_CGROUP = ThreadLocal.withInitial(() -> null);

    private static final class Holder {
        static final CgroupManager INSTANCE = new CgroupManager();
    }

    public static CgroupManager instance() {
        return Holder.INSTANCE;
    }

    private final Map<String, CgroupNode> nodes = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;

    private CgroupManager() {
    }

    public void init() {
        if (initialized) return;

        CgroupNode root = new CgroupNode("aios-root", 1_000_000);
        nodes.put("aios-root", root);

        CgroupNode agents = new CgroupNode("agents", 500_000, root);
        nodes.put("agents", agents);

        CgroupNode system = new CgroupNode("system", 200_000, root);
        nodes.put("system", system);

        CgroupNode tools = new CgroupNode("tools", 300_000, agents);
        nodes.put("tools", tools);

        initialized = true;
        log.info("[CgroupManager] Initialized with default hierarchy: root(1M) → agents(500K), system(200K), tools(300K)");
    }

    public CgroupNode createNode(String name, long quota) {
        return createNode(name, quota, null);
    }

    public CgroupNode createNode(String name, long quota, String parentName) {
        CgroupNode parent = parentName != null ? nodes.get(parentName) : null;
        CgroupNode node = new CgroupNode(name, quota, parent);
        nodes.put(name, node);
        log.info("[CgroupManager] Created node: name={}, quota={}, parent={}", name, quota, parentName);
        return node;
    }

    public CgroupNode getNode(String name) {
        return nodes.get(name);
    }

    public CgroupNode getOrCreateAgentCgroup(int agentId) {
        String name = "agent_" + agentId;
        return nodes.computeIfAbsent(name, n -> {
            CgroupNode parent = nodes.getOrDefault("agents", nodes.get("aios-root"));
            long defaultQuota = 50_000;
            log.info("[CgroupManager] Auto-created cgroup for agent_{}: quota={}", agentId, defaultQuota);
            return new CgroupNode(n, defaultQuota, parent);
        });
    }

    public void bindToCurrentThread(String cgroupName) {
        CgroupNode node = nodes.get(cgroupName);
        if (node != null) {
            CURRENT_CGROUP.set(node);
            log.debug("[CgroupManager] Bound cgroup '{}' to current thread", cgroupName);
        } else {
            log.warn("[CgroupManager] Cgroup '{}' not found, thread unbound", cgroupName);
        }
    }

    public void bindToCurrentThread(CgroupNode node) {
        CURRENT_CGROUP.set(node);
    }

    public void unbindFromCurrentThread() {
        CURRENT_CGROUP.remove();
    }

    public CgroupNode currentCgroup() {
        return CURRENT_CGROUP.get();
    }

    public long consumeTokensForCurrentThread(long amount) {
        CgroupNode node = CURRENT_CGROUP.get();
        if (node == null) return 0;
        node.consumeTokens(amount);
        return amount;
    }

    public long estimateAndConsumeForCurrentThread(String text) {
        CgroupNode node = CURRENT_CGROUP.get();
        if (node == null) return 0;
        return node.estimateAndConsume(text);
    }

    public boolean removeNode(String name) {
        CgroupNode removed = nodes.remove(name);
        if (removed != null) {
            log.info("[CgroupManager] Removed node: {}", name);
            return true;
        }
        return false;
    }

    public Set<String> nodeNames() {
        return Collections.unmodifiableSet(nodes.keySet());
    }

    public void printHierarchy() {
        System.out.println("  ┌─ Cgroup Hierarchy ──────────────────────────────");
        nodes.values().stream()
                .filter(n -> n.parent() == null)
                .forEach(n -> printNode(n, "  │ ", 0));
        System.out.println("  └─────────────────────────────────────────────────");
    }

    private void printNode(CgroupNode node, String prefix, int depth) {
        String indent = "  ".repeat(depth);
        System.out.printf("%s%s ├─ %s [quota=%d, consumed=%d, remaining=%d]%n",
                prefix, indent, node.name(), node.tokenQuota(), node.tokenConsumed(), node.tokenRemaining());
        nodes.values().stream()
                .filter(n -> n.parent() == node)
                .forEach(n -> printNode(n, prefix, depth + 1));
    }
}
