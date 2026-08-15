package com.ouisani.aios.test.redteam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ouisani.aios.core.permission.EscalationPolicy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Replays immutable tool-call traces produced by a real API LLM through the
 * production Java policy and the four Computing baselines.  The LLM is not
 * called during replay, so every architecture sees exactly the same trace.
 */
public final class LlmTraceReplayRunner {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEADLINE_MS = Integer.getInteger("neuron.replay.deadlineMs", 10);
    private static final int SHARED_POOL = Integer.getInteger("neuron.replay.sharedPool", 4);
    private static final int PERMISSION_POOL = Integer.getInteger("neuron.replay.permissionPool", 2);
    private static final int QUEUE = Integer.getInteger("neuron.replay.queueCapacity", 512);

    private enum Architecture {
        LAYER_SEPARATED_FAIL_OPEN(false, false, true),
        LAYER_SEPARATED_FAIL_CLOSED(false, false, false),
        ISOLATED_NO_COUPLING(true, false, false),
        NEURON_COUPLED(true, true, false);

        final boolean isolated;
        final boolean coupled;
        final boolean failOpen;

        Architecture(boolean isolated, boolean coupled, boolean failOpen) {
            this.isolated = isolated;
            this.coupled = coupled;
            this.failOpen = failOpen;
        }
    }

    private record ToolCall(String name, int estimatedTokens) {}
    private record Session(int trial, int variant, int gasLimit, List<ToolCall> calls) {}

