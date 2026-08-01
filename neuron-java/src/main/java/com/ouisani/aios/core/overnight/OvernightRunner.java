package com.ouisani.aios.core.overnight;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.config.AiosPaths;
import com.ouisani.aios.core.ipc.SignalType;
import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.permission.PermissionChecker;
import com.ouisani.aios.core.permission.PermissionDecision;
import com.ouisani.aios.core.permission.PermissionProfile;
import com.ouisani.aios.core.snapshot.EnvironmentSnapshot;
import com.ouisani.aios.core.snapshot.EnvironmentSnapshotManager;
import com.ouisani.aios.core.snapshot.ForkHandle;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Overnight 运行器 — 长跑会话的守护进程与 coordinator 编排器。
 * <p>
 * 双层分离架构（借鉴 jcode overnight.rs 的 run_supervisor + run_turn_monitored）：
 * <ul>
 *   <li><b>OvernightRunner（supervisor）</b>：内核守护进程（单例 Holder，类似 WatchdogDaemon），
 *       拥有 manifest、通过 {@link TaskScheduler#spawn} 拉起 coordinator agent、
 *       周期采样资源、处理取消、检测 coordinator 存活。</li>
 *   <li><b>Coordinator agent</b>：NORMAL 优先级的虚拟线程 Agent，享有 cgroup 隔离、
 *       gas 预算、deadline、OOM 保护、崩溃恢复。它的 Runnable 是主循环：
 *       读 manifest → 算 phase → 选 prompt → thinkWithHistory → 解析卡片 → 持久化 → sleep。</li>
 * </ul>
 * <p>
 * 阶段不存储，由 {@link OvernightPhase#compute} 实时计算。coordinator 每轮自算。
 * manifest + 任务卡片 VFS 持久化到 {@code /var/run/overnight/{runId}/}。
 *
 * @see OvernightContract
 * @see OvernightPhase
 * @see OvernightManifest
 * @see OvernightResultAcceptor
 */
public final class OvernightRunner {

    private static final Logger log = LoggerFactory.getLogger(OvernightRunner.class);

    /** supervisor 采样间隔（秒） */
    private static final long SUPERVISOR_INTERVAL_SEC = 30;

    /** coordinator 每轮间隔（毫秒） */
    private static final long COORDINATOR_LOOP_INTERVAL_MS = 30_000L;

    // ── Singleton ──

    private static final class Holder {
        static final OvernightRunner INSTANCE = new OvernightRunner();
    }

    public static OvernightRunner instance() {
        return Holder.INSTANCE;
    }

    // ── 状态 ──

    private volatile LlmProvider llmProvider;
    private volatile TaskScheduler taskScheduler;
    private ScheduledExecutorService supervisorScheduler;
    private final AtomicBoolean supervisorRunning = new AtomicBoolean(false);
    private volatile OvernightManifest activeManifest;
    private final ConcurrentHashMap<String, OvernightTaskCard> cardIndex = new ConcurrentHashMap<>();

    /** cardId → 该卡片构建时所在 turn 的快照 ID(FAILED 时用于 fork 复现)。 */
    private final ConcurrentHashMap<String, String> preTaskSnapshotByCardId = new ConcurrentHashMap<>();

    /** 当前 turn 的快照 ID(coordinator 单线程,无需同步)。 */
    private volatile String currentTurnSnapshotId;

    /**
     * Overnight 上下文标志 — InheritableThreadLocal 让 overnight 上下文自动传播到
     * coordinator 虚拟线程及其 spawn 的子 agent（OmniMotherAgent/OperatorAgent）。
     * <p>
     * 子 agent 创建 QueryEngine 时调 {@link #getCurrentPermissionProfile()} 检查：
     * 非 null 则用 DONT_ASK 画像构造 QueryEngine，否则用默认（无画像）构造。
     */
    private static final InheritableThreadLocal<PermissionProfile> overnightProfile =
            new InheritableThreadLocal<>();

    /**
     * 全局 denials 缓冲区 — 收集所有子 agent PermissionChecker 转发的 DENY 决策，
     * 供晨报聚合呈现给用户"被拒操作 + 建议规则"清单。
     * <p>
     * 通过 {@link PermissionChecker#setGlobalDenialSink} 注册为 sink；容量 1024 防爆炸。
     */
    private final ConcurrentLinkedDeque<PermissionChecker.DenialRecord> aggregatedDenials =
            new ConcurrentLinkedDeque<>();
    private static final int MAX_AGGREGATED_DENIALS = 1024;

    private OvernightRunner() {}

    /** 注入依赖 — 启动时由 init 序列调用（与 CognitiveDreamDaemon.configure 一致） */
    public void configure(LlmProvider llmProvider, TaskScheduler taskScheduler) {
        this.llmProvider = llmProvider;
        this.taskScheduler = taskScheduler;
        log.info("[OvernightRunner] 已配置: llm={}, taskScheduler={}",
                llmProvider != null ? llmProvider.name() : "null",
                taskScheduler != null ? "ready" : "null");
    }

    // ════════════════════════════════════════════════════════════════
    //  启动一次 overnight run
    // ════════════════════════════════════════════════════════════════

    /**
     * 启动一次 overnight run。
     * <p>
     * 创建 manifest → VFS 持久化 → spawn coordinator（NORMAL 优先级）→ 启动 supervisor。
     *
     * @param mission   使命描述（可空）
     * @param duration  持续时长
     * @return runId
     */
    public synchronized String startOvernightRun(String mission, Duration duration) {
        if (llmProvider == null || taskScheduler == null) {
            throw new IllegalStateException("OvernightRunner 未配置，请先调用 configure()");
        }
        if (activeManifest != null && !activeManifest.isTerminal()) {
            throw new IllegalStateException("已有 run 进行中: " + activeManifest.runId());
        }

        String runId = "overnight-" + System.currentTimeMillis();
        String vfsRunDir = AiosPaths.overnightDir() + "/" + runId;
        OvernightManifest m = OvernightManifest.create(runId, mission, duration, vfsRunDir);
        persistManifest(m);
        activeManifest = m;

        // 设置 overnight 上下文 — coordinator 虚拟线程通过 InheritableThreadLocal 继承，
        // 进而传播到其 spawn 的子 agent（OmniMotherAgent/OperatorAgent），让它们用 DONT_ASK 画像。
        PermissionProfile profile = OvernightPermissionProfile.build();
        overnightProfile.set(profile);
        aggregatedDenials.clear();
        PermissionChecker.setGlobalDenialSink(this::collectDenial);
        log.info("[OvernightRunner] 已注入 DONT_ASK 权限画像: deny={} rules, allow={} rules",
                profile.denyRules().size(), profile.allowRules().size());

        // spawn coordinator agent
        AgentTask coordTask = new AgentTask(
                taskScheduler.nextPid(),
                AgentTask.TaskStatus.READY,
                "overnight/" + runId,
                null, null,
                List.of()
        );
        coordTask.setProcessPriority(ProcessPriority.NORMAL);
        coordTask.setDeadlineMs(m.postWakeGraceUntil().plus(Duration.ofHours(1)).toEpochMilli());
        coordTask.setBudget(500);
        coordTask.setGasLimit(1_000_000);

        int pid = taskScheduler.spawn(coordTask,
                () -> runCoordinatorLoop(coordTask),
                vfsRunDir
        );
        activeManifest = m.withCoordinatorPid(pid);
        persistManifest(activeManifest);

        startSupervisor();

        SemanticEtw.getInstance().logEvent("OVERNIGHT", "START",
                "runId=" + runId + " pid=" + pid + " duration=" + duration);

        log.info("[OvernightRunner] overnight run 已启动: runId={}, pid={}, targetWake={}",
                runId, pid, m.targetWakeLabel());
        return runId;
    }

    /**
     * 取当前线程的 overnight 权限画像（供子 agent 创建 QueryEngine 时检查）。
     * <p>
     * 由 {@link com.ouisani.aios.user.apps.omnifactory.OmniMotherAgent} /
     * {@link com.ouisani.aios.user.apps.omnifactory.OperatorAgent} 在构造 QueryEngine 时调用：
     * <pre>{@code
     * PermissionProfile profile = OvernightRunner.getCurrentPermissionProfile();
     * this.queryEngine = profile != null
     *     ? new QueryEngine(sdk, agentId, workingDir, tools, profile)
     *     : new QueryEngine(sdk, agentId, workingDir, tools);
     * }</pre>
     *
     * @return 当前 overnight 画像；非 overnight 上下文返回 null
     */
    public static PermissionProfile getCurrentPermissionProfile() {
        return overnightProfile.get();
    }

    /** 清除当前线程的 overnight 上下文 — coordinator 主循环退出时调用。 */
    private static void clearOvernightContext() {
        overnightProfile.remove();
    }

    /**
     * 全局 denial sink 回调 — 由 {@link PermissionChecker#setGlobalDenialSink} 注册。
     * <p>
     * 收集到 {@link #aggregatedDenials} 缓冲区，供晨报聚合。
     */
    private void collectDenial(PermissionChecker.DenialRecord record) {
        aggregatedDenials.addLast(record);
        while (aggregatedDenials.size() > MAX_AGGREGATED_DENIALS) {
            aggregatedDenials.pollFirst();
        }
    }

    /**
     * 取出（不清空）聚合的 denials — 供晨报构造时调用。
     */
    public List<PermissionChecker.DenialRecord> getAggregatedDenials() {
        return new ArrayList<>(aggregatedDenials);
    }

    // ════════════════════════════════════════════════════════════════
    //  Coordinator 主循环 — 在 spawned 虚拟线程中执行
    // ════════════════════════════════════════════════════════════════

    /**
     * coordinator 主循环：每轮读 manifest → 算 phase → 选 prompt → LLM → 解析卡片 → sleep。
     * <p>
     * SIGTERM（取消）导致 InterruptedException → 正常退出。
     * 未捕获异常 → 标 FAILED + SemanticCrashAnalyzer.kernelPanic（由 spawn 已处理）。
     */
    private void runCoordinatorLoop(AgentTask selfTask) {
        OvernightManifest m = activeManifest;
        List<LlmProvider.ChatMessage> history = new ArrayList<>();
        boolean firstTurn = true;
        int pid = selfTask.pid();

        log.info("[OvernightRunner] coordinator#{} 启动，使命: {}", pid, m.effectiveMission());

        try {
            while (true) {
                OvernightManifest cur = activeManifest;
                if (cur.isTerminal() || cur.isCancelled()) {
                    log.info("[OvernightRunner] coordinator#{} 检测到终态/取消: {}", pid, cur.status());
                    break;
                }

                OvernightPhase phase = OvernightPhase.compute(cur, Instant.now());
                if (phase == OvernightPhase.FINALIZING) {
                    String prompt = OvernightContract.buildFinalWrapupPrompt(cur);
                    String resp = llmProvider.think(prompt, OvernightContract.COORDINATOR_SYSTEM_PROMPT);
                    parseAndPersistCardUpdates(resp, cur);
                    log.info("[OvernightRunner] coordinator#{} 完成最终收尾", pid);
                    break;
                }

                // 【每轮快照】— 借鉴 mobilegym,每轮 LLM 调用前冻结环境,FAILED 时可 fork 复现
                try {
                    EnvironmentSnapshot turnSnap = EnvironmentSnapshotManager.instance().capture(
                            "overnight-" + cur.runId() + "-turn-" + history.size());
                    currentTurnSnapshotId = turnSnap.snapshotId();
                } catch (Exception ex) {
                    log.debug("[OvernightRunner] turn 快照捕获失败: {}", ex.getMessage());
                    currentTurnSnapshotId = null;
                }

                OvernightResourceSnapshot snapshot = OvernightResourceSnapshot.capture();
                String prompt = firstTurn
                        ? OvernightContract.buildCoordinatorPrompt(cur, snapshot)
                        : OvernightContract.promptForPhase(phase, cur, snapshot);

                if (prompt == null || prompt.isBlank()) {
                    log.debug("[OvernightRunner] coordinator#{} phase={} 无 prompt，跳过", pid, phase);
                } else {
                    history.add(LlmProvider.ChatMessage.user(prompt));
                    // 晨报轮：在主 prompt 之后追加被拒操作清单，让 LLM 据此生成完整晨报
                    if (phase == OvernightPhase.MORNING_REPORT && cur.morningReportPostedAt() == null) {
                        List<PermissionChecker.DenialRecord> denials = getAggregatedDenials();
                        String denialSection = OvernightContract.formatDenialsForMorningReport(denials);
                        if (!denialSection.isBlank()) {
                            history.add(LlmProvider.ChatMessage.user(denialSection));
                            log.info("[OvernightRunner] 晨报聚合: {} 条 DENY 决策已注入 prompt", denials.size());
                            SemanticEtw.getInstance().logEvent("OVERNIGHT", "DENIALS_AGGREGATED",
                                    "runId=" + cur.runId() + " count=" + denials.size());
                        }
                    }
                    String resp = llmProvider.thinkWithHistory(history,
                            OvernightContract.COORDINATOR_SYSTEM_PROMPT);
                    if (resp != null && !resp.isBlank()) {
                        history.add(LlmProvider.ChatMessage.assistant(resp));
                        parseAndPersistCardUpdates(resp, cur);
                    }
                    firstTurn = false;
                }

                // phase 专属副作用
                if (phase == OvernightPhase.MORNING_REPORT && cur.morningReportPostedAt() == null) {
                    activeManifest = cur.withMorningReportPosted(Instant.now());
                    persistManifest(activeManifest);
                    SemanticEtw.getInstance().logEvent("OVERNIGHT", "MORNING_REPORT",
                            "runId=" + cur.runId());
                }

                activeManifest = activeManifest.withLastActivity(Instant.now());
                persistManifest(activeManifest);

                Thread.sleep(COORDINATOR_LOOP_INTERVAL_MS);
            }
        } catch (InterruptedException e) {
            log.info("[OvernightRunner] coordinator#{} 被中断（取消）", pid);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("[OvernightRunner] coordinator#{} 异常: {}", pid, e.getMessage(), e);
            activeManifest = activeManifest.withStatus(OvernightManifest.OvernightRunStatus.FAILED);
        } finally {
            // 清理 overnight 上下文：注销 sink + 清 ThreadLocal
            // 注意：sink 是全局静态，必须显式注销，否则会泄漏到非 overnight 时段
            PermissionChecker.clearGlobalDenialSink();
            clearOvernightContext();
            terminalize(pid);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Supervisor — 资源采样 + 存活检测
    // ════════════════════════════════════════════════════════════════

    private void startSupervisor() {
        if (!supervisorRunning.compareAndSet(false, true)) return;

        supervisorScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aios-overnight-supervisor");
            t.setDaemon(true);
            return t;
        });

        supervisorScheduler.scheduleAtFixedRate(
                this::supervisorTick,
                SUPERVISOR_INTERVAL_SEC,
                SUPERVISOR_INTERVAL_SEC,
                TimeUnit.SECONDS
        );

        log.info("[OvernightRunner] supervisor 已启动，采样间隔: {}s", SUPERVISOR_INTERVAL_SEC);
    }

    /** supervisor 定时 tick：资源采样 + coordinator 存活检测 */
    private void supervisorTick() {
        OvernightManifest m = activeManifest;
        if (m == null || m.isTerminal()) return;

        try {
            OvernightResourceSnapshot snapshot = OvernightResourceSnapshot.capture();
            log.debug("[OvernightRunner] supervisor tick: {}, phase={}",
                    snapshot.summary(),
                    OvernightPhase.compute(m, Instant.now()));

            // 存活检测：coordinator 已退出但 manifest 仍为 RUNNING → 崩溃
            if (m.coordinatorPid() > 0
                    && m.status() == OvernightManifest.OvernightRunStatus.RUNNING
                    && taskScheduler.getTask(m.coordinatorPid()) == null) {
                log.error("[OvernightRunner] coordinator#{} 已退出但状态仍为 RUNNING，标记 FAILED",
                        m.coordinatorPid());
                activeManifest = m.withStatus(OvernightManifest.OvernightRunStatus.FAILED);
                persistManifest(activeManifest);
                SemanticEtw.getInstance().logEvent("OVERNIGHT", "COORDINATOR_DIED",
                        "runId=" + m.runId() + " pid=" + m.coordinatorPid());
            }

            // 资源告警
            if (!snapshot.isHealthy()) {
                log.warn("[OvernightRunner] 资源紧张: {}", snapshot.summary());
            }
        } catch (Exception e) {
            log.warn("[OvernightRunner] supervisor tick 异常: {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  取消 / 终结 / 进度
    // ════════════════════════════════════════════════════════════════

    /** 取消当前 run — 设 CANCEL_REQUESTED + SIGTERM coordinator */
    public synchronized boolean cancel() {
        OvernightManifest m = activeManifest;
        if (m == null || m.isTerminal()) return false;

        activeManifest = m.withCancelRequested(Instant.now());
        persistManifest(activeManifest);

        if (m.coordinatorPid() > 0) {
            taskScheduler.kill(String.valueOf(m.coordinatorPid()), SignalType.SIGTERM);
            log.info("[OvernightRunner] 已发送 SIGTERM 给 coordinator#{}", m.coordinatorPid());
        }

        SemanticEtw.getInstance().logEvent("OVERNIGHT", "CANCEL",
                "runId=" + m.runId());
        return true;
    }

    /** coordinator 退出时终结 run */
    private void terminalize(int pid) {
        OvernightManifest m = activeManifest;
        if (m == null) return;

        if (m.status() == OvernightManifest.OvernightRunStatus.RUNNING) {
            activeManifest = m.withStatus(OvernightManifest.OvernightRunStatus.COMPLETED);
        }
        persistManifest(activeManifest);

        // 触发接纳器评估所有卡片
        if (!cardIndex.isEmpty()) {
            try {
                var result = OvernightResultAcceptor.instance()
                        .evaluateAll(new ArrayList<>(cardIndex.values()));
                log.info("[OvernightRunner] run {} 终结，接纳结果: {}",
                        m.runId(), result);
                SemanticEtw.getInstance().logEvent("OVERNIGHT", "TERMINATE",
                        "runId=" + m.runId() + " status=" + activeManifest.status()
                                + " cards=" + cardIndex.size()
                                + " accepted=" + result.accepted());
            } catch (Exception e) {
                log.error("[OvernightRunner] 接纳评估失败: {}", e.getMessage());
            }
        }

        stopSupervisor();
        log.info("[OvernightRunner] run {} 已终结: status={}", m.runId(), activeManifest.status());
    }

    /** 获取进度摘要 */
    public OvernightTaskCard.Summary progress() {
        return OvernightTaskCard.summarize(new ArrayList<>(cardIndex.values()));
    }

    /**
     * 从种子快照 fork N 个隔离分支 — 借鉴 mobilegym "fork 结构化状态成 N 个并行 rollout"。
     * <p>
     * 失败诊断场景:取 FAILED 卡片关联的 turn 快照({@link #snapshotIdForCard}),
     * fork 出隔离分支重放该 turn,无需重跑整条 DAG。也是 group-RL 策略对比的入口。
     * <p>
     * <b>并发约束</b>:返回的 {@link ForkHandle} 尚未 activate;调用方须串行 activate
     * (activator 会向全局 capturer 注册表注册分支 capturer,并发 activate 会互相覆盖)。
     *
     * @param snapshotId 种子快照 ID(通常来自 {@link #snapshotIdForCard})
     * @param n          分支数
     * @return fork 分支句柄列表(尚未 activate)
     * @throws IllegalStateException 种子快照不存在
     */
    public List<ForkHandle> reproduceWithFork(String snapshotId, int n) {
        log.info("[OvernightRunner] fork 复现: seed={}, branches={}", snapshotId, n);
        return EnvironmentSnapshotManager.instance().forkFromSnapshot(snapshotId, n);
    }

    /**
     * 查询某张卡片构建时所在 turn 的快照 ID(仅 FAILED 卡片会被关联)。
     * <p>
     * 配合 {@link #reproduceWithFork} 实现"出错前一刻"环境快照的 fork 复现。
     *
     * @param cardId 卡片 ID
     * @return 关联快照 ID;未关联或卡片不存在返回 null
     */
    public String snapshotIdForCard(String cardId) {
        return preTaskSnapshotByCardId.get(cardId);
    }

    /** 获取当前 manifest（只读） */
    public OvernightManifest activeManifest() {
        return activeManifest;
    }

    /** 停止 supervisor */
    private void stopSupervisor() {
        if (!supervisorRunning.compareAndSet(true, false)) return;
        if (supervisorScheduler != null) {
            supervisorScheduler.shutdown();
            try {
                if (!supervisorScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    supervisorScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                supervisorScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  VFS 持久化 + 卡片解析
    // ════════════════════════════════════════════════════════════════

    /** 持久化 manifest 到 VFS */
    private void persistManifest(OvernightManifest m) {
        try {
            String path = m.vfsRunDir() + "/manifest.json";
            com.ouisani.aios.core.VfsManager.instance().writeText(path, manifestToJson(m));
        } catch (Exception e) {
            log.warn("[OvernightRunner] persistManifest 失败: {}", e.getMessage());
        }
    }

    /** manifest 序列化为 JSON（简化版） */
    private String manifestToJson(OvernightManifest m) {
        return """
                {"runId":"%s","status":"%s","startedAt":"%s","targetWakeAt":"%s","handoffReadyAt":"%s","postWakeGraceUntil":"%s","morningReportPostedAt":"%s","coordinatorPid":%d,"mission":"%s"}
                """.formatted(
                m.runId(), m.status().label(),
                m.startedAt(), m.targetWakeAt(), m.handoffReadyAt(), m.postWakeGraceUntil(),
                m.morningReportPostedAt(), m.coordinatorPid(),
                m.effectiveMission().replace("\"", "\\\"")
        );
    }

    /**
     * 从 LLM 响应中解析任务卡片更新并持久化。
     * <p>
     * 简化策略：提取包含 "id" 和 "status" 的 JSON 对象，存入 cardIndex + VFS。
     */
    private void parseAndPersistCardUpdates(String llmResponse, OvernightManifest m) {
        if (llmResponse == null || llmResponse.isBlank()) return;

        try {
            String cleaned = llmResponse.replaceAll("(?s)\\[INST\\].*?\\[/INST\\]", "").trim();
            int idx = 0;
            while (true) {
                int start = cleaned.indexOf("{", idx);
                if (start < 0) break;
                int end = findMatchingBrace(cleaned, start);
                if (end < 0) break;
                String json = cleaned.substring(start, end + 1);
                idx = end + 1;

                if (json.contains("\"id\"") && json.contains("\"status\"")) {
                    String cardId = extractField(json, "id");
                    if (cardId != null) {
                        OvernightTaskCard card = buildCardFromJson(cardId, json);
                        cardIndex.put(cardId, card);
                        persistCard(card, m);
                        log.debug("[OvernightRunner] 卡片更新: id={}, status={}",
                                cardId, card.normalizedStatus());
                        // FAILED 卡片关联 turn 快照,供 fork 复现
                        if (card.normalizedStatus() == OvernightTaskCard.CardStatus.FAILED
                                && currentTurnSnapshotId != null) {
                            preTaskSnapshotByCardId.put(cardId, currentTurnSnapshotId);
                            log.warn("[OvernightRunner] 卡片 FAILED: id={}, 关联快照={}, 可 reproduceWithFork 复现",
                                    cardId, currentTurnSnapshotId);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[OvernightRunner] 卡片解析跳过: {}", e.getMessage());
        }
    }

    /** 找到匹配的右花括号 */
    private int findMatchingBrace(String s, int start) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    /** 简单提取 JSON 字段值 */
    private String extractField(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colon = json.indexOf(":", idx + key.length());
        if (colon < 0) return null;
        int valueStart = colon + 1;
        while (valueStart < json.length() && (json.charAt(valueStart) == ' '
                || json.charAt(valueStart) == '\t' || json.charAt(valueStart) == '\n')) {
            valueStart++;
        }
        if (valueStart >= json.length()) return null;
        if (json.charAt(valueStart) == '"') {
            int end = json.indexOf("\"", valueStart + 1);
            return end > 0 ? json.substring(valueStart + 1, end) : null;
        }
        int end = valueStart;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}'
                && json.charAt(end) != '\n') {
            end++;
        }
        return json.substring(valueStart, end).trim();
    }

    /** 从 JSON 构建 OvernightTaskCard（简化版，提取核心字段） */
    private OvernightTaskCard buildCardFromJson(String id, String json) {
        String title = extractField(json, "title");
        String status = extractField(json, "status");
        String riskStr = extractField(json, "risk");
        String outcome = extractField(json, "outcome");
        OvernightTaskCard.RiskLevel risk = OvernightTaskCard.RiskLevel.fromString(riskStr);
        String validationResult = extractField(json, "result");

        // 提取声称的变更文件列表,并自动推断确定性校验规格(mobilegym check_goals 借鉴)
        List<String> filesChanged = extractStringArray(json, "filesChanged");
        if (filesChanged.isEmpty()) {
            filesChanged = extractStringArray(json, "files_changed");  // 兼容 snake_case
        }
        List<VerificationSpec> specs = OvernightTaskCard.inferSpecsFromFiles(filesChanged);

        return new OvernightTaskCard(
                id,
                title != null ? title : id,
                status != null ? status : "active",
                extractField(json, "priority"),
                extractField(json, "source"),
                extractField(json, "why_selected"),
                extractField(json, "verifiability"),
                risk,
                outcome,
                new OvernightTaskCard.Before(extractField(json, "problem"), null),
                new OvernightTaskCard.After(extractField(json, "change"), filesChanged.isEmpty() ? null : filesChanged, null),
                new OvernightTaskCard.Validation(null, validationResult, null),
                null,
                Instant.now().toString(),
                specs
        );
    }

    /** 从 JSON 提取字符串数组字段 — 支持 "field": ["a","b","c"] 格式 */
    private static List<String> extractStringArray(String json, String field) {
        List<String> result = new ArrayList<>();
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return result;
        int bracketStart = json.indexOf('[', idx + key.length());
        if (bracketStart < 0) return result;
        int depth = 0;
        for (int i = bracketStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) break; }
            else if (c == '"') {
                int end = json.indexOf('"', i + 1);
                if (end > 0) {
                    result.add(json.substring(i + 1, end));
                    i = end;
                }
            }
        }
        return result;
    }

    /** 持久化单张卡片到 VFS */
    private void persistCard(OvernightTaskCard card, OvernightManifest m) {
        try {
            String path = OvernightContract.cardsDir(m) + "/" + card.id() + ".json";
            com.ouisani.aios.core.VfsManager.instance().writeText(path,
                    "{\"id\":\"" + card.id() + "\",\"title\":\"" + card.title()
                            + "\",\"status\":\"" + card.status() + "\",\"risk\":\"" + card.risk()
                            + "\"}");
        } catch (Exception e) {
            log.debug("[OvernightRunner] persistCard 失败: {}", e.getMessage());
        }
    }
}
