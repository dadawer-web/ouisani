package com.ouisani.aios.core.a2a;

import java.util.*;

/**
 * A2A 节点描述符 — 描述联邦中一个节点的身份和能力。
 * <p>
 * 借鉴 Agent Zero 的 A2A 协议，用于节点发现和能力匹配。
 */
public class A2aNodeDescriptor {

    private final String nodeId;
    private final String endpoint;       // WebSocket 或 HTTP 端点 URL
    private final Set<A2aProtocol.Capability> capabilities;
    private final Map<String, String> availableAgents; // agentId → agentRole
    private final Map<String, String> availableTools;  // toolName → toolDescription
    private final long registeredAt;
    private volatile long lastHeartbeat;

    public A2aNodeDescriptor(String nodeId, String endpoint) {
        this.nodeId = nodeId;
        this.endpoint = endpoint;
        this.capabilities = new HashSet<>();
        this.availableAgents = new HashMap<>();
        this.availableTools = new HashMap<>();
        this.registeredAt = System.currentTimeMillis();
        this.lastHeartbeat = this.registeredAt;
    }

    // Getters and Setters
    public String getNodeId() { return nodeId; }
    public String getEndpoint() { return endpoint; }
    public Set<A2aProtocol.Capability> getCapabilities() { return Collections.unmodifiableSet(capabilities); }
    public Map<String, String> getAvailableAgents() { return Collections.unmodifiableMap(availableAgents); }
    public Map<String, String> getAvailableTools() { return Collections.unmodifiableMap(availableTools); }
    public long getRegisteredAt() { return registeredAt; }
    public long getLastHeartbeat() { return lastHeartbeat; }

    public void addCapability(A2aProtocol.Capability cap) { capabilities.add(cap); }
    public void removeCapability(A2aProtocol.Capability cap) { capabilities.remove(cap); }
    public void registerAgent(String agentId, String role) { availableAgents.put(agentId, role); }
    public void unregisterAgent(String agentId) { availableAgents.remove(agentId); }
    public void registerTool(String toolName, String description) { availableTools.put(toolName, description); }
    public void unregisterTool(String toolName) { availableTools.remove(toolName); }
    public void updateHeartbeat() { this.lastHeartbeat = System.currentTimeMillis(); }

    public boolean hasCapability(A2aProtocol.Capability cap) { return capabilities.contains(cap); }
    public boolean isAlive(long timeoutMs) { return (System.currentTimeMillis() - lastHeartbeat) < timeoutMs; }
}
