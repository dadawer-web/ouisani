package com.ouisani.aios.core.wiki;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import com.ouisani.aios.core.audit.UnifiedAuditLog;
import com.ouisani.aios.core.config.AiosPaths;
import com.ouisani.aios.core.ipc.MemoryAccessContext;
import com.ouisani.aios.core.ipc.MemoryRecord;
import com.ouisani.aios.core.ipc.SharedMemoryManager;
import com.ouisani.aios.core.ipc.TraceContext;
import com.ouisani.aios.core.memory.VersionedMemoryStore;
import com.ouisani.aios.core.memory.MemoryLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compiles governed memory records into a Wiki read model.
 *
 * <p>The compiler deliberately does not own another memory database.  Scoped
 * {@link MemoryRecord}s remain the source of truth; every query re-projects
 * the records visible to its {@link MemoryAccessContext}.  The only persisted
 * Wiki state is the user's confirmation decision, which is presentation and
 * governance metadata keyed by the deterministic projection id.</p>
 */
public final class WikiCompiler {

    private static final Logger log = LoggerFactory.getLogger(WikiCompiler.class);
    private static final Gson JSON = new Gson();

    private static final class Holder {
        static final WikiCompiler INSTANCE = new WikiCompiler();
    }

    public static WikiCompiler instance() {
        return Holder.INSTANCE;
    }

    /** User-facing Wiki buckets. */
    public enum Category {
        PROJECTS,
        TOPICS,
        DECISIONS,
        SOURCES,
        ARTIFACTS;

        public static Category parse(String value) {
            if (value == null || value.isBlank()) return null;
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            for (Category category : values()) {
                if (category.name().equals(normalized)
                        || category.name().substring(0, category.name().length() - 1).equals(normalized)) {
                    return category;
                }
            }
            return null;
        }
    }

    /** A compiled Wiki page with the original governance metadata intact. */
    public record WikiEntry(
            String wikiId,
            Category category,
            String title,
            String content,
            String memoryId,
            String namespace,
            String source,
            String sourceRef,
            String ownerAgentId,
            String sourceAgentId,
            String workflowId,
            String traceId,
            double confidence,
            long version,
            MemoryLayer layer,
            String visibilityScope,
            String tenantId,
            String teamId,
            boolean userConfirmed,
            boolean superseded,
            String supersedesWikiId,
            String basis,
            Set<String> tags,
            long createdAt,
            long updatedAt) {

        public WikiEntry {
            tags = tags == null ? Set.of() : Set.copyOf(tags);
            title = title == null || title.isBlank() ? memoryId : title;
            content = content == null ? "" : content;
            visibilityScope = visibilityScope == null ? "UNKNOWN" : visibilityScope;
        }

        public WikiEntry withConfirmation(boolean confirmed) {
            return new WikiEntry(wikiId, category, title, content, memoryId, namespace,
                    source, sourceRef, ownerAgentId, sourceAgentId, workflowId, traceId,
                    confidence, version, layer, visibilityScope, tenantId, teamId, confirmed,
                    superseded, supersedesWikiId, basis, tags, createdAt, updatedAt);
        }

        public WikiEntry withRelationship(boolean isSuperseded, String supersedes) {
            return new WikiEntry(wikiId, category, title, content, memoryId, namespace,
                    source, sourceRef, ownerAgentId, sourceAgentId, workflowId, traceId,
                    confidence, version, layer, visibilityScope, tenantId, teamId, userConfirmed,
                    isSuperseded, supersedes, basis, tags, createdAt, updatedAt);
        }
    }

    private record ConfirmationState(boolean confirmed, long changedAt, String agentId) {}

    private final ConcurrentHashMap<String, ConfirmationState> confirmations = new ConcurrentHashMap<>();
    private volatile Path confirmationFile = Paths.get(AiosPaths.memoryDbDir(), "wiki-confirmations.json");
    private volatile boolean loaded;

    private WikiCompiler() {}

    /**
     * Compile all records supplied by an already-authorized caller.
     * This overload is useful for recovery/import code and deterministic tests.
     */
    public List<WikiEntry> compile(Iterable<MemoryRecord> records, MemoryAccessContext context) {
        Objects.requireNonNull(records, "records must not be null");
        ensureLoaded();
        List<WikiEntry> entries = new ArrayList<>();
        for (MemoryRecord record : records) {
            if (record != null && !record.expiredAt(System.currentTimeMillis())) {
                entries.add(project(record));
            }
        }
        return finish(entries);
    }

