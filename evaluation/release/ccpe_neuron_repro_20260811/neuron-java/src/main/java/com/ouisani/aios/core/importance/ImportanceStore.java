package com.ouisani.aios.core.importance;

import com.ouisani.aios.core.config.AiosPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Importance 记录持久化存储 — 仿 provenance.jsonl 的 JSONL 追加 pattern。
 * <p>
 * 落地路径：{@link AiosPaths#bouldersDir()}/importance.jsonl（长期系统级状态目录，
 * 与 boulders 状态机同区，跨 session 累积）。
 * <p>
 * 写：单行 JSON append（{@link StandardOpenOption#APPEND} + {@link StandardOpenOption#CREATE}）。
 * 读：逐行 {@link ImportanceRecord#fromJsonLine}，best-effort 跳过坏行（同 ProvenanceRecord 容错策略）。
 * <p>
 * <b>线程安全</b>：append 用 Files.writeString 加 APPEND 是 POSIX 原子追加（单行写入），
 * 多工作流并发 append 不会交错（每行一次 write 调用）。loadAll 是快照读，不阻塞写。
 */
public final class ImportanceStore {

    private static final Logger log = LoggerFactory.getLogger(ImportanceStore.class);
    private static final String FILE_NAME = "importance.jsonl";

    private ImportanceStore() {}

    /** 生产路径：bouldersDir/importance.jsonl */
    public static Path importanceFile() {
        return Path.of(AiosPaths.bouldersDir(), FILE_NAME);
    }

    /**
     * 追加一条 importance 记录到默认路径。
     * <p>
     * 失败仅 warn，不抛 — importance 是观测信号，绝不阻断工作流主流程。
     */
    public static void append(ImportanceRecord record) {
        append(record, importanceFile());
    }

    /**
     * 追加一条 importance 记录到指定文件（测试用 @TempDir 注入路径）。
     */
    public static void append(ImportanceRecord record, Path file) {
        if (record == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, record.toJsonLine() + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.debug("[ImportanceStore] 已追加: workflowId={}, taskType={}, roles={}",
                    record.workflowId(), record.taskType(), record.roleImportance().size());
        } catch (IOException e) {
            log.warn("[ImportanceStore] 追加失败（不影响主流程）: file={}, err={}", file, e.getMessage());
        }
    }

    /**
     * 读取全部 importance 记录 — 供离线 RoleSubsetOptimizer 聚合。
     * <p>
     * best-effort：跳过空行/解析失败行。文件不存在返回空列表。
     */
    public static List<ImportanceRecord> loadAll() {
        return loadAll(importanceFile());
    }

    /**
     * 从指定文件读取全部记录（测试用）。
     */
    public static List<ImportanceRecord> loadAll(Path file) {
        List<ImportanceRecord> records = new ArrayList<>();
        if (!Files.exists(file)) return records;
        try {
            for (String line : Files.readAllLines(file)) {
                ImportanceRecord r = ImportanceRecord.fromJsonLine(line);
                if (r != null) records.add(r);
            }
        } catch (IOException e) {
            log.warn("[ImportanceStore] 读取失败（返回已读部分）: file={}, err={}", file, e.getMessage());
        }
        return records;
    }
}
