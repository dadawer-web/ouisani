package com.ouisani.aios.core.skill;

import com.ouisani.aios.core.config.AiosPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RunRecord 持久化存储 — R3 运行记录持久化。
 * <p>
 * <b>与 OvernightTaskCard 持久化模式对齐</b>：
 * <ul>
 *   <li>per-run 目录：{@code {AiosPaths.overnightDir()}/skill-chain/{runId}/}</li>
 *   <li>每目录含：{@code manifest.json}（ChainRun 完整 JSON，由 SkillChain 写入 VFS）+
 *       {@code run-record.json}（本记录）+ {@code reproduce.prompt}（人类可读重放指令）+
 *       {@code input.txt}（原始输入）+ {@code snapshot-id.txt}（快照 ID，如有）</li>
 *   <li>append-only JSONL 日志：{@code {overnightDir}/skill-chain-runs.jsonl}，
 *       每行一个 RunRecord JSON，作为权威记录与重启恢复源</li>
 *   <li>内存索引：{@code runId → RunRecord} + 二级索引（metaSkillName / status）</li>
 * </ul>
 * <p>
 * <b>无 SQLite 依赖</b>：与 ProvenanceHook / EnvironmentSnapshotManager 一致采用
 * 文件 + 内存索引模式。若未来 run 数量超过 10 万级，可平滑替换为 SQLite
 * （接口不变，仅替换内部实现）。
 * <p>
 * <b>线程安全</b>：内存索引用 {@link ConcurrentHashMap}，JSONL 追加用
 * {@link FileChannel} APPEND 模式（原子追加）。per-run 目录写入用临时文件 + rename
 * 保证原子性。
 * <p>
 * <b>best-effort</b>：所有 I/O 异常被捕获并 log.warn，<b>不抛异常</b>，
 * 与 ProvenanceHook "Recording must never break the chat flow" 原则一致。
 *
 * @see RunRecord
 * @see SkillChain.ChainRun
 * @see AiosPaths#overnightDir()
 */
public final class RunRecordStore {

    private static final Logger log = LoggerFactory.getLogger(RunRecordStore.class);

    private static final class Holder {
        static final RunRecordStore INSTANCE = new RunRecordStore();
    }

    public static RunRecordStore instance() {
        return Holder.INSTANCE;
    }

    // ── 路径常量 ──

    /** skill-chain 持久化根目录（在 overnightDir 下） */
    private static final String SKILL_CHAIN_SUBDIR = "skill-chain";

    /** per-run 目录名前缀（实际为 {runId}） */
    /** JSONL 日志文件名 */
    private static final String JSONL_FILENAME = "skill-chain-runs.jsonl";

    // ── 状态 ──

    /** runId → RunRecord 内存索引 */
    private final ConcurrentHashMap<String, RunRecord> index = new ConcurrentHashMap<>();

    /** metaSkillName → runId 列表（二级索引） */
    private final ConcurrentHashMap<String, List<String>> byMetaSkill = new ConcurrentHashMap<>();

    /** status → runId 列表（二级索引） */
    private final ConcurrentHashMap<String, List<String>> byStatus = new ConcurrentHashMap<>();

    /** 持久化目录（绝对路径） */
    private volatile Path baseDir;

    /** JSONL 日志文件路径 */
    private volatile Path jsonlFile;

    /** 是否已加载历史日志 */
    private final AtomicBoolean loaded = new AtomicBoolean(false);

    private RunRecordStore() {
        setBaseDir(Paths.get(AiosPaths.overnightDir(), SKILL_CHAIN_SUBDIR));
    }