    /** Compile records visible to the caller, optionally including legacy memory. */
    public List<WikiEntry> compileVisible(MemoryAccessContext context, boolean includeLegacy) {
        MemoryAccessContext caller = context == null ? MemoryAccessContext.current() : context;
        if (caller == null || !caller.hasIdentity()) return List.of();
        List<WikiEntry> entries = new ArrayList<>(compile(
                SharedMemoryManager.instance().listVisibleMemory(caller), caller));
        if (includeLegacy) {
            VersionedMemoryStore legacy = VersionedMemoryStore.getPrimaryStore();
            if (legacy != null) {
                for (com.ouisani.aios.core.memory.providers.MemoryRecord record
                        : legacy.listCurrent(caller.agentId())) {
                    if (record != null) entries.add(projectLegacy(caller.agentId(), record));
                }
            }
        }
        return finish(entries);
    }

    /** Query the compiled read model without bypassing scoped-memory access checks. */
    public List<WikiEntry> query(MemoryAccessContext context, String namespace,
                                 Category category, String search, Boolean confirmed,
                                 boolean includeLegacy) {
        String ns = clean(namespace);
        String needle = clean(search);
        List<WikiEntry> result = new ArrayList<>();
        for (WikiEntry entry : compileVisible(context, includeLegacy)) {
            if (ns != null && !ns.equalsIgnoreCase(entry.namespace())) continue;
            if (category != null && entry.category() != category) continue;
            if (confirmed != null && entry.userConfirmed() != confirmed) continue;
            if (needle != null && !matches(entry, needle)) continue;
            result.add(entry);
        }
        return List.copyOf(result);
    }

    /** Mark a visible Wiki page as confirmed or unconfirmed by the user. */
    public Optional<WikiEntry> confirm(String wikiId, MemoryAccessContext context, boolean confirmed) {
        String id = clean(wikiId);
        MemoryAccessContext caller = context == null ? MemoryAccessContext.current() : context;
        if (id == null || caller == null || !caller.hasIdentity()) return Optional.empty();
        Optional<WikiEntry> visible = compileVisible(caller, true).stream()
                .filter(entry -> id.equals(entry.wikiId())).findFirst();
        if (visible.isEmpty()) return Optional.empty();
        ConfirmationState state = new ConfirmationState(confirmed, System.currentTimeMillis(), caller.agentId());
        confirmations.put(id, state);
        persist();
        WikiEntry updated = visible.get().withConfirmation(confirmed);
        UnifiedAuditLog.append(new UnifiedAuditLog.TimelineEvent(
                UnifiedAuditLog.LAYER_WIKI, "WIKI", "WIKI_CONFIRMATION",
                caller.agentId(), updated.namespace(),
                "wikiId=" + updated.wikiId() + ",confirmed=" + confirmed,
                auditContext(caller)));
        return Optional.of(updated);
    }

    /** Override the confirmation sidecar location in an isolated test. */
    public synchronized void setConfirmationFileForTest(Path path) {
        confirmationFile = Objects.requireNonNull(path, "path must not be null");
        confirmations.clear();
        loaded = false;
    }

    /** Clear only compiled Wiki metadata; authoritative MemoryRecords are untouched. */
    public synchronized void clearConfirmationMetadataForTest() {
        confirmations.clear();
        loaded = true;
        try {
            Files.deleteIfExists(confirmationFile);
        } catch (Exception ignored) { }
    }

    private WikiEntry project(MemoryRecord record) {
        Category category = categoryOf(record.tags(), record.memoryId(), record.namespace(),
                record.source(), record.contentType(), record.content());
        String title = titleOf(record.tags(), record.content(), record.memoryId());
        String supersedes = tagValue(record.tags(), "supersedes:", "replaces:");
        String basis = tagValue(record.tags(), "basis:", "reason:", "rationale:");
        String wikiId = idFor(record.namespace(), record.memoryId(), record.version(), category);
        return applyConfirmation(new WikiEntry(wikiId, category, title, record.content(),
                record.memoryId(), record.namespace(), record.source(), record.sourceRef(),
                record.ownerAgentId(), record.sourceAgentId(), record.workflowId(), record.traceId(),
                record.confidence(), record.version(), record.layer(), record.scope().name(), record.tenantId(),
                record.teamId(), confirmedByTag(record.tags()), false, supersedes, basis,
                record.tags(), record.createdAt(), record.updatedAt()));
    }