    public static void main(String[] args) throws Exception {
        Path input = Path.of(System.getProperty("neuron.replay.input",
                "evaluation/target/redteam/real_llm_resource_abuse.raw.jsonl"));
        Path output = Path.of(System.getProperty("neuron.replay.output",
                "evaluation/target/computing_llm_replay"));
        Files.createDirectories(output);
        List<Session> sessions = readSessions(input);
        List<Map<String, Object>> all = new ArrayList<>();
        for (Architecture architecture : Architecture.values()) {
            all.addAll(runArchitecture(architecture, sessions));
        }
        Path raw = output.resolve("llm_trace_replay.raw.jsonl");
        StringBuilder jsonl = new StringBuilder();
        for (Map<String, Object> row : all) jsonl.append(JSON.writeValueAsString(row)).append('\n');
        Files.writeString(raw, jsonl.toString());
        Files.writeString(output.resolve("llm_trace_replay_summary.csv"), summarize(all));
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("timestamp_utc", Instant.now().toString());
        env.put("input", input.toAbsolutePath().toString());
        env.put("sessions", sessions.size());
        env.put("deadline_ms", DEADLINE_MS);
        env.put("shared_pool", SHARED_POOL);
        env.put("permission_pool", PERMISSION_POOL);
        env.put("queue_capacity", QUEUE);
        Files.writeString(output.resolve("environment.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(env));
        System.out.printf(Locale.ROOT, "LLM trace replay: %d sessions, %d call observations -> %s%n",
                sessions.size(), all.size(), raw);
    }

    private static List<Session> readSessions(Path path) throws Exception {
        List<Session> result = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            if (line.isBlank()) continue;
            JsonNode root = JSON.readTree(line);
            String response = root.path("response").asText("");
            JsonNode responseJson;
            try {
                responseJson = JSON.readTree(response);
            } catch (Exception ignored) {
                continue;
            }
            List<ToolCall> calls = new ArrayList<>();
            for (JsonNode call : responseJson.path("tool_calls")) {
                String name = call.path("tool").asText(call.path("name").asText("unknown"));
                int tokens = call.path("estimated_tokens").asInt(50);
                calls.add(new ToolCall(name, Math.max(1, tokens)));
            }
            if (!calls.isEmpty()) {
                result.add(new Session(root.path("trial").asInt(result.size()),
                        root.path("variant").asInt(0), root.path("gas_limit").asInt(0), calls));
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("No tool-call sessions in " + path);
        return result;
    }

    private static List<Map<String, Object>> runArchitecture(
            Architecture architecture, List<Session> sessions) throws Exception {
        ThreadPoolExecutor shared = pool(SHARED_POOL, "llm-shared");
        ThreadPoolExecutor permission = architecture.isolated ? pool(PERMISSION_POOL, "llm-permission") : shared;
        AtomicInteger resourceRejections = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();
        List<Map<String, Object>> rows = java.util.Collections.synchronizedList(new ArrayList<>());

        for (Session session : sessions) {
            Thread worker = Thread.ofVirtual().start(() -> {
                boolean complete = true;
                for (int index = 0; index < session.calls.size(); index++) {
                    ToolCall call = session.calls.get(index);
                    long started = System.nanoTime();
                    try {
                        shared.execute(() -> LockSupport.parkNanos(Math.min(5_000_000L,
                                Math.max(500_000L, call.estimatedTokens * 10_000L))));
                    } catch (java.util.concurrent.RejectedExecutionException rejected) {
                        resourceRejections.incrementAndGet();
                    }
                    boolean timedOut = false;
                    String verdict;
                    try {
                        Future<EscalationPolicy.Verdict> future = permission.submit(() ->
                                EscalationPolicy.evaluate(1, call.name, "", resourceRejections.get(), architecture.coupled));
                        try {
                            verdict = future.get(DEADLINE_MS, TimeUnit.MILLISECONDS).name();
                        } catch (TimeoutException timeout) {
                            timedOut = true;
                            future.cancel(true);
                            verdict = architecture.failOpen ? "ASK_WITH_CONTEXT" : "DENY_TIMEOUT";
                        }
                    } catch (java.util.concurrent.RejectedExecutionException rejected) {
                        timedOut = true;
                        resourceRejections.incrementAndGet();
                        verdict = architecture.failOpen ? "ASK_WITH_CONTEXT" : "DENY_TIMEOUT";
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        timedOut = true;
                        verdict = architecture.failOpen ? "ASK_WITH_CONTEXT" : "DENY_TIMEOUT";
                    } catch (Exception failed) {
                        timedOut = true;
                        verdict = architecture.failOpen ? "ASK_WITH_CONTEXT" : "DENY_TIMEOUT";
                    }
                    if (timedOut) complete = false;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("experiment", "llm_trace_replay");
                    row.put("architecture", architecture.name());
                    row.put("trial", session.trial);
                    row.put("variant", session.variant);
                    row.put("tenant_id", "tenant_" + (session.trial % 8));
                    row.put("workload_class", session.variant <= 2 ? "benign" : "attack");
                    row.put("gas_limit", session.gasLimit);
                    row.put("call_index", index);
                    row.put("calls_in_session", session.calls.size());
                    row.put("tool", call.name);
                    row.put("estimated_tokens", call.estimatedTokens);
                    row.put("deadline_ms", DEADLINE_MS);
                    row.put("latency_ms", (System.nanoTime() - started) / 1_000_000.0);
                    row.put("timed_out", timedOut);
                    row.put("session_complete_so_far", complete);
                    row.put("verdict", verdict);
                    row.put("resource_rejections", resourceRejections.get());
                    rows.add(row);
                }
            });
            workers.add(worker);
        }
        for (Thread worker : workers) worker.join();
        shared.shutdownNow();
        if (permission != shared) permission.shutdownNow();
        shared.awaitTermination(2, TimeUnit.SECONDS);
        if (permission != shared) permission.awaitTermination(2, TimeUnit.SECONDS);
        return rows;
    }

    private static ThreadPoolExecutor pool(int size, String name) {
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(size, size, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE),
                runnable -> Thread.ofPlatform().name(name + "-" + sequence.incrementAndGet()).unstarted(runnable),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static String summarize(List<Map<String, Object>> rows) {
        StringBuilder out = new StringBuilder("architecture,workload_class,n_calls,n_sessions,session_completion_rate,timeout_rate,latency_p50_ms,latency_p95_ms\n");
        for (Architecture architecture : Architecture.values()) {
            for (String workload : List.of("benign", "attack")) {
                List<Map<String, Object>> selected = rows.stream()
                        .filter(r -> architecture.name().equals(r.get("architecture")))
                        .filter(r -> workload.equals(r.get("workload_class"))).toList();
                long calls = selected.size();
                long timeouts = selected.stream().filter(r -> Boolean.TRUE.equals(r.get("timed_out"))).count();
                long sessions = selected.stream().map(r -> r.get("trial")).distinct().count();
                long complete = selected.stream().filter(r -> Boolean.TRUE.equals(r.get("session_complete_so_far")))
                        .map(r -> r.get("trial")).distinct().count();
                double[] latency = selected.stream().mapToDouble(r -> (double) r.get("latency_ms")).sorted().toArray();
                out.append(String.format(Locale.ROOT, "%s,%s,%d,%d,%.6f,%.6f,%.6f,%.6f%n",
                        architecture.name(), workload, calls, sessions,
                        sessions == 0 ? 0 : complete / (double) sessions,
                        calls == 0 ? 0 : timeouts / (double) calls,
                        percentile(latency, .50), percentile(latency, .95)));
            }
        }
        return out.toString();
    }

    private static double percentile(double[] sorted, double q) {
        if (sorted.length == 0) return Double.NaN;
        double position = q * (sorted.length - 1);
        int lower = (int) Math.floor(position), upper = (int) Math.ceil(position);
        return lower == upper ? sorted[lower] : sorted[lower] * (upper - position) + sorted[upper] * (position - lower);
    }
}
