package com.ouisani.aios.core.skill;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Run 记录 — 一次 SkillChain 执行的持久化快照。
 * <p>
 * 与 {@link SkillChain.ChainRun}（内存中的运行记录）正交：
 * <ul>
 *   <li>{@code ChainRun} — SkillChain.run 返回的内存对象，含完整 {@link SkillChain.StepRun} 列表</li>
 *   <li>{@code RunRecord} — 持久化到磁盘的<b>索引友好</b>摘要，便于查询与重放</li>
 * </ul>
 * <p>
 * <b>与 OvernightTaskCard 模式对齐</b>：
 * <ul>
 *   <li>每个 run 有独立目录：{@code /var/run/overnight/skill-chain/{runId}/}</li>
 *   <li>manifest.json（ChainRun 完整 JSON）+ run-record.json（本记录）+ reproduce.prompt</li>
 *   <li>append-only JSONL 日志（{@code skill-chain-runs.jsonl}）作为权威记录</li>
 * </ul>
 * <p>
 * <b>reproduce prompt</b>：包含重新执行此 run 所需的全部信息（meta-skill 名、输入、ctx、snapshotId），
 * 与 {@link com.ouisani.aios.core.snapshot.EnvironmentSnapshotManager} 的快照/恢复能力天然配合 ——
 * 若记录了 snapshotId，重放前可先 restore 快照回到原始环境状态。
 *
 * @param runId          运行 ID（与 ChainRun.runId 一致）
 * @param metaSkillName  meta-skill 名（用于查询与重放）
 * @param startedAt      开始时间戳（毫秒）
 * @param finishedAt     结束时间戳（毫秒）
 * @param status         链状态
 * @param agentId        调用方 Agent ID
 * @param slug           本次执行 slug（用于输出路径隔离）
 * @param workingDir     工作目录
 * @param input          用户原始输入（重放用）
 * @param snapshotId     执行前捕获的 EnvironmentSnapshot ID（可空）
 * @param outputBasePath VFS 输出根
 * @param stepCount      总步骤数
 * @param successCount   成功步骤数
 * @param failureCount   失败步骤数
 * @param runDir         持久化目录（绝对路径，可空表示未持久化）
 * @see SkillChain.ChainRun
 * @see RunRecordStore
 */
