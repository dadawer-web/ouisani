package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.provenance.ProvenanceQuery;
import com.ouisani.aios.core.provenance.ProvenanceRecord;
import com.ouisani.aios.core.provenance.TraceabilityReport;
import com.ouisani.aios.core.review.ReviewFinding;
import com.ouisani.aios.core.review.ReviewRecord;

import java.util.List;
import java.util.Optional;

/**
 * Provenance DAG 查询工具 — Phase 6 把"数字是否可追溯"从 LLM 读文件判断变成 DAG 查询。
 * <p>
 * 借鉴 OpenScience {@code provenance.ts}：reviewer 在审查循环中调用本工具，走 provenance DAG
 * 找 claim 的来源（哪个 agent、用什么工具、第几版写入），而不是靠 file_read 猜测。
 * <p>
 * <b>只读</b>：不修改任何状态，PLAN 模式（reviewer 盲审锁定）下可用 —— 盲性不破：
 * provenance 记录的 content = artifact 内容（reviewer 本就能 file_read 看到），provenance
 * 额外给的是 agent/tool/version 元数据，不泄漏父 CoT 推理。
 *
 * <h3>查询语义</h3>
 * <ul>
 *   <li>提供 {@code path} → 按 artifact 路径追溯 provenance 版本链 + 关联 review</li>
 *   <li>提供 {@code agentId}（无 path 时）→ 按 agent 追溯其产出 + 被审记录</li>
 *   <li>两者都缺 → 返回参数错误</li>
 * </ul>
 *
 * <h3>跨 session</h3>
 * 从磁盘回读 {@code .aios/provenance.jsonl} + {@code .aios/review.jsonl}（合并内存缓冲、去重），
 * 即使是新 session 无内存缓冲也能查到历史记录。
 */
public class ProvenanceQueryTool implements Tool<ProvenanceQueryTool.Input> {

    /** 记录数上限 — 防止超大 jsonl 撑爆 reviewer 上下文。 */
    private static final int MAX_PROVENANCE_RECORDS = 50;
    private static final int MAX_REVIEW_RECORDS = 20;

    public record Input(String path, String agentId) implements ToolInput {
        public Input {
            if (path != null) path = path.trim();
            if (agentId != null) agentId = agentId.trim();
        }

        @Override
        public String toJson() {
            return "{\"path\":\"" + (path == null ? "" : path.replace("\"", "\\\""))
                    + "\",\"agentId\":\"" + (agentId == null ? "" : agentId.replace("\"", "\\\"")) + "\"}";
        }
    }

    @Override
    public String name() {
        return "provenance_query";
    }

