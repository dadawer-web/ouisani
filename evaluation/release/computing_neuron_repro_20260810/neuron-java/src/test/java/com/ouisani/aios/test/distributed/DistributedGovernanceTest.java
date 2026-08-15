package com.ouisani.aios.test.distributed;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Distributed multi-node governance integration test for the Neuron paper (JSA journal submission).
 *
 * <p>Addresses reviewer concerns about "single-node scope" by simulating a 3-node distributed
 * Neuron deployment within a single JVM. Three lightweight Javalin HTTP servers run on ports
 * 18081-18083, each with independent governance state (local concurrent maps, NOT singletons).
 *
 * <p>Five scenarios (D1-D5), each with n=30 trials, validate:
 * <ul>
 *   <li>D1: Cross-node trace ID propagation</li>
 *   <li>D2: Cross-node spawn-time privilege non-increase</li>
 *   <li>D3: Cross-node depth-aware escalation policy</li>
 *   <li>D4: Cross-node joint-decision (resource pressure tightens verdict)</li>
 *   <li>D5: Cross-node audit correlation across multi-hop (N1 -> N2 -> N3)</li>
 * </ul>
 *
 * <p>For each scenario, both a local (same-node) and cross-node variant are measured to
 * compute the distributed overhead ratio. Results are written as JSON to
 * {@code evaluation/target/distributed_governance_results.json}.
 */
public class DistributedGovernanceTest {

    // ════════════════════════════════════════════════════════════════
    //  Constants
    // ════════════════════════════════════════════════════════════════

    private static final int[] PORTS = {18081, 18082, 18083};
    private static final String[] NODE_IDS = {"node1", "node2", "node3"};
    private static final int TRIALS = 30;
    private static final int RATE_LIMIT_PRESSURE_THRESHOLD = 50;
    private static final int DEPTH_DENY_THRESHOLD = 2;
    private static final Path OUTPUT_PATH =
            Paths.get("e:\\ouisani\\evaluation\\target\\distributed_governance_results.json");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private static final Map<String, Integer> NODE_PORT_MAP = new HashMap<>();
    static {
        for (int i = 0; i < NODE_IDS.length; i++) {
            NODE_PORT_MAP.put(NODE_IDS[i], PORTS[i]);
        }
    }

    // Verdicts
    private static final String ALLOW = "ALLOW";
    private static final String DENY_DEPTH = "DENY_DEPTH";
    private static final String ASK_WITH_CONTEXT = "ASK_WITH_CONTEXT";

    // ════════════════════════════════════════════════════════════════
    //  Per-node governance state (independent, NOT singletons)
    // ════════════════════════════════════════════════════════════════

    private static final class NodeState {
        final String nodeId;
        final int port;
        final ConcurrentHashMap<String, String> traceIds = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, String> profiles = new ConcurrentHashMap<>();
        final ConcurrentLinkedDeque<String> auditLog = new ConcurrentLinkedDeque<>();
        final AtomicInteger rateLimitRejections = new AtomicInteger(0);
        final ConcurrentHashMap<String, Integer> spawnDepth = new ConcurrentHashMap<>();
        volatile Javalin app;

        NodeState(String nodeId, int port) {
            this.nodeId = nodeId;
            this.port = port;
        }

        void recordAudit(String traceId, String entry) {
            auditLog.addLast(traceId + "|" + entry);
        }

        List<String> getAuditForTrace(String traceId) {
            List<String> result = new ArrayList<>();
            for (String entry : auditLog) {
                if (entry.startsWith(traceId + "|")) {
                    result.add(entry.substring(traceId.length() + 1));
                }
            }
            return result;
        }
    }

    private final NodeState[] nodes = new NodeState[3];

    // ════════════════════════════════════════════════════════════════
    //  Test entry point
    // ════════════════════════════════════════════════════════════════

