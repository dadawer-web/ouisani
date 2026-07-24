package com.ouisani.aios.core.provenance;

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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Provenance 追溯钩子 — 每次 Agent 成功写文件时追加一条版本记录到
 * {@code .aios/provenance.jsonl}。
 * <p>
 * 借鉴 ai4s-research/open-science 的 {@code .openscience/provenance.jsonl} 设计，
 * 适配 Java 并发模型与 VFS 抽象。核心能力：
 * <ul>
 *   <li><b>自动追溯</b>：挂接到 {@code VfsManager.writeText}，无需调用方显式记录</li>
 *   <li><b>版本递增</b>：同 path 维护递增 version，旧版本保留为 history（与 P2
 *       VersionedMemoryStore 理念一致）</li>
 *   <li><b>Best-effort</b>：所有异常 catch，<b>永不中断写主流程</b>
 *       （open-science 原则：Recording must never break the chat flow）</li>
 *   <li><b>线程上下文</b>：通过 {@link #CURRENT_AGENT_ID} / {@link #CURRENT_SESSION_ID}
 *       ThreadLocal 传递 agentId/sessionId，避免污染 VfsManager.writeText 签名</li>
 *   <li><b>可禁用</b>：{@link #setEnabled(false)} 全局关闭（测试/性能场景）</li>
 *   <li><b>可查询</b>：{@link #listByPath} 支持后续"记忆查看器"按 path 回溯历史</li>
 * </ul>
 *
 * <h3>OS 类比</h3>
 * 相当于 Linux 的 audit.log + git 的 object store —
 * audit.log 记录"谁在何时用什么工具改了什么文件"，
 * git object store 按 path 维护版本历史。
 *
 * <h3>JSONL 格式</h3>
 * <pre>
 * {"path":"/factory/output/survey.md","version":1,"ts":1784592000000,"tool":"write","content":"...","agentId":"agent_5","sessionId":"sess_abc"}
 * {"path":"/factory/output/survey.md","version":2,"ts":1784592005000,"tool":"edit","content":"...","agentId":"agent_5","sessionId":"sess_abc"}
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>
 * // 上层（AgentLoop/SyscallDispatcher）设置上下文
 * ProvenanceHook.CURRENT_AGENT_ID.set("agent_5");
 * ProvenanceHook.CURRENT_SESSION_ID.set("sess_abc");
 * try {
 *     VfsManager.instance().writeText("/factory/output/survey.md", content);
 *     // → ProvenanceHook 自动追加一条记录
 * } finally {
 *     ProvenanceHook.CURRENT_AGENT_ID.remove();
 *     ProvenanceHook.CURRENT_SESSION_ID.remove();
 * }
 *
 * // 后续查询历史
 * List&lt;ProvenanceRecord&gt; history = ProvenanceHook.listByPath("/factory/output/survey.md");
 * </pre>
 *
 * @see ProvenanceRecord
 */
public final class ProvenanceHook {

    private static final Logger log = LoggerFactory.getLogger(ProvenanceHook.class);

    /**
     * 当前 Agent 标识 — 由上层（AgentLoop/SyscallDispatcher）在进入 agent 上下文时设置。
     * <p>
     * 设计为 ThreadLocal 而非方法参数，避免污染 {@code VfsManager.writeText} 签名
     * （writeText 有 30+ 调用方，改签名成本过高）。
     */
    public static final ThreadLocal<String> CURRENT_AGENT_ID = new ThreadLocal<>();

    /**
     * 当前会话标识 — 由上层在进入会话上下文时设置。
     */
    public static final ThreadLocal<String> CURRENT_SESSION_ID = new ThreadLocal<>();

    /** Provenance JSONL 文件路径（与 open-science 的 .openscience/ 对齐，用 .aios/） */
    private static volatile Path provenanceFile = Paths.get(".aios", "provenance.jsonl");

    /** 全局启用开关 — 默认启用 */
    private static volatile boolean enabled = true;

    /** 按 path 维护递增版本号（path → next version） */
    private static final ConcurrentMap<String, AtomicLong> versionCounters = new ConcurrentHashMap<>();

    /** 内存缓冲（最近 N 条）— 支持快速 listByPath 查询，避免每次读文件 */
    private static final List<ProvenanceRecord> recentBuffer = new ArrayList<>();
    private static final int BUFFER_CAPACITY = 1024;
    private static final Object bufferLock = new Object();

    private ProvenanceHook() {}

    // ════════════════════════════════════════════════════════════════
    //  主入口 — VfsManager.writeText 调用
    // ════════════════════════════════════════════════════════════════

    /**
     * 记录一次写操作 — 由 VfsManager.writeText 包装层调用。
     * <p>
     * <b>Best-effort</b>：所有异常 catch，永不抛出 — 写主流程优先于审计记录。
     * <p>
     * 仅在 {@code success=true} 时记录（与 open-science 一致：failures 不产生版本）。
     *
     * @param path     VFS 虚拟路径
     * @param content  写入的文本内容（可能为 null）
     * @param success  写入是否成功
     */
    public static void onWrite(String path, String content, boolean success) {
        if (!enabled || !success || path == null || path.isEmpty()) {
            return;
        }
        try {
            long version = versionCounters
                    .computeIfAbsent(path, k -> new AtomicLong())
                    .incrementAndGet();
            ProvenanceRecord record = new ProvenanceRecord(
                    path,
                    version,
                    System.currentTimeMillis(),
                    "write",
                    content,
                    CURRENT_AGENT_ID.get(),
                    CURRENT_SESSION_ID.get()
            );
            appendRecord(record);
        } catch (Throwable t) {
            // best-effort: 永不中断主流程
            log.warn("[Provenance] 记录失败 (path={}): {}", path, t.getMessage());
        }
    }

    /**
     * 记录一次写操作 — 带显式 tool 名（如 apply_patch/edit）。
     *
     * @param path     VFS 虚拟路径
     * @param content  写入的文本内容
     * @param success  写入是否成功
     * @param tool     工具名（write/apply_patch/edit 等）
     */
    public static void onWrite(String path, String content, boolean success, String tool) {
        if (!enabled || !success || path == null || path.isEmpty()) {
            return;
        }
        try {
            long version = versionCounters
                    .computeIfAbsent(path, k -> new AtomicLong())
                    .incrementAndGet();
            ProvenanceRecord record = new ProvenanceRecord(
                    path,
                    version,
                    System.currentTimeMillis(),
                    tool,
                    content,
                    CURRENT_AGENT_ID.get(),
                    CURRENT_SESSION_ID.get()
            );
            appendRecord(record);
        } catch (Throwable t) {
            log.warn("[Provenance] 记录失败 (path={}, tool={}): {}", path, tool, t.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  查询 — 支持后续"记忆查看器"
    // ════════════════════════════════════════════════════════════════

    /**
     * 按 path 查询所有版本记录（从内存缓冲）。
     * <p>
     * 返回顺序：最近 N 条（BUFFER_CAPACITY=1024），按时间正序。
     * 若需完整历史，直接读 {@code .aios/provenance.jsonl} 文件。
     *
     * @param path VFS 虚拟路径
     * @return 版本记录列表（可能为空）
     */
    public static List<ProvenanceRecord> listByPath(String path) {
        if (path == null) return List.of();
        synchronized (bufferLock) {
            List<ProvenanceRecord> result = new ArrayList<>();
            for (ProvenanceRecord r : recentBuffer) {
                if (path.equals(r.path())) {
                    result.add(r);
                }
            }
            return result;
        }
    }

    /**
     * 按 agentId 查询所有版本记录（从内存缓冲）。
     */
    public static List<ProvenanceRecord> listByAgent(String agentId) {
        if (agentId == null) return List.of();
        synchronized (bufferLock) {
            List<ProvenanceRecord> result = new ArrayList<>();
            for (ProvenanceRecord r : recentBuffer) {
                if (agentId.equals(r.agentId())) {
                    result.add(r);
                }
            }
            return result;
        }
    }

    /**
     * 获取当前 path 的版本号（下次写入将得到 version+1）。
     */
    public static long currentVersion(String path) {
        AtomicLong counter = versionCounters.get(path);
        return counter == null ? 0 : counter.get();
    }

    // ════════════════════════════════════════════════════════════════
    //  配置 — 启用/禁用 + 文件路径
    // ════════════════════════════════════════════════════════════════

    /**
     * 全局启用/禁用 Provenance 记录。
     * <p>
     * 测试场景可禁用以避免污染 .aios/provenance.jsonl。
     *
     * @param enabled true 启用（默认），false 禁用
     */
    public static void setEnabled(boolean enabled) {
        ProvenanceHook.enabled = enabled;
        log.info("[Provenance] enabled={}", enabled);
    }

    /**
     * 设置 Provenance JSONL 文件路径。
     * <p>
     * 测试场景可指向临时文件。
     *
     * @param file JSONL 文件路径
     */
    public static void setProvenanceFile(Path file) {
        ProvenanceHook.provenanceFile = file;
        log.info("[Provenance] file={}", file);
    }

    /**
     * 获取 Provenance JSONL 文件路径（Phase 6：供 {@link ProvenanceQuery} 跨 session 回读）。
     */
    public static Path provenanceFile() {
        return provenanceFile;
    }

    /**
     * 重置所有状态 — 仅测试使用。
     * <p>
     * 清空版本计数器 + 内存缓冲。不影响已写入的 JSONL 文件。
     */
    public static void resetForTesting() {
        versionCounters.clear();
        synchronized (bufferLock) {
            recentBuffer.clear();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  内部 — JSONL 追加
    // ════════════════════════════════════════════════════════════════

    private static void appendRecord(ProvenanceRecord record) throws IOException {
        // 1. 追加到内存缓冲（快速查询）
        synchronized (bufferLock) {
            if (recentBuffer.size() >= BUFFER_CAPACITY) {
                // 简单的 FIFO 淘汰 — 移除最旧的 1/4
                recentBuffer.subList(0, BUFFER_CAPACITY / 4).clear();
            }
            recentBuffer.add(record);
        }

        // 2. 追加到 JSONL 文件（持久化）
        Path file = provenanceFile;
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        String line = record.toJsonLine() + "\n";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        // 使用 CREATE + APPEND，线程安全由 OS 保证（单次 write 原子）
        try (FileChannel ch = FileChannel.open(file,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            ch.write(java.nio.ByteBuffer.wrap(bytes));
        }
    }
}