    private WikiEntry projectLegacy(String agentId,
                                    com.ouisani.aios.core.memory.providers.MemoryRecord record) {
        String key = record.key() == null ? "legacy-" + record.timestamp() : record.key();
        Set<String> tags = new LinkedHashSet<>();
        tags.add("legacy");
        tags.add("domain:" + record.domain().name().toLowerCase(Locale.ROOT));
        Category category = categoryOf(tags, key, "legacy", record.source(), "text/plain", record.content());
        String wikiId = idFor("legacy", agentId + ":" + key, record.version(), category);
        return applyConfirmation(new WikiEntry(wikiId, category,
                titleOf(tags, record.content(), key), record.content(),
                "legacy:" + agentId + ":" + key, "legacy", record.source(), null,
                agentId, agentId, null, null, record.confidence(), record.version(),
                record.layer(),
                "LEGACY_" + record.domain().name(), null, null, false, false, null, null,
                tags, record.timestamp(), record.timestamp()));
    }

    private List<WikiEntry> finish(Collection<WikiEntry> source) {
        ensureLoaded();
        Map<String, WikiEntry> byId = new LinkedHashMap<>();
        Map<String, String> byMemory = new LinkedHashMap<>();
        for (WikiEntry entry : source) {
            byId.put(entry.wikiId(), entry);
            byMemory.put(entry.memoryId(), entry.wikiId());
        }
        Set<String> supersededIds = new LinkedHashSet<>();
        List<WikiEntry> normalized = new ArrayList<>();
        for (WikiEntry entry : byId.values()) {
            String target = normalizeTarget(entry.supersedesWikiId(), byId, byMemory);
            if (target != null) supersededIds.add(target);
            normalized.add(entry.withRelationship(supersededIds.contains(entry.wikiId()),
                    target == null ? entry.supersedesWikiId() : target));
        }
        // The first pass may discover a target after its entry was emitted.
        normalized = normalized.stream()
                .map(entry -> entry.withRelationship(supersededIds.contains(entry.wikiId()),
                        Optional.ofNullable(normalizeTarget(entry.supersedesWikiId(), byId, byMemory))
                                .orElse(entry.supersedesWikiId())))
                .sorted(Comparator.comparing(WikiEntry::updatedAt).reversed()
                        .thenComparing(WikiEntry::title, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return List.copyOf(normalized);
    }

    private WikiEntry applyConfirmation(WikiEntry entry) {
        ConfirmationState state = confirmations.get(entry.wikiId());
        return state == null ? entry : entry.withConfirmation(state.confirmed());
    }

    private boolean matches(WikiEntry entry, String needle) {
        String q = needle.toLowerCase(Locale.ROOT);
        return contains(entry.title(), q) || contains(entry.content(), q)
                || contains(entry.source(), q) || contains(entry.sourceRef(), q)
                || contains(entry.memoryId(), q) || entry.tags().stream().anyMatch(tag -> contains(tag, q));
    }

    private static boolean contains(String value, String q) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(q);
    }

    private static String normalizeTarget(String raw, Map<String, WikiEntry> byId,
                                          Map<String, String> byMemory) {
        String value = clean(raw);
        if (value == null) return null;
        if (byId.containsKey(value)) return value;
        return byMemory.get(value);
    }

    private static Category categoryOf(Set<String> tags, String memoryId, String namespace,
                                       String source, String contentType, String content) {
        String explicit = tagValue(tags, "wiki:category=", "wiki:category:", "category:");
        Category parsed = Category.parse(explicit);
        if (parsed != null) return parsed;
        String all = ((memoryId == null ? "" : memoryId) + " "
                + (namespace == null ? "" : namespace) + " "
                + (source == null ? "" : source) + " "
                + (contentType == null ? "" : contentType) + " "
                + (content == null ? "" : content)).toLowerCase(Locale.ROOT);
        if (hasTag(tags, "decision") || all.contains("decision") || all.contains("adr") || all.contains("rationale")) {
            return Category.DECISIONS;
        }
        if (hasTag(tags, "artifact") || isArtifact(all, contentType)) return Category.ARTIFACTS;
        if (hasTag(tags, "source") || all.contains("tool_result") || all.contains("conversation")
                || all.contains("dialog") || all.contains("raw-source")) return Category.SOURCES;
        if (hasTag(tags, "project") || namespace != null && (namespace.contains("project")
                || namespace.startsWith("task/"))) return Category.PROJECTS;
        return Category.TOPICS;
    }

    private static boolean isArtifact(String all, String contentType) {
        if (contentType != null && !contentType.equalsIgnoreCase("text/plain")) return true;
        return all.contains("artifact") || all.contains("/file") || all.matches(".*\\.(pdf|md|java|py|json|csv|html)\\b.*");
    }

    private static String titleOf(Set<String> tags, String content, String fallback) {
        String explicit = tagValue(tags, "title:", "wiki:title:");
        if (explicit != null) return explicit;
        if (content != null) {
            String first = content.replace('\r', '\n').lines()
                    .map(String::trim).filter(line -> !line.isBlank()).findFirst().orElse(null);
            if (first != null) return first.length() > 96 ? first.substring(0, 96) + "…" : first;
        }
        return fallback;
    }

    private static boolean confirmedByTag(Set<String> tags) {
        for (String tag : tags == null ? Set.<String>of() : tags) {
            String normalized = tag.toLowerCase(Locale.ROOT).replace('_', '-');
            if (normalized.equals("confirmed") || normalized.equals("user-confirmed")
                    || normalized.equals("confirmed:true") || normalized.equals("user-confirmed:true")) return true;
        }
        return false;
    }

    private static boolean hasTag(Set<String> tags, String expected) {
        if (tags == null) return false;
        for (String tag : tags) {
            if (expected.equalsIgnoreCase(tag) || tag.toLowerCase(Locale.ROOT).startsWith(expected.toLowerCase(Locale.ROOT) + ":")) return true;
        }
        return false;
    }

    private static String tagValue(Set<String> tags, String... prefixes) {
        if (tags == null) return null;
        for (String tag : tags) {
            for (String prefix : prefixes) {
                if (tag.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    String value = clean(tag.substring(prefix.length()));
                    if (value != null) return value;
                }
            }
        }
        return null;
    }

    private static String idFor(String namespace, String memoryId, long version, Category category) {
        String input = String.valueOf(namespace) + '\u0000' + memoryId + '\u0000' + version + '\u0000' + category.name();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("wiki_");
            for (int i = 0; i < 12; i++) hex.append(String.format(Locale.ROOT, "%02x", digest[i]));
            return hex.toString();
        } catch (Exception e) {
            return "wiki_" + Integer.toHexString(input.hashCode());
        }
    }