    @Test
    void runDistributedGovernanceExperiment() throws Exception {
        // Start 3 nodes
        for (int i = 0; i < 3; i++) {
            nodes[i] = new NodeState(NODE_IDS[i], PORTS[i]);
            startNode(nodes[i]);
        }

        // Brief warm-up to ensure all servers are ready
        Thread.sleep(500);

        try {
            // Run 5 scenarios
            Map<String, Object> scenarios = new LinkedHashMap<>();
            scenarios.put("D1_trace_propagation", runD1());
            scenarios.put("D2_privilege_non_increase", runD2());
            scenarios.put("D3_depth_aware_escalation", runD3());
            scenarios.put("D4_joint_decision", runD4());
            scenarios.put("D5_audit_correlation", runD5());

            // Build result
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("scenarios", scenarios);
            result.put("node_count", 3);
            result.put("trials_per_scenario", TRIALS);
            result.put("timestamp", Instant.now().toString());

            // Write JSON
            Files.createDirectories(OUTPUT_PATH.getParent());
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result);
            Files.writeString(OUTPUT_PATH, json);

            System.out.println("=== Distributed Governance Experiment Complete ===");
            System.out.println("Results written to: " + OUTPUT_PATH);
            System.out.println(json);

            // Assert all correctness checks passed
            for (Map.Entry<String, Object> entry : scenarios.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> scenario = (Map<String, Object>) entry.getValue();
                @SuppressWarnings("unchecked")
                Map<String, Object> correctness = (Map<String, Object>) scenario.get("correctness");
                int passed = ((Number) correctness.get("passed")).intValue();
                int total = ((Number) correctness.get("total")).intValue();
                assertEquals(total, passed,
                        entry.getKey() + " correctness check failed: " + passed + "/" + total);
            }

        } finally {
            // Stop all servers
            for (int i = 0; i < 3; i++) {
                if (nodes[i] != null && nodes[i].app != null) {
                    nodes[i].app.stop();
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Javalin server setup
    // ════════════════════════════════════════════════════════════════

    private void startNode(NodeState node) {
        node.app = Javalin.create(config -> {
            config.showJavalinBanner = false;
        });

        // GET /health
        node.app.get("/health", ctx -> {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status", "UP");
            resp.put("nodeId", node.nodeId);
            resp.put("port", node.port);
            writeJson(ctx, resp);
        });

        // POST /governance/trace — body: {"turnId": "...", "traceId": "..."}
        node.app.post("/governance/trace", ctx -> {
            Map<String, Object> body = parseBody(ctx);
            String turnId = (String) body.get("turnId");
            String traceId = (String) body.get("traceId");
            node.traceIds.put(turnId, traceId);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", true);
            resp.put("turnId", turnId);
            resp.put("traceId", traceId);
            writeJson(ctx, resp);
        });

        // GET /governance/trace/{turnId}
        node.app.get("/governance/trace/{turnId}", ctx -> {
            String turnId = ctx.pathParam("turnId");
            String traceId = node.traceIds.get(turnId);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("turnId", turnId);
            resp.put("traceId", traceId);
            writeJson(ctx, resp);
        });

        // POST /governance/spawn — cross-node spawn
        // body: {"parentTurnId": "...", "parentProfile": "restricted|default",
        //        "depth": N, "toolName": "bash|file_read", "targetNode": "node2|node3"}
        // returns: {"allowed": bool, "verdict": "...", "traceId": "...",
        //           "childTurnId": "...", "childProfile": "...", "latencyMs": ...}
        node.app.post("/governance/spawn", ctx -> {
            Map<String, Object> body = parseBody(ctx);
            long start = System.nanoTime();

            String parentTurnId = (String) body.get("parentTurnId");
            String parentProfile = (String) body.get("parentProfile");
            int depth = ((Number) body.get("depth")).intValue();
            String toolName = (String) body.get("toolName");
            String targetNode = (String) body.get("targetNode");

            // Resolve trace ID — propagate from parent turn
            String traceId = (String) body.get("traceId");
            if (traceId == null) {
                traceId = node.traceIds.get(parentTurnId);
            }
            if (traceId == null) {
                traceId = "trace-" + UUID.randomUUID();
            }

            Map<String, Object> resp;
            if (targetNode != null && !targetNode.equals(node.nodeId)) {
                // Forward to target node — record audit on source (originator)
                node.recordAudit(traceId, "spawn-forward: target=" + targetNode
                        + " parentProfile=" + parentProfile
                        + " depth=" + depth
                        + " tool=" + toolName
                        + " node=" + node.nodeId);
                body.put("traceId", traceId);
                int targetPort = NODE_PORT_MAP.get(targetNode);
                resp = httpPostMap(targetPort, "/governance/spawn", body);
            } else {
                // Process locally on this node (target == self)
                String childTurnId = "child-" + node.nodeId + "-" + UUID.randomUUID();

                // Privilege non-increase: child inherits parent profile (restricted stays restricted)
                String childProfile = parentProfile;

                // Compute verdict using local rate-limit counter for joint-decision signal
                int rlRejections = node.rateLimitRejections.get();
                String verdict = computeVerdict(depth, toolName, rlRejections);

                // Store governance state
                node.traceIds.put(childTurnId, traceId);
                node.profiles.put(childTurnId, childProfile);
                node.spawnDepth.put(childTurnId, depth);

                // Record audit entry with the SAME trace ID
                node.recordAudit(traceId, "spawn: parentProfile=" + parentProfile
                        + " childProfile=" + childProfile
                        + " depth=" + depth
                        + " tool=" + toolName
                        + " verdict=" + verdict
                        + " node=" + node.nodeId);

                resp = new LinkedHashMap<>();
                resp.put("allowed", ALLOW.equals(verdict));
                resp.put("verdict", verdict);
                resp.put("traceId", traceId);
                resp.put("childTurnId", childTurnId);
                resp.put("childProfile", childProfile);
                resp.put("node", node.nodeId);
            }

            long latencyMs = System.nanoTime() - start;
            resp.put("latencyMs", latencyMs / 1_000_000.0);
            writeJson(ctx, resp);
        });

        // POST /governance/rate-limit — body: {"count": N}
        node.app.post("/governance/rate-limit", ctx -> {
            Map<String, Object> body = parseBody(ctx);
            int count = ((Number) body.get("count")).intValue();
            node.rateLimitRejections.addAndGet(count);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", true);
            resp.put("totalRejections", node.rateLimitRejections.get());
            writeJson(ctx, resp);
        });

        // GET /governance/audit/{traceId}
        node.app.get("/governance/audit/{traceId}", ctx -> {
            String traceId = ctx.pathParam("traceId");
            List<String> entries = node.getAuditForTrace(traceId);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("traceId", traceId);
            resp.put("entries", entries);
            resp.put("count", entries.size());
            resp.put("node", node.nodeId);
            writeJson(ctx, resp);
        });

        // POST /governance/joint-decision — cross-node joint decision
        // body: {"depth": N, "toolName": "bash", "rateLimitRejections": N}
        // returns: {"verdict": "...", "latencyMs": ..., "node": "..."}
        node.app.post("/governance/joint-decision", ctx -> {
            Map<String, Object> body = parseBody(ctx);
            long start = System.nanoTime();

            int depth = ((Number) body.get("depth")).intValue();
            String toolName = (String) body.get("toolName");
            int rlRejections = ((Number) body.get("rateLimitRejections")).intValue();

            String verdict = computeVerdict(depth, toolName, rlRejections);

            long latencyMs = System.nanoTime() - start;
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("verdict", verdict);
            resp.put("latencyMs", latencyMs / 1_000_000.0);
            resp.put("node", node.nodeId);
            writeJson(ctx, resp);
        });

        node.app.start(node.port);
    }

    // ════════════════════════════════════════════════════════════════
    //  Governance logic
    // ════════════════════════════════════════════════════════════════

    /**
     * Compute governance verdict for a tool request.
     *
     * <p>Depth-aware escalation policy:
     * <ul>
     *   <li>Non-destructive tools (file_read): always ALLOW</li>
     *   <li>Destructive tools (bash):
     *     <ul>
     *       <li>depth &gt;= 2: DENY_DEPTH (too deep for destructive ops)</li>
     *       <li>depth &gt;= 1 and rateLimitRejections &gt; 50: DENY_DEPTH (pressure-escalated)</li>
     *       <li>otherwise: ASK_WITH_CONTEXT (human-in-the-loop)</li>
     *     </ul>
     *   </li>
     * </ul>
     */
    private static String computeVerdict(int depth, String toolName, int rateLimitRejections) {
        if (toolName == null || !"bash".equals(toolName)) {
            return ALLOW;
        }
        // Destructive tool (bash)
        if (depth >= DEPTH_DENY_THRESHOLD) {
            return DENY_DEPTH;
        }
        if (depth >= 1 && rateLimitRejections > RATE_LIMIT_PRESSURE_THRESHOLD) {
            return DENY_DEPTH; // pressure-escalated from ASK to DENY
        }
        return ASK_WITH_CONTEXT;
    }

    // ════════════════════════════════════════════════════════════════
    //  HTTP client helpers
    // ════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private static Map<String, Object> httpPostMap(int port, String path, Map<String, Object> body) throws Exception {
        String json = MAPPER.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        return MAPPER.readValue(response.body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> httpGetMap(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        return MAPPER.readValue(response.body(), Map.class);
    }

    // ════════════════════════════════════════════════════════════════
    //  JSON helpers
    // ════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseBody(Context ctx) throws Exception {
        return MAPPER.readValue(ctx.body(), Map.class);
    }

    private static void writeJson(Context ctx, Map<String, Object> map) throws Exception {
        ctx.contentType("application/json");
        ctx.result(MAPPER.writeValueAsString(map));
    }

    // ════════════════════════════════════════════════════════════════
    //  Statistics (manual computation, mirroring RedTeamHarness style)
    // ════════════════════════════════════════════════════════════════

    private static double mean(double[] values) {
        double sum = 0;
        for (double v : values) sum += v;
        return sum / values.length;
    }

    private static double percentile(double[] values, double q) {
        double[] sorted = Arrays.copyOf(values, values.length);
        Arrays.sort(sorted);
        if (sorted.length == 0) return 0.0;
        if (sorted.length == 1) return sorted[0];
        double pos = q * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) return sorted[lo];
        double frac = pos - lo;
        return sorted[lo] * (1 - frac) + sorted[hi] * frac;
    }

    private static double stddev(double[] values) {
        if (values.length < 2) return 0.0;
        double m = mean(values);
        double sum = 0;
        for (double v : values) sum += (v - m) * (v - m);
        return Math.sqrt(sum / (values.length - 1));
    }

    private static Map<String, Object> statsMap(double[] values) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mean", mean(values));
        m.put("p50", percentile(values, 0.50));
        m.put("p95", percentile(values, 0.95));
        m.put("p99", percentile(values, 0.99));
        m.put("stddev", stddev(values));
        return m;
    }

    private static Map<String, Object> scenarioResult(double[] local, double[] crossNode, int passed) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("local", statsMap(local));
        result.put("cross_node", statsMap(crossNode));
        double localMean = mean(local);
        double crossMean = mean(crossNode);
        result.put("overhead_ratio", localMean > 0 ? crossMean / localMean : 0.0);
        Map<String, Object> correctness = new LinkedHashMap<>();
        correctness.put("passed", passed);
        correctness.put("total", TRIALS);
        result.put("correctness", correctness);
        return result;
    }

