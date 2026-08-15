package com.ouisani.aios.core.audit;

import com.ouisani.aios.core.ipc.CallerContext;
import com.ouisani.aios.core.ipc.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Best-effort JSONL audit sink and queryable cross-layer audit timeline. */
public final class UnifiedAuditLog {

    private static final Logger log = LoggerFactory.getLogger(UnifiedAuditLog.class);

    public static final String LAYER_CGROUP = "CGROUP";
    public static final String LAYER_SANDBOX = "SANDBOX";
    public static final String LAYER_PERMISSION = "PERMISSION";
    public static final String LAYER_RATELIMIT = "RATELIMIT";
    /** Continuity/read-model events (Mission lifecycle, approvals, and completion). */
    public static final String LAYER_MISSION = "MISSION";
    /** Governed Wiki projection and user-confirmation events. */
    public static final String LAYER_WIKI = "WIKI";
    /** Interruption checkpoints, continuation planning, reuse and re-approval. */
    public static final String LAYER_CONTINUATION = "CONTINUATION";
    /** Skill catalog, controlled installation, and activation changes. */
    public static final String LAYER_SKILL = "SKILL";
    /** Browser workspace/session lifecycle and governed commands. */
    public static final String LAYER_BROWSER = "BROWSER";
    /** Multi-channel directory and selection events. */
    public static final String LAYER_CHANNEL = "CHANNEL";
    /** IDE diff review and reversible timeline events. */
    public static final String LAYER_DIFF = "DIFF";

    private static volatile Path auditFile = Paths.get(".aios", "unified_audit.jsonl");
    private static volatile boolean enabled = true;
    private static final List<AuditEntry> recentBuffer = new ArrayList<>();
    private static final int BUFFER_CAPACITY = 1024;
    private static final Object bufferLock = new Object();
    private static final AtomicLong sequence = new AtomicLong();

    private UnifiedAuditLog() {}

