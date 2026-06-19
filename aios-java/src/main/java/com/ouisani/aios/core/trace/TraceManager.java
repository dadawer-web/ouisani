package com.ouisani.aios.core.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 追踪管理器 — AIOS 的 strace/ftrace 内核追踪子系统。
 * <p>
 * 类比 Linux 的 strace（系统调用追踪）和 ftrace（内核函数追踪）：
 * TraceManager 记录 Agent 执行过程中的关键事件（系统调用、信号、上下文切换等），
 * 生成结构化的追踪磁带（trace tape），用于事后分析和调试。
 * <p>
 * 追踪数据以 JSON 格式持久化到 {@code /tmp/aios_trace} 目录，
 * 每个追踪会话生成一个独立的磁带文件。
 *
 * @see com.ouisani.aios.core.telemetry.SemanticEtw
 */
public final class TraceManager {

    private static final Logger log = LoggerFactory.getLogger(TraceManager.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final String TAPE_DIR = "/tmp/aios_trace";

    private static final class Holder {
        static final TraceManager INSTANCE = new TraceManager();
    }

    public static TraceManager instance() {
        return Holder.INSTANCE;
    }

    private volatile TraceMode mode = TraceMode.DISABLED;
    private final Map<String, List<TraceRecord>> timeline = new ConcurrentHashMap<>();
    private final Map<String, Integer> replayCursors = new ConcurrentHashMap<>();
    private final AtomicLong totalRecorded = new AtomicLong(0);
    private final AtomicLong totalReplayed = new AtomicLong(0);
    private final AtomicLong totalMismatches = new AtomicLong(0);

    private TraceManager() {
    }

    public TraceMode mode() {
        return mode;
    }

    public void setMode(TraceMode mode) {
        TraceMode old = this.mode;
        this.mode = mode;
        if (mode == TraceMode.REPLAY) {
            replayCursors.clear();
        }
        log.info("[TraceManager] 模式已切换: {} → {}", old, mode);
    }

    public void recordEvent(String agentId, String eventType, String req, String res) {
        if (mode != TraceMode.RECORD) {
            return;
        }

        TraceRecord record = new TraceRecord(agentId, eventType, req, res);
        timeline.computeIfAbsent(agentId, k -> Collections.synchronizedList(new ArrayList<>())).add(record);
        long count = totalRecorded.incrementAndGet();

        if (count % 100 == 0) {
            log.debug("[TraceManager] 已记录事件总数 {}", count);
        }

        appendToTape(agentId, record);
    }

    public String replayEvent(String agentId, String eventType, String req) {
        if (mode != TraceMode.REPLAY) {
            return null;
        }

        List<TraceRecord> records = timeline.get(agentId);
        if (records == null || records.isEmpty()) {
            log.warn("[TraceManager] 回放未命中: agentId={} 无记录", agentId);
            return null;
        }

        int cursor = replayCursors.getOrDefault(agentId, 0);
        if (cursor >= records.size()) {
            log.warn("[TraceManager] 回放已耗尽: agentId={}, cursor={}, size={}", agentId, cursor, records.size());
            return null;
        }

        TraceRecord record = records.get(cursor);
        replayCursors.put(agentId, cursor + 1);
        totalReplayed.incrementAndGet();

        if (!record.eventType().equals(eventType)) {
            totalMismatches.incrementAndGet();
            log.warn("[TraceManager] 回放不匹配: agentId={}, 期望 eventType='{}', 实际='{}'",
                    agentId, record.eventType(), eventType);
        }

        if (!record.requestPayload().equals(req)) {
            totalMismatches.incrementAndGet();
            log.warn("[TraceManager] 回放不匹配: agentId={}, eventType={}, 请求负载不一致",
                    agentId, eventType);
        }

        log.debug("[TraceManager] 回放命中: agentId={}, eventType={}, cursor={}/{}",
                agentId, eventType, cursor + 1, records.size());
        return record.responsePayload();
    }

    public void clearHistory() {
        timeline.clear();
        replayCursors.clear();
        totalRecorded.set(0);
        totalReplayed.set(0);
        totalMismatches.set(0);
        log.info("[TraceManager] 所有历史已清除");
    }

    public void clearHistory(String agentId) {
        timeline.remove(agentId);
        replayCursors.remove(agentId);
        log.info("[TraceManager] agentId={} 的历史已清除", agentId);
    }

    public void loadTape(String agentId) {
        Path tapePath = Path.of(TAPE_DIR, agentId + ".tape");
        if (!Files.exists(tapePath)) {
            log.warn("[TraceManager] 磁带文件未找到: {}", tapePath);
            return;
        }

        try {
            String json = Files.readString(tapePath);
            TraceRecord[] records = objectMapper.readValue(json, TraceRecord[].class);
            List<TraceRecord> list = Collections.synchronizedList(new ArrayList<>(Arrays.asList(records)));
            timeline.put(agentId, list);
            replayCursors.remove(agentId);
            log.info("[TraceManager] 已加载磁带: agentId={}, records={}", agentId, list.size());
        } catch (IOException e) {
            log.error("[TraceManager] 加载磁带失败: agentId={}, error={}", agentId, e.getMessage());
        }
    }

    public void saveTape(String agentId) {
        List<TraceRecord> records = timeline.get(agentId);
        if (records == null || records.isEmpty()) {
            log.warn("[TraceManager] agentId={} 无记录可保存", agentId);
            return;
        }

        try {
            Path dir = Path.of(TAPE_DIR);
            Files.createDirectories(dir);
            Path tapePath = dir.resolve(agentId + ".tape");
            objectMapper.writeValue(tapePath.toFile(), records);
            log.info("[TraceManager] 已保存磁带: agentId={}, records={}, path={}", agentId, records.size(), tapePath);
        } catch (IOException e) {
            log.error("[TraceManager] 保存磁带失败: agentId={}, error={}", agentId, e.getMessage());
        }
    }

    public void saveAllTapes() {
        Set<String> agentIds = new HashSet<>(timeline.keySet());
        for (String agentId : agentIds) {
            saveTape(agentId);
        }
        log.info("[TraceManager] 所有磁带已保存 ({} 个 Agent)", agentIds.size());
    }

    public void loadAllTapes() {
        Path dir = Path.of(TAPE_DIR);
        if (!Files.isDirectory(dir)) {
            log.warn("[TraceManager] 磁带目录未找到: {}", TAPE_DIR);
            return;
        }

        try {
            Files.list(dir)
                    .filter(p -> p.toString().endsWith(".tape"))
                    .forEach(p -> {
                        String agentId = p.getFileName().toString().replace(".tape", "");
                        loadTape(agentId);
                    });
        } catch (IOException e) {
            log.error("[TraceManager] 列出磁带目录失败: {}", e.getMessage());
        }
    }

    public int recordCount(String agentId) {
        List<TraceRecord> records = timeline.get(agentId);
        return records != null ? records.size() : 0;
    }

    public int totalRecordCount() {
        return timeline.values().stream().mapToInt(List::size).sum();
    }

    public Set<String> agentIds() {
        return Collections.unmodifiableSet(timeline.keySet());
    }

    public TraceStats stats() {
        return new TraceStats(mode, timeline.size(), totalRecordCount(),
                totalRecorded.get(), totalReplayed.get(), totalMismatches.get());
    }

    private void appendToTape(String agentId, TraceRecord record) {
        try {
            Path dir = Path.of(TAPE_DIR);
            Files.createDirectories(dir);
            Path tapePath = dir.resolve(agentId + ".tape");

            List<TraceRecord> existing;
            if (Files.exists(tapePath)) {
                String json = Files.readString(tapePath);
                TraceRecord[] arr = objectMapper.readValue(json, TraceRecord[].class);
                existing = new ArrayList<>(Arrays.asList(arr));
            } else {
                existing = new ArrayList<>();
            }
            existing.add(record);
            objectMapper.writeValue(tapePath.toFile(), existing);
        } catch (IOException e) {
            log.debug("[TraceManager] 磁带追加失败: agentId={}, error={}", agentId, e.getMessage());
        }
    }

    public record TraceStats(TraceMode mode, int agentCount, int totalRecords,
                             long recordedOps, long replayedOps, long mismatches) {
    }
}
