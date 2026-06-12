package com.ouisani.aios.operator.tools;

import com.ouisani.aios.core.tool.*;
import com.ouisani.aios.operator.gateway.GatewayException;
import com.ouisani.aios.operator.gateway.GatewayToolBridge;

import java.util.*;

/**
 * 节点控制工具 — 对标 OpenClaw 的 NodesTool。
 * <p>
 * 通过 Gateway RPC 控制远程节点（设备）：
 * 查询状态、配对审批、发送通知、调用命令等。
 * <p>
 * 这是 OperatorAgent 的核心工具之一，使其能够
 * 操作物理设备（手机、IoT 等）。
 */
public class NodesTool implements Tool<NodesTool.Input> {

    private final GatewayToolBridge bridge;

    public NodesTool(GatewayToolBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public String name() { return "nodes"; }

    @Override
    public String description() {
        return "Control remote nodes (devices). Actions: status, describe, pending, approve, reject, notify, invoke. "
                + "Use 'status' to list connected nodes, 'describe' for node details, "
                + "'pending' for pairing requests, 'approve/reject' for pairing, "
                + "'notify' to send notifications, 'invoke' to execute commands on nodes.";
    }

    @Override
    public String inputSchema() {
        return """
        {
          "type": "object",
          "properties": {
            "action": { "type": "string", "enum": ["status","describe","pending","approve","reject","notify","invoke"], "description": "Action to perform" },
            "nodeId": { "type": "string", "description": "Target node ID (for describe/notify/invoke)" },
            "command": { "type": "string", "description": "Command name (for invoke)" },
            "params": { "type": "object", "description": "Command parameters (for invoke/notify)" }
          },
          "required": ["action"]
        }
        """;
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        try {
            return switch (input.action()) {
                case "status" -> handleStatus(input);
                case "describe" -> handleDescribe(input);
                case "pending" -> handlePending();
                case "approve" -> handleApprove(input);
                case "reject" -> handleReject(input);
                case "notify" -> handleNotify(input);
                case "invoke" -> handleInvoke(input);
                default -> ToolOutput.fail("Unknown action: " + input.action());
            };
        } catch (GatewayException e) {
            return ToolOutput.fail("Gateway error: " + e.getMessage());
        }
    }

    private ToolOutput handleStatus(Input input) throws GatewayException {
        String result = bridge.call("node.list", Map.of());
        return ToolOutput.ok("Node status:\n" + result);
    }

    private ToolOutput handleDescribe(Input input) throws GatewayException {
        if (input.nodeId() == null || input.nodeId().isBlank()) {
            return ToolOutput.fail("nodeId is required for 'describe' action");
        }
        String result = bridge.call("node.describe", Map.of("nodeId", input.nodeId()));
        return ToolOutput.ok("Node details:\n" + result);
    }

    private ToolOutput handlePending() throws GatewayException {
        String result = bridge.call("node.pair.list", Map.of());
        return ToolOutput.ok("Pending pairing requests:\n" + result);
    }

    private ToolOutput handleApprove(Input input) throws GatewayException {
        if (input.nodeId() == null || input.nodeId().isBlank()) {
            return ToolOutput.fail("nodeId is required for 'approve' action");
        }
        String result = bridge.call("node.pair.approve", Map.of("nodeId", input.nodeId()));
        return ToolOutput.ok("Node approved:\n" + result);
    }

    private ToolOutput handleReject(Input input) throws GatewayException {
        if (input.nodeId() == null || input.nodeId().isBlank()) {
            return ToolOutput.fail("nodeId is required for 'reject' action");
        }
        String result = bridge.call("node.pair.reject", Map.of("nodeId", input.nodeId()));
        return ToolOutput.ok("Node rejected:\n" + result);
    }

    private ToolOutput handleNotify(Input input) throws GatewayException {
        if (input.nodeId() == null || input.nodeId().isBlank()) {
            return ToolOutput.fail("nodeId is required for 'notify' action");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("nodeId", input.nodeId());
        params.put("command", "system.notify");
        if (input.params() != null) params.put("params", input.params());
        String result = bridge.call("node.invoke", params);
        return ToolOutput.ok("Notification sent:\n" + result);
    }

    private ToolOutput handleInvoke(Input input) throws GatewayException {
        if (input.nodeId() == null || input.nodeId().isBlank()) {
            return ToolOutput.fail("nodeId is required for 'invoke' action");
        }
        if (input.command() == null || input.command().isBlank()) {
            return ToolOutput.fail("command is required for 'invoke' action");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("nodeId", input.nodeId());
        params.put("command", input.command());
        if (input.params() != null) params.put("params", input.params());
        String result = bridge.call("node.invoke", params);
        return ToolOutput.ok("Command result:\n" + result);
    }

    @Override
    public boolean readOnly() {
        return false; // approve/reject/notify/invoke 都会修改状态
    }

    /** 工具输入 */
    public record Input(
            String action,
            String nodeId,
            String command,
            Map<String, Object> params
    ) implements ToolInput {
        @Override
        public String toJson() {
            StringBuilder sb = new StringBuilder("{\"action\":\"").append(action).append("\"");
            if (nodeId != null) sb.append(",\"nodeId\":\"").append(nodeId).append("\"");
            if (command != null) sb.append(",\"command\":\"").append(command).append("\"");
            if (params != null) sb.append(",\"params\":{}");
            sb.append("}");
            return sb.toString();
        }
    }
}
