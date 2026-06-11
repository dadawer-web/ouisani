package com.ouisani.aios.user.apps.omnifactory.tools;

import com.ouisani.aios.core.skill.SkillLoader;
import com.ouisani.aios.core.skill.SkillLoader.SkillDef;
import com.ouisani.aios.core.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 技能工具 — 对标 Claude Code 的 SkillTool.ts，调用斜杠命令技能。
 * <p>
 * 已从内核空间 (core.tool) 迁移至用户空间 (omnifactory.tools)。
 * 此工具属于母体的高级认知能力，不属于内核系统调用。
 * <p>
 * 从 SkillLoader 查找指定技能，若找到则通过 QueryEngine 执行其提示词，
 * 若未找到则返回失败并列出可用技能。
 */
public class SkillTool implements Tool<SkillTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(SkillTool.class);

    /**
     * 输入参数 — 技能名称和可选参数。
     */
    public record Input(
            String skill,
            String args
    ) implements ToolInput {
        public Input {
            if (skill == null) skill = "";
            if (args == null) args = "";
        }

        @Override
        public String toJson() {
            return "{\"skill\":\"" + skill.replace("\"", "\\\"")
                    + "\",\"args\":\"" + args.replace("\"", "\\\"") + "\"}";
        }
    }

    @Override
    public String name() {
        return "skill";
    }

    @Override
    public String description() {
        return "调用斜杠命令技能";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":"
                + "{\"skill\":{\"type\":\"string\",\"description\":\"技能名称，如 commit、review\"},"
                + "\"args\":{\"type\":\"string\",\"description\":\"可选参数，传递给技能提示词\"}},"
                + "\"required\":[\"skill\"]}";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        String skillName = input.skill().trim();
        log.info("[SkillTool] 调用技能: skill={}, args={}", skillName, input.args());

        // 确保技能缓存已加载
        SkillLoader.loadAll(context.workingDir());

        // 按名称查找技能
        Optional<SkillDef> opt = SkillLoader.get(skillName);

        if (opt.isEmpty()) {
            String available = formatAvailableSkills();
            log.warn("[SkillTool] 技能未找到: {}", skillName);
            return ToolOutput.fail("技能未找到: " + skillName + "\n可用技能列表:\n" + available);
        }

        SkillDef skillDef = opt.get();
        log.info("[SkillTool] 找到技能: name={}, source={}", skillDef.name(), skillDef.source());

        // 构建技能提示词 — 将技能内容和用户参数组合
        String prompt = buildSkillPrompt(skillDef, input.args());

        // 通过 QueryEngine 执行技能提示词
        QueryEngine engine = new QueryEngine(context.sdk(), context.agentId(), context.workingDir());
        String result = engine.query(prompt);

        log.info("[SkillTool] 技能执行完成: skill={}", skillName);
        return ToolOutput.ok(result);
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public String prompt() {
        return "使用 skill 工具调用斜杠命令技能。提供技能名称（如 commit、review）和可选参数。"
                + "如果不确定有哪些技能可用，可以先调用查看可用列表。";
    }

    /**
     * 构建技能提示词 — 将技能定义内容与用户参数合并。
     */
    private String buildSkillPrompt(SkillDef skillDef, String args) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 技能: ").append(skillDef.name()).append("\n");
        if (skillDef.description() != null && !skillDef.description().isEmpty()) {
            sb.append(skillDef.description()).append("\n\n");
        }

        if (skillDef.content() != null && !skillDef.content().isEmpty()) {
            sb.append(skillDef.content()).append("\n\n");
        }

        if (skillDef.allowedTools() != null && !skillDef.allowedTools().isEmpty()) {
            sb.append("允许使用的工具: ").append(String.join(", ", skillDef.allowedTools())).append("\n\n");
        }

        if (args != null && !args.isEmpty()) {
            sb.append("用户参数: ").append(args).append("\n");
        }

        return sb.toString();
    }

    /**
     * 格式化可用技能列表。
     */
    private String formatAvailableSkills() {
        Map<String, SkillDef> skills = SkillLoader.getCached();

        if (skills.isEmpty()) {
            return "  (无可用技能)";
        }

        return skills.values().stream()
                .map(s -> {
                    String desc = s.description() != null && !s.description().isEmpty()
                            ? s.description() : "(无描述)";
                    return String.format("  /%-20s [%s] %s", s.name(), s.source(), desc);
                })
                .collect(Collectors.joining("\n"));
    }
}
