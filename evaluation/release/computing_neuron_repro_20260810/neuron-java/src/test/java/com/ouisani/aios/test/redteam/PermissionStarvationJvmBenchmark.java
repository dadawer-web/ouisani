package com.ouisani.aios.test.redteam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ouisani.aios.core.permission.EscalationPolicy;
import com.ouisani.aios.core.security.RateLimitSyscallFilter;
import com.ouisani.aios.core.syscall.SyscallRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * JVM-native permission starvation benchmark for the Neuron paper.
 *
 * <p>The benchmark deliberately separates two questions that the old Python model conflated:
 * (1) can a bounded permission executor miss a deployment deadline under hostile load, and
 * (2) does Neuron's resource signal change the production {@link EscalationPolicy} verdict?
 * It executes on Java 21, uses virtual attacker threads, the production token-bucket filter,
 * and the production escalation policy. No Python concurrency model participates.</p>
 *
 * <p>Four architecture baselines are evaluated: shared executor + fail-open, shared executor
 * + fail-closed, an isolated permission executor without cross-layer coupling, and Neuron's
 * isolated + coupled configuration. Raw observations are retained as JSONL.</p>
 */
public class PermissionStarvationJvmBenchmark {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path OUTPUT = Paths.get(System.getProperty(
            "neuron.benchmark.output", Paths.get("..", "evaluation", "target", "jvm_starvation").toString()));
    private static final String RUN_ID = sanitize(System.getProperty(
            "neuron.benchmark.runId", Instant.now().toString()));
    private static final String MODE = System.getProperty("neuron.benchmark.mode", "full");
    private static final String HOST_ID = System.getProperty("neuron.benchmark.host", "unspecified");
    private static final int[] DEADLINES_MS = parseInts(System.getProperty(
            "neuron.benchmark.deadlines", MODE.equals("core") ? "3,10,50" : "1,3,5,10,20,50"));
    private static final int DECISIONS_PER_CELL = Integer.getInteger("neuron.benchmark.decisions", 30);
    private static final int SHARED_POOL_SIZE = Integer.getInteger("neuron.benchmark.sharedPool", 4);
    private static final int PERMISSION_POOL_SIZE = Integer.getInteger("neuron.benchmark.permissionPool", 2);
    private static final int QUEUE_CAPACITY = Integer.getInteger("neuron.benchmark.queueCapacity", 512);

    enum Architecture {
        LAYER_SEPARATED_FAIL_OPEN(false, false, true),
        LAYER_SEPARATED_FAIL_CLOSED(false, false, false),
        ISOLATED_NO_COUPLING(true, false, false),
        NEURON_COUPLED(true, true, false);

        final boolean isolatedPermissionPool;
        final boolean coupled;
        final boolean failOpen;

        Architecture(boolean isolatedPermissionPool, boolean coupled, boolean failOpen) {
            this.isolatedPermissionPool = isolatedPermissionPool;
            this.coupled = coupled;
            this.failOpen = failOpen;
        }
    }

    record Load(int attackers, int qpsPerAttacker, double holdMs, String split,
                String morphology, String workloadClass) {}