    /** Correlation fields shared by all governance events. */
    public record AuditContext(
            String tenantId,
            String workflowId,
            String runId,
            String traceId,
            String agentId,
            String parentAgentId,
            String delegationId,
            String nodeId,
            int attempt) {
        public AuditContext {
            tenantId = clean(tenantId);
            workflowId = clean(workflowId);
            runId = clean(runId);
            traceId = clean(traceId);
            agentId = clean(agentId);
            parentAgentId = clean(parentAgentId);
            delegationId = clean(delegationId);
            nodeId = clean(nodeId);
            if (attempt < 0) attempt = -1;
        }

        public static AuditContext empty() {
            return new AuditContext(null, null, null, null, null, null, null, null, -1);
        }

        /** Build the best context available at the current call boundary. */
        public static AuditContext current() {
            CallerContext caller = CallerContext.current();
            return new AuditContext(
                    caller == null ? null : caller.tenantId(),
                    null,
                    null,
                    TraceContext.getCurrentTraceId(),
                    caller == null ? null : caller.agentId(),
                    null, null, null, -1);
        }

        private static String clean(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    /** Event envelope used by enforcement and governance layers. */
    public record TimelineEvent(
            String layer,
            String eventType,
            String decision,
            String agentId,
            String target,
            String reason,
            AuditContext context) {
        public TimelineEvent {
            layer = layer == null ? "" : layer;
            eventType = eventType == null ? decision : eventType;
            decision = decision == null ? "" : decision;
            reason = reason == null ? "" : reason;
        }
    }

    /** Filters for timeline reconstruction and export. Null fields mean no filter. */
    public record TimelineQuery(
            String traceId,
            String tenantId,
            String workflowId,
            String runId,
            String agentId,
            long fromTs,
            long toTs,
            Set<String> layers,
            Set<String> decisions) {
        public TimelineQuery {
            layers = layers == null ? Set.of() : Set.copyOf(layers);
            decisions = decisions == null ? Set.of() : Set.copyOf(decisions);
        }

        public static TimelineQuery all() {
            return new TimelineQuery(null, null, null, null, null, Long.MIN_VALUE, Long.MAX_VALUE,
                    Set.of(), Set.of());
        }

        public static TimelineQuery forTraceId(String traceId) {
            return new TimelineQuery(traceId, null, null, null, null, Long.MIN_VALUE, Long.MAX_VALUE,
                    Set.of(), Set.of());
        }

        private boolean matches(AuditEntry e) {
            return (traceId == null || traceId.equals(e.traceId()))
                    && (tenantId == null || tenantId.equals(e.tenantId()))
                    && (workflowId == null || workflowId.equals(e.workflowId()))
                    && (runId == null || runId.equals(e.runId()))
                    && (agentId == null || agentId.equals(e.agentId()))
                    && e.ts() >= fromTs && e.ts() <= toTs
                    && (layers.isEmpty() || layers.contains(e.layer()))
                    && (decisions.isEmpty() || decisions.contains(e.decision()));
        }
    }

    /** Legacy append API; correlation is read from the current execution context. */
    public static void append(String layer, String decision, String agentId,
                              String target, String reason) {
        append(new TimelineEvent(layer, decision, decision, agentId, target, reason, AuditContext.current()));
    }

    /** Append with explicit correlation fields. */
    public static void append(String layer, String decision, String agentId,
                              String target, String reason, AuditContext context) {
        append(new TimelineEvent(layer, decision, decision, agentId, target, reason, context));
    }

    /** Append a fully described event. Recording never interrupts the caller. */
    public static void append(TimelineEvent event) {
        if (!enabled || event == null) return;
        try {
            AuditContext context = event.context() == null ? AuditContext.current() : event.context();
            AuditEntry entry = new AuditEntry(
                    System.currentTimeMillis(),
                    context.traceId(),
                    event.layer(),
                    event.decision(),
                    event.agentId() == null ? context.agentId() : event.agentId(),
                    event.target(),
                    truncate(event.reason(), 512),
                    "aud_" + UUID.randomUUID(),
                    sequence.incrementAndGet(),
                    event.eventType(),
                    context.tenantId(),
                    context.workflowId(),
                    context.runId(),
                    context.parentAgentId(),
                    context.delegationId(),
                    context.nodeId(),
                    context.attempt());
            appendRecord(entry);
        } catch (Throwable t) {
            log.debug("[UnifiedAuditLog] record failed (layer={}, decision={}): {}",
                    event.layer(), event.decision(), t.getMessage());
        }
    }

    /** Return one trace in deterministic timestamp/sequence order. */
    public static List<AuditEntry> listByTraceId(String traceId) {
        if (traceId == null) return List.of();
        return query(TimelineQuery.forTraceId(traceId));
    }

    /** Query the merged memory/disk timeline by correlation fields. */
    public static List<AuditEntry> query(TimelineQuery query) {
        TimelineQuery q = query == null ? TimelineQuery.all() : query;
        LinkedHashSet<AuditEntry> merged = new LinkedHashSet<>();
        synchronized (bufferLock) {
            merged.addAll(recentBuffer);
        }
        merged.addAll(listAllFromDisk());
        return merged.stream()
                .filter(q::matches)
                .sorted(Comparator.comparingLong(AuditEntry::ts)
                        .thenComparingLong(AuditEntry::sequence)
                        .thenComparing(e -> e.eventId() == null ? "" : e.eventId()))
                .toList();
    }

    /** Export a filtered timeline as JSONL for external audit tooling. */
    public static String exportJsonLines(TimelineQuery query) {
        return String.join("\n", query(query).stream().map(AuditEntry::toJsonLine).toList());
    }

    public static Set<String> listTraceIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        synchronized (bufferLock) {
            for (AuditEntry e : recentBuffer) if (e.traceId() != null) ids.add(e.traceId());
        }
        for (AuditEntry e : listAllFromDisk()) if (e.traceId() != null) ids.add(e.traceId());
        return ids;
    }

    public static Map<String, Long> countByLayer() {
        Map<String, Long> stats = new java.util.LinkedHashMap<>();
        synchronized (bufferLock) {
            for (AuditEntry e : recentBuffer) stats.merge(e.layer(), 1L, Long::sum);
        }
        return stats;
    }

    public static void setEnabled(boolean enabled) { UnifiedAuditLog.enabled = enabled; }
    public static void setAuditFile(Path file) { UnifiedAuditLog.auditFile = file; }
    public static Path auditFile() { return auditFile; }

    public static void resetForTesting() {
        synchronized (bufferLock) { recentBuffer.clear(); }
    }

    private static void appendRecord(AuditEntry entry) throws IOException {
        synchronized (bufferLock) {
            if (recentBuffer.size() >= BUFFER_CAPACITY) recentBuffer.subList(0, BUFFER_CAPACITY / 4).clear();
            recentBuffer.add(entry);
        }
        Path file = auditFile;
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        byte[] bytes = (entry.toJsonLine() + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ch.write(ByteBuffer.wrap(bytes));
        }
    }

    private static List<AuditEntry> listAllFromDisk() {
        List<AuditEntry> result = new ArrayList<>();
        Path file = auditFile;
        if (!Files.exists(file)) return result;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                AuditEntry e = AuditEntry.fromJsonLine(line);
                if (e != null) result.add(e);
            }
        } catch (IOException e) {
            log.debug("[UnifiedAuditLog] disk read failed: {}", e.getMessage());
        }
        return result;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /** JSONL schema with backward-compatible seven-argument construction. */
    public record AuditEntry(
            long ts,
            String traceId,
            String layer,
            String decision,
            String agentId,
            String target,
            String reason,
            String eventId,
            long sequence,
            String eventType,
            String tenantId,
            String workflowId,
            String runId,
            String parentAgentId,
            String delegationId,
            String nodeId,
            int attempt) {
        public AuditEntry(long ts, String traceId, String layer, String decision,
                          String agentId, String target, String reason) {
            this(ts, traceId, layer, decision, agentId, target, reason,
                    null, 0L, null, null, null, null, null, null, null, -1);
        }

        public AuditEntry {
            if (layer == null) layer = "";
            if (decision == null) decision = "";
            if (reason == null) reason = "";
            if (attempt < 0) attempt = -1;
        }

        public String toJsonLine() {
            StringBuilder sb = new StringBuilder(320);
            sb.append('{');
            field(sb, "ts", Long.toString(ts), false);
            field(sb, "traceId", traceId, true);
            field(sb, "layer", layer, true);
            field(sb, "decision", decision, true);
            field(sb, "agentId", agentId, true);
            field(sb, "target", target, true);
            field(sb, "reason", reason, true);
            field(sb, "eventId", eventId, true);
            field(sb, "sequence", Long.toString(sequence), false);
            field(sb, "eventType", eventType, true);
            field(sb, "tenantId", tenantId, true);
            field(sb, "workflowId", workflowId, true);
            field(sb, "runId", runId, true);
            field(sb, "parentAgentId", parentAgentId, true);
            field(sb, "delegationId", delegationId, true);
            field(sb, "nodeId", nodeId, true);
            field(sb, "attempt", Integer.toString(attempt), false);
            sb.append('}');
            return sb.toString();
        }

        private static void field(StringBuilder sb, String key, String value, boolean string) {
            if (sb.length() > 1) sb.append(',');
            sb.append('"').append(key).append("\":");
            if (value == null) sb.append("null");
            else if (string) sb.append(escape(value));
            else sb.append(value);
        }

        public static AuditEntry fromJsonLine(String line) {
            if (line == null || line.isBlank()) return null;
            try {
                com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
                return new AuditEntry(
                        number(o, "ts", 0L), optStr(o, "traceId"), optStr(o, "layer"),
                        optStr(o, "decision"), optStr(o, "agentId"), optStr(o, "target"),
                        optStr(o, "reason"), optStr(o, "eventId"), number(o, "sequence", 0L),
                        optStr(o, "eventType"), optStr(o, "tenantId"), optStr(o, "workflowId"),
                        optStr(o, "runId"), optStr(o, "parentAgentId"), optStr(o, "delegationId"),
                        optStr(o, "nodeId"), (int) number(o, "attempt", -1L));
            } catch (Exception e) {
                return null;
            }
        }

        private static long number(com.google.gson.JsonObject o, String key, long fallback) {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsLong() : fallback;
        }

        private static String optStr(com.google.gson.JsonObject o, String key) {
            return o.has(key) && o.get(key).isJsonPrimitive() && !o.get(key).isJsonNull()
                    ? o.get(key).getAsString() : null;
        }

        private static String escape(String s) {
            StringBuilder out = new StringBuilder(s.length() + 8).append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
                }
            }
            return out.append('"').toString();
        }
    }
}