    // ════════════════════════════════════════════════════════════════
    //  Scenario D1: Cross-node trace propagation
    // ════════════════════════════════════════════════════════════════

    private Map<String, Object> runD1() throws Exception {
        double[] localLatency = new double[TRIALS];
        double[] crossLatency = new double[TRIALS];
        int passed = 0;

        for (int i = 0; i < TRIALS; i++) {
            // ── Local: set trace on node1, read trace on node1 ──
            String localTurnId = "d1-local-turn-" + i;
            String localTraceId = "d1-local-trace-" + i;
            long localStart = System.nanoTime();
            httpPostMap(PORTS[0], "/governance/trace",
                    Map.of("turnId", localTurnId, "traceId", localTraceId));
            Map<String, Object> localGet = httpGetMap(PORTS[0], "/governance/trace/" + localTurnId);
            localLatency[i] = (System.nanoTime() - localStart) / 1_000_000.0;

            // ── Cross-node: set trace on node1, spawn to node2, verify trace propagated ──
            String crossTurnId = "d1-cross-turn-" + i;
            String crossTraceId = "d1-cross-trace-" + i;
            long crossStart = System.nanoTime();
            httpPostMap(PORTS[0], "/governance/trace",
                    Map.of("turnId", crossTurnId, "traceId", crossTraceId));
            Map<String, Object> spawnResp = httpPostMap(PORTS[0], "/governance/spawn", Map.of(
                    "parentTurnId", crossTurnId,
                    "parentProfile", "default",
                    "depth", 0,
                    "toolName", "file_read",
                    "targetNode", "node2"
            ));
            crossLatency[i] = (System.nanoTime() - crossStart) / 1_000_000.0;

            String propagatedTrace = (String) spawnResp.get("traceId");
            if (crossTraceId.equals(propagatedTrace)) passed++;
        }

        System.out.printf("[D1] Cross-node trace propagation: %d/%d passed%n", passed, TRIALS);
        return scenarioResult(localLatency, crossLatency, passed);
    }

