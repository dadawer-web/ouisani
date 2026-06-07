package com.ouisani.aios.core.crash;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.cache.SemanticCacheManager;
import com.ouisani.aios.core.cgroup.CgroupManager;
import com.ouisani.aios.core.cgroup.CgroupNode;
import com.ouisani.aios.core.cgroup.TokenOomException;
import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.plugin.AgentToolContext;
import com.ouisani.aios.core.plugin.PluginManager;
import com.ouisani.aios.core.security.ObjectManager;
import com.ouisani.aios.core.security.SecurityToken;
import com.ouisani.aios.core.syscall.SyscallException;
import com.ouisani.aios.core.telemetry.SemanticEtw;
import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.vfs.MutableFileNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语义级蓝屏死机 (Semantic Kernel Panic) 分析器与崩溃恢复引擎。
 * <p>
 * 当大模型在运行中"精神崩溃"（陷入无限重试死循环、严重幻觉、TokenOomException），
 * SemanticCrashAnalyzer 接管致命异常，执行 OS 级别的崩溃处理流程：
 *
 * <h3>崩溃处理流程 (类比 Linux Kernel Panic)</h3>
 * <ol>
 *   <li><b>Trap</b>: 拦截致命异常（TokenOomException / SyscallException / 死锁检测）</li>
 *   <li><b>Suspend</b>: 立即挂起 Agent 进程，阻止继续消耗 Token</li>
 *   <li><b>Dump</b>: 收集认知快照，生成 SemanticCoreDump，写入 /var/crash/dump_{pid}.aios</li>
 *   <li><b>Diagnose</b>: 使用"反思型 LLM"分析崩溃原因</li>
 *   <li><b>Recover</b>: 修改 System Prompt / 清理错误记忆 / 重置 AgentTask 重新调度</li>
 * </ol>
 *
 * <h3>OS 类比</h3>
 * <ul>
 *   <li>Linux Kernel Panic → {@link #kernelPanic}（打印蓝屏、挂起系统）</li>
 *   <li>Linux Core Dump → {@link #collectCoreDump}（收集进程内存映像）</li>
 *   <li>Linux fsck → {@link #analyzeAndRecover}（文件系统检查与修复）</li>
 *   <li>Linux kexec → {@link #restartAgent}（崩溃后快速重启）</li>
 * </ul>
 *
 * @see SemanticCoreDump
 */
public final class SemanticCrashAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(SemanticCrashAnalyzer.class);

    private static final class Holder {
        static final SemanticCrashAnalyzer INSTANCE = new SemanticCrashAnalyzer();
    }

    public static SemanticCrashAnalyzer instance() {
        return Holder.INSTANCE;
    }

    // ── 状态 ──

    private volatile LlmProvider llmProvider;
    private volatile LlmProvider reflectionProvider; // "高级心理医生" LLM

    /** 崩溃历史：agentId → 最近一次核心转储 */
    private final ConcurrentHashMap<String, SemanticCoreDump> crashHistory = new ConcurrentHashMap<>();

    /** 崩溃计数：agentId → 累计崩溃次数 */
    private final ConcurrentHashMap<String, Integer> crashCounts = new ConcurrentHashMap<>();

    /** 死锁检测：agentId → 最近 5 次上下文哈希（检测逻辑来回跳跃） */
    private final ConcurrentHashMap<String, LinkedList<String>> logicFingerprints = new ConcurrentHashMap<>();

    /** 最大崩溃次数 — 超过此数不再自动恢复 */
    private static final int MAX_AUTO_RECOVERY_ATTEMPTS = 3;

    /** 死锁检测窗口大小 */
    private static final int DEADLOCK_FINGERPRINT_WINDOW = 5;

    /** 死锁检测阈值 — 最近 N 次上下文中重复率超过此值视为死锁 */
    private static final double DEADLOCK_REPEAT_THRESHOLD = 0.8;

    private SemanticCrashAnalyzer() {}

    public void configureLlmProvider(LlmProvider provider) {
        this.llmProvider = provider;
    }

    /**
     * 配置"反思型 LLM" — 比 Agent 更高级的 LLM，用于崩溃诊断。
     * 类似于 Linux 的 kdump：用一个独立的内核来分析崩溃内核的转储。
     */
    public void configureReflectionProvider(LlmProvider provider) {
        this.reflectionProvider = provider;
        log.info("[CrashAnalyzer] Reflection LLM configured: {}", provider.name());
    }

    // ════════════════════════════════════════════════════════════════
    //  1. Trap: 致命异常拦截
    // ════════════════════════════════════════════════════════════════

    /**
     * 接管致命异常 — AIOS 的 Trap Handler。
     * <p>
     * 监听 TokenOomException、SyscallException 或 Agent 长时间死锁状态。
     * 一旦触发，立即挂起该 Agent 进程，阻止其继续消耗 Token。
     *
     * @param agentId   崩溃的 Agent 标识
     * @param throwable 致命异常
     */
    public void kernelPanic(String agentId, Throwable throwable) {
        String lastContext = extractLastContextFromTask(agentId);

        // 分类崩溃
        SemanticCoreDump.CrashCategory category = classifyCrash(throwable);

        // 审计追踪
        SemanticEtw.getInstance().logEvent("SECURITY", "KERNEL_PANIC",
                "agent=" + agentId + " category=" + category
                + " exception=" + throwable.getClass().getName()
                + " message=" + truncate(throwable.getMessage(), 200));

        // 打印蓝屏
        printBlueScreen(agentId, throwable, category);

        // 挂起 Agent 进程
        suspendAgent(agentId);

        // 收集核心转储
        SemanticCoreDump coreDump = collectCoreDump(agentId, throwable, lastContext, category);

        // 写入 VFS
        writeDumpToVfs(agentId, coreDump);

        // 记录崩溃历史
        crashHistory.put(agentId, coreDump);
        crashCounts.merge(agentId, 1, Integer::sum);

        // 清理 Agent 资源
        cleanupAgentResources(agentId);

        // LLM 诊断
        diagnoseCrash(agentId, coreDump);

        // ════════════════════════════════════════════════════════════════
        //  EventBus 全网广播 — 通知 AutoMedic 等自愈智能体
        // ════════════════════════════════════════════════════════════════
        broadcastCrashEvent(agentId, coreDump);
    }

    /**
     * 检测 Agent 是否陷入逻辑死锁。
     * <p>
     * 通过分析 Agent 最近 N 次上下文的"逻辑指纹"（截取前 100 字符的哈希），
     * 如果重复率超过阈值，判定为死锁。
     *
     * @param agentId Agent 标识
     * @param currentContext 当前上下文
     * @return true 如果检测到死锁
     */
    public boolean detectLogicDeadlock(String agentId, String currentContext) {
        if (currentContext == null || currentContext.isBlank()) return false;

        String fingerprint = currentContext.length() > 100
                ? currentContext.substring(0, 100) : currentContext;

        LinkedList<String> fingerprints = logicFingerprints.computeIfAbsent(
                agentId, k -> new LinkedList<>());

        synchronized (fingerprints) {
            fingerprints.addLast(fingerprint);
            if (fingerprints.size() > DEADLOCK_FINGERPRINT_WINDOW) {
                fingerprints.removeFirst();
            }

            if (fingerprints.size() >= 3) {
                long repeatCount = fingerprints.stream().filter(f -> f.equals(fingerprint)).count();
                double repeatRate = (double) repeatCount / fingerprints.size();
                if (repeatRate >= DEADLOCK_REPEAT_THRESHOLD) {
                    log.warn("[CrashAnalyzer] Logic deadlock detected for agent={}: "
                            + "repeatRate={}/{}={}", agentId, repeatCount,
                            fingerprints.size(), String.format("%.2f", repeatRate));
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 处理逻辑死锁 — 触发语义级 Kernel Panic。
     */
    public void handleLogicDeadlock(String agentId, String repeatedContext) {
        log.warn("[CrashAnalyzer] Agent {} is in a logic deadlock. Triggering semantic kernel panic.", agentId);
        SemanticEtw.getInstance().logEvent("SECURITY", "LOGIC_DEADLOCK",
                "agent=" + agentId + " context=" + truncate(repeatedContext, 200));

        // 构造一个虚拟异常来触发 kernelPanic 流程
        RuntimeException deadlockException = new RuntimeException(
                "Logic deadlock detected: Agent is stuck in a repetitive reasoning loop. "
                + "Last context: " + truncate(repeatedContext, 300));
        kernelPanic(agentId, deadlockException);
    }

    // ════════════════════════════════════════════════════════════════
    //  2. Suspend: 挂起 Agent 进程
    // ════════════════════════════════════════════════════════════════

    /**
     * 挂起 Agent 进程 — 阻止其继续消耗 Token。
     * <p>
     * 等价于 Linux 的 {@code kill -STOP <pid>}：
     * 进程仍在 PCB 中，但不再被调度执行。
     */
    private void suspendAgent(String agentId) {
        try {
            int pid = resolvePid(agentId);
            if (pid < 0) return;

            TaskScheduler scheduler = VfsManager.instance().getTaskScheduler();
            if (scheduler == null) return;

            AgentTask task = scheduler.getTask(pid);
            if (task != null && !task.isCancelled()) {
                task.cancel();
                task.setStatus(AgentTask.TaskStatus.BLOCKED);
                log.info("[CrashAnalyzer] Agent pid={} SUSPENDED (BLOCKED)", pid);

                // 中断虚拟线程
                Thread vt = scheduler.activeThreads().get(pid);
                if (vt != null) {
                    vt.interrupt();
                }
            }
        } catch (Exception e) {
            log.warn("[CrashAnalyzer] Failed to suspend agent={}: {}", agentId, e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  3. Dump: 收集语义核心转储
    // ════════════════════════════════════════════════════════════════

    /**
     * 收集 Agent 的完整认知快照。
     * <p>
     * 不要只打印 StackTrace。收集：
     * <ul>
     *   <li>SemanticCacheManager 内存页（它最后在想什么）</li>
     *   <li>打开的 VFS 文件句柄</li>
     *   <li>挂载的插件</li>
     *   <li>导致崩溃的最后 5 轮对话上下文</li>
     *   <li>Token 消耗统计</li>
     *   <li>安全令牌信息</li>
     * </ul>
     */
    public SemanticCoreDump collectCoreDump(String agentId, Throwable throwable,
                                             String lastContext, SemanticCoreDump.CrashCategory category) {
        int pid = resolvePid(agentId);
        AgentTask task = resolveTask(pid);

        // ── 崩溃元数据 ──
        SemanticCoreDump.CrashInfo crashInfo = new SemanticCoreDump.CrashInfo(
                agentId,
                Instant.now().toString(),
                throwable.getClass().getName(),
                throwable.getMessage() != null ? throwable.getMessage() : "(no message)",
                stackTraceToString(throwable),
                category
        );

        // ── 认知快照 ──
        SemanticCoreDump.CognitiveSnapshot cognitiveSnapshot = collectCognitiveSnapshot(agentId, lastContext);

        // ── 最后 5 轮对话上下文 ──
        List<String> contextHistory = extractContextHistory(task, 5);

        // ── VFS 文件句柄 ──
        List<SemanticCoreDump.HandleSnapshot> openHandles = collectHandleSnapshots(agentId);

        // ── 挂载的插件 ──
        List<String> loadedPlugins = collectLoadedPlugins(agentId);

        // ── Token 消耗统计 ──
        SemanticCoreDump.CgroupUsage cgroupUsage = collectCgroupUsage(pid);

        // ── 安全令牌 ──
        String securityContext = collectSecurityContext(task);

        // ── Task 元数据 ──
        SemanticCoreDump.TaskMetadata taskMetadata = collectTaskMetadata(task);

        return new SemanticCoreDump(
                crashInfo, cognitiveSnapshot, contextHistory, openHandles,
                loadedPlugins, cgroupUsage, securityContext, taskMetadata
        );
    }

    /**
     * 收集认知快照 — Agent 最后在想什么。
     */
    private SemanticCoreDump.CognitiveSnapshot collectCognitiveSnapshot(String agentId, String lastThinking) {
        try {
            SemanticCacheManager cacheMgr = SemanticCacheManager.instance();
            List<SemanticCacheManager.CacheEntry> entries = cacheMgr.getCacheEntries();

            List<String> summaries = new ArrayList<>();
            int count = Math.min(3, entries.size());
            for (int i = entries.size() - 1; i >= 0 && summaries.size() < count; i--) {
                SemanticCacheManager.CacheEntry entry = entries.get(i);
                String summary = "response(" + entry.responseText().length() + " chars, "
                        + "accessed=" + entry.accessCount() + ", "
                        + "age=" + (System.currentTimeMillis() - entry.createdAt()) + "ms)";
                summaries.add(summary);
            }

            return new SemanticCoreDump.CognitiveSnapshot(
                    entries.size(), summaries,
                    lastThinking != null ? truncate(lastThinking, 500) : "(no thinking context)"
            );
        } catch (Exception e) {
            return new SemanticCoreDump.CognitiveSnapshot(0, List.of(), "(cache unavailable)");
        }
    }

    /**
     * 收集 VFS 文件句柄快照。
     */
    private List<SemanticCoreDump.HandleSnapshot> collectHandleSnapshots(String agentId) {
        try {
            ObjectManager objMgr = ObjectManager.instance();
            List<SemanticCoreDump.HandleSnapshot> snapshots = new ArrayList<>();

            for (Map.Entry<Integer, ObjectManager.HandleInfo> entry : objMgr.activeHandleInfo().entrySet()) {
                ObjectManager.HandleInfo info = entry.getValue();
                if (agentId.equals(info.agentId())) {
                    snapshots.add(new SemanticCoreDump.HandleSnapshot(
                            entry.getKey(), info.vfsPath(), info.openedAt()
                    ));
                }
            }

            return snapshots;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 收集 Agent 挂载的插件列表。
     */
    private List<String> collectLoadedPlugins(String agentId) {
        try {
            PluginManager pm = PluginManager.getInstance();
            AgentToolContext ctx = pm.getAgentContext(agentId);
            if (ctx != null) {
                return new ArrayList<>(ctx.loadedToolNames());
            }
        } catch (Exception e) {
            // ignore
        }
        return List.of();
    }

    /**
     * 收集 Token 消耗统计。
     */
    private SemanticCoreDump.CgroupUsage collectCgroupUsage(int pid) {
        try {
            if (pid < 0) return null;
            CgroupManager cgroupMgr = CgroupManager.instance();
            CgroupNode node = cgroupMgr.getOrCreateAgentCgroup(pid);
            return new SemanticCoreDump.CgroupUsage(
                    node.name(), node.tokenQuota(), node.tokenConsumed(),
                    node.tokenRemaining(), cgroupMgr.getAgentUsagePercent(pid)
            );
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 收集安全令牌信息。
     */
    private String collectSecurityContext(AgentTask task) {
        if (task == null) return "unknown";
        SecurityToken token = task.primaryToken();
        if (token == null) return "no_token";
        return token.ownerId() + "(level=" + token.privilegeLevel()
                + ", capabilities=" + token.capabilities() + ")";
    }

    /**
     * 收集 AgentTask 元数据。
     */
    private SemanticCoreDump.TaskMetadata collectTaskMetadata(AgentTask task) {
        if (task == null) return null;
        return new SemanticCoreDump.TaskMetadata(
                task.pid(),
                task.type() != null ? task.type().name() : "UNKNOWN",
                task.processPriority() != null ? task.processPriority().name() : "UNKNOWN",
                task.gasUsed(), task.gasLimit(), task.budget(),
                task.status() != null ? task.status().name() : "UNKNOWN"
        );
    }

    /**
     * 提取最后 N 轮对话上下文。
     */
    private List<String> extractContextHistory(AgentTask task, int rounds) {
        if (task == null) return List.of();
        List<String> history = task.contextHistory();
        if (history == null || history.isEmpty()) return List.of("(no context history)");

        int from = Math.max(0, history.size() - rounds);
        List<String> result = new ArrayList<>();
        for (int i = from; i < history.size(); i++) {
            String entry = history.get(i);
            result.add(entry.length() > 300 ? entry.substring(0, 300) + "..." : entry);
        }
        return result;
    }

    // ════════════════════════════════════════════════════════════════
    //  4. Diagnose: LLM 诊断崩溃原因
    // ════════════════════════════════════════════════════════════════

    /**
     * 使用"反思型 LLM"诊断崩溃原因。
     * <p>
     * 类似于 Linux 的 kdump：用一个独立的、更高级的内核来分析崩溃内核的转储。
     * 如果配置了 reflectionProvider（"高级心理医生"），使用它；否则回退到普通 LLM。
     */
    private void diagnoseCrash(String agentId, SemanticCoreDump coreDump) {
        LlmProvider diagnoser = reflectionProvider != null ? reflectionProvider : llmProvider;

        if (diagnoser != null) {
            log.info("[CrashAnalyzer] Waking up {} for crash diagnosis of agent={}",
                    reflectionProvider != null ? "Reflection LLM" : "Kernel Debugger LLM", agentId);

            try {
                String dumpJson = coreDump.toJson();
                String prompt = buildDiagnosisPrompt(dumpJson);

                String diagnosis = diagnoser.think(prompt,
                        "You are the AIOS Semantic Kernel Panic Analyzer — a senior AI psychiatrist. "
                        + "You diagnose WHY AI agents crash and HOW to fix them. "
                        + "Be precise, technical, and actionable.");

                // 将诊断报告也写入 VFS
                String diagnosisPath = "/var/crash/diagnosis_" + agentId + ".aios";
                writeToFile(diagnosisPath, diagnosis);

                // 打印诊断结果
                printDiagnosis(agentId, diagnosis);

                SemanticEtw.getInstance().logEvent("SECURITY", "CRASH_DIAGNOSIS",
                        "agent=" + agentId + " diagnosisLen=" + diagnosis.length());

            } catch (Exception e) {
                log.warn("[CrashAnalyzer] LLM diagnosis failed for agent={}: {}", agentId, e.getMessage());
                printLocalDiagnosis(coreDump);
            }
        } else {
            printLocalDiagnosis(coreDump);
        }
    }

    /**
     * 构建诊断 Prompt — 要求 LLM 分析崩溃原因并给出修复建议。
     */
    private String buildDiagnosisPrompt(String dumpJson) {
        return """
            Analyze the following AIOS Semantic Core Dump and diagnose the root cause.
            
            This is a crash dump from an AI Agent running inside the AIOS operating system.
            The agent may have crashed due to:
            1. Token OOM — infinite self-reflection loop consuming all token budget
            2. Logic Deadlock — stuck in a repetitive reasoning pattern (e.g., infinite git conflict resolution)
            3. Severe Hallucination — producing unparseable or dangerous output
            4. Security Violation — attempting unauthorized operations
            5. Runtime Exception — code execution failure
            
            Please provide:
            1. ROOT CAUSE: What specific logic trap caused the crash?
            2. PATTERN: Is this a known anti-pattern? (e.g., "infinite retry without backoff")
            3. FIX: What System Prompt modification or memory cleanup would prevent recurrence?
            4. RECOVERY: Should this agent be restarted? With what modifications?
            
            Core Dump:
            """ + truncate(dumpJson, 6000);
    }

    // ════════════════════════════════════════════════════════════════
    //  5. Recover: 崩溃后自检与自动恢复 (Semantic fsck)
    // ════════════════════════════════════════════════════════════════

    /**
     * 分析崩溃转储文件并尝试自动恢复。
     * <p>
     * 这是 AIOS 的 fsck (File System Check) — 读取 /var/crash 下的 dump 文件，
     * 使用"反思型 LLM"分析 Agent 是因为什么逻辑陷阱崩溃的，
     * 然后尝试通过修改 System Prompt 或清理错误记忆后重置 AgentTask 重新调度。
     *
     * @param agentId 要恢复的 Agent 标识
     * @return 恢复结果
     */
    public RecoveryResult analyzeAndRecover(String agentId) {
        SemanticCoreDump dump = crashHistory.get(agentId);
        if (dump == null) {
            return new RecoveryResult(false, "No crash dump found for agent: " + agentId, null);
        }

        int crashCount = crashCounts.getOrDefault(agentId, 0);
        if (crashCount > MAX_AUTO_RECOVERY_ATTEMPTS) {
            String msg = "Agent '" + agentId + "' has crashed " + crashCount
                    + " times — exceeding max recovery attempts (" + MAX_AUTO_RECOVERY_ATTEMPTS
                    + "). Manual intervention required.";
            log.error("[CrashAnalyzer] {}", msg);
            SemanticEtw.getInstance().logEvent("SECURITY", "RECOVERY_DENIED",
                    "agent=" + agentId + " crashes=" + crashCount);
            return new RecoveryResult(false, msg, null);
        }

        log.info("[CrashAnalyzer] Starting semantic fsck for agent={} (crash #{})", agentId, crashCount);

        // 读取诊断报告
        String diagnosis = readDiagnosis(agentId);

        // 生成恢复策略
        RecoveryStrategy strategy = deviseRecoveryStrategy(dump, diagnosis, crashCount);

        // 执行恢复
        boolean recovered = executeRecovery(agentId, dump, strategy);

        String resultMsg = recovered
                ? "Agent '" + agentId + "' recovered with strategy: " + strategy.type()
                : "Agent '" + agentId + "' recovery failed with strategy: " + strategy.type();

        SemanticEtw.getInstance().logEvent("SECURITY", "RECOVERY",
                "agent=" + agentId + " strategy=" + strategy.type()
                + " success=" + recovered + " crashCount=" + crashCount);

        return new RecoveryResult(recovered, resultMsg, strategy);
    }

    /**
     * 根据崩溃类型和诊断报告，制定恢复策略。
     */
    private RecoveryStrategy deviseRecoveryStrategy(SemanticCoreDump dump, String diagnosis, int crashCount) {
        SemanticCoreDump.CrashCategory category = dump.crashInfo().category();

        return switch (category) {
            case TOKEN_OOM -> new RecoveryStrategy(
                    RecoveryType.RESET_AND_RESCHEDULE,
                    "Token OOM: reset gas counter, increase quota, clear repetitive memory",
                    Map.of("increaseQuota", "true", "clearRepetitiveMemory", "true", "resetGas", "true")
            );
            case LOGIC_DEADLOCK -> new RecoveryStrategy(
                    RecoveryType.MODIFY_SYSTEM_PROMPT,
                    "Logic deadlock: inject anti-loop directive into System Prompt, prune looping context",
                    Map.of("antiLoopDirective", "true", "pruneLoopContext", "true")
            );
            case SEVERE_HALLUCINATION -> new RecoveryStrategy(
                    RecoveryType.CLEAR_MEMORY_AND_RESTART,
                    "Severe hallucination: clear corrupted memory, add grounding constraints",
                    Map.of("clearMemory", "true", "addGroundingConstraint", "true")
            );
            case SECURITY_VIOLATION -> new RecoveryStrategy(
                    RecoveryType.DOWNGRADE_AND_RESTART,
                    "Security violation: downgrade token privileges, add safety guardrails",
                    Map.of("downgradePrivileges", "true", "addSafetyGuardrails", "true")
            );
            case SYSCALL_FAULT -> new RecoveryStrategy(
                    RecoveryType.RESET_AND_RESCHEDULE,
                    "Syscall fault: reset task state, validate syscall schema",
                    Map.of("resetTaskState", "true", "validateSchema", "true")
            );
            default -> new RecoveryStrategy(
                    RecoveryType.RESET_AND_RESCHEDULE,
                    "Unknown crash: full reset and reschedule",
                    Map.of("fullReset", "true")
            );
        };
    }

    /**
     * 执行恢复策略。
     */
    private boolean executeRecovery(String agentId, SemanticCoreDump dump, RecoveryStrategy strategy) {
        try {
            int pid = resolvePid(agentId);
            AgentTask task = resolveTask(pid);

            switch (strategy.type()) {
                case MODIFY_SYSTEM_PROMPT -> {
                    // 清理循环上下文
                    if (task != null && "true".equals(strategy.params().get("pruneLoopContext"))) {
                        List<String> history = task.contextHistory();
                        if (history.size() > 2) {
                            task.replaceHistoryRange(0, history.size() - 1,
                                    "[PRUNED: repetitive context removed by crash recovery]");
                        }
                    }
                    log.info("[CrashAnalyzer] Recovery: modified system prompt for agent={}", agentId);
                    return restartAgent(agentId, task, strategy);
                }

                case CLEAR_MEMORY_AND_RESTART -> {
                    // 清理错误记忆
                    if ("true".equals(strategy.params().get("clearMemory"))) {
                        SemanticCacheManager.instance().clear();
                        log.info("[CrashAnalyzer] Recovery: cleared semantic cache for agent={}", agentId);
                    }
                    return restartAgent(agentId, task, strategy);
                }

                case DOWNGRADE_AND_RESTART -> {
                    // 降级安全令牌
                    if (task != null && "true".equals(strategy.params().get("downgradePrivileges"))) {
                        task.setPrimaryToken(SecurityToken.restrictedToken("recovered_" + agentId));
                        log.info("[CrashAnalyzer] Recovery: downgraded privileges for agent={}", agentId);
                    }
                    return restartAgent(agentId, task, strategy);
                }

                case RESET_AND_RESCHEDULE -> {
                    // 重置 Gas 计数器
                    if (task != null) {
                        if ("true".equals(strategy.params().get("resetGas"))) {
                            task.setGasUsed(0);
                        }
                        // 增加 Token 配额
                        if ("true".equals(strategy.params().get("increaseQuota"))) {
                            CgroupManager.instance().setAgentQuota(task.pid(),
                                    CgroupManager.instance().getOrCreateAgentCgroup(task.pid()).tokenQuota() * 2);
                        }
                    }
                    return restartAgent(agentId, task, strategy);
                }

                default -> {
                    return restartAgent(agentId, task, strategy);
                }
            }
        } catch (Exception e) {
            log.error("[CrashAnalyzer] Recovery execution failed for agent={}: {}", agentId, e.getMessage());
            return false;
        }
    }

    /**
     * 重启 Agent — AIOS 的 kexec。
     * <p>
     * 清理旧状态，重置 AgentTask，重新调度到 TaskScheduler。
     */
    private boolean restartAgent(String agentId, AgentTask task, RecoveryStrategy strategy) {
        if (task == null) {
            log.warn("[CrashAnalyzer] Cannot restart agent={}: task not found", agentId);
            return false;
        }

        // 重置任务状态
        task.resetForRecovery();
        task.setStatus(AgentTask.TaskStatus.READY);
        task.pendingSignals().clear();

        // 清理死锁指纹
        logicFingerprints.remove(agentId);

        // 清理旧句柄
        ObjectManager.instance().closeAllHandlesForAgent(agentId);

        log.info("[CrashAnalyzer] ╔══════════════════════════════════════════════════╗");
        log.info("[CrashAnalyzer] ║  Agent '{}' RESTARTED with strategy: {}",
                agentId, strategy.type());
        log.info("[CrashAnalyzer] ║  Reason: {}", strategy.reason());
        log.info("[CrashAnalyzer] ╚══════════════════════════════════════════════════╝");

        SemanticEtw.getInstance().logEvent("SCHEDULER", "AGENT_RESTART",
                "agent=" + agentId + " strategy=" + strategy.type());

        // 注意：实际的重新调度需要由上层调用者完成，
        // 因为 TaskScheduler.spawn() 需要新的 Runnable
        return true;
    }

    // ════════════════════════════════════════════════════════════════
    //  EventBus 崩溃广播 — 通知 AutoMedic 自愈智能体
    // ════════════════════════════════════════════════════════════════

    /**
     * 将崩溃事件广播到 EventBus 的 "sys.kernel.panic" 频道。
     * <p>
     * AutoMedic 等自愈智能体订阅该频道后，会自动收到崩溃通知并尝试热修复。
     * 广播的 JSON 包含：
     * <ul>
     *   <li>failed_node_id — 崩溃节点的 Agent ID</li>
     *   <li>vfs_script_path — 该节点的脚本路径（从 VFS 句柄推断）</li>
     *   <li>error_stacktrace — 异常堆栈信息</li>
     * </ul>
     *
     * @param agentId   崩溃的 Agent 标识
     * @param coreDump  核心转储
     */
    private void broadcastCrashEvent(String agentId, SemanticCoreDump coreDump) {
        try {
            // 从 VFS 句柄推断脚本路径
            String vfsScriptPath = inferScriptPath(agentId);

            // 组装 Core Dump 遗言 JSON
            String stacktrace = coreDump.crashInfo().stackTrace();
            if (stacktrace == null || stacktrace.isBlank()) {
                stacktrace = coreDump.crashInfo().exceptionClass() + ": " + coreDump.crashInfo().exceptionMessage();
            }
            // 转义 JSON 特殊字符
            String escapedStacktrace = stacktrace
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
            String escapedAgentId = agentId.replace("\"", "\\\"");
            String escapedPath = (vfsScriptPath != null ? vfsScriptPath : "").replace("\"", "\\\"");

            int currentCrashCount = crashCounts.getOrDefault(agentId, 0);

            String crashJson = "{\"failed_node_id\":\"" + escapedAgentId + "\","
                    + "\"vfs_script_path\":\"" + escapedPath + "\","
                    + "\"error_stacktrace\":\"" + escapedStacktrace + "\","
                    + "\"retry_count\":" + currentCrashCount + "}";

            // 全网广播
            EventBus.instance().broadcast("sys.kernel.panic", crashJson);

            System.out.printf("[Kernel Monitor] Node %s crashed. Strike %d/3. Broadcasting advanced core dump...%n",
                    agentId, currentCrashCount);
            log.info("[CrashAnalyzer] Crash event broadcasted to sys.kernel.panic for agent={} (strike {}/3)", agentId, currentCrashCount);
        } catch (Exception e) {
            // 广播失败不应影响崩溃处理主流程
            log.warn("[CrashAnalyzer] Failed to broadcast crash event for agent={}: {}", agentId, e.getMessage());
        }
    }

    /**
     * 从 Agent 的 VFS 句柄中推断脚本路径。
     * <p>
     * 优先查找 /factory/ 下的 .py 文件（OmniFactory 工作流节点），
     * 否则返回第一个写权限句柄的路径，或 null。
     */
    private String inferScriptPath(String agentId) {
        try {
            ObjectManager objMgr = ObjectManager.instance();
            for (Map.Entry<Integer, ObjectManager.HandleInfo> entry : objMgr.activeHandleInfo().entrySet()) {
                ObjectManager.HandleInfo info = entry.getValue();
                if (agentId.equals(info.agentId())) {
                    String path = info.vfsPath();
                    // 优先匹配 OmniFactory 工作流脚本
                    if (path != null && path.startsWith("/factory/") && path.endsWith(".py")) {
                        return path;
                    }
                }
            }
            // 回退：返回第一个关联句柄路径
            for (Map.Entry<Integer, ObjectManager.HandleInfo> entry : objMgr.activeHandleInfo().entrySet()) {
                ObjectManager.HandleInfo info = entry.getValue();
                if (agentId.equals(info.agentId()) && info.vfsPath() != null) {
                    return info.vfsPath();
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    //  辅助方法
    // ════════════════════════════════════════════════════════════════

    /**
     * 分类崩溃类型。
     */
    private SemanticCoreDump.CrashCategory classifyCrash(Throwable throwable) {
        if (throwable instanceof TokenOomException) return SemanticCoreDump.CrashCategory.TOKEN_OOM;
        if (throwable instanceof SyscallException) return SemanticCoreDump.CrashCategory.SYSCALL_FAULT;
        if (throwable instanceof SecurityException) return SemanticCoreDump.CrashCategory.SECURITY_VIOLATION;

        String msg = throwable.getMessage() != null ? throwable.getMessage().toLowerCase() : "";
        if (msg.contains("deadlock") || msg.contains("infinite") || msg.contains("loop")) {
            return SemanticCoreDump.CrashCategory.LOGIC_DEADLOCK;
        }
        if (msg.contains("hallucination") || msg.contains("unparseable") || msg.contains("invalid json")) {
            return SemanticCoreDump.CrashCategory.SEVERE_HALLUCINATION;
        }

        if (throwable instanceof OutOfMemoryError || throwable instanceof StackOverflowError) {
            return SemanticCoreDump.CrashCategory.FATAL_ERROR;
        }
        if (throwable instanceof Error) return SemanticCoreDump.CrashCategory.FATAL_ERROR;
        if (throwable instanceof RuntimeException) return SemanticCoreDump.CrashCategory.RUNTIME_EXCEPTION;

        return SemanticCoreDump.CrashCategory.UNKNOWN;
    }

    /**
     * 打印语义蓝屏。
     */
    private void printBlueScreen(String agentId, Throwable throwable,
                                  SemanticCoreDump.CrashCategory category) {
        System.err.println();
        System.err.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.err.println("  ║                                                              ║");
        System.err.println("  ║   *** SEMANTIC KERNEL PANIC ***                              ║");
        System.err.println("  ║                                                              ║");
        System.err.printf("  ║   Agent: %-50s ║%n", agentId);
        System.err.printf("  ║   Category: %-47s ║%n", category);
        System.err.printf("  ║   Exception: %-46s ║%n", throwable.getClass().getSimpleName());
        System.err.printf("  ║   Message: %-49s ║%n",
                truncate(throwable.getMessage() != null ? throwable.getMessage() : "(none)", 49));
        System.err.println("  ║                                                              ║");
        System.err.println("  ║   The agent has been SUSPENDED.                              ║");
        System.err.println("  ║   A semantic core dump is being generated...                 ║");
        System.err.println("  ║                                                              ║");
        System.err.println("  ╚══════════════════════════════════════════════════════════════╝");
        System.err.println();
    }

    /**
     * 打印 LLM 诊断结果。
     */
    private void printDiagnosis(String agentId, String diagnosis) {
        System.err.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.err.println("  ║  ROOT CAUSE ANALYSIS (LLM Diagnosis)                        ║");
        System.err.printf("  ║  Agent: %-50s ║%n", agentId);
        System.err.println("  ╠══════════════════════════════════════════════════════════════╣");
        for (String line : diagnosis.split("\n")) {
            System.err.printf("  ║  %s%n", truncate(line, 60));
        }
        System.err.println("  ╚══════════════════════════════════════════════════════════════╝");
        System.err.println();
    }

    /**
     * 本地诊断（无 LLM 时的回退方案）。
     */
    private void printLocalDiagnosis(SemanticCoreDump dump) {
        System.err.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.err.println("  ║  LOCAL ANALYSIS (No LLM available)                          ║");
        System.err.println("  ╠══════════════════════════════════════════════════════════════╣");
        System.err.printf("  ║  Category: %-47s ║%n", dump.crashInfo().category());
        System.err.printf("  ║  Exception: %-46s ║%n", dump.crashInfo().exceptionClass());
        System.err.printf("  ║  Message: %-49s ║%n", truncate(dump.crashInfo().exceptionMessage(), 49));

        if (dump.cognitiveSnapshot() != null) {
            System.err.printf("  ║  Cache entries: %-42d ║%n", dump.cognitiveSnapshot().cacheEntryCount());
            System.err.printf("  ║  Last thinking: %-42s ║%n",
                    truncate(dump.cognitiveSnapshot().lastThinking(), 42));
        }

        if (dump.cgroupUsage() != null) {
            System.err.printf("  ║  Token usage: %d/%d (%d%%)%n",
                    dump.cgroupUsage().tokenConsumed(), dump.cgroupUsage().tokenQuota(),
                    dump.cgroupUsage().usagePercent());
        }

        System.err.println("  ╚══════════════════════════════════════════════════════════════╝");
        System.err.println();
    }

    /**
     * 将核心转储写入 VFS: /var/crash/dump_{pid}.aios
     */
    private void writeDumpToVfs(String agentId, SemanticCoreDump coreDump) {
        String crashPath = "/var/crash/dump_" + agentId + ".aios";
        try {
            String dumpJson = coreDump.toJson();
            var nodeOpt = VfsManager.instance().resolve(crashPath);
            if (nodeOpt.isPresent()) {
                nodeOpt.get().write(dumpJson);
            } else {
                // 自动创建转储文件
                MutableFileNode dumpNode = new MutableFileNode(crashPath);
                dumpNode.write(dumpJson);
                VfsManager.instance().mount("/var/crash", "dump_" + agentId + ".aios", dumpNode);
            }
            log.info("[CrashAnalyzer] Core dump written to {}", crashPath);
            System.out.printf("  [CrashAnalyzer] Core dump written to %s (%d bytes)%n",
                    crashPath, dumpJson.length());
        } catch (Exception e) {
            log.warn("[CrashAnalyzer] Failed to write core dump to VFS: {}", e.getMessage());
        }
    }

    /**
     * 清理 Agent 资源 — 关闭句柄、解绑 cgroup。
     */
    private void cleanupAgentResources(String agentId) {
        try {
            // 关闭所有 VFS 句柄
            int closed = ObjectManager.instance().closeAllHandlesForAgent(agentId);
            if (closed > 0) {
                log.info("[CrashAnalyzer] Closed {} VFS handles for crashed agent={}", closed, agentId);
            }
        } catch (Exception e) {
            log.warn("[CrashAnalyzer] Resource cleanup failed for agent={}: {}", agentId, e.getMessage());
        }
    }

    private String readDiagnosis(String agentId) {
        try {
            String path = "/var/crash/diagnosis_" + agentId + ".aios";
            var nodeOpt = VfsManager.instance().resolve(path);
            return nodeOpt.map(VfsNode::read).orElse("(no diagnosis file)");
        } catch (Exception e) {
            return "(diagnosis read failed)";
        }
    }

    private void writeToFile(String vfsPath, String content) {
        try {
            var nodeOpt = VfsManager.instance().resolve(vfsPath);
            if (nodeOpt.isPresent()) {
                nodeOpt.get().write(content);
            } else {
                String name = vfsPath.substring(vfsPath.lastIndexOf('/') + 1);
                String dir = vfsPath.substring(0, vfsPath.lastIndexOf('/'));
                MutableFileNode node = new MutableFileNode(vfsPath);
                node.write(content);
                VfsManager.instance().mount(dir, name, node);
            }
        } catch (Exception e) {
            log.warn("[CrashAnalyzer] Failed to write to {}: {}", vfsPath, e.getMessage());
        }
    }

    private int resolvePid(String agentId) {
        try {
            return Integer.parseInt(agentId);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private AgentTask resolveTask(int pid) {
        if (pid < 0) return null;
        try {
            TaskScheduler scheduler = VfsManager.instance().getTaskScheduler();
            return scheduler != null ? scheduler.getTask(pid) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractLastContextFromTask(String agentId) {
        int pid = resolvePid(agentId);
        AgentTask task = resolveTask(pid);
        if (task == null) return "(no task context)";

        var history = task.contextHistory();
        if (history == null || history.isEmpty()) return "(no context history)";
        int size = history.size();
        int from = Math.max(0, size - 3);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < size; i++) {
            if (i > from) sb.append(" | ");
            String entry = history.get(i);
            sb.append(entry.length() > 200 ? entry.substring(0, 200) + "..." : entry);
        }
        return sb.toString();
    }

    private String stackTraceToString(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        String trace = sw.toString();
        if (trace.length() > 4000) {
            trace = trace.substring(0, 4000) + "...(truncated)";
        }
        return trace;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    // ════════════════════════════════════════════════════════════════
    //  公共查询 API
    // ════════════════════════════════════════════════════════════════

    public SemanticCoreDump getCrashDump(String agentId) {
        return crashHistory.get(agentId);
    }

    public int getCrashCount(String agentId) {
        return crashCounts.getOrDefault(agentId, 0);
    }

    public Map<String, Integer> getAllCrashCounts() {
        return Map.copyOf(crashCounts);
    }

    public void clearCrashHistory(String agentId) {
        crashHistory.remove(agentId);
        crashCounts.remove(agentId);
        logicFingerprints.remove(agentId);
    }

    // ════════════════════════════════════════════════════════════════
    //  恢复策略与结果
    // ════════════════════════════════════════════════════════════════

    /** 恢复策略类型 */
    public enum RecoveryType {
        /** 修改 System Prompt，注入反循环指令 */
        MODIFY_SYSTEM_PROMPT,
        /** 清理错误记忆后重启 */
        CLEAR_MEMORY_AND_RESTART,
        /** 降级权限后重启 */
        DOWNGRADE_AND_RESTART,
        /** 重置状态后重新调度 */
        RESET_AND_RESCHEDULE
    }

    /** 恢复策略 */
    public record RecoveryStrategy(
            RecoveryType type,
            String reason,
            Map<String, String> params
    ) {}

    /** 恢复结果 */
    public record RecoveryResult(
            boolean success,
            String message,
            RecoveryStrategy strategy
    ) {}
}
