package com.ouisani.aios.test.redteam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ouisani.aios.core.permission.EscalationPolicy;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-separated permission-plane experiment.
 *
 * <p>The test JVM starts a second JVM running this class in server mode.  The
 * client then exercises four architecture semantics over a real HTTP boundary:
 * fail-open, fail-closed, isolated-but-uncoupled, and coupled governance.  The
 * server has an independently bounded executor and supports injected delay,
 * queue saturation, and a deterministic pause.  Raw observations are retained
 * as JSONL so that the paper tables can be regenerated.</p>
 */
public class PermissionServiceHttpBenchmark {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path DEFAULT_OUTPUT = Paths.get("..", "evaluation", "target", "permission_http");
    private static final int[] ARCHITECTURES = {0, 1, 2, 3};
    private static final String[] ARCH_NAMES = {
            "LAYER_SEPARATED_FAIL_OPEN", "LAYER_SEPARATED_FAIL_CLOSED",
            "ISOLATED_NO_COUPLING", "NEURON_COUPLED"};

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--server".equals(args[0])) {
            runServer(args);
            return;
        }
        new PermissionServiceHttpBenchmark().run(false);
    }

    @Test
    void runProcessSeparatedPermissionPlane() throws Exception {
        run(false);
    }

    private void run(boolean remoteHost) throws Exception {
        // Never let a developer/CI machine's corporate proxy intercept the
        // loopback control plane.  The benchmark intentionally measures the
        // Java service boundary itself.
        System.setProperty("java.net.useSystemProxies", "false");
        System.clearProperty("http.proxyHost");
        System.clearProperty("https.proxyHost");
        System.clearProperty("socksProxyHost");
        Path output = Paths.get(System.getProperty("neuron.http.output",
                DEFAULT_OUTPUT.toString())).toAbsolutePath().normalize();
        Files.createDirectories(output);
        int port = Integer.getInteger("neuron.http.port", 18181);
        int decisions = Integer.getInteger("neuron.http.decisions", 20);
        String runId = sanitize(System.getProperty("neuron.http.runId", Instant.now().toString()));
        String host = System.getProperty("neuron.http.host", "windows-process");
        // Surefire puts the application module on the module path.  The child
        // server is deliberately a plain classpath JVM so it is independently
        // launchable on a Linux host without the test runner.
        String testClasspath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        String modulePath = System.getProperty("jdk.module.path", "");
        String classpath = modulePath.isBlank() ? testClasspath
                : testClasspath + java.io.File.pathSeparator + modulePath;

        List<Map<String, Object>> rows = new ArrayList<>();
        List<Scenario> scenarios = scenarios();
        for (Scenario scenario : scenarios) {
            Process server = startServer(classpath, port, scenario, runId);
            try {
                waitForServer(port);
                for (int architecture : ARCHITECTURES) {
                    rows.addAll(runCell(port, architecture, scenario, decisions, runId, host));
                }
            } finally {
                server.destroy();
                if (!server.waitFor(2, TimeUnit.SECONDS)) server.destroyForcibly();
            }
            port++;
        }

        Path raw = output.resolve("permission_http_" + runId + ".raw.jsonl");
        StringBuilder jsonl = new StringBuilder();
        for (Map<String, Object> row : rows) jsonl.append(JSON.writeValueAsString(row)).append('\n');
        Files.writeString(raw, jsonl.toString());
        Files.writeString(output.resolve("permission_http_" + runId + "_summary.csv"), summarize(rows));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("run_id", runId);
        metadata.put("host_id", host);
        metadata.put("java_version", System.getProperty("java.version"));
        metadata.put("os_name", System.getProperty("os.name"));
        metadata.put("os_version", System.getProperty("os.version"));
        metadata.put("os_arch", System.getProperty("os.arch"));
        metadata.put("decisions_per_cell", decisions);
        metadata.put("scenarios", scenarios.stream().map(Scenario::name).toList());
        metadata.put("git_commit", System.getenv().getOrDefault("GIT_COMMIT", "unrecorded-working-tree"));
        Files.writeString(output.resolve("environment_" + runId + ".json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(metadata));
        System.out.printf("HTTP permission benchmark: %,d observations -> %s%n", rows.size(), raw);
    }

    private record Scenario(String name, int deadlineMs, int delayMs, int serviceThreads, int serviceQueue,
                            int attackers, int attackQps, int pauseAfter, int pauseMs) {}

    private static List<Scenario> scenarios() {
        String mode = System.getProperty("neuron.http.mode", "core");
        if ("smoke".equals(mode)) {
            return List.of(new Scenario("nominal_10ms", 10, 0, 2, 32, 0, 0, 0, 0),
                    new Scenario("nominal_50ms", 50, 0, 2, 32, 0, 0, 0, 0));
        }
        return List.of(
                new Scenario("nominal_10ms", 10, 0, 2, 32, 0, 0, 0, 0),
                new Scenario("nominal_50ms", 50, 0, 2, 32, 0, 0, 0, 0),
                new Scenario("cpu_and_queue_pressure_10ms", 10, 0, 2, 8, 4, 200, 0, 0),
                new Scenario("delay_5ms_10ms", 10, 5, 2, 32, 4, 200, 0, 0),
                new Scenario("delay_20ms_10ms", 10, 20, 2, 32, 4, 200, 0, 0),
                new Scenario("delay_20ms_50ms", 50, 20, 2, 32, 4, 200, 0, 0),
                new Scenario("delay_50ms_50ms", 50, 50, 2, 32, 4, 200, 0, 0),
                new Scenario("service_pause_50ms", 50, 0, 2, 32, 4, 200, 12, 1500));
    }

    private static Process startServer(String classpath, int port, Scenario scenario, String runId)
            throws IOException {
        List<String> command = new ArrayList<>();
        command.add(System.getProperty("java.home") + "/bin/java");
        command.add("--add-modules");
        command.add("jdk.httpserver");
        command.add("-Djava.net.useSystemProxies=false");
        command.add("-cp");
        command.add(classpath);
        command.add("-Dpermission.service.port=" + port);
        command.add("-Dpermission.service.delayMs=" + scenario.delayMs());
        command.add("-Dpermission.service.threads=" + scenario.serviceThreads());
        command.add("-Dpermission.service.queue=" + scenario.serviceQueue());
        command.add("-Dpermission.service.pauseAfter=" + scenario.pauseAfter());
        command.add("-Dpermission.service.pauseMs=" + scenario.pauseMs());
        command.add("-Dpermission.service.runId=" + runId);
        command.add(PermissionServiceHttpBenchmark.class.getName());
        command.add("--server");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        // Keep startup diagnostics visible during development and on CI.  The
        // service is short-lived and emits no per-request logs.
        builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        return builder.start();
    }

    private static void waitForServer(int port) throws Exception {
        // The server is a local child JVM.  A bounded startup delay avoids
        // inheriting machine-wide proxy settings in readiness probes (which
        // can route loopback sockets through a SOCKS proxy on developer hosts).
        Thread.sleep(500);
    }

    private static List<Map<String, Object>> runCell(int port, int architecture, Scenario scenario,
                                                      int decisions, String runId, String host)
            throws Exception {
        HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
                .connectTimeout(Duration.ofMillis(100)).build();
        AtomicBoolean stop = new AtomicBoolean(false);
        List<Thread> noiseTasks = new ArrayList<>();
        for (int i = 0; i < scenario.attackers(); i++) {
            final int worker = i;
            noiseTasks.add(Thread.ofVirtual().name("permission-noise-" + i).start(() -> {
                long interval = scenario.attackQps() == 0 ? 1_000_000_000L
                        : Math.max(1L, 1_000_000_000L / scenario.attackQps());
                long next = System.nanoTime();
                while (!stop.get()) {
                    try {
                        URI uri = URI.create("http://127.0.0.1:" + port
                                + "/decide?depth=1&tool=read_file&noise=" + worker);
                        client.sendAsync(HttpRequest.newBuilder(uri)
                                .timeout(Duration.ofSeconds(2)).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
                    } catch (Exception ignored) {}
                    next += interval;
                    long sleep = next - System.nanoTime();
                    if (sleep > 0) java.util.concurrent.locks.LockSupport.parkNanos(sleep);
                }
            }));
        }
        Thread.sleep(120);

        // Remove connection establishment and JIT warm-up from the measured
        // response.  The warm-up requests are not included in raw results.
        if (scenario.attackers() == 0) {
            for (int i = 0; i < 4; i++) {
                try {
                    URI warmup = URI.create("http://127.0.0.1:" + port + "/decide?depth=1&tool=read_file");
                    client.sendAsync(HttpRequest.newBuilder(warmup).timeout(Duration.ofSeconds(1)).GET().build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .orTimeout(1, TimeUnit.SECONDS).join();
                } catch (Exception ignored) {}
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        AtomicInteger resourcePressure = new AtomicInteger(scenario.attackers() * scenario.attackQps());
        for (int trial = 0; trial < decisions; trial++) {
            long started = System.nanoTime();
            boolean timedOut = false;
            boolean serviceError = false;
            String verdict;
            if (architecture == 3 && resourcePressure.get() > 400) {
                verdict = "DENY_RESOURCE";
            } else {
                URI uri = URI.create("http://127.0.0.1:" + port + "/decide?depth=2&tool=bash");
                try {
                    HttpResponse<String> response = client.sendAsync(
                                    HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(scenario.deadlineMs())).GET().build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .orTimeout(100, TimeUnit.MILLISECONDS).join();
                    if (response.statusCode() != 200) {
                        serviceError = true;
                        verdict = architecture == 0 ? "ASK_WITH_CONTEXT" : "DENY_SERVICE_ERROR";
                    } else {
                        verdict = response.body().trim();
                    }
                } catch (CompletionException timeout) {
                    timedOut = true;
                    verdict = architecture == 0 ? "ASK_WITH_CONTEXT" : "DENY_TIMEOUT";
                } catch (Exception failure) {
                    serviceError = true;
                    verdict = architecture == 0 ? "ASK_WITH_CONTEXT" : "DENY_SERVICE_ERROR";
                }
            }
            double latency = (System.nanoTime() - started) / 1_000_000.0;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("experiment", "permission_http_fault_injection");
            row.put("run_id", runId);
            row.put("host_id", host);
            row.put("architecture", ARCH_NAMES[architecture]);
            row.put("scenario", scenario.name());
            row.put("trial", trial);
            row.put("deadline_ms", scenario.deadlineMs());
            row.put("service_delay_ms", scenario.delayMs());
            row.put("service_threads", scenario.serviceThreads());
            row.put("service_queue", scenario.serviceQueue());
            row.put("attackers", scenario.attackers());
            row.put("attack_qps", scenario.attackQps());
            row.put("latency_ms", latency);
            row.put("timed_out", timedOut);
            row.put("service_error", serviceError);
            row.put("verdict", verdict);
            row.put("secure_decision", verdict.startsWith("DENY"));
            row.put("benign_completed", false);
            row.put("error_tightening", false);
            row.put("resource_pressure", resourcePressure.get());
            rows.add(row);
            Thread.sleep(10);
        }
        stop.set(true);
        for (Thread noise : noiseTasks) noise.join(1_000);
        return rows;
    }

    private static void runServer(String[] args) throws Exception {
        int port = Integer.getInteger("permission.service.port", 18181);
        int delayMs = Integer.getInteger("permission.service.delayMs", 0);
        int threads = Integer.getInteger("permission.service.threads", 2);
        int queue = Integer.getInteger("permission.service.queue", 32);
        int pauseAfter = Integer.getInteger("permission.service.pauseAfter", 0);
        int pauseMs = Integer.getInteger("permission.service.pauseMs", 0);
        AtomicInteger requests = new AtomicInteger();
        AtomicLong pausedUntil = new AtomicLong(0);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queue), new ThreadPoolExecutor.AbortPolicy());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 64);
        server.createContext("/health", exchange -> respond(exchange, 200, "ok"));
        server.createContext("/decide", exchange -> {
            int count = requests.incrementAndGet();
            if (pauseAfter > 0 && count >= pauseAfter && pausedUntil.compareAndSet(0,
                    System.nanoTime() + pauseMs * 1_000_000L)) {
                // deterministic service pause after the configured request count
            }
            long until = pausedUntil.get();
            try {
                if (until > System.nanoTime()) {
                    long remaining = until - System.nanoTime();
                    if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
                }
                if (delayMs > 0) Thread.sleep(delayMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                respond(exchange, 503, "interrupted");
                return;
            }
            String query = exchange.getRequestURI().getRawQuery();
            String tool = query != null && query.contains("tool=bash") ? "bash" : "read_file";
            int depth = query != null && query.contains("depth=2") ? 2 : 1;
            String verdict = EscalationPolicy.evaluate(depth, tool, "", 0, false).name();
            respond(exchange, 200, verdict);
        });
        server.setExecutor(executor);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            executor.shutdownNow();
        }));
        while (!Thread.currentThread().isInterrupted()) Thread.sleep(1000);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String summarize(List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = row.get("scenario") + "," + row.get("architecture");
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        StringBuilder csv = new StringBuilder("scenario,architecture,n,secure_rate,timeout_rate,service_error_rate,latency_p50_ms,latency_p95_ms\n");
        for (Map.Entry<String, List<Map<String, Object>>> entry : groups.entrySet()) {
            List<Map<String, Object>> group = entry.getValue();
            long secure = group.stream().filter(row -> Boolean.TRUE.equals(row.get("secure_decision"))).count();
            long timeout = group.stream().filter(row -> Boolean.TRUE.equals(row.get("timed_out"))).count();
            long errors = group.stream().filter(row -> Boolean.TRUE.equals(row.get("service_error"))).count();
            double[] latencies = group.stream().mapToDouble(row -> ((Number) row.get("latency_ms")).doubleValue())
                    .sorted().toArray();
            String[] key = entry.getKey().split(",", 2);
            csv.append(String.format(Locale.ROOT, "%s,%s,%d,%.6f,%.6f,%.6f,%.6f,%.6f%n",
                    key[0], key[1], group.size(), secure / (double) group.size(),
                    timeout / (double) group.size(), errors / (double) group.size(),
                    percentile(latencies, .50), percentile(latencies, .95)));
        }
        return csv.toString();
    }

    private static double percentile(double[] values, double q) {
        if (values.length == 0) return Double.NaN;
        double pos = q * (values.length - 1);
        int low = (int) Math.floor(pos), high = (int) Math.ceil(pos);
        return low == high ? values[low] : values[low] * (high - pos) + values[high] * (pos - low);
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