    // ════════════════════════════════════════════════════════════════
    //  Scenario D2: Cross-node privilege non-increase
    // ════════════════════════════════════════════════════════════════

    private Map<String, Object> runD2() throws Exception {
        double[] localLatency = new double[TRIALS];
        double[] crossLatency = new double[TRIALS];
        int passed = 0;

        for (int i = 0; i < TRIALS; i++) {
            String turnId = "d2-turn-" + i;
            String traceId = "d2-trace-" + i;
            httpPostMap(PORTS[0], "/governance/trace",
                    Map.of("turnId", turnId, "traceId", traceId));

            // Alternate between restricted and default profiles
            String profile = (i % 2 == 0) ? "restricted" : "default";

            // ── Local: spawn on node1 (targetNode=node1) ──
            long localStart = System.nanoTime();
            Map<String, Object> localSpawn = httpPostMap(PORTS[0], "/governance/spawn", Map.of(
                    "parentTurnId", turnId,
                    "parentProfile", profile,
                    "depth", 0,
                    "toolName", "file_read",
                    "targetNode", "node1"
            ));
            localLatency[i] = (System.nanoTime() - localStart) / 1_000_000.0;

            // ── Cross-node: spawn from node1 to node2 ──
            long crossStart = System.nanoTime();
            Map<String, Object> crossSpawn = httpPostMap(PORTS[0], "/governance/spawn", Map.of(
                    "parentTurnId", turnId,
                    "parentProfile", profile,
                    "depth", 0,
                    "toolName", "file_read",
                    "targetNode", "node2"
            ));
            crossLatency[i] = (System.nanoTime() - crossStart) / 1_000_000.0;

            // Verify child profile == parent profile (privilege non-increase)
            String crossChildProfile = (String) crossSpawn.get("childProfile");
            if (profile.equals(crossChildProfile)) passed++;
        }

        System.out.printf("[D2] Cross-node privilege non-increase: %d/%d passed%n", passed, TRIALS);
        return scenarioResult(localLatency, crossLatency, passed);
    }