public record RunRecord(
        String runId,
        String metaSkillName,
        long startedAt,
        long finishedAt,
        String status,
        String agentId,
        String slug,
        String workingDir,
        String input,
        String snapshotId,
        String outputBasePath,
        int stepCount,
        long successCount,
        long failureCount,
        String runDir
) {

    /**
     * 从 ChainRun + 上下文构建 RunRecord。
     *
     * @param run        ChainRun 内存对象
     * @param input      原始用户输入
     * @param ctx        执行上下文
     * @param snapshotId 执行前捕获的快照 ID（可空）
     * @param runDir     持久化目录（可空）
     */
    public static RunRecord from(SkillChain.ChainRun run, String input,
                                   SkillChainContext ctx, String snapshotId, String runDir) {
        return new RunRecord(
                run.runId(),
                run.metaSkillName(),
                run.startedAt(),
                run.finishedAt(),
                run.status().name(),
                ctx.agentId(),
                ctx.slug(),
                ctx.workingDir(),
                input == null ? "" : input,
                snapshotId == null ? "" : snapshotId,
                run.outputBasePath(),
                run.steps().size(),
                run.successCount(),
                run.failureCount(),
                runDir == null ? "" : runDir
        );
    }

    /** 总耗时（毫秒） */
    public long elapsedMs() {
        return finishedAt - startedAt;
    }

    /**
     * 生成人类可读的 reproduce prompt — 可复制粘贴重放此 run。
     * <p>
     * 包含：meta-skill 名、输入、ctx 字段、snapshotId、调用代码片段。
     */
    public String reproducePrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Reproduce Run: ").append(runId).append("\n\n");
        sb.append("## Meta-skill\n").append(metaSkillName).append("\n\n");
        sb.append("## Input\n").append(input).append("\n\n");
        sb.append("## Context\n");
        sb.append("- agentId: ").append(agentId).append("\n");
        sb.append("- slug: ").append(slug).append("\n");
        sb.append("- workingDir: ").append(workingDir).append("\n");
        sb.append("- snapshotId: ").append(snapshotId.isEmpty() ? "<none>" : snapshotId).append("\n\n");
        sb.append("## Results\n");
        sb.append("- status: ").append(status).append("\n");
        sb.append("- elapsedMs: ").append(elapsedMs()).append("\n");
        sb.append("- steps: ").append(stepCount)
                .append(" (success=").append(successCount)
                .append(", failure=").append(failureCount).append(")\n");
        sb.append("- outputBasePath: ").append(outputBasePath).append("\n");
        sb.append("- runDir: ").append(runDir.isEmpty() ? "<none>" : runDir).append("\n\n");
        sb.append("## Replay Code\n");
        sb.append("```java\n");
        sb.append("MetaSkill meta = MetaSkillRegistry.instance().get(\"")
                .append(metaSkillName).append("\").orElseThrow();\n");
        sb.append("SkillChainContext ctx = new SkillChainContext(\"")
                .append(agentId).append("\", \"\", \"")
                .append(workingDir).append("\", \"").append(slug).append("\");\n");
        if (!snapshotId.isEmpty()) {
            sb.append("// 可选：先恢复环境快照\n");
            sb.append("EnvironmentSnapshotManager.instance().load(\"")
                    .append(snapshotId).append("\")\n");
            sb.append("    .ifPresent(EnvironmentSnapshotManager.instance()::restore);\n");
        }
        sb.append("SkillChain.ChainRun rerun = SkillChain.run(meta, \"")
                .append(input.replace("\"", "\\\"")).append("\", ctx, executor);\n");
        sb.append("```\n");
        return sb.toString();
    }

    /**
     * 序列化为 JSON（单行，便于 JSONL 追加）。
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"runId\":\"").append(escape(runId)).append("\",");
        sb.append("\"metaSkillName\":\"").append(escape(metaSkillName)).append("\",");
        sb.append("\"startedAt\":").append(startedAt).append(",");
        sb.append("\"finishedAt\":").append(finishedAt).append(",");
        sb.append("\"elapsedMs\":").append(elapsedMs()).append(",");
        sb.append("\"status\":\"").append(escape(status)).append("\",");
        sb.append("\"agentId\":\"").append(escape(agentId)).append("\",");
        sb.append("\"slug\":\"").append(escape(slug)).append("\",");
        sb.append("\"workingDir\":\"").append(escape(workingDir)).append("\",");
        sb.append("\"input\":\"").append(escape(input)).append("\",");
        sb.append("\"snapshotId\":\"").append(escape(snapshotId)).append("\",");
        sb.append("\"outputBasePath\":\"").append(escape(outputBasePath)).append("\",");
        sb.append("\"runDir\":\"").append(escape(runDir)).append("\",");
        sb.append("\"stepCount\":").append(stepCount).append(",");
        sb.append("\"successCount\":").append(successCount).append(",");
        sb.append("\"failureCount\":").append(failureCount);
        sb.append("}");
        return sb.toString();
    }

    /**
     * 从 JSON 行反序列化 — 兼容 JSONL 中的一行。
     * <p>
     * <b>实现说明</b>：用简单正则提取字段（core 层不引入 Jackson/Gson）。
     * 仅支持 {@link #toJson} 产生的扁平 JSON，不支持嵌套。
     */
    public static RunRecord fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        return new RunRecord(
                extractString(json, "runId"),
                extractString(json, "metaSkillName"),
                extractLong(json, "startedAt"),
                extractLong(json, "finishedAt"),
                extractString(json, "status"),
                extractString(json, "agentId"),
                extractString(json, "slug"),
                extractString(json, "workingDir"),
                extractString(json, "input"),
                extractString(json, "snapshotId"),
                extractString(json, "outputBasePath"),
                (int) extractLong(json, "stepCount"),
                extractLong(json, "successCount"),
                extractLong(json, "failureCount"),
                extractString(json, "runDir")
        );
    }

    // ── JSON 提取工具 ──

    private static final Pattern STRING_FIELD = Pattern.compile("\"(\\w+)\":\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern NUM_FIELD = Pattern.compile("\"(\\w+)\":(-?\\d+(?:\\.\\d+)?)");

    private static String extractString(String json, String field) {
        Matcher m = STRING_FIELD.matcher(json);
        while (m.find()) {
            if (m.group(1).equals(field)) {
                return unescape(m.group(2));
            }
        }
        return "";
    }

    private static long extractLong(String json, String field) {
        Matcher m = NUM_FIELD.matcher(json);
        while (m.find()) {
            if (m.group(1).equals(field)) {
                try {
                    return (long) Double.parseDouble(m.group(2));
                } catch (NumberFormatException e) {
                    return 0L;
                }
            }
        }
        return 0L;
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private static String unescape(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 5 < s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                                i += 4;
                            } catch (NumberFormatException e) {
                                sb.append(c);
                            }
                        } else {
                            sb.append(c);
                        }
                    }
                    default -> {
                        sb.append(c);
                        sb.append(next);
                    }
                }
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