    @Override
    public String description() {
        return "Query the provenance DAG to trace an artifact's origin (which agent wrote it, with what tool, "
                + "at which version) and any past review findings linked to it. Read-only. Use this instead of "
                + "file_read when verifying whether a claim in the final answer traces to a real artifact.";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"path\":{\"type\":\"string\",\"description\":\"Artifact VFS path to trace (takes precedence over agentId)\"},"
                + "\"agentId\":{\"type\":\"string\",\"description\":\"Agent id whose produced artifacts and review records to trace\"}"
                + "},\"required\":[]}";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        if (input == null) {
            return ToolOutput.fail("provenance_query requires 'path' or 'agentId'");
        }
        TraceabilityReport report;
        if (input.path() != null && !input.path().isEmpty()) {
            report = ProvenanceQuery.traceByPath(input.path());
        } else if (input.agentId() != null && !input.agentId().isEmpty()) {
            report = ProvenanceQuery.traceByAgent(input.agentId());
        } else {
            return ToolOutput.fail("provenance_query requires at least one of 'path' or 'agentId'");
        }
        return ToolOutput.ok(format(report));
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public String prompt() {
        return "Use provenance_query to trace an artifact's origin through the provenance DAG instead of guessing "
                + "with file_read. Pass 'path' to trace a specific artifact's version history + linked reviews, "
                + "or 'agentId' to list everything an agent produced. The returned provenance records show "
                + "{path, version, ts, tool, agentId, sessionId}; review records show past verdicts + findings "
                + "with claim/evidence. Use these to verify whether claims in the final answer trace to real artifacts.";
    }

    // ── 强类型 I/O 契约 ──
    @Override
    public List<Port> inputPorts() {
        return List.of(
            new Port("path", DataTypes.FILE_PATH, "Artifact VFS 路径（优先于 agentId）", false),
            new Port("agentId", DataTypes.PLAIN_TEXT, "Agent 标识（无 path 时按 agent 追溯）", false)
        );
    }

    @Override
    public List<Port> outputPorts() {
        return List.of(
            new Port("report", DataTypes.PLAIN_TEXT, "可追溯性报告（provenance 版本链 + review 裁决链）")
        );
    }

    @Override
    public Optional<ToolExample> example() {
        return Optional.of(new ToolExample(
            "如果你需要核实 final answer 中「survey.md 由 agent_5 第二版写入」的断言是否可追溯",
            java.util.Map.of("path", "/factory/output/survey.md")
        ));
    }

    // ════════════════════════════════════════════════════════════════
    //  报告格式化 — 给 reviewer 阅读的紧凑文本
    // ════════════════════════════════════════════════════════════════

    /** 格式化报告为文本 — package-private 供测试断言。 */
    static String format(TraceabilityReport report) {
        if (report.isEmpty()) {
            return "No provenance or review records found for key: " + report.key();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## Traceability Report for: ").append(report.key()).append("\n\n");

        sb.append("### Provenance (").append(report.provenance().size()).append(" records)\n");
        if (report.provenance().isEmpty()) {
            sb.append("(none — artifact has no recorded write history)\n");
        } else {
            int shown = 0;
            for (ProvenanceRecord r : report.provenance()) {
                if (shown++ >= MAX_PROVENANCE_RECORDS) {
                    sb.append("... (").append(report.provenance().size() - MAX_PROVENANCE_RECORDS)
                            .append(" more records truncated)\n");
                    break;
                }
                sb.append("- v").append(r.version()).append(" [").append(r.tool()).append("] ")
                        .append("ts=").append(r.ts());
                if (r.agentId() != null) sb.append(" agent=").append(r.agentId());
                if (r.sessionId() != null) sb.append(" sess=").append(r.sessionId());
                sb.append(" path=").append(r.path()).append("\n");
            }
        }

        sb.append("\n### Reviews (").append(report.reviews().size()).append(" records)\n");
        if (report.reviews().isEmpty()) {
            sb.append("(none — no past review verdicts linked to this key)\n");
        } else {
            int shown = 0;
            for (ReviewRecord r : report.reviews()) {
                if (shown++ >= MAX_REVIEW_RECORDS) {
                    sb.append("... (").append(report.reviews().size() - MAX_REVIEW_RECORDS)
                            .append(" more records truncated)\n");
                    break;
                }
                sb.append("- [").append(r.outcome()).append("] level=").append(r.level())
                        .append(" ts=").append(r.ts())
                        .append(" agent=").append(r.agentId())
                        .append(" target=").append(r.targetPath());
                if (r.deterministicForced()) sb.append(" (deterministic-forced)");
                sb.append("\n");
                if (!r.summary().isEmpty()) {
                    sb.append("  summary: ").append(r.summary()).append("\n");
                }
                for (ReviewFinding f : r.findings()) {
                    sb.append("  - [").append(f.severity()).append("] ");
                    if (f.targetPath() != null && !f.targetPath().isEmpty()) {
                        sb.append(f.targetPath()).append(": ");
                    }
                    sb.append(f.message());
                    if (!f.claim().isEmpty()) {
                        sb.append(" | claim: ").append(f.claim());
                    }
                    if (!f.evidence().isEmpty()) {
                        sb.append(" | evidence: ").append(f.evidence());
                    }
                    sb.append("\n");
                }
            }
        }
        return sb.toString().stripTrailing();
    }
}
