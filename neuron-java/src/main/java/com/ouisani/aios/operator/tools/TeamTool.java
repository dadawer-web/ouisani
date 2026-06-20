package com.ouisani.aios.operator.tools;

import com.ouisani.aios.core.team.TeamManager;
import com.ouisani.aios.core.team.TeamManager.*;
import com.ouisani.aios.core.tool.Tool;
import com.ouisani.aios.core.tool.ToolContext;
import com.ouisani.aios.core.tool.ToolInput;
import com.ouisani.aios.core.tool.ToolOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 团队协作工具集 — 对标 oh-my-openagent 的 12 个 team_* 工具。
 * <p>
 * 将 Team Mode 的核心操作封装为 LLM 可调用的工具，
 * 大模型通过这些工具实现多 Agent 组队协作。
 * <p>
 * 支持的操作：
 * - create: 创建团队
 * - delete: 删除团队
 * - send_message: 发送团队消息（点对点/广播）
 * - task_create: 创建任务
 * - task_list: 列出任务
 * - task_update: 更新任务状态
 * - status: 查看团队状态
 * - shutdown_request: 请求关闭
 * - shutdown_approve: 批准关闭
 * - shutdown_reject: 拒绝关闭
 *
 * @see TeamManager
 */
public class TeamTool implements Tool<TeamTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(TeamTool.class);

    @Override
    public String name() { return "team"; }

    @Override
    public String description() {
        return "Multi-agent team collaboration tool. Supports: create, delete, send_message, "
                + "task_create, task_list, task_update, status, shutdown_request, shutdown_approve, shutdown_reject. "
                + "Use this to orchestrate multiple agents working together on complex tasks.";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "action": {
                      "type": "string",
                      "enum": ["create", "delete", "send_message", "task_create", "task_list", "task_update", "status", "shutdown_request", "shutdown_approve", "shutdown_reject"],
                      "description": "Team operation to perform"
                    },
                    "team_name": { "type": "string", "description": "Team name" },
                    "agent_id": { "type": "string", "description": "Your agent ID" },
                    "target_id": { "type": "string", "description": "Target agent ID (for send_message) or '*' for broadcast" },
                    "member_ids": { "type": "string", "description": "Comma-separated member agent IDs (for create)" },
                    "objective": { "type": "string", "description": "Team objective (for create)" },
                    "message": { "type": "string", "description": "Message content (for send_message, shutdown_request/reject)" },
                    "task_id": { "type": "string", "description": "Task ID (for task_update)" },
                    "description": { "type": "string", "description": "Task description (for task_create)" },
                    "assignee_id": { "type": "string", "description": "Assignee agent ID (for task_create/task_update)" },
                    "task_status": { "type": "string", "enum": ["PENDING", "IN_PROGRESS", "COMPLETED", "FAILED", "CANCELLED"], "description": "New task status (for task_update)" },
                    "task_filter": { "type": "string", "enum": ["PENDING", "IN_PROGRESS", "COMPLETED", "FAILED", "CANCELLED"], "description": "Filter tasks by status (for task_list)" }
                  },
                  "required": ["action", "team_name", "agent_id"]
                }""";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        try {
            return switch (input.action()) {
                case "create" -> handleCreate(input);
                case "delete" -> handleDelete(input);
                case "send_message" -> handleSendMessage(input);
                case "task_create" -> handleTaskCreate(input);
                case "task_list" -> handleTaskList(input);
                case "task_update" -> handleTaskUpdate(input);
                case "status" -> handleStatus(input);
                case "shutdown_request" -> handleShutdownRequest(input);
                case "shutdown_approve" -> handleShutdownApprove(input);
                case "shutdown_reject" -> handleShutdownReject(input);
                default -> ToolOutput.fail("Unknown team action: " + input.action());
            };
        } catch (Exception e) {
            log.warn("[TeamTool] 动作 '{}' 失败: {}", input.action(), e.getMessage());
            return ToolOutput.fail("Team operation failed: " + e.getMessage());
        }
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public String prompt() {
        return """
                Use the team tool to orchestrate multi-agent collaboration:
                1. team(create, team_name, agent_id, member_ids, objective) — Create a team
                2. team(send_message, team_name, agent_id, target_id, message) — Send message (* for broadcast)
                3. team(task_create, team_name, agent_id, description, assignee_id) — Create shared task
                4. team(task_update, team_name, agent_id, task_id, task_status) — Update task (claim/complete)
                5. team(status, team_name, agent_id) — Check team status
                """;
    }

    // ── 操作处理器 ──

    private ToolOutput handleCreate(Input input) {
        List<String> memberIds = input.memberIds() != null && !input.memberIds().isBlank()
                ? List.of(input.memberIds().split(","))
                : List.of();

        TeamSpec spec = new TeamSpec(input.teamName(), input.agentId(), memberIds, input.objective());
        TeamState team = TeamManager.instance().createTeam(spec);

        return ToolOutput.ok("Team '" + input.teamName() + "' created. Lead: " + input.agentId()
                + ", Members: " + memberIds.size()
                + ", Objective: " + input.objective());
    }

    private ToolOutput handleDelete(Input input) {
        TeamManager.instance().deleteTeam(input.teamName(), input.agentId());
        return ToolOutput.ok("Team '" + input.teamName() + "' deleted.");
    }

    private ToolOutput handleSendMessage(Input input) {
        if (input.targetId() == null || input.message() == null) {
            return ToolOutput.fail("send_message requires target_id and message");
        }
        TeamManager.instance().sendMessage(input.teamName(), input.agentId(), input.targetId(), input.message());
        return ToolOutput.ok("Message sent to " + input.targetId() + " in team '" + input.teamName() + "'");
    }

    private ToolOutput handleTaskCreate(Input input) {
        if (input.description() == null) {
            return ToolOutput.fail("task_create requires description");
        }
        TeamTask task = TeamManager.instance().createTask(
                input.teamName(), input.agentId(), input.description(), input.assigneeId());
        return ToolOutput.ok("Task created: " + task.taskId() + " — " + task.description()
                + " (assignee: " + task.assigneeId() + ")");
    }

    private ToolOutput handleTaskList(Input input) {
        TaskStatus filter = input.taskFilter() != null ? TaskStatus.valueOf(input.taskFilter()) : null;
        List<TeamTask> tasks = TeamManager.instance().listTasks(input.teamName(), filter);
        if (tasks.isEmpty()) {
            return ToolOutput.ok("No tasks found in team '" + input.teamName() + "'");
        }
        StringBuilder sb = new StringBuilder("Tasks in team '" + input.teamName() + "':\n");
        for (TeamTask t : tasks) {
            sb.append("  - ").append(t.taskId()).append(": ").append(t.description())
                    .append(" [").append(t.status()).append("] assignee=").append(t.assigneeId()).append("\n");
        }
        return ToolOutput.ok(sb.toString());
    }

    private ToolOutput handleTaskUpdate(Input input) {
        if (input.taskId() == null || input.taskStatus() == null) {
            return ToolOutput.fail("task_update requires task_id and task_status");
        }
        TeamTask updated = TeamManager.instance().updateTask(
                input.teamName(), input.taskId(), input.agentId(),
                TaskStatus.valueOf(input.taskStatus()), input.assigneeId());
        return ToolOutput.ok("Task '" + updated.taskId() + "' updated to " + updated.status());
    }

    private ToolOutput handleStatus(Input input) {
        TeamState team = TeamManager.instance().getTeamState(input.teamName());
        if (team == null) {
            return ToolOutput.fail("Team '" + input.teamName() + "' not found");
        }
        long completedTasks = team.tasks().stream().filter(t -> t.status() == TaskStatus.COMPLETED).count();
        long pendingTasks = team.tasks().stream().filter(t -> t.status() == TaskStatus.PENDING).count();
        long inProgressTasks = team.tasks().stream().filter(t -> t.status() == TaskStatus.IN_PROGRESS).count();

        return ToolOutput.ok("Team '" + input.teamName() + "' status: " + team.status()
                + "\n  Members: " + team.members().size()
                + "\n  Tasks: " + team.tasks().size() + " total, "
                + completedTasks + " completed, " + inProgressTasks + " in progress, " + pendingTasks + " pending"
                + "\n  Objective: " + team.objective());
    }

    private ToolOutput handleShutdownRequest(Input input) {
        TeamManager.instance().requestShutdown(input.teamName(), input.agentId(),
                input.message() != null ? input.message() : "No reason provided");
        return ToolOutput.ok("Shutdown request sent for team '" + input.teamName() + "'");
    }

    private ToolOutput handleShutdownApprove(Input input) {
        TeamManager.instance().approveShutdown(input.teamName(), input.agentId());
        return ToolOutput.ok("Team '" + input.teamName() + "' shutdown approved and disbanded.");
    }

    private ToolOutput handleShutdownReject(Input input) {
        TeamManager.instance().rejectShutdown(input.teamName(), input.agentId(),
                input.message() != null ? input.message() : "No reason provided");
        return ToolOutput.ok("Shutdown rejected for team '" + input.teamName() + "'");
    }

    /**
     * 工具输入参数
     */
    public record Input(
            String action,
            String teamName,
            String agentId,
            String targetId,
            String memberIds,
            String objective,
            String message,
            String taskId,
            String description,
            String assigneeId,
            String taskStatus,
            String taskFilter
    ) implements ToolInput {
        @Override
        public String toJson() {
            return "{\"action\":\"" + action
                    + "\",\"team_name\":\"" + escape(teamName)
                    + "\",\"agent_id\":\"" + escape(agentId)
                    + "\"}";
        }

        private static String escape(String s) {
            return s != null ? s.replace("\"", "\\\"").replace("\n", "\\n") : "";
        }
    }
}