    @Test
    void runJvmStarvationResponseSurface() throws Exception {
        Files.createDirectories(OUTPUT);
        Path rawPath = OUTPUT.resolve("jvm_permission_starvation_" + RUN_ID + ".raw.jsonl");
        Path summaryPath = OUTPUT.resolve("jvm_permission_starvation_" + RUN_ID + "_summary.csv");
        Path metadataPath = OUTPUT.resolve("environment_" + RUN_ID + ".json");

        List<Map<String, Object>> raw = new ArrayList<>();
        if (MODE.equals("qos")) {
            raw.addAll(runQosMatrix());
        } else if (MODE.equals("threshold_holdout")) {
            // This mode is frozen after threshold selection.  The profiles below
            // were not used by choose_threshold(): they deliberately use new
            // attacker rates/hold times and new benign trajectories.
            Load[] holdout = {
                    new Load(2, 1000, 5.0, "holdout", "alternating-burst", "attack"),
                    new Load(8, 1000, 1.0, "holdout", "staggered-ramp", "attack"),
                    new Load(16, 250, 8.0, "holdout", "long-hold-drip", "attack"),
                    new Load(1, 12, 0.8, "holdout", "tenant-batch", "benign"),
                    new Load(2, 300, 0.5, "holdout", "cooperative-burst", "benign"),
                    new Load(4, 8, 1.0, "holdout", "background-ramp", "benign")
            };
            for (Load load : holdout) {
                raw.addAll(runCell(Architecture.NEURON_COUPLED, load, 10,
                        "threshold_holdout"));
            }
        } else {
            // Full deadline sensitivity at one severe but non-singular pressure point.
            for (int deadline : DEADLINES_MS) {
                Load load = new Load(8, 500, 3.0, "train", "burst-lock", "attack");
                for (Architecture architecture : Architecture.values()) {
                    raw.addAll(runCell(architecture, load, deadline, "deadline_sweep"));
                }
            }
            raw.addAll(runCell(
                    Architecture.NEURON_COUPLED,
                    new Load(1, 20, 0.5, "train", "developer-interactive", "benign"),
                    10, "threshold_training"));

            // Attack-pressure response surface. The 10 ms point is fixed before observing results.
            int[] attackers = MODE.equals("core") ? new int[]{1, 4, 16} : new int[]{1, 4, 8, 16};
            int[] qps = {100, 500, 2_000};
            double[] holdMs = {0.5, 2.0, 5.0};
            for (int a : attackers) {
                for (int rate : qps) {
                    for (double hold : holdMs) {
                        Load load = new Load(a, rate, hold, "test", morphology(a, rate, hold), "attack");
                        for (Architecture architecture : Architecture.values()) {
                            raw.addAll(runCell(architecture, load, 10, "pressure_surface"));
                        }
                    }
                }
            }

            // Benign test trajectories are deliberately disjoint from threshold training.
            Load[] benignTest = {
                    new Load(1, 10, 0.5, "test", "interactive", "benign"),
                    new Load(1, 25, 2.0, "test", "single-tenant-steady", "benign"),
                    new Load(4, 10, 0.5, "test", "multi-client-steady", "benign")
            };
            for (Load load : benignTest) {
                raw.addAll(runCell(Architecture.NEURON_COUPLED, load, 10, "threshold_validation"));
            }
            raw.addAll(runJointDecisionProbe());
        }

        StringBuilder jsonl = new StringBuilder();
        for (Map<String, Object> row : raw) {
            jsonl.append(JSON.writeValueAsString(row)).append('\n');
        }
        Files.writeString(rawPath, jsonl);
        Files.writeString(summaryPath, summarize(raw));

        Map<String, Object> env = new LinkedHashMap<>();
        env.put("timestamp_utc", Instant.now().toString());
        env.put("run_id", RUN_ID);
        env.put("benchmark_mode", MODE);
        env.put("host_id", HOST_ID);
        env.put("java_version", System.getProperty("java.version"));
        env.put("java_vendor", System.getProperty("java.vendor"));
        env.put("os_name", System.getProperty("os.name"));
        env.put("os_version", System.getProperty("os.version"));
        env.put("os_arch", System.getProperty("os.arch"));
        env.put("available_processors", Runtime.getRuntime().availableProcessors());
        env.put("max_memory_bytes", Runtime.getRuntime().maxMemory());
        env.put("shared_pool_size", SHARED_POOL_SIZE);
        env.put("permission_pool_size", PERMISSION_POOL_SIZE);
        env.put("queue_capacity", QUEUE_CAPACITY);
        env.put("decisions_per_cell", DECISIONS_PER_CELL);
        env.put("git_commit", System.getenv().getOrDefault("GIT_COMMIT", "unrecorded-working-tree"));
        Files.writeString(metadataPath, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(env));

        assertFalse(raw.isEmpty());
        System.out.printf("JVM starvation benchmark: %,d observations -> %s%n", raw.size(), rawPath);
    }