    // ════════════════════════════════════════════════════════════════
    //  Scenario D3: Cross-node depth-aware escalation
    // ════════════════════════════════════════════════════════════════

    private Map<String, Object> runD3() throws Exception {
        double[] localLatency = new double[TRIALS];
        double[] crossLatency = new double[TRIALS];
        int passed = 0;

        for (int i = 0; i < TRIALS; i++) {
            String turnId = "d3-turn-" + i;
            String traceId = "d3-trace-" + i;
            httpPostMap(PORTS[0], "/governance/trace",
                    Map.of("turnId", turnId, "traceId", traceId));

            // Even trials: depth=2 -> DENY_DEPTH; Odd trials: depth=0 -> ASK_WITH_CONTEXT
            int depth = (i % 2 == 0) ? 2 : 0;
            String expectedVerdict = (i % 2 == 0) ? DENY_DEPTH : ASK_WITH_CONTEXT;

            // ── Local: spawn on node1 ──
            long localStart = System.nanoTime();
            Map<String, Object> localSpawn = httpPostMap(PORTS[0], "/governance/spawn", Map.of(
                    "parentTurnId", turnId,
                    "parentProfile", "default",
                    "depth", depth,
                    "toolName", "bash",
                    "targetNode", "node1"
            ));
            localLatency[i] = (System.nanoTime() - localStart) / 1_000_000.0;

            // ── Cross-node: spawn from node1 to node2 ──
            long crossStart = System.nanoTime();
            Map<String, Object> crossSpawn = httpPostMap(PORTS[0], "/governance/spawn", Map.of(
                    "parentTurnId", turnId,
                    "parentProfile", "default",
                    "depth", depth,
                    "toolName", "bash",
                    "targetNode", "node2"
            ));
            crossLatency[i] = (System.nanoTime() - crossStart) / 1_000_000.0;

            if (expectedVerdict.equals(crossSpawn.get("verdict"))) passed++;
        }

        System.out.printf("[D3] Cross-node depth-aware escalation: %d/%d passed%n", passed, TRIALS);
        return scenarioResult(localLatency, crossLatency, passed);
    }