    /**
     * 自定义持久化目录 — 测试用。
     */
    public void setBaseDir(Path dir) {
        this.baseDir = dir;
        this.jsonlFile = dir.resolve(JSONL_FILENAME);
        this.loaded.set(false);
        this.index.clear();
        this.byMetaSkill.clear();
        this.byStatus.clear();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("[RunRecordStore] 创建目录失败: {}", dir, e);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  写入
    // ════════════════════════════════════════════════════════════════

    /**
     * 持久化一次 SkillChain 执行 — 写 per-run 目录 + JSONL + 更新内存索引。
     * <p>
     * <b>best-effort</b>：任何 I/O 失败只记日志，不抛异常。
     *
     * @param run   ChainRun 内存对象
     * @param input 原始用户输入
     * @param ctx   执行上下文（含 snapshotId）
     * @return 写入的 RunRecord（即使磁盘失败也返回内存中的记录）
     */
    public RunRecord record(SkillChain.ChainRun run, String input, SkillChainContext ctx) {
        ensureLoaded();
        Path runDir = baseDir.resolve(run.runId());
        String runDirStr = runDir.toString();
        RunRecord record = RunRecord.from(run, input, ctx, ctx.snapshotId(), runDirStr);

        // 1. 写 per-run 目录
        try {
            Files.createDirectories(runDir);
            writeAtomic(runDir.resolve("run-record.json"), record.toJson());
            writeAtomic(runDir.resolve("reproduce.prompt"), record.reproducePrompt());
            writeAtomic(runDir.resolve("input.txt"), input == null ? "" : input);
            if (ctx.hasSnapshot()) {
                writeAtomic(runDir.resolve("snapshot-id.txt"), ctx.snapshotId());
            }
        } catch (Exception e) {
            log.warn("[RunRecordStore] per-run 目录写入失败: runId={}, err={}",
                    run.runId(), e.getMessage());
        }

        // 2. 追加 JSONL
        try {
            appendJsonl(record.toJson());
        } catch (Exception e) {
            log.warn("[RunRecordStore] JSONL 追加失败: runId={}, err={}",
                    run.runId(), e.getMessage());
        }

        // 3. 更新内存索引
        index.put(record.runId(), record);
        byMetaSkill.computeIfAbsent(record.metaSkillName(),
                k -> Collections.synchronizedList(new ArrayList<>())).add(record.runId());
        byStatus.computeIfAbsent(record.status(),
                k -> Collections.synchronizedList(new ArrayList<>())).add(record.runId());

        log.info("[RunRecordStore] 已记录: runId={}, meta={}, status={}, dir={}",
                record.runId(), record.metaSkillName(), record.status(), runDirStr);
        return record;
    }

    // ════════════════════════════════════════════════════════════════
    //  查询
    // ════════════════════════════════════════════════════════════════

    /** 按 runId 查找 */
    public Optional<RunRecord> get(String runId) {
        ensureLoaded();
        if (runId == null) return Optional.empty();
        return Optional.ofNullable(index.get(runId));
    }

    /** 按 meta-skill 名查找所有运行 */
    public List<RunRecord> listByMetaSkill(String metaSkillName) {
        ensureLoaded();
        List<String> ids = byMetaSkill.get(metaSkillName);
        if (ids == null) return List.of();
        List<RunRecord> result = new ArrayList<>();
        for (String id : ids) {
            RunRecord r = index.get(id);
            if (r != null) result.add(r);
        }
        return Collections.unmodifiableList(result);
    }

    /** 按状态查找所有运行 */
    public List<RunRecord> listByStatus(String status) {
        ensureLoaded();
        List<String> ids = byStatus.get(status);
        if (ids == null) return List.of();
        List<RunRecord> result = new ArrayList<>();
        for (String id : ids) {
            RunRecord r = index.get(id);
            if (r != null) result.add(r);
        }
        return Collections.unmodifiableList(result);
    }

    /** 最近的 N 次运行（按 startedAt 降序） */
    public List<RunRecord> recent(int n) {
        ensureLoaded();
        List<RunRecord> all = new ArrayList<>(index.values());
        all.sort((a, b) -> Long.compare(b.startedAt(), a.startedAt()));
        if (all.size() <= n) return Collections.unmodifiableList(all);
        return Collections.unmodifiableList(all.subList(0, n));
    }

    /** 总运行数 */
    public int size() {
        ensureLoaded();
        return index.size();
    }

    // ════════════════════════════════════════════════════════════════
    //  重放
    // ════════════════════════════════════════════════════════════════

    /**
     * 重放一次运行 — 用相同 meta-skill + 输入 + ctx 重新执行。
     * <p>
     * <b>snapshot 恢复</b>：若原 run 记录了 snapshotId 且快照在
     * {@link com.ouisani.aios.core.snapshot.EnvironmentSnapshotManager} 中可加载，
     * 重放前会先 restore。失败仅 log.warn，不阻断重放。
     *
     * @param runId    要重放的 run ID
     * @param executor specialist skill 执行器
     * @return 新的 ChainRun（重放结果）；runId 不存在返回 empty
     */
    public Optional<SkillChain.ChainRun> reproduce(String runId, SkillChain.SkillExecutor executor) {
        ensureLoaded();
        RunRecord record = index.get(runId);
        if (record == null) {
            log.warn("[RunRecordStore] reproduce 失败：runId 不存在: {}", runId);
            return Optional.empty();
        }

        Optional<MetaSkill> metaOpt = MetaSkillRegistry.instance().get(record.metaSkillName());
        if (metaOpt.isEmpty()) {
            log.warn("[RunRecordStore] reproduce 失败：meta-skill 未注册: {}", record.metaSkillName());
            return Optional.empty();
        }

        // 可选：恢复环境快照
        if (!record.snapshotId().isEmpty()) {
            try {
                com.ouisani.aios.core.snapshot.EnvironmentSnapshotManager.instance()
                        .load(record.snapshotId())
                        .ifPresent(snap -> {
                            com.ouisani.aios.core.snapshot.EnvironmentSnapshotManager.instance().restore(snap);
                            log.info("[RunRecordStore] 已恢复环境快照: {}", record.snapshotId());
                        });
            } catch (Exception e) {
                log.warn("[RunRecordStore] 快照恢复失败（继续重放）: snapId={}, err={}",
                        record.snapshotId(), e.getMessage());
            }
        }

        // 用新的 slug 避免覆盖原 run 的 VFS 输出
        String newSlug = record.slug() + "-replay-" + System.currentTimeMillis() % 10000;
        SkillChainContext ctx = new SkillChainContext(
                record.agentId(), "", record.workingDir(), newSlug, record.snapshotId()
        );

        log.info("[RunRecordStore] 开始重放: origRunId={}, meta={}, newSlug={}",
                runId, record.metaSkillName(), newSlug);
        SkillChain.ChainRun rerun = SkillChain.run(metaOpt.get(), record.input(), ctx, executor);
        return Optional.of(rerun);
    }

    // ════════════════════════════════════════════════════════════════
    //  内部
    // ════════════════════════════════════════════════════════════════

    /** 懒加载 JSONL 历史 — 仅在第一次查询/写入时执行 */
    private void ensureLoaded() {
        if (loaded.get()) return;
        if (!loaded.compareAndSet(false, true)) return;
        loadJsonl();
    }

    private void loadJsonl() {
        if (!Files.exists(jsonlFile)) return;
        try (var lines = Files.lines(jsonlFile, StandardCharsets.UTF_8)) {
            int count = 0;
            for (String line : lines.toList()) {
                if (line.isBlank()) continue;
                try {
                    RunRecord r = RunRecord.fromJson(line);
                    if (r != null) {
                        index.put(r.runId(), r);
                        byMetaSkill.computeIfAbsent(r.metaSkillName(),
                                k -> Collections.synchronizedList(new ArrayList<>())).add(r.runId());
                        byStatus.computeIfAbsent(r.status(),
                                k -> Collections.synchronizedList(new ArrayList<>())).add(r.runId());
                        count++;
                    }
                } catch (Exception e) {
                    log.warn("[RunRecordStore] JSONL 行解析失败，跳过: {}", line.substring(0, Math.min(80, line.length())));
                }
            }
            if (count > 0) {
                log.info("[RunRecordStore] 已加载 {} 条历史 RunRecord", count);
            }
        } catch (IOException e) {
            log.warn("[RunRecordStore] JSONL 加载失败: {}", e.getMessage());
        }
    }

    /** 追加一行到 JSONL（FileChannel APPEND，原子） */
    private void appendJsonl(String json) throws IOException {
        Files.createDirectories(baseDir);
        String line = json + "\n";
        try (FileChannel ch = FileChannel.open(jsonlFile,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            ch.write(java.nio.ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** 原子写入文件：先写 .tmp，再 rename */
    private static void writeAtomic(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, target,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
