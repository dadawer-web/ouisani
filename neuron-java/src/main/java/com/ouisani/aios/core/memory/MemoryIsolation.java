package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.memory.providers.MemoryRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Defense-in-depth isolation for memory retrieval.
 *
 * <p>A {@link Filter} can be pushed into a provider/store when supported, but
 * callers must still invoke {@link #rowMatchesIsolation(MemoryRecord, String,
 * Filter)} on the returned rows.  This is intentionally stricter than the
 * old permissive legacy path: when a dimension was requested and strict mode
 * is enabled, a missing row marker does not become a default tenant or
 * workflow.</p>
 */
public final class MemoryIsolation {

    public static final String LEGACY_PLACEHOLDER = "__legacy__";

    private MemoryIsolation() {
    }

    /** Query-time dimensions. Null dimensions are intentionally not narrowed. */
    public record Filter(String tenantId, String agentId, String workflowId,
                         String sessionId, boolean strict) {
        public Filter {
            tenantId = clean(tenantId);
            agentId = clean(agentId);
            workflowId = clean(workflowId);
            sessionId = clean(sessionId);
        }

        public Filter(String tenantId, String agentId, String workflowId,
                      String sessionId) {
            this(tenantId, agentId, workflowId, sessionId, true);
        }

        public static Filter permissive(String tenantId, String agentId,
                                        String workflowId, String sessionId) {
            return new Filter(tenantId, agentId, workflowId, sessionId, false);
        }

        public boolean hasBoundary() {
            return tenantId != null || agentId != null || workflowId != null || sessionId != null;
        }
    }

    /** Rows returned after an optional provider pushdown, plus its filter count. */
    public record QueryResult(List<MemoryRecord> rows, int filteredCount) {
        public QueryResult {
            rows = rows == null ? List.of() : List.copyOf(rows);
            filteredCount = Math.max(0, filteredCount);
        }
    }

    /** Context used by adapters that need to validate a write/query boundary. */
    public record Context(String agentId, String tenantId, String workflowId,
                          String sessionId) {
        public Context {
            agentId = clean(agentId);
            tenantId = clean(tenantId);
            workflowId = clean(workflowId);
            sessionId = clean(sessionId);
        }
    }

    /** Strict missing-field error; no placeholder is silently substituted. */
    public static final class IsolationError extends IllegalArgumentException {
        private final List<String> missingFields;

        public IsolationError(String message, List<String> missingFields) {
            super(message);
            this.missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        }

        public List<String> missingFields() {
            return missingFields;
        }
    }

    /**
     * Validate a boundary. In strict mode the required agent/tenant/session
     * dimensions must be present. Legacy compatibility is explicit and is the
     * only path that may fill a placeholder.
     */
    public static Context assertIsolation(Context context, boolean strict,
                                          boolean legacyCompatMode) {
        Context normalized = context == null ? new Context(null, null, null, null) : context;
        List<String> missing = new ArrayList<>();
        if (normalized.agentId() == null) missing.add("agentId");
        if (normalized.tenantId() == null) missing.add("tenantId");
        if (normalized.sessionId() == null) missing.add("sessionId");
        if (strict && !missing.isEmpty() && !legacyCompatMode) {
            throw new IsolationError("memory isolation context is missing: " + String.join(",", missing),
                    missing);
        }
        if (!legacyCompatMode || missing.isEmpty()) return normalized;
        return new Context(
                normalized.agentId() == null ? LEGACY_PLACEHOLDER : normalized.agentId(),
                normalized.tenantId() == null ? LEGACY_PLACEHOLDER : normalized.tenantId(),
                normalized.workflowId(),
                normalized.sessionId() == null ? LEGACY_PLACEHOLDER : normalized.sessionId());
    }

    public static Context assertIsolation(Context context) {
        return assertIsolation(context, true, false);
    }

    /**
     * Post-retrieval row check. The storeAgentId is the boundary used by the
     * backend query; an optional {@code agent=} provenance marker is checked
     * again when a provider returns one. This catches a leaky/old backend
     * without making legacy rows fail solely because they predate the marker.
     */
    public static boolean rowMatchesIsolation(MemoryRecord row, String storeAgentId,
                                              Filter filter) {
        if (row == null || filter == null) return row != null;
        String rowAgent = metadata(row.source(), "agent");
        if (filter.agentId() != null && rowAgent != null
                && !filter.agentId().equals(rowAgent)) return false;
        if (filter.tenantId() != null) {
            String rowTenant = metadata(row.source(), "tenant");
            if (rowTenant == null || !filter.tenantId().equals(rowTenant)) return false;
        }
        // L3 is stable profile/rule context. It remains tenant-bound but is
        // deliberately not tied to a turn workflow/session.
        if (row.layer() == MemoryLayer.L3) return true;

        String rowWorkflow = metadata(row.source(), "workflow");
        String rowSession = metadata(row.source(), "session");
        if (filter.strict()) {
            if (filter.workflowId() != null
                    && (rowWorkflow == null || !filter.workflowId().equals(rowWorkflow))) return false;
            if (filter.sessionId() != null
                    && (rowSession == null || !filter.sessionId().equals(rowSession))) return false;
            return true;
        }
        // Explicitly permissive compatibility mode: only compare dimensions
        // that the old row actually carries; never treat a mismatch as equal.
        if (filter.workflowId() != null && rowWorkflow != null
                && !filter.workflowId().equals(rowWorkflow)) return false;
        if (filter.sessionId() != null && rowSession != null
                && !filter.sessionId().equals(rowSession)) return false;
        return true;
    }

    /** Convenience overload for provider adapters that already scope by Agent. */
    public static boolean rowMatchesIsolation(MemoryRecord row, Filter filter) {
        return rowMatchesIsolation(row, null, filter);
    }

    /** Extract a semicolon-delimited provenance marker. */
    public static String metadata(String source, String name) {
        if (source == null || source.isBlank() || name == null || name.isBlank()) return null;
        String marker = name.trim().toLowerCase(Locale.ROOT) + "=";
        int start = source.toLowerCase(Locale.ROOT).indexOf(marker);
        while (start >= 0 && start > 0 && source.charAt(start - 1) != ';') {
            start = source.toLowerCase(Locale.ROOT).indexOf(marker, start + marker.length());
        }
        if (start < 0) return null;
        int valueStart = start + marker.length();
        int end = source.indexOf(';', valueStart);
        String value = end < 0 ? source.substring(valueStart) : source.substring(valueStart, end);
        return clean(value);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