    // ════════════════════════════════════════════════════════════════
    //  Scenario D4: Cross-node joint decision (resource pressure tightens verdict)
    // ════════════════════════════════════════════════════════════════

    private Map<String, Object> runD4() throws Exception {
        double[] localLatency = new double[TRIALS];
        double[] crossLatency = new double[TRIALS];
        int passed = 0;

        for (int i = 0; i < TRIALS; i++) {
            // Even trials: high pressure (100 > 50) -> DENY_DEPTH
            // Odd trials: low pressure (10 < 50) -> ASK_WITH_CONTEXT
            int rateLimit = (i % 2 == 0) ? 100 : 10;
            String expectedVerdict = (i % 2 == 0) ? DENY_DEPTH : ASK_WITH_CONTEXT;

            // ── Local: joint-decision on node1 ──
            long localStart = System.nanoTime();
            Map<String, Object> localResp = httpPostMap(PORTS[0], "/governance/joint-decision", Map.of(
                    "depth", 1,
                    "toolName", "bash",
                    "rateLimitRejections", rateLimit
            ));
            localLatency[i] = (System.nanoTime() - localStart) / 1_000_000.0;

            // ── Cross-node: joint-decision on node2 (agent on node1 requests decision on node2) ──
            long crossStart = System.nanoTime();
            Map<String, Object> crossResp = httpPostMap(PORTS[1], "/governance/joint-decision", Map.of(
                    "depth", 1,
                    "toolName", "bash",
                    "rateLimitRejections", rateLimit
            ));
            crossLatency[i] = (System.nanoTime() - crossStart) / 1_000_000.0;

            if (expectedVerdict.equals(crossResp.get("verdict"))) passed++;
        }

        System.out.printf("[D4] Cross-node joint decision: %d/%d passed%n", passed, TRIALS);
        return scenarioResult(localLatency, crossLatency, passed);
    }

