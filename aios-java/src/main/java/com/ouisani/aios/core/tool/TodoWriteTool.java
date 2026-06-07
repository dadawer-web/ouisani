package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.permission.PermissionChecker;
import com.ouisani.aios.core.permission.PermissionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Todo 写入工具 — 对标 Claude Code 的 TodoWriteTool。
 * <p>
 * 任务追踪：帮助模型组织工作、展示进度。
 * - 全部完成自动清空
 * - 验证提醒机制
 * - 按 Agent 隔离
 * <p>
 * OS 类比：相当于 Linux 的 /proc 进程状态追踪。
 */
public class TodoWriteTool implements Tool<TodoWriteTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(TodoWriteTool.class);

    /** Todo 项 */
    public record TodoItem(
            String id,
            String content,
            String activeForm,
            String status,    // pending, in_progress, completed
            String priority   // high, medium, low
    ) {}

    public record Input(List<TodoItem> todos) implements ToolInput {
        @Override public String toJson() {
            StringBuilder sb = new StringBuilder("{\"todos\":[");
            for (int i = 0; i < todos.size(); i++) {
                if (i > 0) sb.append(",");
                TodoItem t = todos.get(i);
                sb.append("{\"id\":\"").append(t.id())
                  .append("\",\"content\":\"").append(t.content().replace("\"", "\\\""))
                  .append("\",\"status\":\"").append(t.status())
                  .append("\",\"priority\":\"").append(t.priority())
                  .append("\"}");
            }
            sb.append("]}");
            return sb.toString();
        }
    }

    /** 按 Agent 隔离的 Todo 存储 */
    private static final Map<String, List<TodoItem>> agentTodos = new ConcurrentHashMap<>();

    @Override public String name() { return "todo_write"; }

    @Override public String description() {
        return "Update the task list. Use this to track progress on complex multi-step tasks. All completed = auto-clear.";
    }

    @Override public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{\"todos\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"},\"activeForm\":{\"type\":\"string\"},\"status\":{\"type\":\"string\",\"enum\":[\"pending\",\"in_progress\",\"completed\"]},\"priority\":{\"type\":\"string\",\"enum\":[\"high\",\"medium\",\"low\"]}},\"required\":[\"id\",\"content\",\"status\"]}}},\"required\":[\"todos\"]}";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        String agentId = context.agentId();
        List<TodoItem> newTodos = input.todos();

        List<TodoItem> oldTodos = agentTodos.getOrDefault(agentId, List.of());

        // 全部完成自动清空
        boolean allCompleted = !newTodos.isEmpty() && newTodos.stream()
                .allMatch(t -> "completed".equals(t.status()));
        if (allCompleted) {
            agentTodos.put(agentId, List.of());
            log.info("[TodoWrite] All tasks completed, cleared for agent: {}", agentId);
            return ToolOutput.ok("All tasks completed. Todo list cleared.");
        }

        agentTodos.put(agentId, new ArrayList<>(newTodos));

        // 验证提醒：关闭 3+ 任务且无验证步骤
        long completedCount = newTodos.stream().filter(t -> "completed".equals(t.status())).count();
        boolean hasVerification = newTodos.stream()
                .anyMatch(t -> t.content() != null && t.content().toLowerCase().contains("verif"));

        String verificationNudge = "";
        if (completedCount >= 3 && !hasVerification) {
            verificationNudge = "\n\n⚠️ You've completed 3+ tasks without a verification step. Consider adding a verification task.";
        }

        log.info("[TodoWrite] Updated {} tasks for agent: {}", newTodos.size(), agentId);
        return ToolOutput.ok("Todo list updated: " + newTodos.size() + " items." + verificationNudge);
    }

    @Override public boolean readOnly() { return false; }

    @Override public String prompt() {
        return "Use todo_write to track progress on multi-step tasks. Mark items as in_progress when starting, completed when done. All completed = auto-clear.";
    }

    /**
     * 获取指定 Agent 的 Todo 列表。
     */
    public static List<TodoItem> getTodos(String agentId) {
        return agentTodos.getOrDefault(agentId, List.of());
    }
}
