package com.ouisani.aios.user.apps.omnifactory.tools;

import com.ouisani.aios.core.task.AiosTask;
import com.ouisani.aios.core.task.TaskRegistry;
import com.ouisani.aios.core.task.TaskScheduler;
import com.ouisani.aios.core.task.TaskStatus;
import com.ouisani.aios.core.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * 任务管理工具 — 对标 Claude Code 的 TaskCreateTool / TaskGetTool / TaskListTool /
 * TaskOutputTool / TaskStopTool / TaskUpdateTool，统一合并为一个工具。
 * <p>
 * 已从内核空间 (core.tool) 迁移至用户空间 (omnifactory.tools)。
 * 此工具属于母体的高级认知能力，不属于内核系统调用。
 * <p>
 * 通过 action 字段区分六种操作：
 * - CREATE：创建后台任务，返回任务 ID
 * - GET：按 ID 查询任务状态
 * - LIST：列出所有任务
 * - OUTPUT：获取任务输出/结果
 * - STOP：终止运行中的任务
 * - UPDATE：更新任务描述/状态
 */
public class TaskTool implements Tool<TaskTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(TaskTool.class);

    /** 操作类型 */
    public enum Action {
        CREATE, GET, LIST, OUTPUT, STOP, UPDATE
    }

    /**
     * 任务工具输入 — 通过 action 字段路由到不同操作。
     */
    public record Input(
            Action action,
            String taskId,
            String description,
            String prompt,
            String status
    ) implements ToolInput {

        public Input {
            if (action == null) throw new IllegalArgumentException("action 不能为空");
            if (taskId == null) taskId = "";
            if (description == null) description = "";
            if (prompt == null) prompt = "";
            if (status == null) status = "";
        }

        @Override
        public String toJson() {
            return "{\"action\":\"" + action
                    + "\",\"taskId\":\"" + taskId
                    + "\",\"description\":\"" + description.replace("\"", "\\\"")
                    + "\",\"prompt\":\"" + prompt.replace("\"", "\\\"")
                    + "\",\"status\":\"" + status + "\"}";
        }
    }

    @Override
    public String name() {
        return "task";
    }

    @Override
    public String description() {
        return "管理后台任务的统一工具。支持创建、查询、列表、获取输出、终止和更新任务。"
                + "通过 action 字段指定操作类型：CREATE（创建）、GET（查询）、LIST（列表）、"
                + "OUTPUT（获取输出）、STOP（终止）、UPDATE（更新）。";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\","
                + "\"properties\":{"
                + "\"action\":{\"type\":\"string\",\"enum\":[\"CREATE\",\"GET\",\"LIST\",\"OUTPUT\",\"STOP\",\"UPDATE\"],\"description\":\"操作类型\"},"
                + "\"taskId\":{\"type\":\"string\",\"description\":\"任务 ID（GET/OUTPUT/STOP/UPDATE 时必填）\"},"
                + "\"description\":{\"type\":\"string\",\"description\":\"任务描述（CREATE 时可选，UPDATE 时用于更新描述）\"},"
                + "\"prompt\":{\"type\":\"string\",\"description\":\"任务提示词（CREATE 时必填）\"},"
                + "\"status\":{\"type\":\"string\",\"description\":\"目标状态（UPDATE 时可选，用于标记任务状态）\"}"
                + "},"
                + "\"required\":[\"action\"]}";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        log.info("[TaskTool] 执行操作: action={}, taskId={}", input.action(), input.taskId());

        return switch (input.action()) {
            case CREATE -> handleCreate(input, context);
            case GET    -> handleGet(input);
            case LIST   -> handleList();
            case OUTPUT -> handleOutput(input);
            case STOP   -> handleStop(input);
            case UPDATE -> handleUpdate(input);
        };
    }

    private ToolOutput handleCreate(Input input, ToolContext context) {
        if (input.prompt().isBlank()) {
            return ToolOutput.fail("CREATE 操作需要提供 prompt 参数");
        }

        String agentId = "task_" + System.currentTimeMillis();
        String description = input.description().isBlank() ? input.prompt() : input.description();

        TaskScheduler.SandboxAgentTask task = TaskScheduler.instance().submitAgentTask(
                input.prompt(), agentId, context.workingDir(), context.sdk());

        log.info("[TaskTool] 创建后台任务: taskId={}, description={}", task.taskId(), description);
        return ToolOutput.ok("任务已创建\n"
                + "Task ID: " + task.taskId() + "\n"
                + "描述: " + description + "\n"
                + "状态: " + task.status() + "\n"
                + "输出通道: sys.sandbox.agent." + task.taskId());
    }

    private ToolOutput handleGet(Input input) {
        if (input.taskId().isBlank()) {
            return ToolOutput.fail("GET 操作需要提供 taskId 参数");
        }

        return TaskRegistry.instance().get(input.taskId())
                .map(task -> ToolOutput.ok("任务详情\n"
                        + "Task ID: " + task.taskId() + "\n"
                        + "名称: " + task.name() + "\n"
                        + "类型: " + task.type() + "\n"
                        + "状态: " + task.status() + "\n"
                        + "描述: " + task.description()))
                .orElse(ToolOutput.fail("未找到任务: " + input.taskId()));
    }

    private ToolOutput handleList() {
        Collection<AiosTask> tasks = TaskRegistry.instance().all();

        if (tasks.isEmpty()) {
            return ToolOutput.ok("当前没有活跃任务");
        }

        String taskList = tasks.stream()
                .map(t -> String.format("  %-20s %-14s %-10s %s",
                        t.taskId(), t.type(), t.status(), t.description()))
                .collect(Collectors.joining("\n"));

        String header = String.format("  %-20s %-14s %-10s %s", "TASK_ID", "TYPE", "STATUS", "DESCRIPTION");
        return ToolOutput.ok("任务列表 (" + tasks.size() + " 个)\n"
                + header + "\n"
                + taskList + "\n\n"
                + TaskRegistry.instance().summary());
    }

    private ToolOutput handleOutput(Input input) {
        if (input.taskId().isBlank()) {
            return ToolOutput.fail("OUTPUT 操作需要提供 taskId 参数");
        }

        return TaskRegistry.instance().get(input.taskId())
                .map(task -> {
                    String result = task.result();
                    if (result == null || result.isBlank()) {
                        if (task.status() == TaskStatus.RUNNING || task.status() == TaskStatus.PENDING) {
                            return ToolOutput.ok("任务 " + task.taskId() + " 尚未完成，当前状态: " + task.status());
                        }
                        return ToolOutput.ok("任务 " + task.taskId() + " 无输出（状态: " + task.status() + "）");
                    }
                    return ToolOutput.ok("任务输出 [" + task.taskId() + "] (状态: " + task.status() + ")\n" + result);
                })
                .orElse(ToolOutput.fail("未找到任务: " + input.taskId()));
    }

    private ToolOutput handleStop(Input input) {
        if (input.taskId().isBlank()) {
            return ToolOutput.fail("STOP 操作需要提供 taskId 参数");
        }

        boolean killed = TaskRegistry.instance().kill(input.taskId());
        if (killed) {
            log.info("[TaskTool] 已终止任务: {}", input.taskId());
            return ToolOutput.ok("任务已终止: " + input.taskId());
        }
        return ToolOutput.fail("无法终止任务: " + input.taskId()
                + "（任务不存在或已处于终态）");
    }

    private ToolOutput handleUpdate(Input input) {
        if (input.taskId().isBlank()) {
            return ToolOutput.fail("UPDATE 操作需要提供 taskId 参数");
        }

        return TaskRegistry.instance().get(input.taskId())
                .map(task -> {
                    StringBuilder msg = new StringBuilder("任务更新 [" + task.taskId() + "]\n");

                    if (!input.description().isBlank()) {
                        log.info("[TaskTool] 更新任务描述: taskId={}, newDescription={}",
                                task.taskId(), input.description());
                        msg.append("描述更新请求已记录: ").append(input.description()).append("\n");
                    }

                    if (!input.status().isBlank()) {
                        log.info("[TaskTool] 更新任务状态请求: taskId={}, requestedStatus={}",
                                task.taskId(), input.status());
                        msg.append("状态更新请求已记录: ").append(input.status()).append("\n");
                    }

                    msg.append("当前状态: ").append(task.status());
                    return ToolOutput.ok(msg.toString());
                })
                .orElse(ToolOutput.fail("未找到任务: " + input.taskId()));
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public String prompt() {
        return "使用 task 工具管理后台任务。"
                + "CREATE 创建新任务（需要 prompt），GET 查询任务状态（需要 taskId），"
                + "LIST 列出所有任务，OUTPUT 获取任务输出（需要 taskId），"
                + "STOP 终止任务（需要 taskId），UPDATE 更新任务元数据（需要 taskId）。";
    }
}