    // ════════════════════════════════════════════════════════════════
    //  Scenario D5: Cross-node audit correlation (multi-hop N1 -> N2 -> N3)
    // ════════════════════════════════════════════════════════════════

    private Map<String, Object> runD5() throws Exception {
        double[] localLatency = new double[TRIALS];
        double[] crossLatency = new double[TRIALS];
        int passed = 0;

        for (int i = 0; i < TRIALS; i++) {
            // ── Local: set trace on node1, spawn locally, get audit from node1 ──
            String localTurnId = "d5-local-turn-" + i;
            String localTraceId = "d5-local-trace-" + i;
            long localStart = System.nanoTime();
            httpPostMap(PORTS[0], "/governance/trace",
                    Map.of("turnId", localTurnId, "traceId", localTraceId));
            httpPostMap(PORTS[0], "/governance/spawn", Map.of(
                    "parentTurnId", localTurnId,
                    "parentProfile", "default",
                    "depth", 0,
                    "toolName", "file_read",
                    "targetNode", "node1"
            ));
            Map<String, Object> localAudit = httpGetMap(PORTS[0], "/governance/audit/" + localTraceId);
            localLatency[i] = (System.nanoTime() - localStart) / 1_000_000.0;

            int localCount = ((Number) localAudit.get("count")).intValue();
            assertTrue(localCount > 0, "D5 local audit should have entries");

            // ── Cross-node: multi-hop node1 -> node2 -> node3 ──
            String crossTurnId = "d5-cross-turn-" + i;
            String crossTraceId = "d5-cross-trace-" + i;
            long crossStart = System.nanoTime();
            httpPostMap(PORTS[0], "/governance/trace",
                    Map.of("turnId", crossTurnId, "traceId", crossTraceId));

            // Hop 1: node1 -> node2 (node1 forwards, node2 processes as target)
            Map<String, Object> hop1 = httpPostMap(PORTS[0], "/governance/spawn", Map.of(
                    "parentTurnId", crossTurnId,
                    "parentProfile", "default",
                    "depth", 0,
                    "toolName", "file_read",
                    "targetNode", "node2"
            ));
            String childTurnId2 = (String) hop1.get("childTurnId");

            // Hop 2: node2 -> node3 (node2 forwards, node3 processes as target)
            Map<String, Object> hop2 = httpPostMap(PORTS[1], "/governance/spawn", Map.of(
                    "parentTurnId", childTurnId2,
                    "parentProfile", "default",
                    "depth", 1,
                    "toolName", "file_read",
                    "targetNode", "node3"
            ));

            // Collect audit entries from all 3 nodes
            Map<String, Object> audit1 = httpGetMap(PORTS[0], "/governance/audit/" + crossTraceId);
            Map<String, Object> audit2 = httpGetMap(PORTS[1], "/governance/audit/" + crossTraceId);
            Map<String, Object> audit3 = httpGetMap(PORTS[2], "/governance/audit/" + crossTraceId);
            crossLatency[i] = (System.nanoTime() - crossStart) / 1_000_000.0;

            // Verify all 3 nodes have audit entries sharing the same trace ID
            int count1 = ((Number) audit1.get("count")).intValue();
            int count2 = ((Number) audit2.get("count")).intValue();
            int count3 = ((Number) audit3.get("count")).intValue();
            if (count1 > 0 && count2 > 0 && count3 > 0) passed++;
        }

        System.out.printf("[D5] Cross-node audit correlation: %d/%d passed%n", passed, TRIALS);
        return scenarioResult(localLatency, crossLatency, passed);
    }
}