    /**
     * QoS matrix with a controlled benign/attack mix.  The 100-decision run
     * uses the request class as the unit of the mixture; background attackers
     * create the same resource pressure for every architecture.  This is a
     * separate mode so the core starvation response surface remains unchanged.
     */
    private List<Map<String, Object>> runQosMatrix() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int benignPercent : new int[]{100, 90, 75, 50}) {
            for (Architecture architecture : Architecture.values()) {
                rows.addAll(runQosCell(architecture, benignPercent));
            }
        }
        return rows;
    }

    private List<Map<String, Object>> runQosCell(Architecture architecture, int benignPercent) throws Exception {
        ThreadPoolExecutor shared = pool(SHARED_POOL_SIZE, "neuron-shared");
        ThreadPoolExecutor permission = architecture.isolatedPermissionPool
                ? pool(PERMISSION_POOL_SIZE, "neuron-permission") : shared;
        RateLimitSyscallFilter limiter = new RateLimitSyscallFilter(50);
        SyscallRequest pressureRequest = new SyscallRequest("tool.execute", Map.of("tool", "bash"));
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger resourceRejections = new AtomicInteger();
        List<Thread> producers = new ArrayList<>();
        if (benignPercent < 100) {
            for (int i = 0; i < 8; i++) {
                Thread producer = Thread.ofVirtual().name("qos-attacker-producer-" + i).start(() -> {
                    long intervalNs = 1_000_000_000L / 500L;
                    long next = System.nanoTime();
                    while (!stop.get()) {
                        boolean admitted = true;
                        if (architecture.coupled) {
                            try {
                                limiter.preFilter("attacker_tenant", pressureRequest);
                            } catch (SecurityException denied) {
                                resourceRejections.incrementAndGet();
                                admitted = false;
                            }
                        }
                        if (admitted) {
                            try {
                                shared.execute(() -> LockSupport.parkNanos(3_000_000L));
                            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                                resourceRejections.incrementAndGet();
                            }
                        }
                        next += intervalNs;
                        LockSupport.parkNanos(Math.max(0L, next - System.nanoTime()));
                    }
                });
                producers.add(producer);
            }
        }
        Thread.sleep(100);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int trial = 0; trial < DECISIONS_PER_CELL; trial++) {
            boolean benign = trial % 100 < benignPercent;
            long started = System.nanoTime();
            boolean timedOut = false;
            String verdict;
            Future<EscalationPolicy.Verdict> future = null;
            try {
                final boolean benignRequest = benign;
                future = permission.submit(() -> benignRequest
                        ? EscalationPolicy.evaluate(1, "read_file", "", resourceRejections.get(), architecture.coupled)
                        : EscalationPolicy.evaluate(2, "bash", "", resourceRejections.get(), architecture.coupled));
                try {
                    verdict = future.get(10, TimeUnit.MILLISECONDS).name();
                } catch (TimeoutException timeout) {
                    timedOut = true;
                    future.cancel(true);
                    verdict = architecture.failOpen ? "ASK_WITH_CONTEXT" : "DENY_TIMEOUT";
                }
            } catch (java.util.concurrent.RejectedExecutionException saturated) {
                timedOut = true;
                verdict = architecture.failOpen ? "ASK_WITH_CONTEXT" : "DENY_TIMEOUT";
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("experiment", "qos_mix");
            row.put("run_id", RUN_ID);
            row.put("benchmark_mode", MODE);
            row.put("host_id", HOST_ID);
            row.put("shared_pool_size", SHARED_POOL_SIZE);
            row.put("permission_pool_size", PERMISSION_POOL_SIZE);
            row.put("queue_capacity", QUEUE_CAPACITY);
            row.put("architecture", architecture.name());
            row.put("trial", trial);
            row.put("deadline_ms", 10);
            row.put("attackers", benignPercent < 100 ? 8 : 0);
            row.put("qps_per_attacker", benignPercent < 100 ? 500 : 0);
            row.put("lock_hold_ms", benignPercent < 100 ? 3.0 : 0.0);
            row.put("split", "test");
            row.put("morphology", String.format(Locale.ROOT, "benign-%d-attack-%d", benignPercent, 100 - benignPercent));
            row.put("workload_class", benign ? "benign" : "attack");
            row.put("qos_benign_percent", benignPercent);
            row.put("latency_ms", (System.nanoTime() - started) / 1_000_000.0);
            row.put("timed_out", timedOut);
            row.put("permission_admission_rejected", false);
            row.put("verdict", verdict);
            row.put("secure_decision", benign ? !verdict.startsWith("DENY") : verdict.startsWith("DENY"));
            row.put("benign_completed", benign && !timedOut);
            row.put("error_tightening", benign && verdict.startsWith("DENY"));
            row.put("resource_rejections", resourceRejections.get());
            row.put("attack_tasks_executed", 0);
            row.put("permission_queue_depth", permission.getQueue().size());
            rows.add(row);
            Thread.sleep(2);
        }
        stop.set(true);
        for (Thread producer : producers) producer.join(1_000);
        shared.shutdownNow();
        if (permission != shared) permission.shutdownNow();
        shared.awaitTermination(2, TimeUnit.SECONDS);
        if (permission != shared) permission.awaitTermination(2, TimeUnit.SECONDS);
        return rows;
    }

    private List<Map<String, Object>> runJointDecisionProbe() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Architecture architecture : List.of(
                Architecture.ISOLATED_NO_COUPLING, Architecture.NEURON_COUPLED)) {
            for (int pressure : new int[]{0, 100}) {
                for (int trial = 0; trial < DECISIONS_PER_CELL; trial++) {
                    long started = System.nanoTime();
                    EscalationPolicy.Verdict verdict = EscalationPolicy.evaluate(
                            1, "bash", "", pressure, architecture.coupled);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("experiment", "joint_decision_probe");
                    row.put("run_id", RUN_ID);
                    row.put("benchmark_mode", MODE);
                    row.put("host_id", HOST_ID);
                    row.put("shared_pool_size", SHARED_POOL_SIZE);
                    row.put("permission_pool_size", PERMISSION_POOL_SIZE);
                    row.put("queue_capacity", QUEUE_CAPACITY);
                    row.put("architecture", architecture.name());
                    row.put("trial", trial);
                    row.put("deadline_ms", 10);
                    row.put("attackers", 0);
                    row.put("qps_per_attacker", 0);
                    row.put("lock_hold_ms", 0.0);
                    row.put("split", "test");
                    row.put("morphology", pressure == 0 ? "no-pressure" : "pressure-signal");
                    row.put("workload_class", "policy_probe");
                    row.put("latency_ms", (System.nanoTime() - started) / 1_000_000.0);
                    row.put("timed_out", false);
                    row.put("permission_admission_rejected", false);
                    row.put("verdict", verdict.name());
                    row.put("secure_decision", verdict == EscalationPolicy.Verdict.DENY_DEPTH);
                    row.put("resource_rejections", pressure);
                    row.put("attack_tasks_executed", 0);
                    row.put("permission_queue_depth", 0);
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private List<Map<String, Object>> runCell(
            Architecture architecture, Load load, int deadlineMs, String experiment) throws Exception {
        ThreadPoolExecutor shared = pool(SHARED_POOL_SIZE, "neuron-shared");
        ThreadPoolExecutor permission = architecture.isolatedPermissionPool
                ? pool(PERMISSION_POOL_SIZE, "neuron-permission") : shared;
        RateLimitSyscallFilter limiter = new RateLimitSyscallFilter(50);
        SyscallRequest pressureRequest = new SyscallRequest("tool.execute", Map.of("tool", "bash"));
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger resourceRejections = new AtomicInteger();
        AtomicInteger submitted = new AtomicInteger();
        List<Thread> producers = new ArrayList<>();

        for (int i = 0; i < load.attackers; i++) {
            int attacker = i;
            Thread producer = Thread.ofVirtual().name("attacker-producer-" + i).start(() -> {
                long intervalNs = Math.max(1L, 1_000_000_000L / load.qpsPerAttacker);
                long next = System.nanoTime();
                while (!stop.get()) {
                    boolean admitted = true;
                    if (architecture.coupled) {
                        try {
                            // One tenant identity makes the production token bucket tenant-scoped.
                            limiter.preFilter("attacker_tenant", pressureRequest);
                        } catch (SecurityException denied) {
                            resourceRejections.incrementAndGet();
                            admitted = false;
                        }
                    }
                    if (admitted) {
                        try {
                            shared.execute(() -> {
                                submitted.incrementAndGet();
                                LockSupport.parkNanos((long) (load.holdMs * 1_000_000));
                            });
                        } catch (java.util.concurrent.RejectedExecutionException rejected) {
                            resourceRejections.incrementAndGet();
                        }
                    }
                    next += intervalNs;
                    LockSupport.parkNanos(Math.max(0L, next - System.nanoTime()));
                }
            });
            producers.add(producer);
        }

        Thread.sleep(100); // steady-state warm-up; excluded from observations
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int trial = 0; trial < DECISIONS_PER_CELL; trial++) {
            long started = System.nanoTime();
            boolean timedOut = false;
            boolean admissionRejected = false;
            String verdict;
            Future<EscalationPolicy.Verdict> future = null;
            try {
                future = permission.submit(() -> EscalationPolicy.evaluate(
                        2, "bash", "", resourceRejections.get(), architecture.coupled));
                try {
                    verdict = future.get(deadlineMs, TimeUnit.MILLISECONDS).name();
                } catch (TimeoutException timeout) {
                    timedOut = true;
                    future.cancel(true);
                    verdict = architecture.failOpen ? "ASK_WITH_CONTEXT" : "DENY_TIMEOUT";
                }
            } catch (java.util.concurrent.RejectedExecutionException saturated) {
                // Queue admission failure is a stronger form of policy-plane starvation.
                admissionRejected = true;
                timedOut = true;
                verdict = architecture.failOpen ? "ASK_WITH_CONTEXT" : "DENY_TIMEOUT";
            }
            double latencyMs = (System.nanoTime() - started) / 1_000_000.0;
            boolean secure = verdict.startsWith("DENY");

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("experiment", experiment);
            row.put("run_id", RUN_ID);
            row.put("benchmark_mode", MODE);
            row.put("host_id", HOST_ID);
            row.put("shared_pool_size", SHARED_POOL_SIZE);
            row.put("permission_pool_size", PERMISSION_POOL_SIZE);
            row.put("queue_capacity", QUEUE_CAPACITY);
            row.put("architecture", architecture.name());
            row.put("trial", trial);
            row.put("deadline_ms", deadlineMs);
            row.put("attackers", load.attackers);
            row.put("qps_per_attacker", load.qpsPerAttacker);
            row.put("lock_hold_ms", load.holdMs);
            row.put("split", load.split);
            row.put("morphology", load.morphology);
            row.put("workload_class", load.workloadClass);
            row.put("latency_ms", latencyMs);
            row.put("timed_out", timedOut);
            row.put("permission_admission_rejected", admissionRejected);
            row.put("verdict", verdict);
            row.put("secure_decision", secure);
            row.put("resource_rejections", resourceRejections.get());
            row.put("attack_tasks_executed", submitted.get());
            row.put("permission_queue_depth", permission.getQueue().size());
            rows.add(row);
            Thread.sleep(2);
        }

        stop.set(true);
        for (Thread producer : producers) producer.join(1_000);
        shared.shutdownNow();
        if (permission != shared) permission.shutdownNow();
        shared.awaitTermination(2, TimeUnit.SECONDS);
        if (permission != shared) permission.awaitTermination(2, TimeUnit.SECONDS);
        return rows;
    }

    private static ThreadPoolExecutor pool(int size, String name) {
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(
                size, size, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> Thread.ofPlatform().name(name + "-" + sequence.incrementAndGet()).unstarted(runnable),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static String morphology(int attackers, int qps, double holdMs) {
        if (attackers <= 1) return "single-source";
        if (qps >= 2_000 && holdMs <= 0.5) return "high-rate-short-hold";
        if (qps <= 100 && holdMs >= 5.0) return "low-rate-long-hold";
        return "mixed-contention";
    }

    private static String summarize(List<Map<String, Object>> raw) {
        record Key(String experiment, String architecture, int deadline, int attackers, int qps, double hold) {}
        Map<Key, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : raw) {
            Key key = new Key(
                    (String) row.get("experiment"), (String) row.get("architecture"),
                    (int) row.get("deadline_ms"), (int) row.get("attackers"),
                    (int) row.get("qps_per_attacker"), (double) row.get("lock_hold_ms"));
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }

        StringBuilder csv = new StringBuilder("experiment,architecture,deadline_ms,attackers,qps_per_attacker,lock_hold_ms,n,secure_rate,timeout_rate,latency_p50_ms,latency_p95_ms,mean_resource_rejections\n");
        for (Map.Entry<Key, List<Map<String, Object>>> entry : groups.entrySet()) {
            Key k = entry.getKey();
            List<Map<String, Object>> rows = entry.getValue();
            double[] latency = rows.stream().mapToDouble(r -> (double) r.get("latency_ms")).sorted().toArray();
            long secure = rows.stream().filter(r -> (boolean) r.get("secure_decision")).count();
            long timeout = rows.stream().filter(r -> (boolean) r.get("timed_out")).count();
            double meanRejections = rows.stream().mapToInt(r -> (int) r.get("resource_rejections")).average().orElse(0);
            csv.append(String.format(Locale.ROOT,
                    "%s,%s,%d,%d,%d,%.3f,%d,%.6f,%.6f,%.6f,%.6f,%.3f%n",
                    k.experiment, k.architecture, k.deadline, k.attackers, k.qps, k.hold,
                    rows.size(), secure / (double) rows.size(), timeout / (double) rows.size(),
                    percentile(latency, 0.50), percentile(latency, 0.95), meanRejections));
        }
        return csv.toString();
    }

    private static double percentile(double[] sorted, double q) {
        if (sorted.length == 0) return Double.NaN;
        double position = q * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return sorted[lower];
        return sorted[lower] * (upper - position) + sorted[upper] * (position - lower);
    }

    private static int[] parseInts(String value) {
        String[] parts = value.split(",");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Integer.parseInt(parts[i].trim());
        }
        return result;
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