    private synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            if (!Files.exists(confirmationFile)) return;
            JsonElement parsed = JsonParser.parseString(Files.readString(confirmationFile));
            if (!parsed.isJsonObject()) return;
            for (Map.Entry<String, JsonElement> item : parsed.getAsJsonObject().entrySet()) {
                if (!item.getValue().isJsonObject()) continue;
                JsonObject value = item.getValue().getAsJsonObject();
                boolean confirmed = value.has("confirmed") && value.get("confirmed").getAsBoolean();
                long changedAt = value.has("changedAt") ? value.get("changedAt").getAsLong() : 0L;
                String agentId = value.has("agentId") ? value.get("agentId").getAsString() : null;
                confirmations.put(item.getKey(), new ConfirmationState(confirmed, changedAt, agentId));
            }
        } catch (Exception e) {
            log.warn("[Wiki] confirmation metadata could not be loaded: {}", e.getMessage());
        }
    }

    private synchronized void persist() {
        try {
            Path parent = confirmationFile.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            JsonObject root = new JsonObject();
            for (Map.Entry<String, ConfirmationState> item : confirmations.entrySet()) {
                JsonObject value = new JsonObject();
                value.addProperty("confirmed", item.getValue().confirmed());
                value.addProperty("changedAt", item.getValue().changedAt());
                if (item.getValue().agentId() != null) value.addProperty("agentId", item.getValue().agentId());
                root.add(item.getKey(), value);
            }
            Files.writeString(confirmationFile, JSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[Wiki] confirmation metadata could not be persisted: {}", e.getMessage());
        }
    }

    private static UnifiedAuditLog.AuditContext auditContext(MemoryAccessContext caller) {
        return new UnifiedAuditLog.AuditContext(caller.effectiveTenantId(), caller.effectiveWorkflowId(),
                null, TraceContext.getCurrentTraceId(), caller.agentId(), null, null, null, -1);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
