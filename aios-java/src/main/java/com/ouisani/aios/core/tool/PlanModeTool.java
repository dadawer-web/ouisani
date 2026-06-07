package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.permission.PermissionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 计划模式工具 — 对标 Claude Code 的 EnterPlanModeTool + ExitPlanModeTool。
 * <p>
 * EnterPlanMode: 切换到只读计划模式，只允许读取操作
 * ExitPlanMode: 退出计划模式，恢复写权限
 * <p>
 * OS 类比：相当于 Linux 的 single-user mode 切换 — 安全模式下只读检查，
 * 确认方案后再回到正常模式执行。
 */
public class PlanModeTool implements Tool<PlanModeTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(PlanModeTool.class);

    public record Input(
            String action,  // "enter" or "exit"
            String plan     // 退出时提交的计划内容
    ) implements ToolInput {
        public Input {
            if (action == null) action = "enter";
            if (plan == null) plan = "";
        }

        @Override public String toJson() {
            return "{\"action\":\"" + action + "\",\"plan\":\"" + plan.replace("\"", "\\\"") + "\"}";
        }
    }

    @Override public String name() { return "plan_mode"; }

    @Override public String description() {
        return "Enter or exit plan mode. In plan mode, only read-only tools are allowed. Exit with a plan to get user approval before executing.";
    }

    @Override public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"enter\",\"exit\"]},\"plan\":{\"type\":\"string\",\"description\":\"The plan when exiting plan mode\"}},\"required\":[\"action\"]}";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        if ("enter".equals(input.action())) {
            log.info("[PlanMode] Entering plan mode for agent: {}", context.agentId());
            System.out.println("[PlanMode] ┌─ Entering plan mode (read-only)");
            return ToolOutput.ok("Entered plan mode. Only read-only tools (file_read, grep, glob) are available. "
                    + "Analyze the codebase and create a plan, then exit plan mode with your plan.");
        } else {
            log.info("[PlanMode] Exiting plan mode for agent: {}", context.agentId());
            System.out.println("[PlanMode] └─ Exiting plan mode");
            String plan = input.plan();
            if (plan.isEmpty()) {
                return ToolOutput.ok("Exited plan mode. No plan provided.");
            }
            return ToolOutput.ok("Exited plan mode. Plan submitted:\n\n" + plan
                    + "\n\nPlan is ready for execution. You may now use write tools.");
        }
    }

    @Override public boolean readOnly() { return true; }

    @Override public String prompt() {
        return """
                Use plan_mode for complex tasks:
                1. Enter plan mode to analyze the codebase (read-only)
                2. Create a detailed plan
                3. Exit plan mode with your plan
                This ensures you understand the codebase before making changes.""";
    }
}
