package com.ouisani.aios.core.memory;

import com.google.gson.JsonObject;
import com.ouisani.aios.core.memory.providers.MemoryDomain;
import com.ouisani.aios.core.memory.providers.MemoryRecord;
import com.ouisani.aios.core.network.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * A real, asynchronous memory lifecycle:
 * <pre>
 * L0 capture -&gt; L1 extraction -&gt; L2 scenario synthesis -&gt; L3 promotion
 * </pre>
 *
 * <p>The pipeline deliberately keeps the extraction/synthesis boundary explicit.
 * The default extractor is deterministic and local (markers plus sentence
 * segmentation), so the lifecycle can run without an LLM.  A future semantic
 * extractor can replace this class without changing the queue or the store
 * contract.</p>
 *
 * <p>The queue is bounded.  A rejected item is reported to the returned future
 * instead of silently dropping evidence.  Persistence is performed through
 * {@link VersionedMemoryStore}, which gives every lifecycle key a version and
 * keeps the previous layer result in history.</p>
 */
public final class MemoryLifecyclePipeline implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MemoryLifecyclePipeline.class);

    /** EventBus channel emitted once for every lifecycle stage. */
    public static final String STAGE_CHANNEL = "memory.lifecycle.stage";
    /** EventBus channel emitted after all stages for a turn have completed. */
    public static final String COMPLETED_CHANNEL = "memory.lifecycle.completed";
    /** EventBus channel emitted when a queued lifecycle task fails. */
    public static final String FAILED_CHANNEL = "memory.lifecycle.failed";

    private static final int DEFAULT_WORKERS = 2;
    private static final int DEFAULT_QUEUE_CAPACITY = 256;
    private static final int MAX_RAW_CHARS = 32_000;
    private static final int MAX_ATOM_CHARS = 1_000;
    private static final Pattern SENTENCE_SEPARATOR =
            Pattern.compile("(?<=[.!?])\\s+|[\\r\\n。！？!?；;]+");

    private final VersionedMemoryStore store;
    private final ThreadPoolExecutor queue;
    private final ConcurrentHashMap<String, EvidenceCounter> evidence = new ConcurrentHashMap<>();
    private final Set<String> explicitStable = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile boolean closed;

    public MemoryLifecyclePipeline(VersionedMemoryStore store) {
        this(store, DEFAULT_WORKERS, DEFAULT_QUEUE_CAPACITY);
    }

    /**
     * Create a lifecycle queue with explicit capacity, useful for service
     * sizing and deterministic queue-pressure tests.
     */
    public MemoryLifecyclePipeline(VersionedMemoryStore store, int workers, int queueCapacity) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        if (workers <= 0) throw new IllegalArgumentException("workers must be positive");
        if (queueCapacity <= 0) throw new IllegalArgumentException("queueCapacity must be positive");

        this.queue = new ThreadPoolExecutor(
                workers,
                workers,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofVirtual().name("memory-lifecycle-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.queue.allowCoreThreadTimeOut(false);
    }

    /**
     * Submit one completed Agent turn.  The caller only waits for queue
     * admission; extraction and persistence run on the lifecycle workers.
     */
    public CompletableFuture<LifecycleResult> submit(TurnInput input) {
        Objects.requireNonNull(input, "input must not be null");
        CompletableFuture<LifecycleResult> future = new CompletableFuture<>();
        if (closed) {
            future.completeExceptionally(new IllegalStateException("memory lifecycle pipeline is closed"));
            return future;
        }

        pending.add(future);
        submitted.incrementAndGet();
        try {
            queue.execute(() -> run(input, future));
        } catch (RejectedExecutionException e) {
            pending.remove(future);
            failed.incrementAndGet();
            emitFailure(input, e);
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Capture only the raw L0 evidence.  Capture hooks use this for
     * interrupted/failed turns; completed turns run {@link #submit(TurnInput)}
     * so L0 is persisted before the asynchronous semantic stages.
     */
    public CompletableFuture<MemoryRecord> capture(TurnInput input) {
        Objects.requireNonNull(input, "input must not be null");
        CompletableFuture<MemoryRecord> future = new CompletableFuture<>();
        if (closed) {
            future.completeExceptionally(new IllegalStateException("memory lifecycle pipeline is closed"));
            return future;
        }
        pending.add(future);
        submitted.incrementAndGet();
        try {
            queue.execute(() -> {
                try {
                    MemoryRecord record = captureRaw(input);
                    completed.incrementAndGet();
                    future.complete(record);
                } catch (Throwable t) {
                    failed.incrementAndGet();
                    emitFailure(input, t);
                    future.completeExceptionally(t);
                } finally {
                    pending.remove(future);
                }
            });
        } catch (RejectedExecutionException e) {
            pending.remove(future);
            failed.incrementAndGet();
            emitFailure(input, e);
            future.completeExceptionally(e);
        }
        return future;
    }

    /** Number of queued (not currently executing) lifecycle tasks. */
    public int queueDepth() {
        return queue.getQueue().size();
    }

    /** Snapshot queue counters and active worker count. */
    public QueueStats stats() {
        return new QueueStats(
                submitted.get(),
                completed.get(),
                failed.get(),
                queue.getQueue().size(),
                queue.getActiveCount(),
                queue.getQueue().remainingCapacity());
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
        queue.shutdownNow();
        failPending();
    }

    /** Stop workers immediately; queued evidence is failed rather than lost silently. */
    public void shutdownNow() {
        closed = true;
        queue.shutdownNow();
        failPending();
    }

    private void failPending() {
        IllegalStateException closedError = new IllegalStateException("memory lifecycle pipeline is closed");
        for (CompletableFuture<?> future : pending) {
            future.completeExceptionally(closedError);
        }
        pending.clear();
    }

    private void run(TurnInput input, CompletableFuture<LifecycleResult> future) {
        try {
            LifecycleResult result = process(input);
            completed.incrementAndGet();
            emitCompleted(result);
            future.complete(result);
        } catch (Throwable t) {
            failed.incrementAndGet();
            emitFailure(input, t);
            future.completeExceptionally(t);
            log.warn("[MemoryLifecycle] turn {} failed at lifecycle boundary: {}",
                    input.turnId(), t.getMessage());
        } finally {
            pending.remove(future);
        }
    }

    private LifecycleResult process(TurnInput input) {
        long now = System.currentTimeMillis();
        // Every lifecycle record is addressable as a Chat Memory asset.  The
        // asset registry is intentionally best-effort here: capture must not
        // fail merely because an application has not pre-registered its
        // per-Agent namespace.
        MemoryAssetRegistry.global().ensureChatMemory(input.agentId(), input.tenantId());

        // L0: keep the original evidence together.  It is intentionally not
        // deduplicated by content; turnId is the evidence identity.
        String l0Key = "l0:" + tenantKey(input) + ":" + keyPart(input.turnId());
        String raw = rawConversation(input);
        MemoryDomain rawDomain = !input.userMessage().isBlank()
                ? MemoryDomain.USER : MemoryDomain.AGENT;
        MemoryRecord existingL0 = store.current(input.agentId(), l0Key);
        String l0Source = "lifecycle:l0-capture" + metadata(input);
        MemoryRecord l0 = MemoryRecord.raw(l0Key, raw, l0Source, now, rawDomain);
        if (existingL0 == null || existingL0.layer() != MemoryLayer.L0
                || !raw.equals(existingL0.content())
                || !l0Source.equals(existingL0.source())) {
            persist(input, l0);
        } else {
            l0 = existingL0;
        }
        emitStage(input, "L0_CAPTURE", MemoryLayer.L0, 1, l0.key());

        // L1: turn-local atomic facts/preferences/constraints/events.
        Map<String, Candidate> candidates = extract(input);
        List<MemoryRecord> l1 = new ArrayList<>();
        for (Candidate candidate : candidates.values()) {
            String fingerprint = fingerprint(candidate.content());
            String evidenceKey = evidenceKey(input.agentId(), input.tenantId(), fingerprint);
            EvidenceCounter counter = evidence.computeIfAbsent(evidenceKey, ignored -> new EvidenceCounter());
            counter.turnIds.add(input.turnId());
            if (candidate.stable()) explicitStable.add(evidenceKey);

            String scope = firstNonBlank(input.workflowId(), input.sessionId(), input.agentId());
            MemoryRecord atom = MemoryRecord.atomic(
                    "l1:" + tenantKey(input) + ":" + fingerprint,
                    candidate.content(),
                    "lifecycle:l1-extraction:" + candidate.kind()
                            + ";turn=" + keyPart(input.turnId())
                            + ";scope=" + keyPart(scope)
                            + metadata(input)
                            + ";from=" + l0.key(),
                    now,
                    candidate.confidence(),
                    candidate.domain());
            persist(input, atom);
            l1.add(atom);
        }
        emitStage(input, "L1_EXTRACTION", MemoryLayer.L1, l1.size(),
                l1.stream().map(MemoryRecord::key).toList());

        // L2: synthesize a scenario even for one explicit atom.  This makes
        // the stage observable while still allowing ordinary conversational
        // sentences to remain only at L1 when no candidate was extracted.
        Optional<MemoryRecord> l2 = Optional.empty();
        if (!l1.isEmpty()) {
            String scope = firstNonBlank(input.workflowId(), input.sessionId(), input.agentId());
            List<MemoryRecord> scenarioInputs = scenarioInputs(input, l1, scope);
            String joined = scenarioInputs.stream().map(MemoryRecord::content)
                    .reduce((a, b) -> a + "; " + b).orElse("");
            String scenarioKey = "l2:" + tenantKey(input) + ":" + keyPart(scope)
                    + ":" + fingerprint(joined).substring(0, 16);
            double confidence = Math.min(0.99, l1.stream()
                    .mapToDouble(MemoryRecord::confidence).average().orElse(0.5) * 0.95);
            String provenance = "lifecycle:l2-synthesis" + metadata(input) + ";from="
                    + String.join(",", scenarioInputs.stream().map(MemoryRecord::key).toList());
            MemoryRecord scenario = MemoryRecord.scenario(
                    scenarioKey,
                    "scenario[" + scope + "]: " + joined,
                    provenance,
                    now,
                    confidence,
                    MemoryDomain.AGENT);
            persist(input, scenario);
            l2 = Optional.of(scenario);
            emitStage(input, "L2_SCENARIO_SYNTHESIS", MemoryLayer.L2, 1, scenario.key());
        } else {
            emitStage(input, "L2_SCENARIO_SYNTHESIS", MemoryLayer.L2, 0, List.of());
        }

        // L3: promote only evidence that is stable across two distinct turns,
        // or explicitly marked [stable]/[policy]/[always]/[remember].
        List<MemoryRecord> l3 = new ArrayList<>();
        if (l2.isPresent()) {
            for (Candidate candidate : candidates.values()) {
                String fingerprint = fingerprint(candidate.content());
                String evidenceKey = evidenceKey(input.agentId(), input.tenantId(), fingerprint);
                EvidenceCounter counter = evidence.get(evidenceKey);
                boolean promotionEligible = candidate.domain() == MemoryDomain.USER || candidate.stable();
                boolean promotable = promotionEligible && (explicitStable.contains(evidenceKey)
                        || repeatedAcrossTurns(input.agentId(),
                        "l1:" + tenantKey(input) + ":" + fingerprint,
                        input.tenantId(), counter, input.turnId()));
                if (!promotable) continue;

                double confidence = Math.min(0.99, candidate.confidence() + 0.10);
                MemoryRecord core = MemoryRecord.core(
                        "l3:" + tenantKey(input) + ":" + fingerprint,
                        candidate.content(),
                        "lifecycle:l3-promotion" + metadata(input) + ";from=" + l2.get().key(),
                        now,
                        confidence,
                        candidate.domain());
                persist(input, core);
                l3.add(core);
            }
        }
        emitStage(input, "L3_PROMOTION", MemoryLayer.L3, l3.size(),
                l3.stream().map(MemoryRecord::key).toList());

        return new LifecycleResult(
                input,
                l0,
                List.copyOf(l1),
                l2,
                List.copyOf(l3),
                System.currentTimeMillis());
    }

    private MemoryRecord captureRaw(TurnInput input) {
        long now = System.currentTimeMillis();
        MemoryAssetRegistry.global().ensureChatMemory(input.agentId(), input.tenantId());
        String raw = rawConversation(input);
        MemoryDomain domain = !input.userMessage().isBlank()
                ? MemoryDomain.USER : MemoryDomain.AGENT;
        MemoryRecord record = MemoryRecord.raw(
                "l0:" + tenantKey(input) + ":" + keyPart(input.turnId()), raw,
                "lifecycle:l0-capture" + metadata(input), now, domain);
        persist(input, record);
        emitStage(input, "L0_CAPTURE", MemoryLayer.L0, 1, record.key());
        return record;
    }

    private Map<String, Candidate> extract(TurnInput input) {
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        addCandidates(candidates, input.userMessage(), "user", MemoryDomain.USER, 0.90);
        addCandidates(candidates, input.assistantResponse(), "assistant", MemoryDomain.AGENT, 0.72);
        for (ToolObservation observation : input.toolObservations()) {
            addCandidates(candidates, observation.result(), "tool:" + observation.name(),
                    MemoryDomain.AGENT, 0.65);
        }
        return candidates;
    }

    private List<MemoryRecord> scenarioInputs(TurnInput input,
                                               List<MemoryRecord> current,
                                               String scope) {
        LinkedHashMap<String, MemoryRecord> merged = new LinkedHashMap<>();
        for (MemoryRecord record : current) merged.put(record.key(), record);
        // Keep a bounded, same-scope context window for L2 synthesis.  L1
        // records remain atomic; only the scenario view is widened.
        List<MemoryRecord> historical = store.listByLayer(input.agentId(), MemoryLayer.L1);
        for (int i = historical.size() - 1; i >= 0 && merged.size() < 16; i--) {
            MemoryRecord record = historical.get(i);
            if (record.source() != null
                    && record.source().contains("scope=" + keyPart(scope))
                    && sameTenant(record.source(), input.tenantId())) {
                merged.putIfAbsent(record.key(), record);
            }
        }
        return List.copyOf(merged.values());
    }

    private boolean repeatedAcrossTurns(String agentId, String atomKey,
                                        String tenantId,
                                        EvidenceCounter counter, String currentTurnId) {
        Set<String> turns = new HashSet<>();
        if (currentTurnId != null) turns.add(currentTurnId);
        if (counter != null) turns.addAll(counter.turnIds);
        if (turns.size() >= 2) return true;
        // Version history is the durable cross-turn signal.  The in-memory
        // counter guards the first write; the history check survives runtime
        // reconfiguration and prevents same-turn replays from promotion.
        turns.addAll(store.history(agentId, atomKey).stream()
                .map(MemoryRecord::source)
                .filter(source -> sameTenant(source, tenantId))
                .map(this::turnFromSource)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet()));
        return turns.size() >= 2;
    }

    private String turnFromSource(String source) {
        if (source == null) return null;
        int index = source.indexOf("turn=");
        if (index < 0) return null;
        String value = source.substring(index + 5);
        int end = value.indexOf(';');
        return end >= 0 ? value.substring(0, end) : value;
    }

    private void addCandidates(Map<String, Candidate> candidates,
                               String text,
                               String origin,
                               MemoryDomain domain,
                               double baseConfidence) {
        if (text == null || text.isBlank()) return;
        String bounded = text.length() > MAX_RAW_CHARS
                ? text.substring(0, MAX_RAW_CHARS) : text;
        for (String rawPart : SENTENCE_SEPARATOR.split(bounded)) {
            String part = cleanPart(rawPart);
            if (part.length() < 2 || isNoise(part)) continue;

            Marker marker = marker(part);
            String content = marker.content() != null ? marker.content() : part;
            content = cleanPart(content);
            if (content.length() < 2) continue;
            if (content.length() > MAX_ATOM_CHARS) content = content.substring(0, MAX_ATOM_CHARS);

            double confidence = baseConfidence;
            if (marker.kind() != null) confidence += 0.05;
            if (marker.stable()) confidence += 0.08;
            confidence = Math.min(0.99, confidence);
            Candidate candidate = new Candidate(
                    content,
                    marker.kind() != null ? marker.kind() : origin,
                    domain,
                    marker.stable(),
                    confidence);
            String key = fingerprint(content);
            Candidate previous = candidates.get(key);
            if (previous == null || candidate.confidence() > previous.confidence()) {
                candidates.put(key, candidate);
            } else if (candidate.stable() && !previous.stable()) {
                candidates.put(key, new Candidate(previous.content(), previous.kind(),
                        previous.domain(), true, Math.max(previous.confidence(), candidate.confidence())));
            }
        }
    }

    private static Marker marker(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        String[] names = {"fact", "preference", "constraint", "event", "stable", "policy", "always", "remember"};
        for (String name : names) {
            String bracket = "[" + name + "]";
            if (lower.startsWith(bracket)) {
                return new Marker(name, value.substring(bracket.length()).trim(),
                        name.equals("stable") || name.equals("policy")
                                || name.equals("always") || name.equals("remember"));
            }
            String prefix = name + ":";
            if (lower.startsWith(prefix)) {
                return new Marker(name, value.substring(prefix.length()).trim(),
                        name.equals("stable") || name.equals("policy")
                                || name.equals("always") || name.equals("remember"));
            }
        }
        return Marker.NONE;
    }

    private static String cleanPart(String value) {
        if (value == null) return "";
        String result = value.trim();
        while (result.startsWith("-") || result.startsWith("*") || result.startsWith("•")) {
            result = result.substring(1).trim();
        }
        return result;
    }

    private static boolean isNoise(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[.!?。！？]+$", "").trim();
        return normalized.equals("ok")
                || normalized.equals("okay")
                || normalized.equals("sure")
                || normalized.equals("done")
                || normalized.equals("thanks")
                || normalized.equals("thank you");
    }

    private void persist(TurnInput input, MemoryRecord record) {
        if (!store.store(input.agentId(), record)) {
            throw new IllegalStateException("memory provider rejected " + record.layer() + " record " + record.key());
        }
    }

    private static String rawConversation(TurnInput input) {
        StringBuilder raw = new StringBuilder();
        raw.append("user: ").append(input.userMessage()).append('\n');
        raw.append("assistant: ").append(input.assistantResponse());
        for (ToolObservation observation : input.toolObservations()) {
            raw.append('\n').append("tool[").append(observation.name()).append("]: ")
                    .append(observation.result());
        }
        String value = raw.toString();
        return value.length() > MAX_RAW_CHARS ? value.substring(0, MAX_RAW_CHARS) : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "global";
    }

    private static String keyPart(String value) {
        if (value == null || value.isBlank()) return "global";
        String normalized = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return normalized.length() > 80 ? normalized.substring(0, 80) : normalized;
    }

    private static String tenantKey(TurnInput input) {
        String tenant = input.tenantId();
        if (tenant == null || tenant.isBlank()) return "global";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "t" + HexFormat.of().formatHex(
                    digest.digest(tenant.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Stable, parseable provenance markers used by the recall isolation gate. */
    private static String metadata(TurnInput input) {
        StringBuilder result = new StringBuilder();
        appendMetadata(result, "asset", MemoryAsset.chatAssetId(input.agentId()));
        appendMetadata(result, "agent", input.agentId());
        appendMetadata(result, "tenant", input.tenantId());
        appendMetadata(result, "workflow", input.workflowId());
        appendMetadata(result, "session", input.sessionId());
        return result.toString();
    }

    private static void appendMetadata(StringBuilder target, String name, String value) {
        if (value == null || value.isBlank()) return;
        target.append(';').append(name).append('=').append(value
                .replace(';', '_').replace('=', '_'));
    }

    private static String evidenceKey(String agentId, String tenantId, String fingerprint) {
        return agentId + "|" + (tenantId == null ? "global" : tenantId) + "|" + fingerprint;
    }

    private static boolean sameTenant(String source, String tenantId) {
        return Objects.equals(metadataValue(source, "tenant"), tenantId);
    }

    private static String metadataValue(String source, String name) {
        if (source == null || source.isBlank()) return null;
        String marker = name + "=";
        int start = source.indexOf(marker);
        while (start >= 0 && start > 0 && source.charAt(start - 1) != ';') {
            start = source.indexOf(marker, start + marker.length());
        }
        if (start < 0) return null;
        int valueStart = start + marker.length();
        int end = source.indexOf(';', valueStart);
        String value = end < 0 ? source.substring(valueStart) : source.substring(valueStart, end);
        return value.isBlank() ? null : value;
    }

    private static String fingerprint(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.trim().toLowerCase(Locale.ROOT)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void emitStage(TurnInput input, String stage, MemoryLayer layer,
                                  int count, Object keys) {
        JsonObject payload = basePayload(input);
        payload.addProperty("stage", stage);
        payload.addProperty("layer", layer.name());
        payload.addProperty("count", count);
        if (keys instanceof String key) {
            payload.addProperty("key", key);
        } else if (keys instanceof List<?> list) {
            var jsonKeys = new com.google.gson.JsonArray();
            for (Object key : list) jsonKeys.add(String.valueOf(key));
            payload.add("keys", jsonKeys);
        }
        EventBus.instance().broadcast(STAGE_CHANNEL, payload.toString());
    }

    private static void emitCompleted(LifecycleResult result) {
        JsonObject payload = basePayload(result.input());
        payload.addProperty("stage", "COMPLETED");
        payload.addProperty("l0", result.l0() != null);
        payload.addProperty("l1Count", result.l1().size());
        payload.addProperty("l2", result.l2().isPresent());
        payload.addProperty("l3Count", result.l3().size());
        EventBus.instance().broadcast(COMPLETED_CHANNEL, payload.toString());
    }

    private static void emitFailure(TurnInput input, Throwable error) {
        JsonObject payload = basePayload(input);
        payload.addProperty("error", error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage());
        EventBus.instance().broadcast(FAILED_CHANNEL, payload.toString());
    }

    private static JsonObject basePayload(TurnInput input) {
        JsonObject payload = new JsonObject();
        payload.addProperty("agentId", input.agentId());
        payload.addProperty("turnId", input.turnId());
        if (input.tenantId() != null) payload.addProperty("tenantId", input.tenantId());
        if (input.workflowId() != null) payload.addProperty("workflowId", input.workflowId());
        if (input.sessionId() != null) payload.addProperty("sessionId", input.sessionId());
        return payload;
    }

    public record TurnInput(
            String tenantId,
            String workflowId,
            String sessionId,
            String agentId,
            String turnId,
            String userMessage,
            String assistantResponse,
            List<ToolObservation> toolObservations) {

        public TurnInput {
            if (agentId == null || agentId.isBlank()) throw new IllegalArgumentException("agentId must not be blank");
            agentId = agentId.trim();
            tenantId = tenantId == null || tenantId.isBlank() ? null : tenantId.trim();
            workflowId = workflowId == null || workflowId.isBlank() ? null : workflowId.trim();
            turnId = turnId == null || turnId.isBlank() ? UUID.randomUUID().toString() : turnId.trim();
            sessionId = sessionId == null || sessionId.isBlank() ? agentId : sessionId.trim();
            userMessage = userMessage == null ? "" : userMessage;
            assistantResponse = assistantResponse == null ? "" : assistantResponse;
            toolObservations = toolObservations == null ? List.of() : List.copyOf(toolObservations);
        }

        public static TurnInput of(String agentId, String turnId,
                                   String userMessage, String assistantResponse) {
            return new TurnInput(null, null, null, agentId, turnId,
                    userMessage, assistantResponse, List.of());
        }
    }

    public record ToolObservation(String name, String result) {
        public ToolObservation {
            name = name == null || name.isBlank() ? "unknown" : name.trim();
            result = result == null ? "" : result;
        }
    }

    public record LifecycleResult(
            TurnInput input,
            MemoryRecord l0,
            List<MemoryRecord> l1,
            Optional<MemoryRecord> l2,
            List<MemoryRecord> l3,
            long completedAt) {
        public LifecycleResult {
            l1 = l1 == null ? List.of() : List.copyOf(l1);
            l2 = l2 == null ? Optional.empty() : l2;
            l3 = l3 == null ? List.of() : List.copyOf(l3);
        }

        public boolean promotedToL3() {
            return !l3.isEmpty();
        }
    }

    public record QueueStats(
            long submitted,
            long completed,
            long failed,
            int queueDepth,
            int activeWorkers,
            int remainingCapacity) {
    }

    private record Candidate(String content, String kind, MemoryDomain domain,
                             boolean stable, double confidence) {
    }

    private record Marker(String kind, String content, boolean stable) {
        private static final Marker NONE = new Marker(null, null, false);
    }

    private static final class EvidenceCounter {
        final Set<String> turnIds = ConcurrentHashMap.newKeySet();
    }
}
