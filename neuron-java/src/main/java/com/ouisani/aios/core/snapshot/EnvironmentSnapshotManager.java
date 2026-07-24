package com.ouisani.aios.core.snapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 统一执行环境快照管理器 — 编排所有 {@link SnapshotCapturer} 完成
 * capture/restore/persist/load,是借鉴 mobilegym 方法论的统一入口。
 * <p>
 * <h3>依赖倒置</h3>
 * core/snapshot 定义此管理器与 {@link SnapshotCapturer} 接口,user 态
 * (如 WorkflowContextCapturer)与 core 内建实现(如 ProcessSectionCapturer)
 * 各自实现并通过 {@link #registerCapturer} 注册。core 绝不 import user 态。
 * <p>
 * <h3>与现有三套机制的关系</h3>
 * <ul>
 *   <li>{@link SnapshotManager}(ProcessSnapshot)→ 被 ProcessSectionCapturer 包装为 ProcessSection</li>
 *   <li>{@code BoulderStateManager}(BoulderCheckpoint)→ 被 BoulderSectionCapturer 包装为 BoulderSection</li>
 *   <li>{@code HibernationManager}(AgentSnapshot)→ 被 HibernationSectionCapturer 包装为 HibernationSection</li>
 * </ul>
 * 过渡期:WorkflowEngine 现有 Boulder 调用保留,新增 EnvironmentSnapshot 双写,
 * 验证稳定后收敛。
 * <p>
 * <h3>Phase 进度</h3>
 * <ul>
 *   <li>Phase 1(本类骨架):register/capture/restore/persist/load</li>
 *   <li>Phase 4:diff(before, after, expectation)</li>
 *   <li>Phase 5:forkFromSnapshot(snapshotId, n)</li>
 * </ul>
 *
 * @see SnapshotCapturer
 * @see SnapshotSection
 * @see EnvironmentSnapshot
 * @see ForkHandle
 */
public final class EnvironmentSnapshotManager {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentSnapshotManager.class);

    private static final String SNAPSHOT_DIR =
            System.getProperty("user.home") + "/.aios/env_snapshots/";

    private static final class Holder {
        static final EnvironmentSnapshotManager INSTANCE = new EnvironmentSnapshotManager();
    }

    public static EnvironmentSnapshotManager instance() {
        return Holder.INSTANCE;
    }

    private EnvironmentSnapshotManager() {
        try {
            Files.createDirectories(Paths.get(SNAPSHOT_DIR));
        } catch (Exception e) {
            log.error("[EnvSnapshot] 初始化快照目录失败: {}", SNAPSHOT_DIR, e);
        }
    }

    // ── 注册表 ──

    /** sectionType → capturer。同一 sectionType 后注册者覆盖前者。 */
    private final Map<String, SnapshotCapturer> capturers = new ConcurrentHashMap<>();

    /** fork 工厂列表(Phase 5 forkFromSnapshot 使用)。 */
    private final List<SnapshotCapturerFactory> factories =
            Collections.synchronizedList(new ArrayList<>());

    /** snapshotId → EnvironmentSnapshot 内存索引。 */
    private final Map<String, EnvironmentSnapshot> store = new ConcurrentHashMap<>();

    // ── 统计 ──
    private final AtomicLong totalCaptures = new AtomicLong(0);
    private final AtomicLong totalRestores = new AtomicLong(0);
    private final AtomicLong totalFailedCaptures = new AtomicLong(0);

    /**
     * 捕获计数器 — 单调递增，确保 snapshotId 全局唯一。
     * <p>
     * <b>修复 known issue</b>：原 ID 格式 {@code env-{ms}-{hex(scopeId.hashCode())}}
     * 在同毫秒、同 scopeId 并发捕获时会发生碰撞覆盖（store.put 会丢前者）。
     * 加入 counter 后格式为 {@code env-{ms}-{counter}-{hex}}，counter 由
     * {@link AtomicLong#incrementAndGet} 保证原子性，即使同毫秒同 scopeId 也不冲突。
     */
    private final AtomicLong captureCounter = new AtomicLong(0);

    // ════════════════════════════════════════════════════════════════
    //  注册
    // ════════════════════════════════════════════════════════════════

    /** 注册一个捕获器。后注册者覆盖同 sectionType 的前者。 */
    public void registerCapturer(SnapshotCapturer capturer) {
        capturers.put(capturer.sectionType(), capturer);
        log.info("[EnvSnapshot] 已注册 capturer: sectionType={}", capturer.sectionType());
    }

    /** 注销指定类型的捕获器。 */
    public void unregisterCapturer(String sectionType) {
        capturers.remove(sectionType);
        log.info("[EnvSnapshot] 已注销 capturer: sectionType={}", sectionType);
    }

    /** 注册 fork 工厂(Phase 5)。 */
    public void registerFactory(SnapshotCapturerFactory factory) {
        factories.add(factory);
        log.info("[EnvSnapshot] 已注册 factory: sectionTypes={}", factory.sectionTypes());
    }

    // ════════════════════════════════════════════════════════════════
    //  capture / restore
    // ════════════════════════════════════════════════════════════════

    /**
     * 从当前运行态捕获统一快照 — 遍历所有已注册 capturer,各 capture() 组装。
     *
     * @param scopeId 作用域标识(如 workflowId)
     * @return EnvironmentSnapshot
     */
    public EnvironmentSnapshot capture(String scopeId) {
        Map<String, SnapshotSection> sections = new LinkedHashMap<>();
        for (Map.Entry<String, SnapshotCapturer> e : capturers.entrySet()) {
            try {
                SnapshotSection s = e.getValue().capture();
                if (s != null) {
                    sections.put(e.getKey(), s);
                }
            } catch (Exception ex) {
                totalFailedCaptures.incrementAndGet();
                log.warn("[EnvSnapshot] capture section '{}' 失败: {}", e.getKey(), ex.getMessage());
            }
        }

        String snapshotId = "env-" + System.currentTimeMillis()
                + "-" + captureCounter.incrementAndGet()
                + "-" + Integer.toHexString(scopeId.hashCode());
        EnvironmentSnapshot snapshot = new EnvironmentSnapshot(
                snapshotId, System.currentTimeMillis(), scopeId,
                Collections.unmodifiableMap(sections)
        );
        store.put(snapshotId, snapshot);
        totalCaptures.incrementAndGet();

        log.info("[EnvSnapshot] capture 完成: id={}, scope={}, sections={}",
                snapshotId, scopeId, sections.keySet());
        return snapshot;
    }

    /**
     * 显式 section 捕获 — 绕开全局注册表,供并发工作流直接传入本地 context 捕获的 section。
     * <p>
     * WorkflowEngine 是单例,并发工作流共享全局 capturer 注册表会互相覆盖(同一 sectionType
     * 后注册者覆盖前者)。此重载接受调用方(持有本地 WorkflowContext)已 capture 的 section,
     * 避免注册竞争。WorkflowEngine 双写即用此入口。
     *
     * @param scopeId          作用域标识(如 workflowId)
     * @param explicitSections 调用方已捕获的 section(可为空数组)
     * @return EnvironmentSnapshot
     */
    public EnvironmentSnapshot capture(String scopeId, SnapshotSection... explicitSections) {
        Map<String, SnapshotSection> sections = new LinkedHashMap<>();
        for (SnapshotSection s : explicitSections) {
            if (s != null) {
                sections.put(s.sectionType(), s);
            }
        }
        String snapshotId = "env-" + System.currentTimeMillis()
                + "-" + captureCounter.incrementAndGet()
                + "-" + Integer.toHexString(scopeId.hashCode());
        EnvironmentSnapshot snapshot = new EnvironmentSnapshot(
                snapshotId, System.currentTimeMillis(), scopeId,
                Collections.unmodifiableMap(sections)
        );
        store.put(snapshotId, snapshot);
        totalCaptures.incrementAndGet();

        log.info("[EnvSnapshot] capture(explicit) 完成: id={}, scope={}, sections={}",
                snapshotId, scopeId, sections.keySet());
        return snapshot;
    }

    /**
     * 恢复快照到运行态 — 遍历 sections,调对应 capturer.restore。
     * <p>
     * 缺失 capturer 的 section 被跳过(日志告警),不阻断整体恢复。
     */
    public void restore(EnvironmentSnapshot snapshot) {
        if (snapshot == null || snapshot.sections() == null) {
            log.warn("[EnvSnapshot] restore 跳过:快照为空");
            return;
        }
        for (Map.Entry<String, SnapshotSection> e : snapshot.sections().entrySet()) {
            SnapshotCapturer capturer = capturers.get(e.getKey());
            if (capturer == null) {
                log.warn("[EnvSnapshot] restore 跳过 section '{}': 无注册 capturer", e.getKey());
                continue;
            }
            try {
                capturer.restore(e.getValue());
            } catch (Exception ex) {
                log.warn("[EnvSnapshot] restore section '{}' 失败: {}", e.getKey(), ex.getMessage());
            }
        }
        totalRestores.incrementAndGet();
        log.info("[EnvSnapshot] restore 完成: id={}, sections={}",
                snapshot.snapshotId(), snapshot.sections().keySet());
    }

    // ════════════════════════════════════════════════════════════════
    //  persist / load(Java 序列化到 ~/.aios/env_snapshots/)
    // ════════════════════════════════════════════════════════════════

    /** 持久化快照到磁盘。 */
    public void persist(EnvironmentSnapshot snapshot) {
        try {
            Path file = Paths.get(SNAPSHOT_DIR, snapshot.snapshotId() + ".envsnap");
            byte[] data = serialize(snapshot);
            Files.write(file, data);
            log.debug("[EnvSnapshot] 已持久化: {} ({} bytes)", file, data.length);
        } catch (Exception e) {
            log.error("[EnvSnapshot] 持久化失败: id={}, error={}", snapshot.snapshotId(), e.getMessage());
        }
    }

    /** 加载快照:先查内存索引,再查磁盘。 */
    public Optional<EnvironmentSnapshot> load(String snapshotId) {
        EnvironmentSnapshot cached = store.get(snapshotId);
        if (cached != null) return Optional.of(cached);
        try {
            Path file = Paths.get(SNAPSHOT_DIR, snapshotId + ".envsnap");
            if (Files.exists(file)) {
                byte[] data = Files.readAllBytes(file);
                EnvironmentSnapshot s = deserialize(data);
                store.put(snapshotId, s);
                return Optional.of(s);
            }
        } catch (Exception e) {
            log.error("[EnvSnapshot] 加载失败: id={}, error={}", snapshotId, e.getMessage());
        }
        return Optional.empty();
    }

    /** 列出所有已注册快照 ID(内存索引)。 */
    public java.util.Set<String> listSnapshots() {
        return Collections.unmodifiableSet(store.keySet());
    }

    /** 删除快照(内存 + 磁盘)。 */
    public boolean deleteSnapshot(String snapshotId) {
        EnvironmentSnapshot removed = store.remove(snapshotId);
        try {
            Files.deleteIfExists(Paths.get(SNAPSHOT_DIR, snapshotId + ".envsnap"));
        } catch (Exception e) {
            log.warn("[EnvSnapshot] 删除磁盘快照失败: {}", e.getMessage());
        }
        return removed != null;
    }

    // ════════════════════════════════════════════════════════════════
    //  fork / diff — Phase 4/5 实现
    // ════════════════════════════════════════════════════════════════

    /**
     * 从种子快照派生 N 个隔离分支。
     * <p>
     * 借鉴 mobilegym 的 "fork 结构化状态成 N 个并行 rollout"。遍历已注册
     * {@link SnapshotCapturerFactory},为每个 branchId 调用
     * {@link SnapshotCapturerFactory#createForFork} 创建隔离 capturer(各 factory
     * 负责新建隔离运行态并回填种子 section),组装 {@link ForkHandle}。
     * <p>
     * <b>并发约束</b>:activator 会把分支 capturer 注册到全局注册表(按 sectionType
     * 去重),并发多分支同时 activate 会互相覆盖。调用方须串行 activate,或为每分支
     * 使用独立 manager 实例。
     *
     * @param snapshotId 种子快照 ID
     * @param n          分支数
     * @return fork 分支句柄列表(尚未 activate)
     */
    public List<ForkHandle> forkFromSnapshot(String snapshotId, int n) {
        EnvironmentSnapshot seed = load(snapshotId)
                .orElseThrow(() -> new IllegalStateException("种子快照不存在: " + snapshotId));
        List<ForkHandle> handles = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String branchId = snapshotId + "-fork-" + i;
            List<SnapshotCapturer> branchCapturers = new ArrayList<>();
            for (SnapshotCapturerFactory f : factories) {
                branchCapturers.addAll(f.createForFork(branchId, seed));
            }
            // activator:注册分支 capturer 到全局表,使该分支可被 capture/restore。
            // createForFork 已负责把种子 section 回填到隔离运行态,故无需再 restore。
            Runnable activator = () -> branchCapturers.forEach(this::registerCapturer);
            handles.add(new ForkHandle(branchId, snapshotId, branchCapturers, activator));
        }
        log.info("[EnvSnapshot] fork 完成: seed={}, branches={}", snapshotId, n);
        return handles;
    }

    /**
     * 计算两个快照的状态差异(宽松期望:允许所有 section 变更)。
     *
     * @param before 之前快照
     * @param after  之后快照
     * @return 状态差异
     */
    public StateDiff diff(EnvironmentSnapshot before, EnvironmentSnapshot after) {
        return SnapshotDiffEngine.diff(before, after, DiffExpectation.permissive());
    }

    /**
     * 计算两个快照的状态差异,带期望约束判定 meetsExpectation。
     *
     * @param before 之前快照
     * @param after  之后快照
     * @param exp    期望约束(声明允许/禁止变更的 section)
     * @return 状态差异(含 meetsExpectation 判定)
     */
    public StateDiff diff(EnvironmentSnapshot before, EnvironmentSnapshot after, DiffExpectation exp) {
        return SnapshotDiffEngine.diff(before, after, exp);
    }

    // ════════════════════════════════════════════════════════════════
    //  序列化辅助
    // ════════════════════════════════════════════════════════════════

    private byte[] serialize(EnvironmentSnapshot snapshot) throws Exception {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(snapshot);
            oos.flush();
            return baos.toByteArray();
        }
    }

    private EnvironmentSnapshot deserialize(byte[] data) throws Exception {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            return (EnvironmentSnapshot) ois.readObject();
        }
    }

    /** 统计报告。 */
    public String getStatsReport() {
        return """
                ┌─ EnvironmentSnapshotManager Stats ──────────────────
                │  Total Captures      : %d
                │  Total Restores      : %d
                │  Failed Captures     : %d
                │  Registered Capturers: %d
                │  Registered Factories: %d
                │  Stored Snapshots    : %d
                │  Snapshot Directory  : %s
                └─────────────────────────────────────────────────""".formatted(
                totalCaptures.get(), totalRestores.get(), totalFailedCaptures.get(),
                capturers.size(), factories.size(), store.size(), SNAPSHOT_DIR);
    }
}
